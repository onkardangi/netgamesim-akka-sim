package simruntime

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import simruntime.actor.NodeActor
import simruntime.actor.NodeActor.TimerMode

import scala.concurrent.duration.DurationInt

class NodeActorRoutingTest extends AnyFlatSpecLike with Matchers with BeforeAndAfterAll:
  private val system = ActorSystem("node-actor-routing-test")

  override def afterAll(): Unit =
    system.terminate()
    ()

  "NodeActor" should "forward only allowed message types to neighbors" in {
    val neighbor = TestProbe()(system)
    val node = system.actorOf(NodeActor.props(1), "routing-node-1")

    node ! NodeActor.Init(
      neighbors = Map(2 -> neighbor.ref),
      allowedOnEdge = Map(2 -> Set("WORK")),
      pdf = Map.empty
    )

    node ! NodeActor.ExternalInput("WORK", "ok")
    neighbor.expectMsg(NodeActor.Envelope(from = 1, kind = "WORK", payload = "ok"))

    node ! NodeActor.ExternalInput("PING", "blocked")
    neighbor.expectNoMessage()
  }

  it should "emit periodic fixed timer traffic when configured" in {
    val neighbor = TestProbe()(system)
    val node = system.actorOf(NodeActor.props(11), "routing-node-11")

    node ! NodeActor.Init(
      neighbors = Map(2 -> neighbor.ref),
      allowedOnEdge = Map(2 -> Set("WORK")),
      pdf = Map.empty
    )
    node ! NodeActor.ConfigureTimer(50.millis, TimerMode.Fixed, Some("WORK"))

    val msg = neighbor.expectMsgType[NodeActor.Envelope]
    msg.from shouldBe 11
    msg.kind shouldBe "WORK"
    msg.payload should include("tick-from-11")
  }

  it should "publish dropped events when no edge allows the message kind" in {
    val node = system.actorOf(NodeActor.props(12), "routing-node-12")
    val probe = TestProbe()(system)
    system.eventStream.subscribe(probe.ref, classOf[NodeActor.Dropped])

    node ! NodeActor.Init(
      neighbors = Map.empty,
      allowedOnEdge = Map.empty,
      pdf = Map.empty
    )
    node ! NodeActor.ExternalInput("PING", "payload")

    probe.expectMsg(NodeActor.Dropped(nodeId = 12, kind = "PING", payload = "payload"))
  }
