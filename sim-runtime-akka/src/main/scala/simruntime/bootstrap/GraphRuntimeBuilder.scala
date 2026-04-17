package simruntime.bootstrap

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import simcore.model.EnrichedGraph
import simruntime.actor.NodeActor
import simruntime.actor.NodeActor.TimerMode
import simruntime.metrics.{MetricsCollectorActor, RunMetrics}

import scala.concurrent.Await
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

  def shutdown(): Unit = system.terminate()

object GraphRuntimeBuilder:
  def start(
      graph: EnrichedGraph,
      systemName: String = "sim-runtime",
      algorithmNames: Set[String] = Set.empty,
      initiatorNodes: Set[Int] = Set.empty
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
        isInitiator = initiatorNodes.contains(id)
      )
    }

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
