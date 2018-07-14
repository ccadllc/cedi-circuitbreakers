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
 * An Algebraic Data Type (ADT) representing statistics associated with a [[CircuitBreaker]]
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
    testing: Option[FailureStatistics.Testing],
    change: Option[FailureStatistics.Change],
    lastActivity: Instant
) extends Statistics {

  import FailureStatistics._

  val lastChangeOpened: Boolean = change contains Change.Opened

  val lastChangeClosed: Boolean = change contains Change.Closed

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
    case Some(t) =>
      val updatedTesting = t.update(timestamp, success)
      if (updatedTesting.successes >= config.test.minimumSuccesses) copy(metrics = metrics.reset, testing = None, change = Some(Change.Closed), lastActivity = timestamp)
      else copy(testing = Some(updatedTesting), change = None, lastActivity = timestamp)
    case None =>
      val effectiveMetrics = metrics.addToWindow(timestamp, success)
      val tripsBreaker = effectiveMetrics.percentFailure greaterThan config.degradationThreshold
      if (tripsBreaker) copy(
        metrics = effectiveMetrics,
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

/**
 * The companion object to the `FailureStatistics` instances, providing various data types and constructor
 * functions used by and for those instances.
 */
object FailureStatistics {
  /**
   * An Algebraic Data Type (ADT) which represents the latest state change for a `FailureStatistics` instance.
   * If the `changed` property of the `FailureStatististics` instance is set to Some(`Opened`), it indicates the
   * associated `CircuitBreaker` has just transitioned to open, if set to Some(`Closed`), it indicates it has just
   * transitioned to closed, and if set to None, it indicates that the `CircuitBreaker` was already opened or
   * closed prior to the latest statistics update.
   */
  sealed abstract class Change extends Product with Serializable {
    /**
     * Renders the change as a human-readable string value.
     */
    def show: String
  }
  object Change {
    /** Indicates that the associated `CircuitBreaker` has just been opened. */
    case object Opened extends Change {
      /**
       * Renders the opened change as a human-readable string value.
       */
      def show: String = "opened"
    }

    /** Indicates that the associated `CircuitBreaker` has just been closed. */
    case object Closed extends Change {
      /**
       * Renders the closed change as a human-readable string value.
       */
      def show: String = "closed"
    }
  }

  /**
   * The sliding statistics, which maintains a pass/failure boolean indicator for results of the
   * execution of programs protected by the associated [[CircuitBreaker]].  Each pass/failure indicator
   * is associated with a `java.time.Instant` timestamp in the [[SlidingVector]] contained here and the
   * sliding vector keeps only the items with associated time-stamps in the configured range of the statistics
   * window (specified when the `SlidingVector[Boolean]` is created).
   * @param vector - the `SlidingVector[Boolean]` containing the pass/fail indicators for the latest
   *   protected program executions which fall within the configured time range of the statistics window (e.g.,
   *   a 30s configuration would keep all items with a timestamp that falls within 30 seconds of the current time).
   */
  case class SlidingAggregateMetrics(vector: SlidingVector[Boolean]) {
    /**
     * Adds a pass/failure boolean indicator and associated timestamp to the sliding vector, resulting in a new
     * instance of this data type with an updated `vector`.
     * @param timestamp - a `java.time.Instant` timestamp (usually close to the current time).
     * @param value - a `Boolean` value representing that the protected program has succeeded or failed (where the
     *   failure determined by the associated `CircuitBreaker` `Evaluator` is applicable for consideration in determining
     *   whether or not to open the `CircuitBreaker`).
     * @return newSlidingAggregateMetrics - a new instance of this data type with an updated `vector` now containing the new
     *   value and possibly having removed older values which no longer fall within its time range.
     */
    def addToWindow(timestamp: Instant, value: Boolean): SlidingAggregateMetrics =
      copy(vector = vector.add(timestamp, value))
    /**
     * Resets the aggregate metrics by resetting the contained `SlidingVector[Boolean]` to its initial value.
     * @return newSlidingAggregateMetrics - a new instance of this data type with an updated `vector` reset to its initial
     *   value.
     */
    def reset: SlidingAggregateMetrics = copy(vector = vector.reset)

    /**
     * The current percentage of failures contained within the sliding vector - if the vector has not yet collected a
     *   sufficient number of elements to properly derived an average (determined by checking to see if a full time window
     *   of results have been collected), it returns 0 percent; otherwise, the percentage is calculated and stored in this
     *   value.
     */
    val percentFailure: Percentage = if (!vector.fullWindowCollected) Percentage.minimum else {
      val countF: Int = vector.entries.foldLeft(0) { case (count, success) => if (!success.value) count + 1 else count }
      val total = vector.entries.size
      Percentage.withinLimits(if (total == 0) 0.0 else (countF.toDouble / total.toDouble) * 100.0)
    }
  }
  /**
   * The companion object for instances of `SlidingAggregateMetrics` instances provides a smart constructor for an initial
   * instance (all other instances are created via instance methods).
   */
  object SlidingAggregateMetrics {
    /**
     * Creates an initial instance, with a [[SlidingVector]] constructed using the passed-in [[SampleWindow]], which specifies
     * the time-range for which to maintain individual pass/fail statistics.  All other instances are created by instance methods
     * when the state needs updating.
     * @param window - a `SampleWindow` which specifies the time-range desired for the `SlidingVector[Boolean]`.
     * @return initialSlidingAggregateMetrics - the initial instance of the `SlidingAggregateMetrics` data type.
     */
    def initial(window: SampleWindow): SlidingAggregateMetrics = SlidingAggregateMetrics(SlidingVector[Boolean](window))
  }
  /**
   * This data type is used for testing the protected program by periodically letting the program execute when the associated
   * [[CircuitBreaker]] is open, so that we can determine when it is appropriate to close the CB after a configured minimum
   * number of consecutive execution successes.
   * @param config - the [[FailureSettings#Test]] configuration for testing an open `CircuitBreaker`, including the minimum number of
   *   consecutive tests to close an open CB and the minimum time interval between tests (between which program executions are failed
   *   fast without executing).
   * @param timestamp - the `java.time.Instant` timestamp indicating the time the last test was executed.[[FailureSettings#Test]]
   *   configuration for testing an open `CircuitBreaker`.
   * @param successes - the current number of consecutive test execution successes.
   */
  case class Testing(config: FailureSettings.Test, timestamp: Instant = Instant.EPOCH, successes: Int = 0) {
    /**
     * Renders the test as a human-readable string value.
     */
    def show: String = s"$successes successes"

    /**
     * Updates this testing instance, passing in the test success/failure indicator and a timestamp signifying when
     * the test took place.  It returns an updated instance with the state modified appropriately.
     * @param ts - a `java.time.Instant` timestamp indicating the time at which the test is being updated.
     * @param success - a boolean flag indicating whether or not the test execution was successful.
     * @return updatedTest - a new instance with the state updated appropriately.
     */
    def update(ts: Instant, success: Boolean): Testing =
      if (success) copy(timestamp = ts, successes = successes + 1) else copy(timestamp = ts, successes = 0)

    /**
     * Used by [[CircuitBreaker]]s to determine if a test interval time has passed (or is in the past, thus the passed/past
     * play on words of the name).
     * @param current - the current `java.time.Instant` timestamp.
     * @return timeHasPassed - a `Boolean` indicating that the configured test time interval has passed between the `timestamp`
     *   of this instance and the `current` timestamp passed into this method.
     */
    def intervalHasPast(current: Instant): Boolean = timestamp.isBefore(current minusMillis config.interval.toMillis)
  }
  /**
   * Creates an initial instance of the `FailureStatistics` with the passed-in identifier and configuration.  All other
   * instances are created by instance methods when the state needs updating.
   * @param id - the [[CircuitBreaker#Identifier]] of the associated [[CircuitBreaker]] instance, binding the
   *   two together.
   * @param config - the [[FailureSettings]] of the associated `CircuitBreaker`, used to configure the statistics
   *   as appropriate.
   * @return initialFailureSettings - the initial instance for this data type.
   */
  def initial(id: CircuitBreaker.Identifier, config: FailureSettings): FailureStatistics =
    FailureStatistics(id, config, SlidingAggregateMetrics.initial(config.sampleWindow), None, None, Instant.EPOCH)
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
    change: Option[FlowControlStatistics.Change]
) extends Statistics {

  import FlowControlStatistics._

  /**
   * Indicates that the last state change was that the maximum acceptable rate was increased
   * (the [[CircuitBreaker]] can allow its protected program execution to throttle up).
   * @return isThrottledUp - the last state change indicates that the maximum acceptable rate has been increased.
   */
  def throttledUp: Boolean = change contains Change.ThrottledUp

  /**
   * Indicates that the last state change was that the maximum acceptable rate was decreased
   * (the [[CircuitBreaker]] should throttle down the rate of requests it allows through to execution, throttling the service down).
   * @return isThrottledDown - the last state change indicates that the maximum acceptable rate has been decreased.
   */
  def throttledDown: Boolean = change contains Change.ThrottledDown

  /**
   * The mean/average effective inbound rate per second (effective in that requests that are throttled/failed fast are not
   * counted in the effective rate) over the sample window.
   * @return meanInboundFlowRate - the mean effective inbound rate.
   */
  def meanInboundRate: MeanFlowRate = metrics.meanInboundRate

  /**
   * The mean/average observed processing rate per second over the sample window.
   * @return meanProcessingFlowRate - the mean processing rate.
   */
  def meanProcessingRate: MeanFlowRate = metrics.meanProcessingRate

  /**
   * The maximum acceptable inbound rate.  This is differentiated from the mean processing rate in that you can configure
   * a percentage that we should allow the inbound rate exceed the processing rate (to account for spikes and valleys, for instance).
   * This value is the mean processing rate + the added percentage over allowed, if it is configured as a non-zero value.
   * @return maxAcceptableRate - the maximum acceptable rate, if it has been calculated yet (it will not be calculated until
   *   a full window of statistics has been collected).
   */
  def maxAcceptableRate: Option[MeanFlowRate] = metrics.maxAcceptableRate

  /**
   * Should the current request be throttled (failed fast), given this statistics object? This function will evaluate
   * the inbound rate and processing rate averages, assuming both have followed a full window's worth of statistics, and
   * determine if the current effective inbound rate is greater than the maximum acceptable rate, or if the configured hard
   * limit rate has been exceeded.  If either of those things evaluate true, this function will also.
   * @return shouldBeThrottled - true if the current request should be throttled.
   */
  def shouldBeThrottled: Boolean = indicatesShouldBeThrottled(metrics)

  /**
   * Convenience copy constructor, applying the passed-in [[FailureStatistics]].
   * @param failure - a [[FailureStatistics]] to copy into this `FlowControlStatistics` instance.
   * @return newFlowControlStatsInstance - a new instance of this data type with an updated `failure` property.
   */
  def withFailure(failure: FailureStatistics): FlowControlStatistics = copy(failure = failure)

  /**
   * Lifecycle function invoked by the [[CircuitBreaker]] before it checks to see if it should throttle a request, passing
   * a timestamp indicating the instant a request has been received.
   * @param startTime - a `java.time.Instant` timestamp indicating the start time of a request (or, to be more precise, the
   *   time the request was received, since it may not be started at all should it get throttled).
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

  private def indicatesShouldBeThrottled(m: Metrics) = (
    m.meanInboundRate.perSecond >= m.config.perSecondRateMinimum && m.maxAcceptableRate.exists { m.meanInboundRate.perSecond > _.perSecond }
  ) || (m.config.perSecondRateHardLimit > 0L && m.inboundRate.currentSample.perSecondCount > m.config.perSecondRateHardLimit)
}

/**
 * The companion object to the `FlowControlStatistics` instances, providing various data types and constructor
 * functions used by and for those instances.
 */
object FlowControlStatistics {
  /**
   * An Algebraic Data Type (ADT) which represents the latest state change for a `FlowControlStatistics` instance.
   * If the `changed` property of the `FlowControlStatististics` instance is set to Some(`ThrottledUp`), it indicates the
   * associated `CircuitBreaker` can allow / has just allowed more requests to execute per second (the maximum acceptable
   * rate has increased), if set to Some(`ThrottledDown`), it indicates it must allow / has just allowed fewer requests to
   * execute per second (the maximum acceptable rate has decreased), and if set to None, it indicates that the `CircuitBreaker`
   * maximum allowable inbound rate has not been modified with the latest state change.
   */
  sealed abstract class Change extends Product with Serializable {
    /**
     * Renders the change as a human-readable string value.
     */
    def show: String
  }
  object Change {

    /** Indicates that the associated `CircuitBreaker` has just been allowed to execute more requests per second. */
    case object ThrottledUp extends Change {
      /**
       * Renders the throttled up change as a human-readable string value.
       */
      def show: String = "throttled up"
    }

    /** Indicates that the associated `CircuitBreaker` has just been required to execute fewer requests per second. */
    case object ThrottledDown extends Change {
      /**
       * Renders the throttled down change as a human-readable string value.
       */
      def show: String = "throttled down"
    }
  }

  /**
   * The sliding statistics, which maintains a rate per second for the associated [[CircuitBreaker]].  For each associated
   * `CircuitBreaker`, there are two instances of this data type, one where the rate represents the number of inbound
   * requests per second and one where the rate represents the number of processed requests per second.  Each one-second
   * sample rate is associated with a `java.time.Instant` timestamp in the [[SlidingVector]] contained here and the
   * sliding vector keeps only the items with associated time-stamps in the configured range of the statistics
   * window (specified when the `SlidingVector[Boolean]` is created).
   * @param vector - the `SlidingVector[Long]` containing the request rate per second for the latest
   *   protected program executions which fall within the configured time range of the statistics window (e.g.,
   *   a 30s configuration would keep all items with a timestamp that falls within 30 seconds of the current time).
   */
  case class SlidingAggregateMetrics(vector: SlidingVector[Long]) {
    /**
     * The mean/average of the per-second rate values in the contained `SlidingVector[Long]`.
     */
    val mean: Double = if (vector.entries.isEmpty) 0.0 else vector.entries.map(_.value.toDouble).sum / vector.entries.size.toDouble
    /**
     * Add the per-second rate value to the `SlidingVector[Long]`, associated with the passed-in `java.time.Instant` timestamp. If
     * there has been more than one second of idle time (more than one second since the last time `addToWindow` was called), pass in
     * the number of 0 per-second sample values to first add to the sliding vector to represent that idle time, providing them with
     * associated time-stamps increasingly in the past by one second intervals as appropriate.  The sliding vector is truncated if necessary
     * to remove any values with time-stamps older than the time-range of the sample window as configured when this data type is initially
     * constructed.
     * @param timestamp - the `java.time.Instant` timestamp, indicating the time at the end of the second for which the `value` parameter
     *   represents the per-second request rate.
     * @param value - the `Long` value indicating the number of requests received or processed in the past second.
     * @param zeroFillIdleTime - the number of idle seconds which have passed since the last call to this function, used to zero fill the
     *   sliding vector of per-second rate values to represent that idle time.
     * @return updatedSlidingAggregateMetrics - a new instance of this data type, updated appropriately.
     */
    def addToWindow(timestamp: Instant, value: Long, zeroFillIdleTime: Long): SlidingAggregateMetrics = {
      val idleTimeAdjusted = 1L.to(zeroFillIdleTime.min(vector.window.maximumEntries.toLong)).toVector.reverse.foldLeft(vector) {
        case (vec, i) => vec.add(timestamp.minusSeconds(i), value)
      }
      copy(vector = idleTimeAdjusted.add(timestamp, value))
    }
  }
  /**
   * The companion object for instances of `SlidingAggregateMetrics` instances provides a smart constructor for an initial
   * instance (all other instances are created via instance methods).
   */
  object SlidingAggregateMetrics {
    /**
     * Creates an initial instance, with a [[SlidingVector]] constructed using the passed-in [[SampleWindow]], which specifies
     * the time-range for which to maintain individual per-second request rate statistics.  All other instances are created by
     * instance methods when the state needs updating.
     * @param window - a `SampleWindow` which specifies the time-range desired for the `SlidingVector[Long]`.
     * @return initialSlidingAggregateMetrics - the initial instance of the `SlidingAggregateMetrics` data type.
     */
    def initial(window: SampleWindow): SlidingAggregateMetrics = SlidingAggregateMetrics(SlidingVector[Long](window))
  }

  /**
   * This represents the current second's request count (either inbound or processed, depending on the
   * usage) and the starting timestamp.  When the second has elapsed, the count is added to the
   * associated sliding statistics collection, the timestamp updated and the count reset to zero.
   * @param startTime - a `java.time.Instant` timestamp representing the start of the current second.
   * @param perSecondCount - the count of requests during this second.
   */
  case class FlowRateSample(startTime: Instant, perSecondCount: Long) {
    /**
     * Renders the flow rate sample as a human-readable string value.
     */
    def show: String = s"$perSecondCount successes"
  }
  case class MeanFlowRate(perSecond: Double) {
    /**
     * Renders the mean flow rate as a human-readable string value.
     */
    def show: String = s"$perSecond per second"
  }

  /**
   * Maintains the sliding window per second request rates as well as the current second's request count.
   * @param perSecond - a [[SlidingAggregateMetrics]] instance which contains a sliding window of the aggregate rate statistics.
   * @param currentSample - a [[FlowRateSample]] instance which contains the current per-second request count
   *   and a timestamp indicating the start time of this count (when a second has past, the value is added to the sliding
   *   window of values and cleared to begin the next count).
   */
  case class AggregateFlowRate(perSecond: SlidingAggregateMetrics, currentSample: FlowRateSample) {
    /**
     * The mean/average of the per-second rate values in the contained `SlidingAggregateMetrics`.
     */
    def mean: Double = perSecond.mean

    /**
     * Indicates whether we've collected enough per-second request rate samples to completely fill the sliding window of values,
     * indicating we have enough data to derive meaningful statistics.  For instance, if the sample window for the sliding vector
     * is 30 seconds, this will return true if we've collected over 30 samples.
     */
    def fullWindowCollected: Boolean = perSecond.vector.fullWindowCollected

    /**
     * Increments the per-second request rate count.
     * @return newAggregateFlowRate - A new instance of this data type with the per-second count incremented by 1.
     */
    def addToSample(): AggregateFlowRate = copy(currentSample = currentSample.copy(perSecondCount = currentSample.perSecondCount + 1))

    /**
     * Possibly age the current per-second sample, if more than one second has passed since the current per-second sample count started,
     * by copying the count into the aggregate sliding window and resetting the current sample count to 0 and its start time to the passed-in
     * `time` parameter.  The aggregate sliding window of stats is first filled with zero count samples for the number of seconds over 2
     * which have occurred since the last time this function was invoked.
     * @param time - the `java.time.Instant` timestamp representing the current time, usually (or at least the relative current time;
     *   not necessarily the current wall clock time).
     * @return newAggregateFlowRate - updated with the state updated if sufficient time has passed or else it returns itself.
     */
    def ageSample(time: Instant): AggregateFlowRate = if (time.isBefore(currentSample.startTime.plusSeconds(1))) this else {
      val secondsPast = if (currentSample.startTime == Instant.EPOCH) 0L else ChronoUnit.SECONDS.between(currentSample.startTime, time)
      val zeroFillIdleTime = if (secondsPast < 2L) 0L else secondsPast - 1L
      AggregateFlowRate(perSecond.addToWindow(time, currentSample.perSecondCount, zeroFillIdleTime), currentSample = FlowRateSample(time, 0L))
    }

    /**
     * Renders the aggregate flow rate as a human-readable string value.
     */
    def show: String = s"mean rate $mean, current per-second count ${currentSample.show}, stats count ${perSecond.vector.entries.size}, full window collected ${perSecond.vector.fullWindowCollected}"
  }

  /**
   * The companion object to the `AggregateFlowRate` instances, providing a constructor for the initial instance.
   */
  object AggregateFlowRate {
    /**
     * Creates an initial instance, with a [[SlidingAggregateMetrics]] instance constructed using the passed-in [[SampleWindow]], which specifies
     * the time-range for which to maintain individual request rate statistics.  All other instances are created by instance methods
     * when the state needs updating.
     * @param window - a `SampleWindow` which specifies the time-range desired for the `SlidingAggregateMetrics`.
     * @return initialSlidingFlowRate - the initial instance of the `AggregateFlowRate` data type.
     */
    def initial(window: SampleWindow): AggregateFlowRate =
      AggregateFlowRate(SlidingAggregateMetrics.initial(window), FlowRateSample(Instant.EPOCH, 0L))
  }

  /**
   * This data structure consists of the aggregate flow rate statistics (with sliding stats windows) for both the inbound and
   * the processing rate, along with the [[FlowControlSettings]] used to initial configure them.
   * @param config - the `FlowControlSettings` containing the configuration used to constrain and inform the inbound and processed
   *   rate statistics.
   * @param inboundRate - the [[AggregateFlowRate]] containing the current per-second request inbound rate (count) and the
   *   time-constrained window (collection) of per-second rates so we can derive statistics on them.
   * @param processingRate - the [[AggregateFlowRate]] containing the current per-second request processing rate (count) and
   *   the time-constrained window (collection) of per-second rates so we can derive statistics on them.
   */
  case class Metrics(config: FlowControlSettings, inboundRate: AggregateFlowRate, processingRate: AggregateFlowRate) {

    /**
     * The mean/average inbound request rate.
     */
    val meanInboundRate: MeanFlowRate = MeanFlowRate(inboundRate.mean)

    /**
     * The mean/average processing request rate.
     */
    val meanProcessingRate: MeanFlowRate = MeanFlowRate(processingRate.mean)

    /**
     * The maximum acceptable inbound request rate, if we can calculate one (we must have a full window of
     * inbound rate samples collected in order to calculate it).  This is calculated by current mean processing
     * rate + a configurable percentage over the processing rate.
     */
    val maxAcceptableRate: Option[MeanFlowRate] =
      if (inboundRate.fullWindowCollected && meanProcessingRate.perSecond > 0) Some(MeanFlowRate(meanProcessingRate.perSecond + (meanProcessingRate.perSecond * config.allowedOverProcessingRate.percent / 100.0))) else None

    /**
     * This ages both the inbound and processing rate statistics.  This is a lifecycle function invoked by the
     * associated `CircuitBreaker` prior to determining whether or not to throttle the inbound request (by
     * throttle, we mean that the request will be failed fast rather than executed and that request therefore
     * not counted in the inbound rate, thus bringing the effect rate down).
     * @param startTime - the `java.time.Instant` timestamp that represents the time a request was received
     *   (used as its start time).
     * @return newMetrics - a new instance of this data type with the state possibly updated (if the sample
     *   rates were aged).
     */
    def beforeThrottle(startTime: Instant): Metrics =
      copy(inboundRate = inboundRate.ageSample(startTime), processingRate = processingRate.ageSample(startTime))

    /**
     * This increments the current sample's inbound request count.
     * This is a lifecycle function invoked by the associated `CircuitBreaker` after determining we do not need
     * to throttle the request but before we execute it.
     * @return newMetrics - a new instance of this data type with the inbound rate incremented by 1.
     */
    def beforeExecution: Metrics = copy(inboundRate = inboundRate.addToSample())

    /**
     * This increments the current sample's processing request count.
     * This is a lifecycle function invoked by the associated `CircuitBreaker` after we have executed
     * the request and it has completed.
     * @return newMetrics - a new instance of this data type with the processing rate incremented by 1.
     */
    def afterExecution: Metrics = copy(processingRate = processingRate.addToSample())

    /**
     * Renders the aggregate flow rate as a human-readable string value.
     */
    def show: String = s"inbound: [ ${inboundRate.show} ], processed: [ ${processingRate.show} ]"
  }

  /**
   * Creates an initial instance of the `FlowControlStatistics` with the passed-in identifier and configuration.
   * All other instances are created by instance methods when the state needs updating.
   * @param id - the [[CircuitBreaker#Identifier]] of the associated [[CircuitBreaker]] instance, binding the
   *   two together.
   * @param config - the [[FlowControlSettings]] of the associated `CircuitBreaker`, used to configure the statistics
   *   as appropriate.
   * @return initialFlowControlSettings - the initial instance for this data type.
   */
  def initial(id: CircuitBreaker.Identifier, config: FlowControlSettings): FlowControlStatistics = FlowControlStatistics(
    id,
    Metrics(config, AggregateFlowRate.initial(config.sampleWindow), AggregateFlowRate.initial(config.sampleWindow)),
    FailureStatistics.initial(id, config.failure),
    None
  )
}
