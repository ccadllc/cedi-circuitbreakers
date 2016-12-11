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

case class Percentage(percent: Double) {
  import Percentage._
  require(percent >= Minimum && percent <= Maximum, s"Percent must be between $Minimum and $Maximum inclusive but was $percent")
  def plus(other: Percentage): Percentage = Percentage.withinLimits(percent + other.percent)
  def minus(other: Percentage): Percentage = Percentage.withinLimits(percent - other.percent)
  def greaterThan(other: Percentage): Boolean = percent > other.percent
  def inverse: Percentage = Percentage.withinLimits(100.0 - percent)
  def show: String = s"$percent%"
}
object Percentage {
  final val Minimum: Double = 0.0
  final val Maximum: Double = 95.0
  implicit val configParser: ConfigParser[Percentage] = derived[Percentage]
  val minimum: Percentage = Percentage(Minimum)
  def withinLimits(value: Double): Percentage = Percentage(value.min(Maximum).max(Minimum))
}

case class SampleWindow(duration: FiniteDuration, maximumEntries: Int = Int.MaxValue) {
  require(maximumEntries > 0, s"maximum entries must be a positive number")
  def show: String = s"${duration.toCoarsest} with failsafe maximum entries of $maximumEntries"
}
object SampleWindow {
  implicit val configParser: ConfigParser[SampleWindow] = (
    duration("duration") ~ int("maximum-entries").optional
  ) map { case (dur, mes) => SampleWindow(dur, mes.getOrElse(dur.toSeconds.toInt + 1)) }
}

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

case class FlowControlSettings(
    failure: FailureSettings,
    sampleWindow: SampleWindow,
    allowedOverProcessingRate: Percentage,
    minimumReportableRateChange: Percentage,
    perSecondRateHardLimit: Long,
    enabled: Boolean = true
) {
  def show: String = s"failure [ {failure.show} ], sample window [ ${sampleWindow.show} ], allowed over processing rate [ ${allowedOverProcessingRate.show} ], minimum reportable rate change [ ${minimumReportableRateChange.show} ], per second rate hard limit [ $perSecondRateHardLimit ], enabled [ $enabled ]"
}
object FlowControlSettings {
  implicit val configParser: ConfigParser[FlowControlSettings] = (
    subconfig("failure")(FailureSettings.configParser) ~
    subconfig("sample-window")(SampleWindow.configParser) ~
    subconfig("allowed-over-processing-rate")(Percentage.configParser) ~
    subconfig("minimum-reportable-rate-change")(Percentage.configParser) ~
    long("per-second-rate-hard-limit") ~
    bool("enabled").withFallbackDefault(true)
  ) map { case (f, sw, aopr, mrrc, psrhl, e) => FlowControlSettings(f, sw, aopr, mrrc, psrhl, e) }
}
