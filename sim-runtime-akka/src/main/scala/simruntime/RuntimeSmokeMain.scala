package simruntime

import simcore.model.{EnrichedEdge, EnrichedGraph, EnrichedNode}
import simruntime.actor.NodeActor
import simruntime.bootstrap.GraphRuntimeBuilder

object RuntimeSmokeMain:
  def main(args: Array[String]): Unit =
    val graph = EnrichedGraph(
      nodes = Set(
        EnrichedNode(1, Map("WORK" -> 1.0)),
        EnrichedNode(2, Map("WORK" -> 1.0))
      ),
      edges = Set(EnrichedEdge(1, 2, "WORK"))
    )

    val runtime = GraphRuntimeBuilder.start(graph, systemName = "runtime-smoke")
    val listener = runtime.system.actorOf(akka.actor.Props(new akka.actor.Actor:
      override def receive: Receive =
        case r: NodeActor.Received =>
          println(s"received at node=${r.nodeId} from=${r.from} kind=${r.kind} payload=${r.payload}")
    ))

    runtime.system.eventStream.subscribe(listener, classOf[NodeActor.Received])
    runtime.nodeRefs(1) ! NodeActor.ExternalInput("WORK", "smoke")
    Thread.sleep(500)
    runtime.shutdown()
