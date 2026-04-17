package simalgorithms.leaderelection

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import simalgorithms.api.{AlgorithmMessage, NodeContext}

import scala.collection.mutable

class LeaderElectionTreeAlgorithmTest extends AnyFlatSpec with Matchers:
  "LeaderElectionTreeAlgorithm" should "broadcast own id on start" in {
    val ctx = new FakeContext(3, Set(1, 2))
    val algo = new LeaderElectionTreeAlgorithm
    algo.onStart(ctx)

    ctx.events.exists(_.contains("bestLeader=3")) shouldBe true
    ctx.broadcasts.lastOption.map(_._2) shouldBe Some("3")
  }

  it should "adopt higher candidate and rebroadcast" in {
    val ctx = new FakeContext(2, Set(1, 3))
    val algo = new LeaderElectionTreeAlgorithm
    algo.onStart(ctx)
    algo.onMessage(ctx, AlgorithmMessage(from = 1, kind = "LE_CAND", payload = "7"))

    ctx.events.exists(_.contains("bestLeader=7")) shouldBe true
    ctx.broadcasts.lastOption.map(_._2) shouldBe Some("7")
  }

  it should "ignore lower candidate ids" in {
    val ctx = new FakeContext(5, Set(4))
    val algo = new LeaderElectionTreeAlgorithm
    algo.onStart(ctx)
    val before = ctx.broadcasts.size
    algo.onMessage(ctx, AlgorithmMessage(from = 4, kind = "LE_CAND", payload = "2"))
    ctx.broadcasts.size shouldBe before
  }

  it should "converge to max node id across a fixed tree" in {
    val sim = new DeterministicTreeSimulation(
      topology = Map(
        1 -> Set(2),
        2 -> Set(1, 3),
        3 -> Set(2)
      )
    )
    val latestByNode = sim.runAndCollectLeaders()
    latestByNode shouldBe Map(1 -> 3, 2 -> 3, 3 -> 3)
  }

  private final class FakeContext(val nodeId: Int, val neighbors: Set[Int]) extends NodeContext:
    val events: mutable.Buffer[String] = mutable.Buffer.empty
    val broadcasts: mutable.Buffer[(String, String, Option[Int])] = mutable.Buffer.empty
    override def send(to: Int, kind: String, payload: String): Boolean = true
    override def broadcast(kind: String, payload: String, except: Option[Int]): Int =
      broadcasts.append((kind, payload, except))
      neighbors.size - except.toSet.size
    override def emit(event: String): Unit = events.append(event)

  private final class DeterministicTreeSimulation(topology: Map[Int, Set[Int]]):
    private val algorithms = topology.keys.map(id => id -> new LeaderElectionTreeAlgorithm).toMap
    private val latestLeaderByNode = mutable.Map.empty[Int, Int]
    private val queue = mutable.Queue.empty[(Int, Int, String, String)] // (to, from, kind, payload)

    private val contexts: Map[Int, NodeContext] = topology.map { case (id, nbrs) =>
      id -> new NodeContext:
        override def nodeId: Int = id
        override def neighbors: Set[Int] = nbrs
        override def send(to: Int, kind: String, payload: String): Boolean =
          if nbrs.contains(to) then
            queue.enqueue((to, id, kind, payload))
            true
          else false
        override def broadcast(kind: String, payload: String, except: Option[Int]): Int =
          val targets = nbrs.toList.sorted.filterNot(t => except.contains(t))
          targets.foreach(t => queue.enqueue((t, id, kind, payload)))
          targets.size
        override def emit(event: String): Unit =
          val parts = event.split("\\s+").toList
          val best = parts
            .find(_.startsWith("bestLeader="))
            .flatMap(x => scala.util.Try(x.stripPrefix("bestLeader=").toInt).toOption)
          best.foreach(v => latestLeaderByNode.update(id, v))
    }

    def runAndCollectLeaders(): Map[Int, Int] =
      topology.keys.toList.sorted.foreach { id =>
        algorithms(id).onStart(contexts(id))
      }
      while queue.nonEmpty do
        val (to, from, kind, payload) = queue.dequeue()
        algorithms(to).onMessage(contexts(to), AlgorithmMessage(from = from, kind = kind, payload = payload))
      latestLeaderByNode.toMap
