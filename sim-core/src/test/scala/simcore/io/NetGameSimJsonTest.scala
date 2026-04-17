package simcore.io

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import simcore.model.{PlainEdge, PlainGraph, PlainNode}

import scala.io.Source

class NetGameSimJsonTest extends AnyFlatSpec with Matchers:

  "NetGameSimJson" should "parse the bundled JSON artifact and yield correct node and edge counts" in {
    val content = Source.fromResource("sample-netgamesim.json")(using scala.io.Codec.UTF8).mkString
    val result = NetGameSimJson.parseArtifact(content)
    result.isRight shouldBe true
    val graph = result.getOrElse(fail("expected successful parse"))
    graph.nodeCount shouldBe 3
    graph.edgeCount shouldBe 2
    graph.nodes should contain theSameElementsAs Set(
      PlainNode(0),
      PlainNode(1),
      PlainNode(2)
    )
    graph.edges should contain theSameElementsAs Set(
      PlainEdge(0, 1),
      PlainEdge(1, 2)
    )
  }

  it should "parse nodes and edges from separate lines" in {
    val nodes = """[{"id":10},{"id":20}]"""
    val edges = """[{"fromId":10,"toId":20}]"""
    NetGameSimJson.parseTwoLines(nodes, edges) shouldBe Right(
      PlainGraph(
        Set(PlainNode(10), PlainNode(20)),
        Set(PlainEdge(10, 20))
      )
    )
  }

  it should "reject artifacts with duplicate node ids" in {
    val nodes = """[{"id":1},{"id":1}]"""
    val edges = """[]"""
    NetGameSimJson.parseTwoLines(nodes, edges).isLeft shouldBe true
  }

  it should "reject content without a newline separator" in {
    NetGameSimJson.parseTwoLineArtifact("""[{"id":0}]""").isLeft shouldBe true
  }

  it should "parse object artifacts with top-level nodes and edges arrays" in {
    val content =
      """{"nodes":[{"id":1},{"id":2}],"edges":[{"fromId":1,"toId":2}]}"""
    NetGameSimJson.parseArtifact(content) shouldBe Right(
      PlainGraph(Set(PlainNode(1), PlainNode(2)), Set(PlainEdge(1, 2)))
    )
  }

  it should "fallback to fromNode.id/toNode.id when fromId/toId are out of node set" in {
    val nodes = """[{"id":0},{"id":1},{"id":2}]"""
    val edges =
      """[{"fromId":999,"toId":888,"fromNode":{"id":1},"toNode":{"id":2}}]"""
    NetGameSimJson.parseTwoLines(nodes, edges) shouldBe Right(
      PlainGraph(
        Set(PlainNode(0), PlainNode(1), PlainNode(2)),
        Set(PlainEdge(1, 2))
      )
    )
  }

end NetGameSimJsonTest
