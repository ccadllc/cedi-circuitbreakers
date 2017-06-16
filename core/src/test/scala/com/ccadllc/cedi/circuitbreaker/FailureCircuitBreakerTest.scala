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

import scala.concurrent.ExecutionContext.Implicits.global

import cats.effect.IO

import org.scalatest.WordSpec

import scala.concurrent.duration._

class FailureCircuitBreakerTest extends WordSpec with TestSupport {
  "The failure circuit breaker" should {
    "switch to the open position when the failure rate of the services it is protecting exceeds the configured threshold" in {
      val id = CircuitBreaker.Identifier("test")
      val registry = CircuitBreakerRegistry.create[IO](testRegistryConfig).unsafeRunSync
      val failureThreshold = Percentage(20.0)
      val cb = registry.forFailure(id, testFailureConfig.copy(degradationThreshold = failureThreshold)).unsafeRunSync
      val tseo = TestStreamedEventObserver.create(registry)
      tseo.openedCount(id) shouldBe 0
      val results = protectFailure(cb, failureThreshold.plus(Percentage(10.0)))
      tseo.openedCount(id) should be > 0
      results.collectFirst { case Some(CircuitBreaker.OpenException(id, _)) => id } shouldBe Some(id)
    }
    "switch to the closed position after opening when the configured minimum number of service tests succeed" in {
      val id = CircuitBreaker.Identifier("test")
      val registry = CircuitBreakerRegistry.create[IO](testRegistryConfig).unsafeRunSync
      val failureThreshold = Percentage(20.0)
      val cb = registry.forFailure(
        id,
        testFailureConfig.copy(degradationThreshold = failureThreshold, test = testFailureConfig.test.copy(interval = 0.seconds))
      ).unsafeRunSync
      val tseo = TestStreamedEventObserver.create(registry)
      tseo.closedCount(id) shouldBe 0
      protectFailure(cb, failureThreshold.plus(Percentage(10.0)))
      tseo.openedCount(id) should be > 0
      protectFailure(cb, Percentage.minimum)
      tseo.closedCount(id) should be > 0
    }
  }
}
