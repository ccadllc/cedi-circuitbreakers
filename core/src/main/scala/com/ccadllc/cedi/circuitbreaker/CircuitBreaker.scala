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

import java.time.Instant

import scala.language.higherKinds

import statistics._

import CircuitBreaker._

sealed abstract class CircuitBreaker[F[_]](val id: Identifier)(implicit F: Async[F]) {

  def lastActivity: F[Instant]

  def currentStatistics: F[Statistics]

  final def protect[A](executable: F[A]): F[A] = for {
    stats <- beforeExecution
    attempt <- executable.attempt
    result <- afterExecution(stats, attempt)
  } yield result

  private[circuitbreaker]type StatisticsVariant

  private[circuitbreaker] final def now: F[Instant] = F.delay(Instant.now)

  private[circuitbreaker] def beforeExecution: F[StatisticsVariant]

  private[circuitbreaker] def afterExecution[A](beforeStats: StatisticsVariant, result: Either[Throwable, A]): F[A]
}

object CircuitBreaker {
  sealed abstract class CircuitBreakerException(
    val id: Identifier,
    val message: String
  ) extends RuntimeException(s"Circuit Breaker ${id.show} $message") with Product with Serializable

  case class OpenException(override val id: Identifier, stats: FailureStatistics) extends CircuitBreakerException(id, OpenException.deriveMessage(stats))
  object OpenException {
    private def deriveMessage(stats: FailureStatistics) =
      s"open due to failure threshold (${stats.config.degradationThreshold.show}) exceeded with ${stats.metrics.percentFailure.show}."
  }
  case class ThrottledException(override val id: Identifier, stats: FlowControlStatistics) extends CircuitBreakerException(id, ThrottledException.deriveMessage(stats))
  object ThrottledException {
    private def deriveMessage(stats: FlowControlStatistics) = {
      val reason = stats.metrics.inboundRate.currentSample.perSecondCount > stats.metrics.config.perSecondRateHardLimit match {
        case true => s"current requests per second (${stats.metrics.inboundRate.currentSample.perSecondCount} exceeding configured hard limit of ${stats.metrics.config.perSecondRateHardLimit}"
        case false => stats.maxAcceptableRate.fold(throw new IllegalStateException(s"Throttling without any acceptable max rate for ${stats.id}!")) { mar =>
          s"allowable rate of ${mar.show} exceeding effective inbound rate of ${stats.meanInboundRate.show}"
        }
      }
      s"throttled request due to $reason."
    }
  }

  sealed abstract class CircuitBreakerEvent extends Product with Serializable { def id: Identifier }
  case class OpenedEvent(override val id: Identifier, stats: FailureStatistics) extends CircuitBreakerEvent
  case class ClosedEvent(override val id: Identifier, stats: FailureStatistics) extends CircuitBreakerEvent
  case class ThrottledUpEvent(override val id: Identifier, stats: FlowControlStatistics) extends CircuitBreakerEvent
  case class ThrottledDownEvent(override val id: Identifier, stats: FlowControlStatistics) extends CircuitBreakerEvent
  case class Identifier(value: String) extends AnyVal { def show: String = value }
  class FailureEvaluator(val tripsCircuitBreaker: Throwable => Boolean)
  object FailureEvaluator {
    def apply(tripsCircuitBreaker: Throwable => Boolean): FailureEvaluator = new FailureEvaluator(tripsCircuitBreaker)
    val default: FailureEvaluator = new FailureEvaluator(_ => true)
  }
  private[circuitbreaker] class FailureCircuitBreaker[F[_]](
      id: Identifier,
      config: FailureSettings,
      evaluator: FailureEvaluator,
      publishEvent: CircuitBreakerEvent => F[Unit],
      statistics: StateRef[F, FailureStatistics]
  )(implicit F: Async[F]) extends CircuitBreaker[F](id) {
    type StatisticsVariant = FailureStatistics

    def lastActivity: F[Instant] = statistics.get map { _.lastActivity }

    def currentStatistics: F[Statistics] = statistics.get map { s => s: Statistics }

    private[circuitbreaker] def beforeExecution: F[FailureStatistics] = for {
      ts <- now
      stats <- statistics.get
      _ <- if (config.enabled && stats.openButUntestable(ts)) F.fail(OpenException(id, stats)) else F.pure(())
    } yield stats

    private[circuitbreaker] def afterExecution[A](beforeStats: FailureStatistics, attempt: Either[Throwable, A]): F[A] = {
      def notifyIfReportableChange(stats: FailureStatistics): F[Unit] = {
        def triggerOpenedEvent(stats: FailureStatistics): F[Unit] = publishEvent(OpenedEvent(id, stats))
        def triggerClosedEvent(stats: FailureStatistics): F[Unit] = publishEvent(ClosedEvent(id, stats))
        if (stats.lastChangeOpened) triggerOpenedEvent(stats) else if (stats.lastChangeClosed) triggerClosedEvent(stats) else F.pure(())
      }
      def attemptToAsyncF: F[A] = attempt.fold(F.fail, F.pure)
      if (config.enabled) {
        for {
          ts <- now
          s <- statistics.modify(_.afterExecution(timestamp = ts, success = attempt.fold(!evaluator.tripsCircuitBreaker(_), _ => true)))
          _ <- notifyIfReportableChange(s)
          result <- attemptToAsyncF
        } yield result
      } else attemptToAsyncF
    }
  }
  def forFailure[F[_]](
    id: Identifier,
    config: FailureSettings,
    evaluator: FailureEvaluator,
    publishEvent: CircuitBreakerEvent => F[Unit]
  )(implicit F: Async[F]): F[CircuitBreaker[F]] =
    StateRef.create[F, FailureStatistics](FailureStatistics.initial(id, config)) map { new FailureCircuitBreaker(id, config, evaluator, publishEvent, _) }

  private[circuitbreaker] class FlowControlCircuitBreaker[F[_]](
      id: Identifier,
      config: FlowControlSettings,
      evaluator: FailureEvaluator,
      publishEvent: CircuitBreakerEvent => F[Unit],
      statistics: StateRef[F, FlowControlStatistics],
      failureCircuitBreaker: FailureCircuitBreaker[F]
  )(implicit F: Async[F]) extends CircuitBreaker[F](id) {

    type StatisticsVariant = FlowControlStatistics

    def lastActivity: F[Instant] = failureCircuitBreaker.lastActivity

    def currentStatistics: F[Statistics] = statistics.get map { s => s: Statistics }

    private[circuitbreaker] def beforeExecution: F[FlowControlStatistics] = {
      def applyFailure(failureStats: FailureStatistics): F[FlowControlStatistics] = statistics.modify(_.withFailure(failureStats))
      def applyStatisticsBeforeThrottling: F[FlowControlStatistics] = now flatMap { ts => statistics.modify(_.beforeThrottle(ts)) }
      def throttleIfNecessary(stats: FlowControlStatistics): F[Unit] = if (stats.shouldBeThrottled) F.fail(ThrottledException(id, stats)) else F.pure(())
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
  def forFlowControl[F[_]](
    id: Identifier,
    config: FlowControlSettings,
    evaluator: FailureEvaluator,
    publishEvent: CircuitBreakerEvent => F[Unit]
  )(implicit F: Async[F]): F[CircuitBreaker[F]] = for {
    flowControlStats <- StateRef.create[F, FlowControlStatistics](FlowControlStatistics.initial(id, config))
    failureStats <- StateRef.create[F, FailureStatistics](FailureStatistics.initial(id, config.failure))
    failureCB = new FailureCircuitBreaker(id, config.failure, evaluator, publishEvent, failureStats)
  } yield new FlowControlCircuitBreaker(id, config, evaluator, publishEvent, flowControlStats, failureCB)
}
