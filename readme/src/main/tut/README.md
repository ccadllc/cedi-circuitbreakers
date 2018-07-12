# Cedi Circuitbreakers

Quick links:

- [About the library](#about)
- [Examples of use](#usage)
- [Configuration](#config)
- [How to get latest version](#getit)
- [API Docs](https://oss.sonatype.org/service/local/repositories/releases/archive/com/ccadllc/cedi/circuitbreaker_2.12/1.0.0/circuitbreaker_2.12-1.0.0-javadoc.jar/!/com/ccadllc/cedi/circuitbreaker/index.html)

### <a id="about"></a>About the library

The term `Circuit breaker` in software engineering applies to a programming pattern meant to provide a similar service for software components that its namesake provides for electrical grids: ensuring that a failure or overload does not cascade to bring down the whole system.  Advanced circuit breakers such as the ones provided by this library also enable the means for throttling (degrading or limiting) the component usage when a service cannot keep up with inbound requests.  Finally, this library additionally provides the means by which clients - for example, a monitoring task - can subscribe to 1.) events related to the circuit breaker activity (e.g., alert users when a circuit breaker has opened, closed, or is throttling requests due to excessive inbound traffic); and/or 2.) a stream of statistics related to the circuitbreaker emitted on a periodic basis.

### <a id="usage"></a>Examples of use

#### <a id="protectusage"></a> Circuitbreaker Protection

Here is a simple example of the use of a circuit breaker to protect against cascading failures. Aside from its configuration - described later in this README - the syntax and semantics in the use of the flow control circuit breaker is identical.

```tut:silent
import cats.effect.IO
import fs2.Stream

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import com.ccadllc.cedi.circuitbreaker._

/*
 * The circuit breaker registry settings are fairly simple and only consist
 * of how often it should evaluate any of the circuit breakers and remove those
 * that have had no activity for a given time period.  If the checkInterval
 * property evaluates to 0 (0.seconds for example), no circuit breaker garbage
 * collection will occur.
 */
val circuitBreakerRegistrySettings: RegistrySettings = RegistrySettings(
  garbageCollection = RegistrySettings.GarbageCollection(checkInterval = 1.hour, inactivityCutoff = 24.hours)
)

/*
 * This constitutes the configuration of the circuitbreaker we shall create as part of this example.
 * It consists of the constraints that define sliding sample window of statistics to gather,
 * the percentage failures over that sample window time-frame which will trigger the circuitbreaker
 * to open and to fail fast subsequent requests, the interval at which a request should be let
 * through to test when the circuitbreaker has opened, and the number of consecutive test requests
 * which must succeed to close it.
 */
val databaseCircuitBreakerSettings: FailureSettings = FailureSettings(
  sampleWindow = SampleWindow(duration = 2.minutes),
  degradationThreshold = Percentage(percent = 40.0),
  test = FailureSettings.Test(interval = 5.seconds, minimumSuccesses = 5)
)

/* The very simple data type which will represent the database object we are querying for this example. */
case class QuarterlyProductSales(productId: UUID, productName: String, totalSales: Long)

/* A simple stub to represent a query to a remote dynamodb persistent database */
def retrieveQuarterlyProductSales: IO[Vector[QuarterlyProductSales]] =
  IO(Vector(QuarterlyProductSales(UUID.randomUUID, "widgets", 565000L)))

/*
 * A service to abstract interactions with a remote database.  For our example purposes, it only has one
 * entry point, to query for `QuarterlyProductSales` objects.
 */
class DatabaseService(cbRegistry: CircuitBreakerRegistry[IO], circuitBreakerSettings: FailureSettings) {
  /*
   * The dynamodb client circuit breaker is identified in the registry via the
   * `CircuitBreaker.Identifier`
   */
  final val DynamoDbCircuitBreakerId: CircuitBreaker.Identifier = CircuitBreaker.Identifier("dynamodb-client")
  /*
   * The dynamodb client circuit breaker will evaluate task failures using
   * this to determine if the failure should be considered in determining
   * whether a circuit breaker be opened. For example, application level failures
   * typically should not be considered, since that category of failure generally
   * does not indicate systemic problems of the kind a circuit breaker was designed
   * to protect against.
   */
  final val DynamoDbCircuitBreakerEvaluator: CircuitBreaker.FailureEvaluator = CircuitBreaker.FailureEvaluator {
    case _: IOException | _: TimeoutException => true
    case _ => false
  }

  def getAllQuarterlyProductSales: IO[Vector[QuarterlyProductSales]] = for {
    /*
     * The registry will look up the circuit breaker by identifier,
     * creating it if it does not yet exist.
     */
    cb <- cbRegistry.forFailure(DynamoDbCircuitBreakerId, circuitBreakerSettings, DynamoDbCircuitBreakerEvaluator)
    /*
     * The protect function of the circuit breaker will apply its current statistics
     * both before and after the task execution.  If the circuit breaker is open and
     * a test is not yet ready to be run, the task itself will not be executed but
     * will 'fail fast' with a `CircuitBreaker.OpenException`; Similarly, with a flow control
     * circuit breaker, if the rate per second is too high, requests will 'fail fast' with a
     * 'CircuitBreaker.ThrottledException' - and thus not be counted in the inbound rate stats -
     * until the inbound rate has been brought down to a level the processing rate statistics
     * indicates can be handled.
     */
    result <- cb.protect(retrieveQuarterlyProductSales)
  } yield result
}

/*
 * Near the beginning of the universe, create a circuit breaker registry for the
 * system to use. The circuit breaker is passed configuration settings as described
 * above.
 */
val protectedSystem = (for {
  cbRegistry <- Stream.eval(CircuitBreakerRegistry.create[IO](circuitBreakerRegistrySettings))
  dbService = new DatabaseService(cbRegistry, databaseCircuitBreakerSettings)
  result <- Stream.eval(dbService.getAllQuarterlyProductSales)
} yield ()).runLast.map(_.getOrElse(Vector.empty))

/*
 * Here would be the rest of the program in a real world application.
 */

/* At the end of the universe, run things! */
protectedSystem.unsafeRunSync()
```

#### <a id="eventsusage"></a> Circuit Breaker Event Subscription

Interested processes can subscribe to events related to a system's circuit breaker activity.  There is an example of its use.

```tut:silent
import cats.effect.{Concurrent, IO}
import fs2.Stream
import com.ccadllc.cedi.circuitbreaker.{ CircuitBreakerRegistry, CircuitBreaker }
import com.ccadllc.cedi.circuitbreaker.statistics.{ FailureStatistics, FlowControlStatistics }

/**
 * In this method, we subscribe to a stream of circuit breaker events triggered on
 * state changes and process them in some manner (that manner is not shown here).
 */
def monitorCircuitBreakerEvents(cbRegistry: CircuitBreakerRegistry[IO]): IO[Unit] = {
  def processCircuitBreakerEvent(event: CircuitBreaker.CircuitBreakerEvent): IO[Unit] = event match {
    case CircuitBreaker.OpenedEvent(id: CircuitBreaker.Identifier, stats: statistics.FailureStatistics) => ???
    case CircuitBreaker.ClosedEvent(id: CircuitBreaker.Identifier, stats: statistics.FailureStatistics) => ???
    case CircuitBreaker.ThrottledDownEvent(id: CircuitBreaker.Identifier, stats: statistics.FlowControlStatistics) => ???
    case CircuitBreaker.ThrottledUpEvent(id: CircuitBreaker.Identifier, stats: statistics.FlowControlStatistics) => ???
  }
  val maxQueuedEvents = 100
  val eventStream = cbRegistry.events(maxQueuedEvents) flatMap { event =>
    Stream.eval_(processCircuitBreakerEvent(event))
  }
  Concurrent[IO].start(eventStream.run) map { _ => () }
}

/*
 * Near the beginning of the universe, create a circuit breaker registry for the
 * system to use. The circuit breaker is passed configuration settings, as described above.
 */
val protectedSystem = (for {
  cbRegistry <- Stream.eval(CircuitBreakerRegistry.create[IO](circuitBreakerRegistrySettings))
  dbService = new DatabaseService(cbRegistry, databaseCircuitBreakerSettings)
  result <- Stream.eval(dbService.getAllQuarterlyProductSales)
} yield ()).runLast.map(_.getOrElse(Vector.empty))

/*
 * Here would be the rest of the program in a real world application.
 */

/* At the end of the universe, run things! */
protectedSystem.unsafeRunSync()
```

### <a id="config"></a> Configuration

#### <a id="registryconfiguration"></a> Circuit Breaker Registry

The circuit breaker registry settings are defined in the `com.ccadllc.cedi.circuitbreaker` namespace and the defaults in this library's `reference.conf` generally do not need to be altered; however they are described below so that the library users understand their purpose.
```
com.ccadllc.cedi.circuitbreaker {
  registry {
    # The circuit breaker registry periodically checks to see if a
    # circuit breaker has been inactive for a lengthy period of time,
    # indicating it is no longer in use.  Since circuit breakers
    # use memory to maintain statistics, it is vital for long running
    # systems which create and discard circuit breakers that we
    # evict such inactive circuit breakers from the registry (they will
    # be re-created again if needed).  If a circuit breaker is inactive
    # for 60 minutes, by default, it shall be so evicted.
    # If check-interval evaluates to 0 (for instance, is specified as 0
    # seconds) then no garbage collection shall occur.
    garbage-collection {
      check-interval: 5 minutes
      inactivity-cutoff: 60 minutes
    }
  }
}
```

#### <a id="failureconfiguration"></a> Cascading Failure Circuit Breaker

The cascading failure circuit breaker has a number of configurable parameters, each of
which is described below.  The circuit breaker settings are typically defined inline subordinate
to the subsystem that uses them in the application configuration.  For example, for a DynamoDb
client circuit breaker in a `persistence` library, they would
be nested in the `dynamodb {  }` settings in the `persistence` reference.conf
file.

```
failure-circuitbreaker {
  # The sample window configuration determines the number of request
  # failure statistics that are maintained.  It is a sliding window.
  # For example, in this configuration, the system will maintain
  # request success/failure metrics for the last 2 minutes, with a
  # maximum of 50,000 entries.  The circuit breaker will only apply
  # protection once the sample window has initially been filled (the
  # first two minutes or 50,000 requests collected in this example).
  # Thereafter, the window continues to slide over the latest two
  # minutes of requests.
  # The maximum-entries entry is meant as a failsafe to ensure that
  # we hold no more than this number at any one time, to avoid
  # excessive memory consumption.
  sample-window {
    duration: 2 minutes
    maximum-entries: 50000
  }
  # This parameter indicates the percentage of failure detected by the
  # circuit breaker of the requests in the sample window which are
  # determine to be failures.  As described in the example below, a failure
  # of a protected task is determined by the `Evaluator` passed to the
  # given circuit breaker when created and may be unique to a given
  # subsystem.  For example, in this configuration, if 40% of the
  # requests processed in the latest 2 minute window have been deemed
  # failures, the circuit breaker will open and subsequent requests
  # will fail fast with a custom exception, with the exception of
  # those passed through for periodic testing.
  degradation-threshold {
    percent: 40.0
  }
  # Once a circuit breaker is open, we need a way to determine
  # that the failure condition(s) have been resolved so that
  # it can be closed again and normal request processing
  # resumed.  This is done by periodically letting a request
  # pass through and monitoring its result.  In this example,
  # when a circuit breaker has opened, a request will be passed
  # through every 30 seconds and if 5 requests in succession are
  # successful, the breaker will again open.
  test {
    interval: 30 seconds
    minimum-successes: 5
  }
  # This is set to true by default but if set to false, the circuit
  # breaker's failure protection will be disabled.  This defaults to
  # 'true' if not specified and is only specified here for illustration
  # purposes as it is not meant to be a 'public' parameter and should
  # generally be changed to 'false' only when troubleshooting.
  enabled: true
}
```

#### <a id="flowcontrolconfiguration"></a> Flow Control Circuit Breaker ###

The flow control circuit breaker has a number of configurable parameters, each of which is described below.  The circuit breaker settings are typically defined subordinate to the subsystem that uses them in the application configuration.  For example, for a circuit breaker protecting an http client in a `service` microservice, they would be nested in the `http-client {  }` settings in the `service` reference.conf file.  See the [typesafe documentation](https://github.com/typesafehub/config) for more details on the general usage of this type of configuration.

```
flow-control-circuitbreaker {
  # Since the throttling circuit breaker also has cascading failure protection, it includes those
  # configuration parameters, as in the previous section above.  Note that if the 'enabled'
  # parameter is set to 'false' in the failure sub-section of the flow control circuit breaker
  # configuration, it will disable failure protection; however, flow control protection will
  # still be enabled unless the flow control 'enabled' parameter is set to 'false.'
  failure {
    sample-window {
      duration: 2 minutes
      maximum-entries: 50000
    }
    degradation-threshold {
      percent: 40.0
    }
    test {
      interval: 30 seconds
      minimum-successes: 5
    }
    enabled: true
  }
  # This sliding sample window maintains the mean rate per second of
  # both inbound requests and requests processed, so it can compare the two
  # to determine if the protected task is keeping up by ensuring the requests
  # processed per second does not fall behind the inbound rate in a sustained manner.
  # For that reason, the sample window here is usually larger than the failure
  # sample window described above as you typically want a larger sample size by
  # which to factor out bursts of traffic versus sustained rates. The circuit
  # breaker protection will not be applied until the sample window has initially
  # been filled (the first 30 seconds in this example). Thereafter, the window
  # continues to slide over the latest 30 seconds of inbound and processing rates.
  # A maximum-entries value can also be specified here; however, in general it
  # is not needed since the maximum entries will never be more than the number of
  # seconds for the sample window (in this case, 30).  It is only if you have a
  # very, very long window that you would need to specify it to override the
  # calculation based on the sample window.
  sample-window {
    duration: 30 seconds
  }

  # In order to ensure we avoid throttling requests unnecessarily, and to further
  # guard against bursts of traffic, you can configure a circuit breaker such that
  # it will not start throttling requests until the observed mean processing
  # rate per second has fallen behind the inbound rate by the percentage configured
  # here.  If set to zero, the mean processing rate must equal the mean inbound
  # rate or throttling will begin.  Because of bursting and timing, You typically
  # want this value to be non-zero to avoid overly aggressive throttling. In the
  # example below, the processing rate must fall behind the inbound rate by 10
  # percent.  Only performance testing and tuning can determine the appropriate
  # value for a given system.

  allowed-over-processing-rate {
    percent: 10.0
  }

  # This value is used to determine whether or not to trigger a throttled up or
  # throttled down event, in order to avoid a flood of events while
  # throttling is in effect.  So in this configuration, an event would only been
  # triggered when throttling up 5% (allowing 5% more requests per second through)
  # or throttling down 5% (throttling - failing fast - 5% more requests per
  # second).
  minimum-reportable-rate-change {
    percent: 5.0
  }
  # This is set to true by default but if set to false, the circuit
  # breaker's flow control protection will be disabled.  This defaults to
  # 'true' if not specified and is only specified here for illustration
  # purposes as it is not meant to be a 'public' parameter and should
  # generally be changed only when troubleshooting.  Note that if this is
  # set to 'false' but the failure sub-config has its enabled set to 'true'
  # failure protection will still be enabled and the circuit breaker will
  # in effect act like a standard failure protection only variant.
  enabled: true

  # This indicates the hard limit for inbound requests per second.  If at any time the
  # number of requests per second exceed this value, requests will get throttled
  # until the start of the next second.  This is meant as a "failsafe" to
  # prevent sudden spikes from overwhelming a protected service prior to the
  # adaptive measures kicking in (the input versus mean processing rates in
  # the sample window).  In general, it should be set higher than the protected
  # service can be handled in a sustained manner but not so high that it will
  # overwhelm the system for the period of time that it takes for the sample window
  # mean values to adjust to the increase in load and provide adaptive protection.
  per-second-rate-threshold: 25
}
```

### <a id="getit"></a>How to get latest Version

Cedi Circuitbreaker supports 2.11 and 2.12. It is published on Maven Central.

```scala
libraryDependencies += "com.ccadllc.cedi" %% "circuitbreaker" % "1.0.0"
```

## Copyright and License

This project is made available under the [Apache License, Version 2.0](LICENSE). Copyright information can be found in [NOTICE](NOTICE).
