package simruntime.actor

import akka.actor.{Actor, ActorRef, Props, Timers}
import simalgorithms.AlgorithmRegistry
import simalgorithms.api.{AlgorithmMessage, DistributedAlgorithm, NodeContext}

import scala.concurrent.duration.FiniteDuration
import scala.util.Random

object NodeActor:
  def props(id: Int): Props = Props(new NodeActor(id))

  sealed trait Msg
  enum TimerMode:
    case Fixed, Pdf

  final case class Init(
      neighbors: Map[Int, ActorRef],
      allowedOnEdge: Map[Int, Set[String]],
      pdf: Map[String, Double],
      algorithmNames: Set[String] = Set.empty,
      isInitiator: Boolean = false
  ) extends Msg
  final case class ConfigureTimer(
      tickEvery: FiniteDuration,
      mode: TimerMode,
      fixedMsg: Option[String]
  ) extends Msg
  final case class ExternalInput(kind: String, payload: String) extends Msg
  final case class Envelope(from: Int, kind: String, payload: String) extends Msg
  private case object Tick extends Msg

  /** Published to eventStream when a message is received by a node actor. */
  final case class Sent(from: Int, to: Int, kind: String, payload: String)
  final case class Received(nodeId: Int, from: Int, kind: String, payload: String)
  final case class Dropped(nodeId: Int, kind: String, payload: String)
  final case class Initialized(nodeId: Int)
  final case class AlgorithmEvent(nodeId: Int, algorithm: String, event: String)
  final case class AlgorithmError(nodeId: Int, algorithm: String, error: String)

final class NodeActor(id: Int) extends Actor with Timers:
  import NodeActor.*

  private var neighbors: Map[Int, ActorRef] = Map.empty
  private var allowedOnEdge: Map[Int, Set[String]] = Map.empty
  private var pdf: Map[String, Double] = Map.empty
  private var timerMode: TimerMode = TimerMode.Pdf
  private var fixedMsg: Option[String] = None
  private val random = new Random(id.toLong)
  private var algorithms: List[DistributedAlgorithm] = Nil

  override def receive: Receive =
    case Init(nbrs, allow, pdf0, algorithmNames, isInitiator) =>
      neighbors = nbrs
      allowedOnEdge = allow
      pdf = pdf0
      algorithms = loadAlgorithms(algorithmNames, isInitiator)
      val ctx = buildNodeContext()
      algorithms.foreach(algo => runSafely(algo.name)(algo.onStart(ctx)))
      context.system.eventStream.publish(Initialized(id))

    case ConfigureTimer(tickEvery, mode, fixed) =>
      timerMode = mode
      fixedMsg = fixed.map(_.trim).filter(_.nonEmpty)
      timers.startTimerAtFixedRate("tick", Tick, tickEvery)

    case ExternalInput(kind, payload) =>
      sendToOneNeighbor(kind, payload).orElse {
        context.system.eventStream.publish(Dropped(id, kind, payload))
        None
      }

    case Tick =>
      val kind = timerMode match
        case TimerMode.Fixed => fixedMsg.getOrElse("PING")
        case TimerMode.Pdf => sampleFromPdfOrDefault("PING")
      val payload = s"tick-from-$id"
      sendToOneNeighbor(kind, payload).orElse {
        context.system.eventStream.publish(Dropped(id, kind, payload))
        None
      }
      val ctx = buildNodeContext()
      algorithms.foreach(algo => runSafely(algo.name)(algo.onTick(ctx)))

    case Envelope(from, kind, payload) =>
      context.system.eventStream.publish(Received(id, from, kind, payload))
      val ctx = buildNodeContext()
      val msg = AlgorithmMessage(from, kind, payload)
      algorithms.foreach(algo => runSafely(algo.name)(algo.onMessage(ctx, msg)))

  private def sendToOneNeighbor(kind: String, payload: String): Option[Int] =
    val eligible = neighbors.keys.filter { to =>
      allowedOnEdge.getOrElse(to, Set.empty).contains(kind)
    }.toList.sorted

    eligible.headOption.map { to =>
      neighbors(to) ! Envelope(from = id, kind = kind, payload = payload)
      context.system.eventStream.publish(Sent(id, to, kind, payload))
      to
    }

  private def sendToNeighbor(to: Int, kind: String, payload: String): Boolean =
    if !neighbors.contains(to) then false
    else if !allowedOnEdge.getOrElse(to, Set.empty).contains(kind) then
      context.system.eventStream.publish(Dropped(id, kind, payload))
      false
    else
      neighbors(to) ! Envelope(from = id, kind = kind, payload = payload)
      context.system.eventStream.publish(Sent(id, to, kind, payload))
      true

  private def sampleFromPdfOrDefault(default: String): String =
    if pdf.isEmpty then default
    else
      val total = pdf.values.sum
      if total <= 0.0 then default
      else
        val r = random.nextDouble() * total
        var cumulative = 0.0
        val ordered = pdf.toSeq.sortBy(_._1)
        ordered
          .find { case (_, p) =>
            cumulative += p
            r <= cumulative
          }
          .map(_._1)
          .getOrElse(ordered.last._1)

  private def buildNodeContext(): NodeContext =
    new NodeContext:
      override def nodeId: Int = id
      override def neighbors: Set[Int] = NodeActor.this.neighbors.keySet
      override def send(to: Int, kind: String, payload: String): Boolean =
        sendToNeighbor(to, kind, payload)
      override def broadcast(kind: String, payload: String, except: Option[Int]): Int =
        NodeActor.this.neighbors.keySet.toSeq.sorted
          .filterNot(to => except.contains(to))
          .count(to => sendToNeighbor(to, kind, payload))
      override def emit(event: String): Unit =
        context.system.eventStream.publish(AlgorithmEvent(id, "custom", event))

  private def loadAlgorithms(algorithmNames: Set[String], isInitiator: Boolean): List[DistributedAlgorithm] =
    algorithmNames.toSeq.sorted.flatMap { name =>
      AlgorithmRegistry.create(name, isInitiator) match
        case Right(a) => Some(a)
        case Left(err) =>
          context.system.eventStream.publish(AlgorithmError(id, name, err))
          None
    }.toList

  private def runSafely(algorithmName: String)(f: => Unit): Unit =
    try f
    catch
      case ex: Throwable =>
        context.system.eventStream.publish(AlgorithmError(id, algorithmName, ex.getMessage))
