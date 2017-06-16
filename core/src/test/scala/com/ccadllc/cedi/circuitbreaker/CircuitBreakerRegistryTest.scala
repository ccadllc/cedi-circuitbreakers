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

class CircuitBreakerRegistryTest extends WordSpec with TestSupport {
  "The circuit breaker registry" should {
    "register a new failure circuit breaker when an existing one with the given identifier does not exist" in {
      val id = CircuitBreaker.Identifier("test")
      val registry = CircuitBreakerRegistry.create[IO](testRegistryConfig).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync shouldBe 'empty
      val cb = registry.forFailure(id, testFailureConfig).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync.get(id) shouldBe Some(cb)
    }
    "return the existing failure circuit breaker when requested with the given identifier when it is already registered" in {
      val id = CircuitBreaker.Identifier("test")
      val registry = CircuitBreakerRegistry.create[IO](testRegistryConfig).unsafeRunSync
      val cb = registry.forFailure(id, testFailureConfig).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync.get(id) shouldBe Some(cb)
      registry.forFailure(id, testFailureConfig).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync.get(id) shouldBe Some(cb)
    }
    "remove an existing failure circuit breaker" in {
      val id = CircuitBreaker.Identifier("test")
      val registry = CircuitBreakerRegistry.create[IO](testRegistryConfig).unsafeRunSync
      val cb = registry.forFailure(id, testFailureConfig).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync.get(id) shouldBe Some(cb)
      registry.removeCircuitBreaker(id).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync.get(id) shouldBe 'empty
    }
    "register a new flow control circuit breaker when an existing one with the given identifier does not exist" in {
      val id = CircuitBreaker.Identifier("test")
      val registry = CircuitBreakerRegistry.create[IO](testRegistryConfig).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync shouldBe 'empty
      val cb = registry.forFlowControl(id, testFlowControlConfig).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync.get(id) shouldBe Some(cb)
    }
    "return the existing flow control circuit breaker when requested with the given identifier when it is already registered" in {
      val id = CircuitBreaker.Identifier("test")
      val registry = CircuitBreakerRegistry.create[IO](testRegistryConfig).unsafeRunSync
      val cb = registry.forFlowControl(id, testFlowControlConfig).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync.get(id) shouldBe Some(cb)
      registry.forFlowControl(id, testFlowControlConfig).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync.get(id) shouldBe Some(cb)
    }
    "remove an existing flow control circuit breaker" in {
      val id = CircuitBreaker.Identifier("test")
      val registry = CircuitBreakerRegistry.create[IO](testRegistryConfig).unsafeRunSync
      val cb = registry.forFlowControl(id, testFlowControlConfig).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync.get(id) shouldBe Some(cb)
      registry.removeCircuitBreaker(id).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync.get(id) shouldBe 'empty
    }
    "ignore removal of a non-existent circuit breaker" in {
      val id = CircuitBreaker.Identifier("test")
      val registry = CircuitBreakerRegistry.create[IO](testRegistryConfig).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync.get(id) shouldBe 'empty
      registry.removeCircuitBreaker(id).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync.get(id) shouldBe 'empty
    }
    "request a stream of events (when events are available)" in {
      val id = CircuitBreaker.Identifier("test")
      val registry = CircuitBreakerRegistry.create[IO](testRegistryConfig).unsafeRunSync
      val tseo = TestStreamedEventObserver.create(registry)
      val failureThreshold = Percentage(20.0)
      val cb = registry.forFailure(id, testFailureConfig.copy(degradationThreshold = failureThreshold)).unsafeRunSync
      val _ = protectFailure(cb, failureThreshold.plus(Percentage(10.0)))
      tseo.openedCount(id) should be > 0
    }
    "request a stream of statistics (when statistics are available)" in {
      val id = CircuitBreaker.Identifier("test")
      val registry = CircuitBreakerRegistry.create[IO](testRegistryConfig).unsafeRunSync
      val tsso = TestStreamedStatisticsObserver.create(registry, 20.milliseconds)
      val failureThreshold = Percentage(20.0)
      val cb = registry.forFailure(id, testFailureConfig.copy(degradationThreshold = failureThreshold)).unsafeRunSync
      val _ = protectFailure(cb, failureThreshold.plus(Percentage(10.0)))
      tsso.failureStatisticsCount(id) should be > 0
      tsso.flowControlStatisticsCount(id) shouldBe 0
    }
    "ensure a circuit breaker is removed after being inactive for the configured inactivity time period and that the registry periodically executes this cleanup service" in {
      val id = CircuitBreaker.Identifier("test")
      val gcCheckInterval = 50.milliseconds
      val inactivityCutoff = 500.milliseconds
      val gcSettings = RegistrySettings.GarbageCollection(gcCheckInterval, inactivityCutoff)
      val registry = CircuitBreakerRegistry.create[IO](testRegistryConfig.copy(garbageCollection = gcSettings)).unsafeRunSync
      registry.forFailure(id, testFailureConfig).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync should not be ('empty)
      Thread.sleep(inactivityCutoff.toMillis + (gcCheckInterval.toMillis * 2L))
      registry.circuitBreakers.unsafeRunSync shouldBe 'empty
      registry.forFailure(id, testFailureConfig).unsafeRunSync
      registry.circuitBreakers.unsafeRunSync should not be ('empty)
      Thread.sleep(inactivityCutoff.toMillis + (gcCheckInterval.toMillis * 2L))
      registry.circuitBreakers.unsafeRunSync shouldBe 'empty
    }
  }
}
