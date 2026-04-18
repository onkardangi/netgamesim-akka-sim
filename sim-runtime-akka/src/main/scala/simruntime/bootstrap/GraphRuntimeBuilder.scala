package simruntime.bootstrap

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import simcore.model.EnrichedGraph
import simruntime.actor.NodeActor
import simruntime.actor.NodeActor.TimerMode
import simruntime.metrics.{MetricsCollectorActor, RunMetrics}

import org.slf4j.LoggerFactory

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.DurationInt

final case class RunningRuntime(system: ActorSystem, nodeRefs: Map[Int, ActorRef], metricsCollector: ActorRef):
  def configureTimer(nodeId: Int, tickEvery: FiniteDuration, mode: TimerMode, fixedMsg: Option[String]): Either[String, Unit] =
    nodeRefs.get(nodeId) match
      case None => Left(s"unknown node id for timer initiator: $nodeId")
      case Some(ref) =>
        ref ! NodeActor.ConfigureTimer(tickEvery = tickEvery, mode = mode, fixedMsg = fixedMsg)
        Right(())

  def inject(nodeId: Int, kind: String, payload: String): Either[String, Unit] =
    nodeRefs.get(nodeId) match
      case None => Left(s"unknown node id for input injection: $nodeId")
      case Some(ref) =>
        ref ! NodeActor.ExternalInput(kind = kind, payload = payload)
        Right(())

  def metricsSnapshot(timeout: FiniteDuration = 2.seconds): Either[String, RunMetrics] =
    implicit val to: Timeout = Timeout(timeout)
    val fut = (metricsCollector ? MetricsCollectorActor.Snapshot).map(_.asInstanceOf[RunMetrics])(system.dispatcher)
    Right(Await.result(fut, timeout + 200.millis))

  def finalizeMetrics(timeout: FiniteDuration = 2.seconds): Either[String, RunMetrics] =
    implicit val to: Timeout = Timeout(timeout)
    val fut = (metricsCollector ? MetricsCollectorActor.Snapshot).map(_.asInstanceOf[RunMetrics])(system.dispatcher)
    Right(Await.result(fut, timeout + 200.millis))

  /** Poll until every node's WORK queue depth is 0 and in-flight estimates are 0, or `maxWaitMs` elapses. */
  def waitUntilWorkloadDrained(maxWaitMs: Long, pollMs: Long = 50L): Unit =
    if nodeRefs.isEmpty then return
    val log = LoggerFactory.getLogger("simruntime.bootstrap.RunningRuntime")
    implicit val timeout: Timeout = Timeout(3.seconds)
    implicit val ec: ExecutionContext = system.dispatcher
    val deadline = System.currentTimeMillis() + maxWaitMs
    while System.currentTimeMillis() < deadline do
      finalizeMetrics() match
        case Left(_) =>
          Thread.sleep(pollMs)
        case Right(metrics) =>
          val inFlightOk = metrics.inFlightEstimateByEdge.values.forall(_ <= 0L)
          val depthPairs = Await.result(
            Future.sequence(nodeRefs.toSeq.map { case (nid, ref) =>
              (ref ? NodeActor.GetWorkQueueDepth).mapTo[Int].map(t => (nid, t))
            }),
            5.seconds
          )
          val allZero = depthPairs.forall(_._2 == 0)
          if inFlightOk && allZero then
            log.info("Workload drained: WORK queues empty and in-flight edge counts zero")
            return
      Thread.sleep(pollMs)
    log.warn(s"Workload drain wait exceeded ${maxWaitMs}ms; continuing")

  def shutdown(): Unit =
    val log = LoggerFactory.getLogger("simruntime.bootstrap.RunningRuntime")
    log.info(s"Terminating ActorSystem ${system.name}")
    system.terminate()

object GraphRuntimeBuilder:
  private val log = LoggerFactory.getLogger("simruntime.bootstrap.GraphRuntimeBuilder")
  def start(
      graph: EnrichedGraph,
      systemName: String = "sim-runtime",
      algorithmNames: Set[String] = Set.empty,
      initiatorNodes: Set[Int] = Set.empty,
      runtimeSeed: Long = 0L,
      workQueueEnabled: Boolean = false,
      initialWorkUnitsByNode: Map[Int, Int] = Map.empty
  ): RunningRuntime =
    validateGraph(graph)
    val system = ActorSystem(systemName)
    val nodeIds = graph.nodes.map(_.id).toSeq.sorted

    val nodeRefs = nodeIds.map { id =>
      id -> system.actorOf(NodeActor.props(id), s"node-$id")
    }.toMap
    val metricsCollector = system.actorOf(
      MetricsCollectorActor.props(System.currentTimeMillis()),
      "metrics-collector"
    )
    system.eventStream.subscribe(metricsCollector, classOf[NodeActor.Sent])
    system.eventStream.subscribe(metricsCollector, classOf[NodeActor.Received])
    system.eventStream.subscribe(metricsCollector, classOf[NodeActor.Dropped])
    system.eventStream.subscribe(metricsCollector, classOf[NodeActor.AlgorithmEvent])

    val nodePdf = graph.nodes.map(n => n.id -> n.pdf).toMap
    val outgoingBySource = graph.edges.groupBy(_.source)

    nodeIds.foreach { id =>
      val outgoing = outgoingBySource.getOrElse(id, Set.empty).toSeq
      val neighbors: Map[Int, ActorRef] = outgoing.map(e => e.destination -> nodeRefs(e.destination)).toMap
      val allowedOnEdge: Map[Int, Set[String]] =
        outgoing
          .groupBy(_.destination)
          .map { case (to, edges) => to -> edges.map(_.messageTypeLabel).toSet }
      nodeRefs(id) ! NodeActor.Init(
        neighbors = neighbors,
        allowedOnEdge = allowedOnEdge,
        pdf = nodePdf.getOrElse(id, Map.empty),
        algorithmNames = algorithmNames,
        isInitiator = initiatorNodes.contains(id),
        runtimeSeed = runtimeSeed,
        workQueueEnabled = workQueueEnabled,
        initialWorkSeedCount = initialWorkUnitsByNode.getOrElse(id, 0)
      )
    }

    log.info(
      s"ActorSystem '$systemName' started: ${nodeIds.size} node(s), algorithms=[${algorithmNames.toSeq.sorted.mkString(",")}], seed=$runtimeSeed"
    )
    RunningRuntime(system, nodeRefs, metricsCollector)

  private def validateGraph(graph: EnrichedGraph): Unit =
    val nodeIds = graph.nodes.map(_.id)
    val missingDestinations = graph.edges.map(_.destination) -- nodeIds
    val missingSources = graph.edges.map(_.source) -- nodeIds

    if missingSources.nonEmpty || missingDestinations.nonEmpty then
      val srcPart =
        if missingSources.isEmpty then ""
        else s"missing edge sources in nodes: ${missingSources.toSeq.sorted.mkString(", ")}"
      val dstPart =
        if missingDestinations.isEmpty then ""
        else s"missing edge destinations in nodes: ${missingDestinations.toSeq.sorted.mkString(", ")}"
      val sep = if srcPart.nonEmpty && dstPart.nonEmpty then "; " else ""
      throw new IllegalArgumentException(srcPart + sep + dstPart)
