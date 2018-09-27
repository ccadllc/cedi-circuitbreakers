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

/**
 * This data type provides the storage and access of a sliding
 * time window of statistics, used to maintain aggregate program failures
 * as well as observed inbound program request and processing rates.
 * @param window - the [[SampleWindow]] for a `SlidingVector` indicates the time
 *   period (the `scala.concurrent.duration.FiniteDuration`) for which this
 *   collection should maintain statistics. For instance, a sample window of "five
 *   minutes" would indicate that the `entries` `Vector` contains items with a
 *   time stamp no more than five minutes in the past.
 * @param fullWindowCollected - a flag indicating whether or not at least one full pass equal to the time
 *   period has been collected in the underlying vector (used to determine whether there is enough data
 *   to derive statistics from it).
 * @param entries - the `scala.collection.immutable.Vector` of `SlidingVector.TimeStamped[A]`
 *   items (the `TimeStamped` data type associates a `java.time.Instant` with a simple statistic
 *   item `A`, such as `Boolean` or `Long`, for example).
 */
case class SlidingVector[A](
  window: SampleWindow,
  fullWindowCollected: Boolean = false,
  entries: Vector[SlidingVector.TimeStamped[A]] = Vector.empty) {
  /**
   * Resets the vector of items to zero, returning a new copy.
   * @return newSlidingVector - a new copy of the vector.
   */
  def reset: SlidingVector[A] = copy(fullWindowCollected = false, entries = Vector.empty)

  /**
   * Adds a value to the vector with an associated timestamp.  The sliding window
   * of the vector is re-evaluated to remove oldest items no longer in the time window.
   * @param timestamp - the `java.time.Instant` timestamp associated with the value.
   * @param value - the unconstrained value `A` to be associated with the timestamp.
   * @return newSlidingVector - a new copy of the sliding vector.
   */
  def add(timestamp: Instant, value: A): SlidingVector[A] = {
    val earliest = timestamp.minusMillis(window.duration.toMillis)
    val updated = (
      entries.span(_.time.isBefore(earliest))._2 :+ SlidingVector.TimeStamped(timestamp, value)).takeRight(window.maximumEntries)
    def sizeHasNotIncreased = updated.size <= entries.size
    copy(fullWindowCollected = fullWindowCollected || sizeHasNotIncreased, entries = updated)
  }
}

/**
 * The companion for `SlidingVector`, consisting of data types used by its associated instances.
 */
object SlidingVector {
  /**
   * A simple data type which associates a date/time stamp with a value.
   * @param time - a `java.time.Instant` representing a timestamp
   * @param value - an unconstrained value `A` to be associated with the timestamp.
   */
  case class TimeStamped[A](time: Instant, value: A)
}
