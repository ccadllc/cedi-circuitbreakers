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

import cats.effect.Sync
import cats.implicits._

import java.util.concurrent.atomic.AtomicReference

/*
 * Provides lock-free atomic retrieval and update capabilities for an unconstrained immutable
 * data type.  Used by this library to maintain state in the [[CircuitBreaker]] instances and the [[CircuitBreakerRegistry]].
 */
private[circuitbreaker] final class StateRef[F[_], A] private (ref: AtomicReference[A])(implicit F: Sync[F]) {
  /* Retrieve the state */
  def get: F[A] = F.delay(ref.get)
  /* Modify the state with the passed-in function, returning the modified value */
  def modify(f: A => A): F[A] = F.delay(ref.updateAndGet(a => f(a)))
  /*
   * Retrieve a sub-component `B` of the state `A` with the `retriever` function,
   * creating the component `B` with the `creator` function if it does not exist in `A`
   * (modifying `A` with the newly created `B` using the `toA` function), returning an
   * effectful program that when run will produce the desired component `B`.
   */
  def getOrCreate[B](retriever: A => Option[B], creator: F[B], toA: (A, B) => A): F[B] = {
    def createB: F[B] = for {
      cb <- creator
      a <- modify(a => retriever(a).fold(toA(a, cb))(_ => a))
      b <- retriever(a).fold(createB)(F.pure)
    } yield b
    for {
      a <- get
      bMaybe <- F.pure(retriever(a))
      b <- bMaybe.fold(createB)(F.pure)
    } yield b
  }
}
private[circuitbreaker] object StateRef {
  def create[F[_], A](value: => A)(implicit F: Sync[F]): F[StateRef[F, A]] = F.delay(new StateRef[F, A](new AtomicReference(value)))
}
