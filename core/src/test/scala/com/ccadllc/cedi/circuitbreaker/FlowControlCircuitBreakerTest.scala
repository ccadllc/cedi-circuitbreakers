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

import org.scalatest.OptionValues._
import org.scalatest.WordSpec

import scala.concurrent.duration._

class FlowControlCircuitBreakerTest extends WordSpec with TestSupport {
  "The flow control circuitbreaker" should {
    "throttle down the request rate when the inbound rate continue to exceed the max acceptable rate" in {
      val id = CircuitBreaker.Identifier("test")
      val registry = CircuitBreakerRegistry.create[IO](testRegistryConfig, scheduler).unsafeRunSync
      val cb = registry.forFlowControl(id, testFlowControlConfig).unsafeRunSync
      val tseo = TestStreamedEventObserver.create(registry)
      protectFlowControl(cb, 20.milliseconds, 5.milliseconds, 750.milliseconds)
      val throttledEvents = tseo.throttledDownEvents(id)
      val maxAcceptableRate = throttledEvents.lastOption.value.stats.maxAcceptableRate.value.perSecond
      val meanInboundRate = throttledEvents.lastOption.value.stats.meanInboundRate.perSecond
      meanInboundRate should be > maxAcceptableRate
      protectFlowControl(cb, 200.milliseconds, 5.milliseconds, 500.milliseconds)
      val throttledDownEvents = tseo.throttledDownEvents(id)
      throttledDownEvents.lastOption.value.stats.maxAcceptableRate.value.perSecond should be < maxAcceptableRate
    }
    "throttle up the request rate when the inbound rate no longer exceeds the max acceptable rate" in {
      val id = CircuitBreaker.Identifier("test")
      val registry = CircuitBreakerRegistry.create[IO](testRegistryConfig, scheduler).unsafeRunSync
      val cb = registry.forFlowControl(id, testFlowControlConfig).unsafeRunSync
      val tseo = TestStreamedEventObserver.create(registry)
      protectFlowControl(cb, 20.milliseconds, 10.milliseconds, 750.milliseconds)
      val throttledEvents = tseo.throttledDownEvents(id)
      val maxAcceptableRate = throttledEvents.headOption.value.stats.maxAcceptableRate.value.perSecond
      val meanInboundRate = throttledEvents.headOption.value.stats.meanInboundRate.perSecond
      meanInboundRate should be > maxAcceptableRate
      protectFlowControl(cb, 250.milliseconds, 20.milliseconds, 500.milliseconds)
      val throttledDownEvents = tseo.throttledDownEvents(id)
      val decreasedMaxAcceptableRate = throttledDownEvents.lastOption.value.stats.maxAcceptableRate.value.perSecond
      decreasedMaxAcceptableRate should be < maxAcceptableRate
      protectFlowControl(cb, 50.milliseconds, -10.milliseconds, 500.milliseconds)
      val throttledUpEvents = tseo.throttledUpEvents(id)
      val increasedMaxAcceptableRate = throttledUpEvents.headOption.value.stats.maxAcceptableRate.value.perSecond
      increasedMaxAcceptableRate should be > decreasedMaxAcceptableRate
    }
  }
}
