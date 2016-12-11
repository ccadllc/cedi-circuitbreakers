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

import fs2._
import fs2.async.mutable.{ Signal, Topic }
import fs2.util.Async
import fs2.util.syntax._

import java.time.Instant

import scala.concurrent.duration._

import scala.language.higherKinds

import CircuitBreaker._
import CircuitBreakerRegistry._

import statistics.Statistics

final class CircuitBreakerRegistry[F[_]] private (
    state: StateRef[F, State[F]],
    eventTopic: Topic[F, Option[CircuitBreakerEvent]],
    shutdownTrigger: ShutdownTrigger[F]
)(implicit strategy: Strategy, scheduler: Scheduler, F: Async[F]) {

  def events(maxQueued: Int): Stream[F, CircuitBreakerEvent] =
    eventTopic.subscribe(maxQueued).collect { case Some(event) => event }.interruptWhen(shutdownTrigger.signal)

  def statistics(retrievalInterval: FiniteDuration): Stream[F, Statistics] = {
    def retrieveStatistics: F[Vector[Statistics]] = for {
      cbs <- circuitBreakers
      stats <- cbs.values.toVector.traverse { _.currentStatistics }
    } yield stats
    time.awakeEvery[F](retrievalInterval).evalMap { _ => retrieveStatistics }.flatMap { Stream.emits }.interruptWhen(shutdownTrigger.signal)
  }

  def shutdown: F[Unit] = state.modify(_.shutdown) flatMap { _ => shutdownTrigger.execute }

  def removeCircuitBreaker(id: Identifier): F[Unit] = state.modify(_.removeCircuitBreaker(id)) map { _ => () }

  def circuitBreakers: F[Map[Identifier, CircuitBreaker[F]]] = state.get map { _.circuitBreakers }

  def forFailure(
    id: Identifier,
    config: FailureSettings,
    evaluator: FailureEvaluator = FailureEvaluator.default
  ): F[CircuitBreaker[F]] = State.circuitBreaker(
    id,
    state,
    CircuitBreaker.forFailure(id, config, evaluator, publishEvent)
  )

  def forFlowControl(
    id: Identifier,
    config: FlowControlSettings,
    evaluator: FailureEvaluator = FailureEvaluator.default
  ): F[CircuitBreaker[F]] = State.circuitBreaker(
    id,
    state,
    CircuitBreaker.forFlowControl(id, config, evaluator, publishEvent)
  )

  private def publishEvent(event: CircuitBreakerEvent) = eventTopic.publish1(Some(event))
}

object CircuitBreakerRegistry {
  private class ShutdownTrigger[F[_]: Async](val signal: Signal[F, Boolean]) {
    def execute: F[Unit] = signal.set(true)
  }
  private case class State[F[_]: Async](circuitBreakers: Map[Identifier, CircuitBreaker[F]]) {
    def removeCircuitBreaker(id: Identifier): State[F] = copy(circuitBreakers = circuitBreakers - id)
    def addCircuitBreaker(cb: CircuitBreaker[F]): State[F] = copy(circuitBreakers = circuitBreakers + (cb.id -> cb))
    def shutdown: State[F] = State.empty[F]
  }
  private object State {
    def empty[F[_]: Async]: State[F] = State[F](Map.empty)
    def circuitBreaker[F[_]: Async](
      id: Identifier,
      ref: StateRef[F, State[F]],
      creator: F[CircuitBreaker[F]]
    ): F[CircuitBreaker[F]] = ref.getOrCreate[CircuitBreaker[F]](_.circuitBreakers.get(id), creator, _.addCircuitBreaker(_))
  }
  def create[F[_]](settings: RegistrySettings)(implicit strategy: Strategy, scheduler: Scheduler, F: Async[F]): F[CircuitBreakerRegistry[F]] = {
    def collectGarbageInBackground(state: StateRef[F, State[F]], shutdownSignal: Signal[F, Boolean]) = {
      val collectGarbage = for {
        now <- F.delay(Instant.now)
        inactivityCutoff = now minusMillis settings.garbageCollection.inactivityCutoff.toMillis
        circuitBreakers <- state.get map { _.circuitBreakers.values.toVector }
        expiredIds <- circuitBreakers.traverse { cb =>
          cb.lastActivity map { activityTs => if (activityTs.isBefore(inactivityCutoff)) Some(cb.id) else None }
        }.map { _.flatten }
        _ <- state.modify(s => s.copy(circuitBreakers = s.circuitBreakers filterNot { case (id, _) => expiredIds.contains(id) }))
      } yield ()
      if (settings.garbageCollection.checkInterval > 0.nanoseconds) F.start(
        time.awakeEvery[F](settings.garbageCollection.checkInterval).evalMap { _ => collectGarbage }.interruptWhen(shutdownSignal).run.map { _ => () }
      )
      else F.pure(())
    }
    for {
      eventTopic <- async.topic[F, Option[CircuitBreakerEvent]](None)
      state <- StateRef.create[F, State[F]](State.empty[F])
      shutdownSignal <- async.signalOf[F, Boolean](false)
      _ <- collectGarbageInBackground(state, shutdownSignal)
    } yield new CircuitBreakerRegistry(state, eventTopic, new ShutdownTrigger(shutdownSignal))
  }
}
