package simruntime.actor

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Timers}
import simalgorithms.AlgorithmRegistry
import simalgorithms.api.{AlgorithmMessage, DistributedAlgorithm, NodeContext}
import simruntime.work.WorkUnit

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

object NodeActor:
  def props(id: Int): Props = Props(new NodeActor(id))

  /** Derives a per-node RNG seed from the global `sim.runtime.seed` and node id (PDF / timer-Pdf traffic). */
  def mixRuntimeSeed(runtimeSeed: Long, nodeId: Int): Long =
    val z = runtimeSeed + 0x9e3779b97f4a7c15L + nodeId.toLong
    val z2 = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z2 ^ (z2 >>> 27)

  sealed trait Msg
  enum TimerMode:
    case Fixed, Pdf

  /** Outcome of attempting to move one queued WORK unit (send or local completion). */
  private[actor] enum WorkDispatch:
    case SentTo(to: Int)
    case ConsumedLocally

  final case class Init(
      neighbors: Map[Int, ActorRef],
      allowedOnEdge: Map[Int, Set[String]],
      pdf: Map[String, Double],
      algorithmNames: Set[String] = Set.empty,
      isInitiator: Boolean = false,
      runtimeSeed: Long = 0L,
      /** When true, WORK uses a per-node FIFO `WorkUnit` queue: seeded units, injections, and inbound WORK. */
      workQueueEnabled: Boolean = false,
      /** Count of `WorkUnit.Seeded` entries enqueued at startup (per-node). */
      initialWorkSeedCount: Int = 0
  ) extends Msg

  /** Reply: current local WORK queue depth (for terminating-workload drain detection). */
  case object GetWorkQueueDepth extends Msg

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

final class NodeActor(id: Int) extends Actor with Timers with ActorLogging:
  import NodeActor.*

  /* Akka classic: these fields are actor-local mailbox state — only `receive` mutates them (single-threaded per actor). */
  private var neighbors: Map[Int, ActorRef] = Map.empty
  private var allowedOnEdge: Map[Int, Set[String]] = Map.empty
  private var pdf: Map[String, Double] = Map.empty
  private var timerMode: TimerMode = TimerMode.Pdf
  private var fixedMsg: Option[String] = None
  private var pdfRandom: Random = new Random(NodeActor.mixRuntimeSeed(0L, id))
  private var algorithms: List[DistributedAlgorithm] = Nil
  private var workQueueEnabled: Boolean = false
  private val workQueue: mutable.Queue[WorkUnit] = mutable.Queue.empty

  override def receive: Receive =
    case Init(nbrs, allow, pdf0, algorithmNames, isInitiator, runtimeSeed, wqe, initialSeedCount) =>
      neighbors = nbrs
      allowedOnEdge = allow
      pdf = pdf0
      pdfRandom = new Random(NodeActor.mixRuntimeSeed(runtimeSeed, id))
      workQueueEnabled = wqe
      workQueue.clear()
      var i = 0
      while i < math.max(0, initialSeedCount) do
        workQueue.enqueue(WorkUnit.Seeded(i))
        i += 1
      algorithms = loadAlgorithms(algorithmNames, isInitiator)
      val ctx = buildNodeContext()
      algorithms.foreach(algo => runSafely(algo.name)(algo.onStart(ctx)))
      context.system.eventStream.publish(Initialized(id))
      log.info(
        s"Node $id initialized: ${neighbors.size} neighbor(s), algorithms=[${algorithmNames.toSeq.sorted.mkString(",")}], initiator=$isInitiator, workQueueEnabled=$workQueueEnabled workQueueDepth=${workQueue.size}"
      )

    case GetWorkQueueDepth =>
      sender() ! workQueue.size

    case ConfigureTimer(tickEvery, mode, fixed) =>
      timerMode = mode
      fixedMsg = fixed.map(_.trim).filter(_.nonEmpty)
      timers.startTimerAtFixedRate("tick", Tick, tickEvery)
      log.info(s"Node $id timer: interval=$tickEvery mode=$mode fixedMsg=${fixedMsg.getOrElse("")}")

    case ExternalInput(kind, payload) =>
      if workQueueEnabled && kind == "WORK" then
        workQueue.enqueue(WorkUnit.Injected(payload))
        log.info(s"Node $id external input: kind=$kind payload=$payload")
        dispatchOneQueuedWork() match
          case None =>
            context.system.eventStream.publish(Dropped(id, kind, payload))
          case Some(WorkDispatch.SentTo(_)) | Some(WorkDispatch.ConsumedLocally) => ()
      else
        log.info(s"Node $id external input: kind=$kind payload=$payload")
        sendToOneNeighbor(kind, payload) match
          case None =>
            context.system.eventStream.publish(Dropped(id, kind, payload))
          case Some(_) => ()

    case Tick =>
      val kind = timerMode match
        case TimerMode.Fixed => fixedMsg.getOrElse("PING")
        case TimerMode.Pdf => sampleFromPdfOrDefault("PING")
      val payload = s"tick-from-$id"
      if workQueueEnabled && kind == "WORK" then
        dispatchOneQueuedWork() match
          case None =>
            context.system.eventStream.publish(Dropped(id, kind, payload))
          case Some(WorkDispatch.SentTo(_)) | Some(WorkDispatch.ConsumedLocally) => ()
      else
        sendToOneNeighbor(kind, payload) match
          case None =>
            context.system.eventStream.publish(Dropped(id, kind, payload))
          case Some(_) => ()

      val ctx = buildNodeContext()
      algorithms.foreach(algo => runSafely(algo.name)(algo.onTick(ctx)))

    case Envelope(from, kind, payload) =>
      context.system.eventStream.publish(Received(id, from, kind, payload))
      val ctx = buildNodeContext()
      val msg = AlgorithmMessage(from, kind, payload)
      algorithms.foreach(algo => runSafely(algo.name)(algo.onMessage(ctx, msg)))
      if workQueueEnabled && kind == "WORK" then
        workQueue.enqueue(WorkUnit.Received(from, payload))
        drainRelayOrCompleteLocally()

  private def eligibleWorkNeighbors: List[Int] =
    neighbors.keys.filter(to => allowedOnEdge.getOrElse(to, Set.empty).contains("WORK")).toList.sorted

  /** Dequeue one unit: send on first WORK edge, or complete locally if none (sink / dead-end). */
  private def dispatchOneQueuedWork(): Option[WorkDispatch] =
    if workQueue.isEmpty then None
    else
      val unit = workQueue.dequeue()
      eligibleWorkNeighbors.headOption match
        case Some(to) =>
          neighbors(to) ! Envelope(from = id, kind = "WORK", payload = unit.wirePayload)
          context.system.eventStream.publish(Sent(id, to, "WORK", unit.wirePayload))
          Some(WorkDispatch.SentTo(to))
        case None =>
          Some(WorkDispatch.ConsumedLocally)

  private def drainRelayOrCompleteLocally(): Unit =
    while workQueue.nonEmpty do
      dispatchOneQueuedWork() match
        case None => return
        case Some(WorkDispatch.SentTo(_)) | Some(WorkDispatch.ConsumedLocally) => ()

  private def sendToOneNeighbor(kind: String, payload: String): Option[Int] =
    if workQueueEnabled && kind == "WORK" then
      dispatchOneQueuedWork() match
        case None => None
        case Some(WorkDispatch.SentTo(to)) => Some(to)
        case Some(WorkDispatch.ConsumedLocally) => None
    else
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
    else if workQueueEnabled && kind == "WORK" then
      if workQueue.isEmpty then
        context.system.eventStream.publish(Dropped(id, kind, payload))
        false
      else
        val unit = workQueue.dequeue()
        neighbors(to) ! Envelope(from = id, kind = "WORK", payload = unit.wirePayload)
        context.system.eventStream.publish(Sent(id, to, "WORK", unit.wirePayload))
        true
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
        val r = pdfRandom.nextDouble() * total
        val ordered = pdf.toSeq.sortBy(_._1)
        val probs = ordered.map(_._2)
        val cumAfter = probs.scanLeft(0.0)(_ + _).tail
        ordered
          .map(_._1)
          .zip(cumAfter)
          .find { case (_, cum) => r <= cum }
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
