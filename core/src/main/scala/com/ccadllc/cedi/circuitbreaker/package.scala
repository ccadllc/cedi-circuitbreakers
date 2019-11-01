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
package com.ccadllc.cedi

/**
 * The circuitbreaker library provides protection against cascading failure and
 * system overload via the [[CircuitBreaker]] and its primary function [[CircuitBreaker#protect]].
 * A [[CircuitBreakerRegistry]] maintains the collection of active circuit breakers and is
 * the interface through which circuit breakers are created, retrieved, and removed and by
 * which clients can subscribe for state change and statistics events related to the
 * circuit breakers.
 */
package object circuitbreaker {
  /**
   * Provides syntax enrichment on the effectful program `F[A]` so that
   * one can write, for example, `prg.protect(circuitBreaker)` rather than
   * `circuitBreaker.protect(prg)`.  This can lead to less awkward code
   * under certain circumstances.
   */
  implicit class CircuitBreakerOps[F[_], A](val self: F[A]) extends AnyVal {
    /**
     * Alternate manner in which to protect the effectful program `F[A]`.
     * See [[CircuitBreaker#protect]] for details.
     *
     * @param cb - the [[CircuitBreaker]] instance which will protect this `F[A]`.
     * @return enhancedProgram - the protected effectful program.
     */
    def protect(cb: CircuitBreaker[F]): F[A] = cb.protect(self)
  }
}
