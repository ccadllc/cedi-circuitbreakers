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

import fs2.util.Async
import fs2.util.syntax._

import java.util.function.UnaryOperator

import java.util.concurrent.atomic.AtomicReference

import scala.language.higherKinds

private[circuitbreaker] final class StateRef[F[_], A] private (ref: AtomicReference[A])(implicit F: Async[F]) {
  def get: F[A] = F.delay(ref.get)
  def modify(f: A => A): F[A] = F.delay(ref.updateAndGet(new UnaryOperator[A] { def apply(a: A) = f(a) }))
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
  def create[F[_], A](value: => A)(implicit F: Async[F]): F[StateRef[F, A]] = F.delay(new StateRef[F, A](new AtomicReference(value)))
}
