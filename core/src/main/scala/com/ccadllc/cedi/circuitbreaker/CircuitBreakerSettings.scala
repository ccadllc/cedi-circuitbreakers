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

import scala.concurrent.duration._

import com.ccadllc.cedi.config._
import ConfigParser._

/**
 * Represents a percentage value, used throughout the library.
 */
case class Percentage(percent: Double) {
  import Percentage._
  require(percent >= Minimum && percent <= Maximum, s"Percent must be between $Minimum and $Maximum inclusive but was $percent")

  /**
   * Add two percentages together, ensuring that the result will be within the limits
   * of the [[Percentage#Minimum]] and [[Percentage#Maximum]].
   * @param other - the percentage to add to `this`
   * @return combinedPercentage - the sum total of the two percentages,
   *   within the limits of [[Percentage#Minimum]] and [[Percentage#Maximum]].
   */
  def plus(other: Percentage): Percentage = Percentage.withinLimits(percent + other.percent)

  /**
   * Subtract two percentages, ensuring that the result will be within the limits
   * of the [[Percentage#Minimum]] and [[Percentage#Maximum]].
   * @param other - the percentage to subtract from `this`
   * @return subtractedPercentage - the result of subtracting the `other` percentage from `this`,
   *   within the limits of [[Percentage#Minimum]] and [[Percentage#Maximum]].
   */
  def minus(other: Percentage): Percentage = Percentage.withinLimits(percent - other.percent)

  /**
   * Is the passed-in percentage greater than `this`?
   * @param other - the percentage to compare
   * @return isGreater - the passed-in percentage is (or isn't) greater than `this`.
   */
  def greaterThan(other: Percentage): Boolean = percent > other.percent

  /**
   * The inverse of `this`.  That is, subtract 100.00 from the value of `this`.
   * @return inversePercentage - the result of subtracting 100.00 from the value of `this`.
   */
  def inverse: Percentage = Percentage.withinLimits(100.0 - percent)

  /**
   * human-readable rendering of the `Percentage`.
   */
  def show: String = s"$percent%"
}

object Percentage {

  /** The minimum value of a `Percentage` */
  final val Minimum: Double = 0.0

  /** The maximum value of a `Percentage` */
  final val Maximum: Double = 100.0

  /** Used to parse from `HCON` formatted configuration files */
  implicit val configParser: ConfigParser[Percentage] = derived[Percentage]

  /** commonly used value representing a minimum percentage */
  val minimum: Percentage = Percentage(Minimum)

  /** Is the value within our imposed percentage limits? */
  def withinLimits(value: Double): Percentage = Percentage(value.min(Maximum).max(Minimum))
}

/**
 * Represents a window of time, defined by the `duration` property and
 * used to constrain the number of timestamped sample items retained in order
 * to calculate statistics for deriving [[CircuitBreaker]] state changes.  A
 * hard limit of the number of samples is defined by the `maximumEntries`
 * property.
 */
case class SampleWindow(duration: FiniteDuration, maximumEntries: Int = Int.MaxValue) {
  require(maximumEntries > 0, s"maximum entries must be a positive number")
  def show: String = s"${duration.toCoarsest} with failsafe maximum entries of $maximumEntries"
}
object SampleWindow {
  implicit val configParser: ConfigParser[SampleWindow] = (
    duration("duration") ~ int("maximum-entries").optional
  ) map { case (dur, mes) => SampleWindow(dur, mes.getOrElse(dur.toSeconds.toInt + 1)) }
}

/**
 * The configuration used for the [[CircuitBreakerRegistry]] - it defines how often the
 * registry should examine the [[CircuitBreaker]]s under its control to remove instances
 * which have been inactive longer than the configured cut-off time.  If the `checkInterval`
 * property of the [[RegistrySettings#GarbageCollection]] configuration item is set to a
 * 0 value (e.g., 0.milliseconds), this feature will then be disabled.
 */
case class RegistrySettings(garbageCollection: RegistrySettings.GarbageCollection) {
  def show: String = s"garbage collection [ ${garbageCollection.show} ]"
}
object RegistrySettings {
  case class GarbageCollection(checkInterval: FiniteDuration, inactivityCutoff: FiniteDuration) {
    def show: String = s"check interval [ ${checkInterval.toCoarsest} ], inactivity cutoff [ ${inactivityCutoff.toCoarsest} ]"
  }
  object GarbageCollection {
    implicit val configParser: ConfigParser[GarbageCollection] = derived[GarbageCollection]
  }
  implicit val configParser: ConfigParser[RegistrySettings] =
    subconfig("com.ccadllc.cedi.circuitbreaker.registry")(derived[RegistrySettings])
}

/**
 * The configuration which controls the failure-based [[CircuitBreaker]]s (that is, those circuit breakers
 * which are created via the [[CircuitBreakerRegistry#forFailure]] function).
 * @param sampleWindow - the time-based window to be used to constrain the number of program execution
 *   success/failure indicators to store in order to determine the percentage of failures over this
 *   time period.  An example would be `SampleWindow(2.minutes)` which means maintain the last two minutes
 *   of program execution success/failure indicators for a program protected by a given [[CircuitBreaker]].
 * @param degradationThreshold - the [[Percentage]] of items within the sample window which indicate program
 *   failure.  For instance, if the percentage were `Percentage(40.0)` and there were 50 sample items in the
 *   2 minute window, with 25 indicating program failure, then since 50% is greater than 40%, the circuit breaker
 *   would open.
 * @param test - the testing configuration is used when the circuit breaker is open in order to determine how
 *   often a program should be executed rather than failed fast and then how many consecutively successful
 *   test executions need to occur in order to close the circuit breaker and allow normal operation of the
 *   protected program.
 * @param enabled - The [[CircuitBreaker]] can be enabled or disabled with this parameter.
 */
case class FailureSettings(
    sampleWindow: SampleWindow,
    degradationThreshold: Percentage,
    test: FailureSettings.Test,
    enabled: Boolean = true
) {
  def show: String = s"sample window [ ${sampleWindow.show} ], degradation threshold [ ${degradationThreshold.show} ], test [ ${test.show} ], enabled [ $enabled ]"
}
object FailureSettings {
  case class Test(interval: FiniteDuration, minimumSuccesses: Int) {
    def show: String = s"interval [ ${interval.toCoarsest} ], minimum successes [ $minimumSuccesses ]"
  }
  object Test {
    implicit val configParser: ConfigParser[Test] = (duration("interval") ~ int("minimum-successes")) map { case (i, ms) => Test(i, ms) }
  }
  implicit val configParser: ConfigParser[FailureSettings] = (
    subconfig("sample-window")(SampleWindow.configParser) ~
    subconfig("degradation-threshold")(Percentage.configParser) ~
    subconfig("test")(Test.configParser) ~
    bool("enabled").withFallbackDefault(true)
  ) map { case (sw, dt, t, e) => FailureSettings(sw, dt, t, e) }
}

/**
 * The configuration which controls the flow control-based [[CircuitBreaker]]s (that is, those circuit breakers
 * which are created via the [[CircuitBreakerRegistry#forFlowControl]] function).
 * @param failure - the configuration for the failure component of the flow control [[CircuitBreaker]].  In addition
 *   to providing flow control, it also provides the same failure functionality as its failure-only variant.
 * @param sampleWindow - the time-based window to be used to constrain the number of inbound program execution
 *   requests per second and program executions processed per second.  The flow control circuit breaker maintains
 *   two separate collections (the inbound rate and the processing/completed rate) and both use this sample window
 *   configuration to constrain the sizes of these collections.
 * @param allowedOverProcessingRate - the [[Percentage]] that the mean inbound rate is allowed to exceed the mean processing
 *   rate before this circuit breaker starts throttling inbound requests to lower it to an acceptable rate.  Put another
 *   way, the acceptable rate is the processing rate + (processing rate * percentage / 100).
 * @param minimumReportableRateChange - this is the percent that the calculated acceptable rate must be observed to have
 *   changed (either lower or higher) before a state change event is triggered.  If it is 0, all state changes result
 *   in an event.  This configuration item is meant to alleviate a flood of events when the request traffic pattern
 *   is very choppy (lots of spikes and valleys in a short time period).
 * @param perSecondRateMinimum - this is the minimum observed inbound rate at which flow control throttling will kick in.
 * @param perSecondRateHardLimit - this is the hard limit of the inbound requests per second above which requests will be
 *   throttled by the circuit breaker (failed fast without execution) for the remainder of the current second.  It will
 *   be monitored and acted upon continuously.
 * @param enabled - The flow control portion of the [[CircuitBreaker]] can be enabled or disabled with this parameter.
 */
case class FlowControlSettings(
    failure: FailureSettings,
    sampleWindow: SampleWindow,
    allowedOverProcessingRate: Percentage,
    minimumReportableRateChange: Percentage,
    perSecondRateMinimum: Long,
    perSecondRateHardLimit: Long,
    enabled: Boolean = true
) {
  def show: String = s"failure [ {failure.show} ], sample window [ ${sampleWindow.show} ], allowed over processing rate [ ${allowedOverProcessingRate.show} ], minimum reportable rate change [ ${minimumReportableRateChange.show} ], per second rate minimum [ $perSecondRateMinimum ], per second rate hard limit [ $perSecondRateHardLimit ], enabled [ $enabled ]"
}
object FlowControlSettings {
  implicit val configParser: ConfigParser[FlowControlSettings] = (
    subconfig("failure")(FailureSettings.configParser) ~
    subconfig("sample-window")(SampleWindow.configParser) ~
    subconfig("allowed-over-processing-rate")(Percentage.configParser) ~
    subconfig("minimum-reportable-rate-change")(Percentage.configParser) ~
    long("per-second-rate-minimum").withFallbackDefault(1L) ~
    long("per-second-rate-hard-limit") ~
    bool("enabled").withFallbackDefault(true)
  ) map { case (f, sw, aopr, mrrc, psrm, psrhl, e) => FlowControlSettings(f, sw, aopr, mrrc, psrm, psrhl, e) }
}
