package simalgorithms.laiyang

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import simalgorithms.api.{AlgorithmMessage, NodeContext}

import scala.collection.mutable

class LaiYangAlgorithmTest extends AnyFlatSpec with Matchers:
  "LaiYangAlgorithm" should "emit snapshot event immediately for initiator" in {
    val ctx = new FakeContext(1, Set(2, 3))
    val algo = new LaiYangAlgorithm(isInitiator = true)
    algo.onStart(ctx)

    ctx.events.exists(_.contains("snapshotTaken=true")) shouldBe true
    ctx.broadcasts.exists(_._1 == "LY_MARKER") shouldBe true
  }

  it should "take snapshot on first marker for non-initiator" in {
    val ctx = new FakeContext(2, Set(3))
    val algo = new LaiYangAlgorithm(isInitiator = false)
    algo.onStart(ctx)
    algo.onMessage(ctx, AlgorithmMessage(from = 1, kind = "LY_MARKER", payload = "m"))

    ctx.events.exists(_.contains("trigger=marker-from-1")) shouldBe true
    ctx.broadcasts.exists(_._1 == "LY_MARKER") shouldBe true
  }

  private final class FakeContext(val nodeId: Int, val neighbors: Set[Int]) extends NodeContext:
    val events: mutable.Buffer[String] = mutable.Buffer.empty
    val broadcasts: mutable.Buffer[(String, String, Option[Int])] = mutable.Buffer.empty

    override def send(to: Int, kind: String, payload: String): Boolean = true
    override def broadcast(kind: String, payload: String, except: Option[Int]): Int =
      broadcasts.append((kind, payload, except))
      neighbors.size - except.toSet.size
    override def emit(event: String): Unit = events.append(event)
