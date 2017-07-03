/*
 * Copyright 2015 - 2016 Red Bull Media House GmbH <http://www.redbullmediahouse.com> - all rights reserved.
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

package com.rbmhtechnology.eventuate

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import com.typesafe.config.Config

import scala.collection.immutable.Seq
import scala.concurrent._
import scala.concurrent.duration._

private class EventsourcedProcessorSettings(config: Config) {
  val readTimeout =
    config.getDuration("eventuate.log.read-timeout", TimeUnit.MILLISECONDS).millis

  val writeTimeout =
    config.getDuration("eventuate.log.write-timeout", TimeUnit.MILLISECONDS).millis

  val writeBatchSize =
    config.getInt("eventuate.log.write-batch-size")
}

object EventsourcedProcessor {
  /**
   * Type of an [[EventsourcedProcessor]]'s event handler.
   */
  //#process
  type Process = PartialFunction[Any, Seq[Any]]
  //#
}

/**
 * An [[EventsourcedWriter]] that writes processed events to a `targetEventLog`. `EventsourcedProcessor`
 * is an idempotent writer that guarantees that no duplicates are ever written to the target event log,
 * also under failure conditions. Hence, applications don't need to take extra care about idempotency.
 * Processed events are those returned by `processEvent`, an application-defined event handler that is
 * invoked with events from the source `eventLog`.
 *
 * During initialization, a processor reads the processing progress from the target event log. The timeout
 * for this read operation can be configured with the `eventuate.log.read-timeout` parameter for all
 * event-sourced processors or defined on a per class or instance basis by overriding `readTimeout`. The timeout
 * for write operations to the target log can be configured with the `eventuate.log.write-timeout` parameter
 * for all event-sourced processors or defined on a per class or instance basis by overriding `writeTimeout`.
 *
 * An `EventsourcedProcessor` is a stateless processor i.e. in-memory state created from source events can
 * not be recovered. An application that needs stateful event processing should use [[StatefulProcessor]]
 * instead.
 *
 * An `EventsourcedProcessor` processor writes events with vector timestamps set to source event vector timestamp.
 * In other words, it does not modify event vector timestamps.
 *
 * The source event log and the target event log of an `EventsourcedProcessor` must be different. Writing
 * processed events back to the source event log has no effect.
 *
 * @see [[StatefulProcessor]]
 */
trait EventsourcedProcessor extends EventsourcedWriter[Long, Long] with ActorLogging {
  import ReplicationProtocol._
  import context.dispatcher

  /**
   * Type of this processor's event handler.
   */
  type Process = EventsourcedProcessor.Process

  private val settings = new EventsourcedProcessorSettings(context.system.settings.config)

  private var processedEvents: Vector[Seq[DurableEvent]] = Vector.empty
  private var processingProgress: Long = 0L

  /**
   * This processor's target event log.
   */
  def targetEventLog: ActorRef

  /**
   * This processor's event handler. It may generate zero or more processed events per source event.
   */
  def processEvent: Process

  /**
   * Override to allow post-processing of DurableEvent instances wrapping events generated by `processEvent`.
   *
   * Amongst other things, this can e.g. be used to set different or additional aggregate IDs for custom routing
   * destinations (which by default take the same routing destinations as the original event that was processed).
   */
  def postProcessDurableEvent(e: DurableEvent): DurableEvent = e

  /**
   * Returns an command [[BehaviorContext]] that doesn't allow event handler behavior changes. An
   * attempt to change the event handler behavior with `eventContext.become()` will throw an
   * [[UnsupportedOperationException]].
   */
  override final lazy val eventContext: BehaviorContext =
    new StaticBehaviorContext(onEvent)

  /**
   * Collects processed events generated by `processEvent`.
   */
  override final def onEvent = {
    case payload if processEvent.isDefinedAt(payload) =>
      val currentProcessedEvents = processEvent(payload)
      if (lastSequenceNr > processingProgress)
        processedEvents = processedEvents :+ currentProcessedEvents.map { e =>
          postProcessDurableEvent(createEvent(e, lastHandledEvent.customDestinationAggregateIds))
        }
  }

  /**
   * Asynchronously writes processed events that have been collected since the last write together
   * with the current processing progress. If the number of processed events since the last write
   * is greater than the configured `eventuate.log.write-batch-size` multiple batches with a size
   * less than or equal to `eventuate.log.write-batch-size` will be written sequentially. When
   * splitting into multiple batches it is guaranteed that the processing result of a single event
   * is part of the same batch i.e. it is guaranteed that the processing result of a single event
   * is written atomically. If the processing result of a single event is larger than
   * `eventuate.log.write-batch-size`, `write` write does not split that batch into smaller batches.
   */
  override final def write(): Future[Long] =
    if (lastSequenceNr > processingProgress) {
      val result = writeBatches(processedEvents, processingProgress, lastSequenceNr)
      processedEvents = Vector.empty
      result
    } else Future.successful(processingProgress)

  /**
   * Asynchronously reads the processing progress from the target event log.
   */
  override final def read(): Future[Long] = {
    targetEventLog.ask(GetReplicationProgress(id))(Timeout(readTimeout)).flatMap {
      case GetReplicationProgressSuccess(_, progress, _) => Future.successful(progress)
      case GetReplicationProgressFailure(cause)          => Future.failed(cause)
    }
  }

  /**
   * Sets the written processing progress for this processor.
   */
  override def writeSuccess(progress: Long): Unit = {
    processingProgress = progress
    super.writeSuccess(progress)
  }

  /**
   * Sets the read processing progress for this processor and returns it incremented by 1.
   */
  override def readSuccess(progress: Long): Option[Long] = {
    processingProgress = progress
    Some(progress + 1L)
  }

  /**
   * The default write timeout configured with the `eventuate.log.write-timeout` parameter.
   * Can be overridden.
   */
  def writeTimeout: FiniteDuration =
    settings.writeTimeout

  /**
   * The default read timeout configured with the `eventuate.log.read-timeout` parameter.
   * Can be overridden.
   */
  def readTimeout: FiniteDuration =
    settings.readTimeout

  /**
   * Internal API.
   */
  private[eventuate] def createEvent(payload: Any, customDestinationAggregateIds: Set[String]): DurableEvent =
    DurableEvent(
      payload = payload,
      emitterId = id,
      emitterAggregateId = aggregateId,
      customDestinationAggregateIds = customDestinationAggregateIds,
      vectorTimestamp = lastVectorTimestamp,
      processId = DurableEvent.UndefinedLogId)

  private def splitBatches(events: Vector[Seq[DurableEvent]]): (Vector[Seq[DurableEvent]], Vector[Seq[DurableEvent]]) = {
    var num = 0
    // Ensure that a single processEvent result
    // is not partitioned across write batches.
    events.span { w =>
      val size = w.size
      num += size
      num <= settings.writeBatchSize || num == size
    }
  }

  private def writeBatches(events: Vector[Seq[DurableEvent]], previousProgress: Long, currentProgress: Long): Future[Long] =
    Future(splitBatches(events)).flatMap {
      case (w, r) => (w.flatten, r) match {
        case (Vector(), Vector()) =>
          Future.successful(currentProgress)
        case (batch, Vector()) =>
          writeBatch(batch, currentProgress)
        case (batch, tail) =>
          writeBatch(batch, previousProgress).flatMap(_ => writeBatches(tail, previousProgress, currentProgress))
      }
    }

  private def writeBatch(events: Seq[DurableEvent], progress: Long): Future[Long] =
    targetEventLog.ask(ReplicationWrite(events, Map(id -> ReplicationMetadata(progress, VectorTime.Zero))))(Timeout(writeTimeout)).flatMap {
      case _: ReplicationWriteSuccess => Future.successful(progress)
      case f: ReplicationWriteFailure => Future.failed(f.cause)
    }
}

/**
 * An [[EventsourcedProcessor]] that supports stateful event processing. In-memory state created from source
 * events is recovered during event replay, either starting from scratch or from a previously saved snapshot.
 *
 * A `StatefulProcessor` writes events with vector timestamps set to the processor's current vector time. In
 * other words, a written event has a potential causal relationship to all past source events.
 *
 * @see [[EventsourcedProcessor]].
 */
trait StatefulProcessor extends EventsourcedProcessor with EventsourcedVersion {
  /**
   * Internal API.
   */
  override private[eventuate] def createEvent(payload: Any, customDestinationAggregateIds: Set[String]): DurableEvent =
    durableEvent(payload, customDestinationAggregateIds)

  /**
   * Sets the read processing progress for this processor and returns `None`.
   */
  override final def readSuccess(progress: Long): Option[Long] = {
    super.readSuccess(progress)
    None
  }
}