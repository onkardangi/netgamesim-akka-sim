package simruntime

import akka.actor.ActorIdentity
import akka.testkit.TestProbe
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import simcore.model.{EnrichedEdge, EnrichedGraph, EnrichedNode}
import simruntime.actor.NodeActor
import simruntime.actor.NodeActor.TimerMode
import simruntime.bootstrap.GraphRuntimeBuilder
import scala.concurrent.duration.DurationInt

class GraphRuntimeBuilderTest extends AnyFlatSpec with Matchers:

  "GraphRuntimeBuilder" should "create one actor per node and initialize routes" in {
    val graph = EnrichedGraph(
      nodes = Set(
        EnrichedNode(1, Map("WORK" -> 1.0)),
        EnrichedNode(2, Map("WORK" -> 1.0))
      ),
      edges = Set(
        EnrichedEdge(1, 2, "WORK")
      )
    )

    val runtime = GraphRuntimeBuilder.start(graph, systemName = "graph-runtime-builder-test")
    try
      runtime.nodeRefs.keySet shouldBe Set(1, 2)

      val probe = TestProbe()(runtime.system)
      runtime.system.actorSelection("/user/node-1").tell(akka.actor.Identify("n1"), probe.ref)
      probe.expectMsgType[ActorIdentity].ref.isDefined shouldBe true

      runtime.system.actorSelection("/user/node-2").tell(akka.actor.Identify("n2"), probe.ref)
      probe.expectMsgType[ActorIdentity].ref.isDefined shouldBe true

      runtime.system.eventStream.subscribe(probe.ref, classOf[NodeActor.Received])
      runtime.nodeRefs(1) ! NodeActor.ExternalInput("WORK", "hello")

      probe.expectMsg(NodeActor.Received(nodeId = 2, from = 1, kind = "WORK", payload = "hello"))
      runtime.nodeRefs(1) ! NodeActor.ExternalInput("PING", "blocked")
      probe.expectNoMessage()
    finally runtime.shutdown()
  }

  it should "fail fast when an edge references unknown nodes" in {
    val badGraph = EnrichedGraph(
      nodes = Set(EnrichedNode(1, Map("WORK" -> 1.0))),
      edges = Set(EnrichedEdge(1, 99, "WORK"))
    )

    val ex = the[IllegalArgumentException] thrownBy GraphRuntimeBuilder.start(
      badGraph,
      systemName = "graph-runtime-builder-invalid"
    )
    ex.getMessage should include("missing edge destinations")
    ex.getMessage should include("99")
  }

  it should "support runtime injection and timer configuration helpers" in {
    val graph = EnrichedGraph(
      nodes = Set(
        EnrichedNode(1, Map("WORK" -> 1.0)),
        EnrichedNode(2, Map("WORK" -> 1.0))
      ),
      edges = Set(EnrichedEdge(1, 2, "WORK"))
    )

    val runtime = GraphRuntimeBuilder.start(graph, systemName = "graph-runtime-builder-control")
    val probe = TestProbe()(runtime.system)
    runtime.system.eventStream.subscribe(probe.ref, classOf[NodeActor.Received])

    try
      runtime.inject(1, "WORK", "manual") shouldBe Right(())
      probe.expectMsg(NodeActor.Received(nodeId = 2, from = 1, kind = "WORK", payload = "manual"))

      runtime.configureTimer(1, 50.millis, TimerMode.Fixed, Some("WORK")) shouldBe Right(())
      val tickMsg = probe.expectMsgType[NodeActor.Received]
      tickMsg.nodeId shouldBe 2
      tickMsg.kind shouldBe "WORK"
      tickMsg.payload should include("tick-from-1")

      runtime.inject(999, "WORK", "x").isLeft shouldBe true
      runtime.configureTimer(999, 100.millis, TimerMode.Fixed, Some("WORK")).isLeft shouldBe true

      val metrics = runtime.metricsSnapshot().getOrElse(fail("metrics snapshot"))
      metrics.messagesByType.getOrElse("WORK", 0L) should be >= 2L
      metrics.sentByEdge.getOrElse("1->2:WORK", 0L) should be >= 2L
      metrics.receivedByEdge.getOrElse("1->2:WORK", 0L) should be >= 2L
    finally runtime.shutdown()
  }

  it should "load Lai-Yang algorithm configuration without runtime errors" in {
    val graph = EnrichedGraph(
      nodes = Set(
        EnrichedNode(1, Map("WORK" -> 1.0)),
        EnrichedNode(2, Map("WORK" -> 1.0))
      ),
      edges = Set(EnrichedEdge(1, 2, "LY_MARKER"))
    )

    val runtime = GraphRuntimeBuilder.start(
      graph,
      systemName = "graph-runtime-builder-laiyang",
      algorithmNames = Set("lai-yang"),
      initiatorNodes = Set(1)
    )
    val probe = TestProbe()(runtime.system)
    runtime.system.eventStream.subscribe(probe.ref, classOf[NodeActor.AlgorithmError])

    try
      Thread.sleep(200)
      runtime.inject(1, "LY_MARKER", "trigger") shouldBe Right(())
      probe.expectNoMessage()
    finally runtime.shutdown()
  }

  it should "propagate leader-election candidate messages over a tree topology" in {
    val graph = EnrichedGraph(
      nodes = Set(
        EnrichedNode(1, Map("WORK" -> 1.0)),
        EnrichedNode(2, Map("WORK" -> 1.0)),
        EnrichedNode(3, Map("WORK" -> 1.0))
      ),
      edges = Set(
        EnrichedEdge(1, 2, "LE_CAND"),
        EnrichedEdge(2, 1, "LE_CAND"),
        EnrichedEdge(2, 3, "LE_CAND"),
        EnrichedEdge(3, 2, "LE_CAND")
      )
    )

    val runtime = GraphRuntimeBuilder.start(
      graph,
      systemName = "graph-runtime-builder-leader-election",
      algorithmNames = Set("leader-election-tree"),
      initiatorNodes = Set.empty
    )
    val probe = TestProbe()(runtime.system)
    runtime.system.eventStream.subscribe(probe.ref, classOf[NodeActor.AlgorithmError])

    try
      Thread.sleep(500)
      probe.expectNoMessage()
      val metrics = runtime.metricsSnapshot().getOrElse(fail("metrics snapshot"))
      val totalLeaderMsgs = metrics.messagesByType.getOrElse("LE_CAND", 0L)
      totalLeaderMsgs should be > 0L
      metrics.sentByEdge.keys.exists(_.contains("LE_CAND")) shouldBe true
      metrics.latestLeaderByNode shouldBe Map("1" -> 3, "2" -> 3, "3" -> 3)
    finally runtime.shutdown()
  }
