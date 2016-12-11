/*
 * Copyright 2016 Combined Conditional Access Development, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ccadllc.cedi.circuitbreaker
package statistics

import java.time.Instant
import java.time.temporal.ChronoUnit

sealed abstract class Statistics extends Product with Serializable { def show: String }

case class FailureStatistics private (
    id: CircuitBreaker.Identifier,
    config: FailureSettings,
    metrics: FailureStatistics.SlidingAggregateMetrics,
    testing: Option[FailureStatistics.Testing] = None,
    change: Option[FailureStatistics.Change] = None,
    lastActivity: Instant = Instant.EPOCH
) extends Statistics {

  import FailureStatistics._

  val lastChangeOpened: Boolean = change exists { _ == Change.Opened }

  val lastChangeClosed: Boolean = change exists { _ == Change.Closed }

  val open: Boolean = testing.isDefined

  def openButUntestable(current: Instant): Boolean = testing exists { !_.intervalHasPast(current) }

  def beforeExecution(timestamp: Instant): FailureStatistics = copy(lastActivity = timestamp)

  def afterExecution(timestamp: Instant, success: Boolean): FailureStatistics = testing match {
    case Some(testing) =>
      val updatedTesting = testing.update(timestamp, success)
      if (updatedTesting.successes >= config.test.minimumSuccesses)
        copy(testing = None, change = Some(Change.Closed), lastActivity = timestamp)
      else
        copy(testing = Some(updatedTesting), change = None, lastActivity = timestamp)
    case None =>
      val effectiveMetrics = metrics.addToWindow(timestamp, success)
      val tripsBreaker = effectiveMetrics.percentFailure greaterThan config.degradationThreshold
      if (tripsBreaker) copy(
        metrics = effectiveMetrics.reset,
        testing = Some(Testing(config.test)),
        change = Some(Change.Opened),
        lastActivity = timestamp
      )
      else copy(metrics = effectiveMetrics, testing = None, change = None, lastActivity = timestamp)
  }
  def show: String =
    s"id: ${id.show}, failure: ${metrics.percentFailure.show}, full window collected: ${metrics.vector.fullWindowCollected}, stats count: ${metrics.vector.entries.size}, testing: ${testing.fold("N/A")(_.show)}, last change: ${change.fold("None")(_.show)}"
}

object FailureStatistics {
  sealed abstract class Change extends Product with Serializable { def show: String }
  object Change {
    case object Opened extends Change { def show: String = "opened" }
    case object Closed extends Change { def show: String = "closed" }
  }
  case class SlidingAggregateMetrics(vector: SlidingVector[Boolean]) {
    def addToWindow(timestamp: Instant, value: Boolean): SlidingAggregateMetrics =
      copy(vector = vector.add(timestamp, value))
    def reset: SlidingAggregateMetrics = copy(vector = vector.reset)
    val percentFailure: Percentage = if (!vector.fullWindowCollected) Percentage.minimum else {
      val countF: Int = vector.entries.foldLeft(0) { case (count, success) => if (!success.value) count + 1 else count }
      val total = vector.entries.size
      Percentage.withinLimits(if (total == 0) 0.0 else ((countF.toDouble / total.toDouble) * 100.0))
    }
  }
  object SlidingAggregateMetrics {
    def initial(window: SampleWindow): SlidingAggregateMetrics = SlidingAggregateMetrics(SlidingVector[Boolean](window))
  }
  case class Testing(config: FailureSettings.Test, timestamp: Instant = Instant.EPOCH, successes: Int = 0) {
    def show: String = s"$successes successes"
    def update(ts: Instant, success: Boolean): Testing =
      if (success) copy(timestamp = ts, successes = successes + 1) else copy(timestamp = ts, successes = 0)
    def intervalHasPast(current: Instant): Boolean = timestamp.isBefore(current minusMillis config.interval.toMillis)
  }
  def initial(id: CircuitBreaker.Identifier, config: FailureSettings): FailureStatistics =
    FailureStatistics(id, config, SlidingAggregateMetrics.initial(config.sampleWindow))
}

case class FlowControlStatistics private (
    id: CircuitBreaker.Identifier,
    metrics: FlowControlStatistics.Metrics,
    failure: FailureStatistics,
    change: Option[FlowControlStatistics.Change] = None
) extends Statistics {

  import FlowControlStatistics._

  def throttledUp: Boolean = change exists { _ == Change.ThrottledUp }

  def throttledDown: Boolean = change exists { _ == Change.ThrottledDown }

  def meanInboundRate: MeanFlowRate = metrics.meanInboundRate

  def meanProcessingRate: MeanFlowRate = metrics.meanProcessingRate

  def maxAcceptableRate: Option[MeanFlowRate] = metrics.maxAcceptableRate

  def shouldBeThrottled: Boolean = indicatesShouldBeThrottled(metrics)

  def withFailure(failure: FailureStatistics): FlowControlStatistics = copy(failure = failure)

  def beforeThrottle(startTime: Instant): FlowControlStatistics = {
    val updated = metrics.beforeThrottle(startTime)
    copy(metrics = updated, change = throttlingChange(updated))
  }

  def beforeExecution: FlowControlStatistics = {
    val updated = metrics.beforeExecution
    copy(metrics = updated, change = throttlingChange(updated))
  }

  def afterExecution: FlowControlStatistics = {
    val updated = metrics.afterExecution
    copy(metrics = updated, change = throttlingChange(updated))
  }

  def show: String = s"id: ${id.show}, metrics: ${metrics.show}"

  private def throttlingChange(updated: Metrics): Option[FlowControlStatistics.Change] = for {
    updatedMar <- updated.maxAcceptableRate
    currentMar <- maxAcceptableRate
    change <- {
      lazy val updatedShouldBeThrottled = indicatesShouldBeThrottled(updated)
      val throttleJustStarted = !shouldBeThrottled && updatedShouldBeThrottled
      val throttleJustEnded = shouldBeThrottled && !updatedShouldBeThrottled
      lazy val prevAcceptRate = currentMar.perSecond
      lazy val reportableRateChange =
        prevAcceptRate * (updated.config.minimumReportableRateChange.percent / 100.0)
      lazy val reportableThrottleUp =
        updatedShouldBeThrottled && updatedMar.perSecond > (prevAcceptRate + reportableRateChange)
      lazy val reportableThrottleDown =
        updatedShouldBeThrottled && updatedMar.perSecond < (prevAcceptRate - reportableRateChange)
      if (throttleJustStarted || reportableThrottleDown) Some(Change.ThrottledDown)
      else if (throttleJustEnded || reportableThrottleUp) Some(Change.ThrottledUp) else None
    }
  } yield change

  private def indicatesShouldBeThrottled(m: Metrics) = m.maxAcceptableRate.exists { m.meanInboundRate.perSecond > _.perSecond } || (
    m.config.perSecondRateHardLimit > 0.0 && m.inboundRate.currentSample.perSecondCount > m.config.perSecondRateHardLimit
  )
}

object FlowControlStatistics {

  sealed abstract class Change extends Product with Serializable { def show: String }
  object Change {
    case object ThrottledUp extends Change { def show: String = "throttled up" }
    case object ThrottledDown extends Change { def show: String = "throttled down" }
  }
  case class SlidingAggregateMetrics(vector: SlidingVector[Long]) {
    val mean: Double = if (vector.entries.isEmpty) 0.0 else vector.entries.map(_.value.toDouble).sum / vector.entries.size.toDouble
    def addToWindow(timestamp: Instant, value: Long, zeroFillIdleTime: Long): SlidingAggregateMetrics = {
      val idleTimeAdjusted = 1L.to(zeroFillIdleTime.min(vector.window.maximumEntries.toLong)).toVector.reverse.foldLeft(vector) {
        case (vec, i) => vec.add(timestamp.minusSeconds(i), value)
      }
      copy(vector = idleTimeAdjusted.add(timestamp, value))
    }
  }
  object SlidingAggregateMetrics {
    def initial(window: SampleWindow): SlidingAggregateMetrics = SlidingAggregateMetrics(SlidingVector[Long](window))
  }

  case class FlowRateSample(startTime: Instant, perSecondCount: Long) { def show: String = s"$perSecondCount successes" }
  case class MeanFlowRate(perSecond: Double) { def show: String = s"$perSecond per second" }

  case class AggregateFlowRate(perSecond: SlidingAggregateMetrics, currentSample: FlowRateSample) {
    def mean: Double = perSecond.mean
    def fullWindowCollected: Boolean = perSecond.vector.fullWindowCollected
    def addToSample: AggregateFlowRate = copy(currentSample = currentSample.copy(perSecondCount = currentSample.perSecondCount + 1))
    def ageSample(time: Instant): AggregateFlowRate = if (time.isBefore(currentSample.startTime.plusSeconds(1))) this else {
      val secondsPast = if (currentSample.startTime == Instant.EPOCH) 0L else ChronoUnit.SECONDS.between(currentSample.startTime, time)
      val zeroFillIdleTime = if (secondsPast < 2L) 0L else secondsPast - 1L
      AggregateFlowRate(perSecond.addToWindow(time, currentSample.perSecondCount, zeroFillIdleTime), currentSample = FlowRateSample(time, 0L))
    }
    def show: String = s"mean rate $mean, current per-second count ${currentSample.show}, stats count ${perSecond.vector.entries.size}, full window collected ${perSecond.vector.fullWindowCollected}"
  }
  object AggregateFlowRate {
    def initial(window: SampleWindow): AggregateFlowRate =
      AggregateFlowRate(SlidingAggregateMetrics.initial(window), FlowRateSample(Instant.EPOCH, 0L))
  }

  case class Metrics(config: FlowControlSettings, inboundRate: AggregateFlowRate, processingRate: AggregateFlowRate) {
    val meanInboundRate: MeanFlowRate = MeanFlowRate(inboundRate.mean)
    val meanProcessingRate: MeanFlowRate = MeanFlowRate(processingRate.mean)

    val maxAcceptableRate: Option[MeanFlowRate] = if (inboundRate.fullWindowCollected)
      Some(MeanFlowRate(meanProcessingRate.perSecond + (meanProcessingRate.perSecond * config.allowedOverProcessingRate.percent / 100.0))) else None

    def beforeThrottle(startTime: Instant): Metrics =
      copy(inboundRate = inboundRate.ageSample(startTime), processingRate = processingRate.ageSample(startTime))
    def beforeExecution: Metrics = copy(inboundRate = inboundRate.addToSample)
    def afterExecution: Metrics = copy(processingRate = processingRate.addToSample)

    def show: String = s"inbound: [ ${inboundRate.show} ], processed: [ ${processingRate.show} ]"
  }
  def initial(id: CircuitBreaker.Identifier, config: FlowControlSettings): FlowControlStatistics = FlowControlStatistics(
    id,
    Metrics(config, AggregateFlowRate.initial(config.sampleWindow), AggregateFlowRate.initial(config.sampleWindow)),
    FailureStatistics.initial(id, config.failure)
  )
}
