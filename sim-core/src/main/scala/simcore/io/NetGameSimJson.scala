package simcore.io

import io.circe.Decoder
import io.circe.parser.parse
import simcore.model.{PlainEdge, PlainGraph, PlainNode}

/** Parses the two-line JSON artifact produced by NetGameSim when `OutputGraphRepresentation.contentType = json`. */
object NetGameSimJson:
  private case class EdgeNodeRef(id: Int)
  private case class RawEdge(
      fromId: Int,
      toId: Int,
      fromNode: Option[EdgeNodeRef],
      toNode: Option[EdgeNodeRef]
  )
  private case class Artifact(nodes: Vector[PlainNode], edges: Vector[RawEdge])

  private given Decoder[PlainNode] =
    Decoder.forProduct1("id")(PlainNode.apply)

  private given Decoder[EdgeNodeRef] =
    Decoder.forProduct1("id")(EdgeNodeRef.apply)

  /** NetGameSim Action JSON includes `fromId`/`toId`; some exports also include `fromNode`/`toNode`. */
  private given Decoder[RawEdge] =
    Decoder.forProduct4("fromId", "toId", "fromNode", "toNode")(RawEdge.apply)

  private given Decoder[Artifact] =
    Decoder.forProduct2("nodes", "edges")(Artifact.apply)

  /** Preferred parser: supports object JSON (`{"nodes":[...],"edges":[...]}`) and legacy two-line JSON. */
  def parseArtifact(content: String): Either[String, PlainGraph] =
    parseObjectArtifact(content).orElse(parseTwoLineArtifact(content))

  /** First line: JSON array of node objects; second line: JSON array of edge objects. */
  def parseTwoLineArtifact(content: String): Either[String, PlainGraph] =
    val trimmed = content.stripLeading()
    val nl = trimmed.indexOf('\n')
    if nl < 0 then Left("Expected two lines: nodes JSON array, then edges JSON array")
    else
      val nodesLine = trimmed.substring(0, nl).strip()
      val edgesLine = trimmed.substring(nl + 1).strip()
      parseTwoLines(nodesLine, edgesLine)

  /** Valid JSON object artifact with top-level `nodes` and `edges` arrays. */
  def parseObjectArtifact(content: String): Either[String, PlainGraph] =
    parse(content) match
      case Left(err) => Left(err.message)
      case Right(json) =>
        json.as[Artifact] match
          case Left(err) => Left(err.message)
          case Right(artifact) =>
            buildGraph(artifact.nodes, artifact.edges)

  def parseTwoLines(nodesJsonLine: String, edgesJsonLine: String): Either[String, PlainGraph] =
    parse(nodesJsonLine) match
      case Left(err) => Left(err.message)
      case Right(nodesJson) =>
        parse(edgesJsonLine) match
          case Left(err) => Left(err.message)
          case Right(edgesJson) =>
            nodesJson.as[Vector[PlainNode]] match
              case Left(err) => Left(err.message)
              case Right(nodes) =>
                edgesJson.as[Vector[RawEdge]] match
                  case Left(err) => Left(err.message)
                  case Right(edges) =>
                    buildGraph(nodes, edges)

  private def buildGraph(nodes: Vector[PlainNode], edges: Vector[RawEdge]): Either[String, PlainGraph] =
    val nodeSet = nodes.toSet
    if nodeSet.size != nodes.size then Left("Duplicate node ids in artifact")
    else
      val nodeIds = nodeSet.map(_.id)
      resolveEdges(edges, nodeIds).map(resolved => PlainGraph(nodeSet, resolved.toSet))

  private def resolveEdges(edges: Vector[RawEdge], nodeIds: Set[Int]): Either[String, Vector[PlainEdge]] =
    traverse(edges.zipWithIndex) { (raw, idx) =>
      val from =
        if nodeIds.contains(raw.fromId) then Some(raw.fromId)
        else raw.fromNode.map(_.id).filter(nodeIds.contains)
      val to =
        if nodeIds.contains(raw.toId) then Some(raw.toId)
        else raw.toNode.map(_.id).filter(nodeIds.contains)

      (from, to) match
        case (Some(a), Some(b)) => Right(PlainEdge(a, b))
        case _ =>
          Left(
            s"edge[$idx] could not be mapped to known node ids: " +
              s"fromId=${raw.fromId}, toId=${raw.toId}, " +
              s"fromNode.id=${raw.fromNode.map(_.id).getOrElse("n/a")}, " +
              s"toNode.id=${raw.toNode.map(_.id).getOrElse("n/a")}"
          )
    }

  private def traverse[A, B](xs: Seq[A])(f: A => Either[String, B]): Either[String, Vector[B]] =
    xs.foldLeft(Right(Vector.empty): Either[String, Vector[B]]) { (acc, x) =>
      acc.flatMap(v => f(x).map(v :+ _))
    }

end NetGameSimJson
