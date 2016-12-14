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

/**
 * An Algebriac Data Type (ADT) representing statistics associated with a [[CircuitBreaker]]
 * instance which can be rendered as a `String` for use in monitoring applications.
 */
sealed abstract class Statistics extends Product with Serializable {
  /**
   * Renders the statistics as a human-readable string value.
   */
  def show: String
}

/**
 * This represents the statistics that [[CircuitBreaker]] instances use to track the
 * success and failure rates of their protected programs, for use in determining when
 * to trip ("open") the breaker and, when open, to maintain the periodic testing state
 * for use in determining when the breaker can be closed and normal traffic let flow
 * to the program.
 * @param id - the unique identifier of the [[CircuitBreaker]] which "owns" this
 *   statistics instance.
 * @param config - the configuration of the associated [[CircuitBreaker]], also used to
 *   configure this statistics instance.
 * @param metrics - the sliding time-based metrics for the success/failure indicators of
 *   the protected program execution.
 * @param testing - if the associated [[CircuitBreaker]] is open, there will be a value
 *   here that maintains statistics on program execution tests (periodically allowing
 *   a program to execute and keeping track of the consecutive successes or, if a test failure,
 *   resetting these stats).
 * @param change - if the statistics have undergone any state change, the last change will
 *   be present here.
 * @param lastActivity - the timestamp of the last state change for these statistics.
 */
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

  /**
   * Lifecycle function invoked by the [[CircuitBreaker]] after it performed circuit breaker open checked and determined the program
   * execution request should go forth (either for testing if the circuit breaker is open or for normal execution).
   * @return newStatistics - The metrics are updated with the last activity timestamp updated.
   */
  def beforeExecution(timestamp: Instant): FailureStatistics = copy(lastActivity = timestamp)

  /**
   * Lifecycle function invoked by the [[CircuitBreaker]] after the program execution has completed.  If goes down two separate
   * paths depending on whether the circuit breaker is open and therefore the execution is a test or if it is a normal
   * execution.  In either case, the statistics are updated appropriately and a new value is returned.
   * @param timestamp - the timestamp of the execution completion.
   * @param success - the indicator as to whether the request was successful or a failure.
   * @return newStatistics - The metrics are updated based on the evaluation of the statistics.
   */
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
  /**
   * Render this statistics object in human readable form.
   */
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

/**
 * This represents the statistics that [[CircuitBreaker]] instances use to track the
 * inbound and processing rate of their protected programs, for use in determining when
 * to throttle (fail fast) requests in order to maintain the effective inbound rate
 * to an acceptable level such that the program (or system/service behind it) is not
 * overloaded.
 * @param id - the unique identifier of the [[CircuitBreaker]] which "owns" this
 *   statistics instance.
 * @param metrics - the sliding time-based metrics for both the inbound requests per second
 *   as well as the observed processing rate per second of the protected program.
 *   the protected program execution.
 * @param failure - the [[FailureStatistics]] used for the failure component of the flow
 *   control circuit breaker.
 * @param change - if the statistics have undergone any state change, the last change will
 *   be present here.
 */
case class FlowControlStatistics private (
    id: CircuitBreaker.Identifier,
    metrics: FlowControlStatistics.Metrics,
    failure: FailureStatistics,
    change: Option[FlowControlStatistics.Change] = None
) extends Statistics {

  import FlowControlStatistics._

  /**
   * Indicates that the last state change was that the maximum acceptable rate was increased
   * (the [[CircuitBreaker]] can allow its protected program execution to throttle up).
   */
  def throttledUp: Boolean = change exists { _ == Change.ThrottledUp }

  /**
   * Indicates that the last state change was that the maximum acceptable rate was decreased
   * (the [[CircuitBreaker]] should throttle down the rate of requests it allows through to execution, throttling the service down).
   */
  def throttledDown: Boolean = change exists { _ == Change.ThrottledDown }

  /**
   * The mean/average effective inbound rate per second (effective in that requests that are throttled/failed fast are not
   * counted in the effective rate) over the sample window.
   * @return meanFlowRate - the mean effective inbound rate.
   */
  def meanInboundRate: MeanFlowRate = metrics.meanInboundRate

  /**
   * The mean/average observed processing rate per second over the sample window.
   * @return meanFlowRate - the mean processing rate.
   */
  def meanProcessingRate: MeanFlowRate = metrics.meanProcessingRate

  /**
   * The maximum acceptable inbound rate.  This is differentated from the mean processing rate in that you can configure
   * a percentage that we should allow the inbound rate exceed the processing rate (to account for spikes and valleys, for instance).
   * This value is the mean processing rate + the added percentage over allowed, if it is configured as a non-zero value.
   * @return maxAcceptableRate - the maximum acceptable rate, if it has been calculated yet (it will not be calculated until
   *   a full window of statistics has been collected).
   */
  def maxAcceptableRate: Option[MeanFlowRate] = metrics.maxAcceptableRate

  /**
   * Should the current request be throttled (failed fast), given this statistics object? This function will evaluat
   * the inbound rate and processing rate averages, assuming both have followed a full window's worth of statistics, and
   * determine if the current effective inbound rate is greater than the maximum acceptable rate, or if the configured hard
   * limit rate has been exceeded.  If either of those things evaluate true, this function will also.
   */
  def shouldBeThrottled: Boolean = indicatesShouldBeThrottled(metrics)

  /**
   * Convenience copy constructor, apply the passed-in [[FailureStatistics]].
   */
  def withFailure(failure: FailureStatistics): FlowControlStatistics = copy(failure = failure)

  /**
   * Lifecycle function invoked by the [[CircuitBreaker]] before it checks to see if it should throttle a request, passing
   * a timestamp indicating the instant a request has been received.
   * @return newStatistics - The metrics are updated with the timestamp of the request received.
   */
  def beforeThrottle(startTime: Instant): FlowControlStatistics = {
    val updated = metrics.beforeThrottle(startTime)
    copy(metrics = updated, change = throttlingChange(updated))
  }

  /**
   * Lifecycle function invoked by the [[CircuitBreaker]] after it performed throttling checks and determined the program
   * execution request should go forth.
   * @return newStatistics - The metrics are updated, including update to the effective inbound rate per second.
   */
  def beforeExecution: FlowControlStatistics = {
    val updated = metrics.beforeExecution
    copy(metrics = updated, change = throttlingChange(updated))
  }

  /**
   * Lifecycle function invoked by the [[CircuitBreaker]] after the program execution has completed
   * @return newStatistics - The metrics are updated, including update to the effective processing rate per second.
   */
  def afterExecution: FlowControlStatistics = {
    val updated = metrics.afterExecution
    copy(metrics = updated, change = throttlingChange(updated))
  }

  /**
   * Render this statistics object in human readable form.
   */
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
