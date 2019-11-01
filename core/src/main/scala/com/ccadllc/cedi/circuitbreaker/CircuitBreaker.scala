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

import java.time.Instant

import statistics._

import CircuitBreaker._

/**
 * The library's main abstraction, representing protection of an effectful
 * program.  The API is relatively simple, consisting of one primary function, `protect`,
 * which takes the program to protect and returns that an enhanced version of that program
 * having the ability to fail fast without invoking the underlying service if conditions
 * warrant, ensuring failure of the service it represents does not result in a cascade
 * of failure.  The `CircuitBreaker` has two secondary
 * functions: 1.) `currentStatistics`, which provides the current [[statistics.Statistics]]
 * for use by monitoring and dashboarding applications and; 2.) `lastActivity` which provides
 * the `java.time.Instant` timestamp indicating the instant the `CircuitBreaker` was last active.
 *
 * The `CircuitBreaker` is not generally directly instantiated; rather, a `CircuitBreaker` is requested
 * from the [[CircuitBreakerRegistry]] via the [[CircuitBreakerRegistry#forFailure]] function which
 * provides a variant that trips/opens given a configured percentage of protected program failures within a
 * configured time period or via the [[CircuitBreakerRegistry#forFlowControl]] function which provides a
 * variant that, in addition to the failure protected just described, also throttles requests when the
 * average rate of requests over a configured time period is greater than the observed
 * average rate of processing for the protected program.
 *
 * @param id - the unique identifier for this circuit breaker.  This is used to look up the
 *   `CircuitBreaker` instance from the registry (or, if not present, to create a new instance).
 * @tparam F - the type of effectful program.
 */
sealed abstract class CircuitBreaker[F[_]](val id: Identifier)(implicit F: Sync[F]) {

  /**
   * Provides protection over the provided effectful program based on the configuration
   * of `this` instance.
   * @param program - the effectful program represented by `F[A]`.
   * @tparam A - the result type of the effectful program.
   * @return program - an enhanced version of the passed-in program, wrapped in a protective
   *   layer that will fail fast when a threshold of failures have been observed for the
   *   underlying service and may also throttle requests when the observed inbound rate exceeds
   *   the processing rate.
   */
  final def protect[A](program: F[A]): F[A] = for {
    stats <- beforeExecution
    attempt <- program.attempt
    result <- afterExecution(stats, attempt)
  } yield result

  /**
   * A description of the current [[statistics.Statistics]] for this `CircuitBreaker`.
   * @return statistics - an effectful program that when run returns the current
   *   `Statistics` for this circuitbreaker.
   */
  def currentStatistics: F[Statistics]

  /**
   * A description of the date/time that this `CircuitBreaker` was last active (that is, the last time
   * its `protect` function was invoked).
   * @return lastActivity - an effectful program that when run returns the `java.time.Instant` this
   *   circuitbreaker was last active.
   */
  def lastActivity: F[Instant]

  private[circuitbreaker] type StatisticsVariant

  private[circuitbreaker] final def now: F[Instant] = F.delay(Instant.now)

  private[circuitbreaker] def beforeExecution: F[StatisticsVariant]

  private[circuitbreaker] def afterExecution[A](beforeStats: StatisticsVariant, result: Either[Throwable, A]): F[A]
}

/**
 * The companion object for a `CircuitBreaker` instance.  The smart constructors for creation of failure and flow control
 * `CircuitBreaker` instances live here, as do common data types used by `CircuitBreaker` instances.
 */
object CircuitBreaker {
  /**
   * An Algebraic Data Type (ADT) representing the exceptions which may be returned by a protected
   * program to indicate that the `CircuitBreaker` itself is failing the request prior to underlying
   * program invocation.
   * @param id - identifies the `CircuitBreaker` which triggered this error.
   * @param message - A descriptive message related to the error.
   */
  sealed abstract class CircuitBreakerException(
    val id: Identifier,
    val message: String) extends RuntimeException(s"Circuit Breaker ${id.show} $message") with Product with Serializable

  /**
   * This data type represents the error returned when the `CircuitBreaker` is failing a request because the configured percentage
   * of failures of the protected program has exceeded the configured maximum threshold over the configured time period.
   * @param id - identifies the `CircuitBreaker` which triggered this error.
   * @param stats - the [[statistics.FailureStatistics]] which provide details on the current state of the `CircuitBreaker`.
   */
  case class OpenException(override val id: Identifier, stats: FailureStatistics) extends CircuitBreakerException(id, OpenException.deriveMessage(stats))
  object OpenException {
    private def deriveMessage(stats: FailureStatistics) =
      s"open due to failure threshold (${stats.config.degradationThreshold.show}) exceeded with ${stats.metrics.percentFailure.show}."
  }

  /**
   * This data type represents the error returned when the `CircuitBreaker` is failing a request as a means of throttling
   * the input rate to bring it down to the average observed rate at which the underlying program can process them or
   * to a configured hard limit.
   * @param id - identifies the `CircuitBreaker` which triggered this error.
   * @param stats - the [[statistics.FlowControlStatistics]] which provide details on the current state of the `CircuitBreaker`.
   */
  case class ThrottledException(override val id: Identifier, stats: FlowControlStatistics) extends CircuitBreakerException(id, ThrottledException.deriveMessage(stats))
  object ThrottledException {
    private def deriveMessage(stats: FlowControlStatistics) = {
      val reason = stats.metrics.inboundRate.currentSample.perSecondCount > stats.metrics.config.perSecondRateHardLimit match {
        case true => s"current requests per second (${stats.metrics.inboundRate.currentSample.perSecondCount} exceeding configured hard limit of ${stats.metrics.config.perSecondRateHardLimit})"
        case false => stats.maxAcceptableRate.fold(throw new IllegalStateException(s"Throttling without any acceptable max rate for ${stats.id}!")) { mar =>
          s"allowable rate of ${mar.show} is less than effective inbound rate of ${stats.meanInboundRate.show}"
        }
      }
      s"throttled request due to $reason."
    }
  }

  /**
   * An Algebraic Data Type (ADT) representing the events which may be published to the `fs2.Stream` by the `CircuitBreaker`
   * whenever there is a state change (a `CircuitBreaker` opening or closing, or a `CircuitBreaker` throttling a request, or
   * increasing/decreasing the rate at which a request is throttled).
   * @param id - identifies the `CircuitBreaker` which triggered this error.
   */
  sealed abstract class CircuitBreakerEvent extends Product with Serializable { def id: Identifier }

  /**
   * This event is published whenever a `CircuitBreaker` is "opened" (causing subsequent requests to fail fast excepting for periodic tests).
   * @param id - identifies the `CircuitBreaker` which triggered this event.
   * @param stats - the [[statistics.FailureStatistics]] which provide details on the current state of the `CircuitBreaker`.
   */
  case class OpenedEvent(override val id: Identifier, stats: FailureStatistics) extends CircuitBreakerEvent
  /**
   * This event is published whenever a `CircuitBreaker` is "closed" (testing having determined the underlying program is capable of
   * normal invocation).
   * @param id - identifies the `CircuitBreaker` which triggered this event.
   * @param stats - the [[statistics.FailureStatistics]] which provide details on the current state of the `CircuitBreaker`.
   */
  case class ClosedEvent(override val id: Identifier, stats: FailureStatistics) extends CircuitBreakerEvent
  /**
   * This event is published whenever a `CircuitBreaker` is throttling up requests.  This means that the acceptable inbound rate
   *   has risen based on observation that the processing rate has decreased/improved.
   * @param id - identifies the `CircuitBreaker` which triggered this event.
   * @param stats - the [[statistics.FlowControlStatistics]] which provide details on the current state of the `CircuitBreaker`.
   */
  case class ThrottledUpEvent(override val id: Identifier, stats: FlowControlStatistics) extends CircuitBreakerEvent
  /**
   * This event is published whenever a `CircuitBreaker` is throttling down requests.  This means that the acceptable inbound rate
   *   has lowered based on observation that the processing rate for the program has degraded.
   * @param id - identifies the `CircuitBreaker` which triggered this event.
   * @param stats - the [[statistics.FlowControlStatistics]] which provide details on the current state of the `CircuitBreaker`.
   */
  case class ThrottledDownEvent(override val id: Identifier, stats: FlowControlStatistics) extends CircuitBreakerEvent

  /**
   * Uniquely identifies a `CircuitBreaker` within the [[CircuitBreakerRegistry]].
   */
  case class Identifier(value: String) extends AnyVal { def show: String = value }
  /**
   * An evaluator determines whether or not a failure of a protected program should qualify as a systemic failure
   * to be added to the `CircuitBreaker` statistics and used to determine whether the failure threshold has been
   * reached and the circuit breaker should be opened. A program can fail for many reasons, including application-level
   * errors, and it is often not desirable to count these as failures a circuit breaker should keep track of (they
   * may have no bearing on errors which could cause cascading failures).
   * Different subsystems may look for different error or exception hierarchies in this determination (e.g., a
   * circuit breaker protecting a database access program may provide an evaluator that looks for
   * exceptions related to the API used to access the database).
   * @param tripsCircuitBreaker - a predicate function that is passed a `Throwable` and determines whether or not
   *   that exception is be of a type which can, in aggregate, trip the circuit breaker.
   */
  class FailureEvaluator(val tripsCircuitBreaker: Throwable => Boolean)
  /**
   * The `FailureEvaluator` companion object, providing smart constructor and a default evaluator.
   */
  object FailureEvaluator {
    /**
     * Smart constructor of a `FailureEvaluator` given the passed-in function value.
     * @param tripsCircuitBreaker - a predicate function that is passed a `Throwable` and determines whether or not
     *   the exception should be one that can, in aggregate, trip the circuit breaker.
     * @return failureEvaluator - an instance of `FailureEvaluator` using the passed-in function.
     */
    def apply(tripsCircuitBreaker: Throwable => Boolean): FailureEvaluator = new FailureEvaluator(tripsCircuitBreaker)
    /**
     * The default value for `FailureEvaluator` if one is not provided by the [[CircuitBreakerRegistry]] upon CB creation.
     * This evaluator treats all program failures as included in the statistics for use in determining whether to
     * trip ("open") a `CircuitBreaker`.
     */
    val default: FailureEvaluator = new FailureEvaluator(_ => true)
  }

  /*
   * Package-private `CircuitBreaker` class which is used by the [[#forFailure]] smart constructor when creating
   * an instance which protects against cascading failures.
   */
  private[circuitbreaker] class FailureCircuitBreaker[F[_]](
    id: Identifier,
    config: FailureSettings,
    evaluator: FailureEvaluator,
    publishEvent: CircuitBreakerEvent => F[Unit],
    statistics: StateRef[F, FailureStatistics])(implicit F: Sync[F]) extends CircuitBreaker[F](id) {
    type StatisticsVariant = FailureStatistics

    def currentStatistics: F[Statistics] = statistics.get map { s => s: Statistics }

    def lastActivity: F[Instant] = statistics.get map { _.lastActivity }

    private[circuitbreaker] def beforeExecution: F[FailureStatistics] = for {
      ts <- now
      stats <- statistics.get
      _ <- if (config.enabled && stats.openButUntestable(ts)) F.raiseError(OpenException(id, stats)) else F.pure(())
    } yield stats

    private[circuitbreaker] def afterExecution[A](beforeStats: FailureStatistics, attempt: Either[Throwable, A]): F[A] = {
      def notifyIfReportableChange(stats: FailureStatistics): F[Unit] = {
        def triggerOpenedEvent(stats: FailureStatistics): F[Unit] = publishEvent(OpenedEvent(id, stats))
        def triggerClosedEvent(stats: FailureStatistics): F[Unit] = publishEvent(ClosedEvent(id, stats))
        if (stats.lastChangeOpened) triggerOpenedEvent(stats) else if (stats.lastChangeClosed) triggerClosedEvent(stats) else F.pure(())
      }
      def attemptToF: F[A] = attempt.fold(F.raiseError, F.pure)
      if (config.enabled) {
        for {
          ts <- now
          s <- statistics.modify(_.afterExecution(timestamp = ts, success = attempt.fold(!evaluator.tripsCircuitBreaker(_), _ => true)))
          _ <- notifyIfReportableChange(s)
          result <- attemptToF
        } yield result
      } else attemptToF
    }
  }

  /**
   * Smart constructor, normally not called directly but rather by the [[CircuitBreakerRegistry]] to create
   * a `CircuitBreaker` instance which protects against cascading failure.
   * @param id - uniquely identifies a `CircuitBreaker` instance within the [[CircuitBreakerRegistry]].
   * @param config - the [[FailureSettings]] configuration for the `CircuitBreaker`.
   * @param evaluator - the [[FailureEvaluator]] to use for the `CircuitBreaker`, used to determine what
   *   program errors should be used to determine state changes.
   * @param publishEvent - the function to call in order to publish an event - the [[CircuitBreakerRegistry]],
   *   for instance, provides a function which takes the event and returns an effectful program that when run
   *   will publish to an `fs2.Stream` which interested clients can subscribe to.
   * @param statistics - a `StateRef[F, FailureStatistics]` which provides a thread-safe atomic reference to
   *   an instance of [[statistics.FailureStatistics]], which can then be used as the 'state' for the
   *   `CircuitBreaker` instance.  See [[StateRef]] for details.
   * @return circuitBreaker - an effectful program that when run will return an instance of a failure
   *   `CircuitBreaker`.
   */
  def forFailure[F[_]](
    id: Identifier,
    config: FailureSettings,
    evaluator: FailureEvaluator,
    publishEvent: CircuitBreakerEvent => F[Unit])(implicit F: Sync[F]): F[CircuitBreaker[F]] =
    StateRef.create[F, FailureStatistics](FailureStatistics.initial(id, config)) map { new FailureCircuitBreaker(id, config, evaluator, publishEvent, _) }

  /*
   * Package-private `CircuitBreaker` class which is used by the [[#forFlowControl]] smart constructor when creating
   * an instance which both protects against cascading failures as well as throttling to protect against service overload.
   */
  private[circuitbreaker] class FlowControlCircuitBreaker[F[_]](
    id: Identifier,
    config: FlowControlSettings,
    evaluator: FailureEvaluator,
    publishEvent: CircuitBreakerEvent => F[Unit],
    statistics: StateRef[F, FlowControlStatistics],
    failureCircuitBreaker: FailureCircuitBreaker[F])(implicit F: Sync[F]) extends CircuitBreaker[F](id) {

    type StatisticsVariant = FlowControlStatistics

    def currentStatistics: F[Statistics] = statistics.get map { s => s: Statistics }

    def lastActivity: F[Instant] = failureCircuitBreaker.lastActivity

    private[circuitbreaker] def beforeExecution: F[FlowControlStatistics] = {
      def applyFailure(failureStats: FailureStatistics): F[FlowControlStatistics] = statistics.modify(_.withFailure(failureStats))
      def applyStatisticsBeforeThrottling: F[FlowControlStatistics] = now flatMap { ts => statistics.modify(_.beforeThrottle(ts)) }
      def throttleIfNecessary(stats: FlowControlStatistics): F[Unit] = if (stats.shouldBeThrottled) F.raiseError(ThrottledException(id, stats)) else F.pure(())
      def applyStatisticsBeforeExecution: F[FlowControlStatistics] = for {
        beforeStats <- statistics.modify(_.beforeExecution)
        _ <- notifyIfReportableChange(beforeStats)
      } yield beforeStats
      def applyFlowControl: F[FlowControlStatistics] = for {
        initial <- applyStatisticsBeforeThrottling
        _ <- notifyIfReportableChange(initial)
        _ <- throttleIfNecessary(initial)
        updated <- applyStatisticsBeforeExecution
      } yield updated

      for {
        beforeExecFailure <- failureCircuitBreaker.beforeExecution
        withFailure <- applyFailure(beforeExecFailure)
        beforeExecFlow <- if (config.enabled) applyFlowControl else F.pure(withFailure)
      } yield beforeExecFlow
    }

    private[circuitbreaker] def afterExecution[A](beforeStats: FlowControlStatistics, attempt: Either[Throwable, A]): F[A] = {
      def applyFlowControl: F[Unit] = statistics.modify(_.afterExecution) flatMap notifyIfReportableChange
      for {
        result <- failureCircuitBreaker.afterExecution(beforeStats.failure, attempt)
        _ <- if (config.enabled) applyFlowControl else F.pure(())
      } yield result
    }

    private def notifyIfReportableChange(stats: FlowControlStatistics): F[Unit] = {
      def triggerThrottledUpEvent(stats: FlowControlStatistics): F[Unit] = publishEvent(ThrottledUpEvent(id, stats))
      def triggerThrottledDownEvent(stats: FlowControlStatistics): F[Unit] = publishEvent(ThrottledDownEvent(id, stats))
      if (stats.throttledUp) triggerThrottledUpEvent(stats) else if (stats.throttledDown) triggerThrottledDownEvent(stats) else F.pure(())
    }
  }

  /**
   * Smart constructor, normally not called directly but rather by the [[CircuitBreakerRegistry]] to create a
   * `CircuitBreaker` instance which protects against both cascading failure and system overload.
   * @param id - uniquely identifies a `CircuitBreaker` instance within the [[CircuitBreakerRegistry]].
   * @param config - the [[FlowControlSettings]] configuration for the `CircuitBreaker`.
   * @param evaluator - the [[FailureEvaluator]] to use for the `CircuitBreaker`, used to determine what program
   *   errors should be used to determine state changes.
   * @param publishEvent - the function to call in order to publish an event - the [[CircuitBreakerRegistry]],
   *   for instance, provides a function which takes the event and returns an effectful program that when run
   *   will publish to an `fs2.Stream` which interested clients can subscribe to.
   * @param statistics - a `StateRef[F, FlowControlStatistics]` which provides a thread-safe atomic reference
   *   to an instance of [[statistics.FlowControlStatistics]], which can then be used as the 'state' for the
   *   `CircuitBreaker` instance.  See [[StateRef]] for details.
   * @return circuitBreaker - an effectful program that when run will return an instance of a flow control
   *   `CircuitBreaker`.
   */
  def forFlowControl[F[_]](
    id: Identifier,
    config: FlowControlSettings,
    evaluator: FailureEvaluator,
    publishEvent: CircuitBreakerEvent => F[Unit])(implicit F: Sync[F]): F[CircuitBreaker[F]] = for {
    flowControlStats <- StateRef.create[F, FlowControlStatistics](FlowControlStatistics.initial(id, config))
    failureStats <- StateRef.create[F, FailureStatistics](FailureStatistics.initial(id, config.failure))
    failureCB = new FailureCircuitBreaker(id, config.failure, evaluator, publishEvent, failureStats)
  } yield new FlowControlCircuitBreaker(id, config, evaluator, publishEvent, flowControlStats, failureCB)
}
