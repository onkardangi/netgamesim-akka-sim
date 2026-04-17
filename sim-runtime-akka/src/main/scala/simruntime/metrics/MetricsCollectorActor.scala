package simruntime.metrics

import akka.actor.{Actor, Props}
import simruntime.actor.NodeActor

object MetricsCollectorActor:
  def props(startedAtEpochMs: Long): Props = Props(new MetricsCollectorActor(startedAtEpochMs))

  sealed trait Command
  case object Snapshot extends Command
  case object StopAndSnapshot extends Command

final class MetricsCollectorActor(startedAtEpochMs: Long) extends Actor:
  import MetricsCollectorActor.*

  private val sentByEdge = scala.collection.mutable.Map.empty[String, Long].withDefaultValue(0L)
  private val receivedByEdge = scala.collection.mutable.Map.empty[String, Long].withDefaultValue(0L)
  private val droppedByNodeAndType = scala.collection.mutable.Map.empty[String, Long].withDefaultValue(0L)
  private val messagesByType = scala.collection.mutable.Map.empty[String, Long].withDefaultValue(0L)
  private val algorithmEventsByType = scala.collection.mutable.Map.empty[String, Long].withDefaultValue(0L)
  private val latestLeaderByNode = scala.collection.mutable.Map.empty[String, Int]
  private val snapshotTakenByNode = scala.collection.mutable.Map.empty[String, Boolean]

  override def receive: Receive =
    case NodeActor.Sent(from, to, kind, _) =>
      val edge = RunMetrics.edgeKey(from, to, kind)
      sentByEdge.update(edge, sentByEdge(edge) + 1)
      messagesByType.update(kind, messagesByType(kind) + 1)

    case NodeActor.Received(nodeId, from, kind, _) =>
      val edge = RunMetrics.edgeKey(from, nodeId, kind)
      receivedByEdge.update(edge, receivedByEdge(edge) + 1)

    case NodeActor.Dropped(nodeId, kind, _) =>
      val key = RunMetrics.droppedKey(nodeId, kind)
      droppedByNodeAndType.update(key, droppedByNodeAndType(key) + 1)

    case NodeActor.AlgorithmEvent(nodeId, _, event) =>
      val attrs = parseAttributes(event)
      val algoName = attrs.getOrElse("algorithm", "unknown")
      val eventKey = attrs.getOrElse("phase", attrs.getOrElse("event", "generic"))
      val key = s"$algoName:$eventKey"
      algorithmEventsByType.update(key, algorithmEventsByType(key) + 1)

      attrs.get("bestLeader").flatMap(v => scala.util.Try(v.toInt).toOption).foreach { leader =>
        latestLeaderByNode.update(nodeId.toString, leader)
      }
      attrs.get("snapshotTaken").foreach { v =>
        snapshotTakenByNode.update(nodeId.toString, v.equalsIgnoreCase("true"))
      }

    case Snapshot =>
      sender() ! toRunMetrics(System.currentTimeMillis())

    case StopAndSnapshot =>
      val m = toRunMetrics(System.currentTimeMillis())
      sender() ! m
      context.stop(self)

  private def toRunMetrics(now: Long): RunMetrics =
    val sentSnapshot = sentByEdge.toMap
    val receivedSnapshot = receivedByEdge.toMap
    val inflight = sentSnapshot.map { case (edge, sent) =>
      edge -> (sent - receivedSnapshot.getOrElse(edge, 0L))
    }

    RunMetrics(
      startedAtEpochMs = startedAtEpochMs,
      endedAtEpochMs = now,
      durationMs = now - startedAtEpochMs,
      messagesByType = messagesByType.toMap,
      sentByEdge = sentSnapshot,
      receivedByEdge = receivedSnapshot,
      droppedByNodeAndType = droppedByNodeAndType.toMap,
      inFlightEstimateByEdge = inflight,
      algorithmEventsByType = algorithmEventsByType.toMap,
      latestLeaderByNode = latestLeaderByNode.toMap,
      snapshotTakenByNode = snapshotTakenByNode.toMap
    )

  private def parseAttributes(event: String): Map[String, String] =
    event
      .split("\\s+")
      .toList
      .flatMap { token =>
        token.split("=", 2).toList match
          case key :: value :: Nil => Some(key -> value)
          case _ => None
      }
      .toMap
