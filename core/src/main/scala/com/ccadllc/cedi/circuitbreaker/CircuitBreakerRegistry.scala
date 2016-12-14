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

/**
 * The circuit breaker registry maintains the non-persistent collection of [[CircuitBreaker]]s created
 * for a given VM.  It provides functions to create and retrieve circuit breakers and to subscribe to
 * `fs2.Stream`s of [[statistics.Statistics]] and
 * [[CircuitBreaker#CircuitBreakerEvent]]s.  A `CircuitBreakerRegistry`
 * is not normally directly instantiated but rather is created via the smart constructor in the companion
 * object.  The effectful program types protected are fixed by an `F` where an instance of `fs2.Async[F]` is
 * provided in implicit scope.
 * @param state - the [[State]] of the registry which can be updated and retrieved in an atomic manner.
 *   See [[StateRef]] for details.
 * @param eventTopic - the `fs2.async.mutable.Topic` to which we can publish state change events and from which
 *   subscribers can be notified of state changes.
 * @param shutdownTrigger - an effectful data type which allows the registry to clean up when shutting down.
 */
final class CircuitBreakerRegistry[F[_]] private (
    state: StateRef[F, State[F]],
    eventTopic: Topic[F, Option[CircuitBreakerEvent]],
    shutdownTrigger: ShutdownTrigger[F]
)(implicit strategy: Strategy, scheduler: Scheduler, F: Async[F]) {

  /**
   * Creates an `fs2.Stream` of [[CircuitBreaker#CircuitBreakerEvent]]s by subscribing to the event `fs2.async.mutable.Topic` maintained
   * by the registry.
   * @param maxQueued - the maximum number of events to queue before dropping the oldest.
   * @return streamOfEvents - an `fs2.Stream[F, CircuitBreakerEvent]` constituting the stream of state change events.
   */
  def events(maxQueued: Int): Stream[F, CircuitBreakerEvent] =
    eventTopic.subscribe(maxQueued).collect { case Some(event) => event }.interruptWhen(shutdownTrigger.signal)

  /**
   * Creates a `fs2.Stream` of [[statistics.Statistics]] for all [[CircuitBreaker]]s which are emitted at the interval provided.
   * @param retrievalInterval - the interval at which statistics are retrieved from registered [[CircuitBreaker]]s and emitted
   *  to the stats stream.
   * @return streamOfStatistics - an `fs2.Stream[F, Statistics]` constituting the stream of statistics.
   */
  def statistics(retrievalInterval: FiniteDuration): Stream[F, Statistics] = {
    def retrieveStatistics: F[Vector[Statistics]] = for {
      cbs <- circuitBreakers
      stats <- cbs.values.toVector.traverse { _.currentStatistics }
    } yield stats
    time.awakeEvery[F](retrievalInterval).evalMap { _ => retrieveStatistics }.flatMap { Stream.emits }.interruptWhen(shutdownTrigger.signal)
  }

  /**
   * Shuts down the registry, cleaning up its resources.
   * @return shutdownProgram - a program which when run will shut down the registry.
   */
  def shutdown: F[Unit] = state.modify(_.shutdown) flatMap { _ => shutdownTrigger.execute }

  /**
   * Removes the [[CircuitBreaker]] whose identifier is passed-in, if it exists.  It is a no-op if the identifier does not exist.
   * @return removalProgram - a program which when run will remove the [[CircuitBreaker]] identified by the [[CircuitBreaker#Identifier]],
   *   if it exists.
   */
  def removeCircuitBreaker(id: Identifier): F[Unit] = state.modify(_.removeCircuitBreaker(id)) map { _ => () }

  /**
   * Retrieves a collection of all the [[CircuitBreaker]]s indexed by their [[CircuitBreaker#Identifier]].
   * @return collectionOfCircuitBreakers - a program which when run will result in a `Map` of [[CircuitBreaker#Identifier]] -> [[CircuitBreaker]].
   */
  def circuitBreakers: F[Map[Identifier, CircuitBreaker[F]]] = state.get map { _.circuitBreakers }

  /**
   * Retrieves an existing [[CircuitBreaker]] instance which protects against cascading failure conditions, if it exists in the registry.  If
   * it does not yet exist, a new instance is created and stored in the registry state.
   * @param id - uniquely identifies a [[CircuitBreaker]] instance within this registry.
   * @param config - the [[FailureSettings]] configuration for the [[CircuitBreaker]].
   * @param evaluator - the [[CircuitBreaker#FailureEvaluator]] to use for the [[CircuitBreaker]], used to determine what program errors should be
   *   used to determine state changes.
   * @return circuitBreaker - an effectful program that when run will return an instance of a failure [[CircuitBreaker]].
   */
  def forFailure(
    id: Identifier,
    config: FailureSettings,
    evaluator: FailureEvaluator = FailureEvaluator.default
  ): F[CircuitBreaker[F]] = State.circuitBreaker(
    id,
    state,
    CircuitBreaker.forFailure(id, config, evaluator, publishEvent)
  )

  /**
   * Retrieves an existing [[CircuitBreaker]] instance which protects against both cascading failure and system overload
   * conditions, if it exists in the registry.  If it does not yet exist, a new instance is created and stored in the
   * registry state.
   * @param id - uniquely identifies a [[CircuitBreaker]] instance within this registry.
   * @param config - the [[FlowControlSettings]] configuration for the [[CircuitBreaker]].
   * @param evaluator - the [[CircuitBreaker#FailureEvaluator]] to use for the [[CircuitBreaker]], used to determine what program errors should be
   *   used to determine state changes.
   * @return circuitBreaker - an effectful program that when run will return an instance of a flow control [[CircuitBreaker]].
   */
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

/**
 * The companion object to the `CircuitBreakerRegistry` - contains a smart constructor for registry creation,
 *   optionally registering a garbage collector to reap [[CircuitBreaker]]s within the registry when they
 *   have not been accessed for a given period of time.  The companion also defines private data types used by the
 *   registry.
 */
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

  /**
   * Creates a `CircuitBreakerRegistry` instance given a [[RegistrySettings]] configuration, providing for clean up of resources
   *   when the registry is shutdown.
   * @param settings - the configuration for the registry.
   * @param strategy - the `fs2.Strategy` used for the execution of an effectful program `F` with a `fs2.util.Async` in implicit scope.
   * @param scheduler - the `fs2.Scheduler` used for the execution of periodic tasks, such as the statistics stream and the
   *   registry [[CircuitBreaker]] garbage collection.
   * @param F - the `fs2.util.Async` instance that describes how the programs protected by [[CircuitBreaker]]s in this registry are executed.
   */
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
