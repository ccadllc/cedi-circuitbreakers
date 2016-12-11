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

case class SlidingVector[A](
    window: SampleWindow,
    fullWindowCollected: Boolean = false,
    entries: Vector[SlidingVector.TimeStamped[A]] = Vector.empty
) {
  def reset: SlidingVector[A] = copy(fullWindowCollected = false, entries = Vector.empty)
  def add(timestamp: Instant, value: A): SlidingVector[A] = {
    val earliest = timestamp.minusMillis(window.duration.toMillis)
    val updated = (
      entries.span(_.time.isBefore(earliest))._2 :+ SlidingVector.TimeStamped(timestamp, value)
    ).takeRight(window.maximumEntries)
    def sizeHasNotIncreased = updated.size <= entries.size
    copy(fullWindowCollected = fullWindowCollected || sizeHasNotIncreased, entries = updated)
  }
}
object SlidingVector { case class TimeStamped[A](time: Instant, value: A) }
