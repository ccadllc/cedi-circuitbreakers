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

import fs2.util.Async

import scala.language.higherKinds

/**
 * The circuitbreaker library provides protection against cascading failure and
 * system overload via the [[CircuitBreaker]] and its primary function [[CircuitBreaker#protect]].
 * A [[CircuitBreakerRegistry]] maintains the collection of active circuit breakers and is
 * the interface through which circuit breakers are created, retrieved, and removed and by
 * which clients can subscribe for state change and statistics events related to the
 * circuit breakers.
 */
package object circuitbreaker {
  object syntax {
    /**
     * Provides syntax enrichment on the effectful program `F[A]` so that
     * one can write, for example, `task.protect(circuitBreaker)` rather than
     * `circuitBreaker.protect(task)`.  This can lead to less awkward code
     * under certain circumstances.
     */
    implicit class CircuitBreakerAsyncF[F[_], A](val self: F[A]) extends AnyVal {
      /**
       * Alternate manner in which to protect the effectful program `F[A]` when
       * there is an instance of `fs2.util.Async[F]` in implicit scope.  See [[CircuitBreaker#protect]]
       * for details.
       * @param cb - the [[CircuitBreaker]] instance which will protect this `F[A]`.
       * @param F - the `fs2.util.Async[F]` in implicit scope which provides the constructs
       *   under which `F[A]` executes.
       * @return enhancedProgram - the protected effectful program.
       */
      def protect(cb: CircuitBreaker[F])(implicit F: Async[F]): F[A] = cb.protect(self)
    }
  }
}
