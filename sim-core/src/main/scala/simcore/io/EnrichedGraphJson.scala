package simcore.io

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.parser.parse
import io.circe.syntax.*
import simcore.model.{EnrichedEdge, EnrichedGraph, EnrichedNode}

/** JSON persistence for [[EnrichedGraph]]. */
object EnrichedGraphJson:

  /** Bump when the JSON shape changes; [[fromJson]] rejects any other value. */
  val CurrentSchemaVersion: Int = 1

  private case class Document(
      schemaVersion: Int,
      nodes: List[EnrichedNode],
      edges: List[EnrichedEdge]
  )

  private given Encoder[EnrichedNode] = deriveEncoder
  private given Decoder[EnrichedNode] = deriveDecoder

  private given Encoder[EnrichedEdge] =
    Encoder.forProduct3("source", "destination", "messageTypeLabel")(e =>
      (e.source, e.destination, e.messageTypeLabel)
    )
  private given Decoder[EnrichedEdge] =
    Decoder.forProduct3("source", "destination", "messageTypeLabel")(EnrichedEdge.apply)

  private given Encoder[Document] = deriveEncoder
  private given Decoder[Document] = deriveDecoder

  def toJson(graph: EnrichedGraph): String =
    val doc = Document(
      CurrentSchemaVersion,
      graph.nodes.toList.sortBy(_.id),
      graph.edges.toList.sortBy(e => (e.source, e.destination))
    )
    doc.asJson.noSpaces

  def fromJson(content: String): Either[String, EnrichedGraph] =
    parse(content) match
      case Left(err) => Left(err.message)
      case Right(json) =>
        json.hcursor.downField("schemaVersion").as[Int] match
          case Left(_) => Left("missing or invalid schemaVersion")
          case Right(v) if v != CurrentSchemaVersion =>
            Left(s"unsupported schema version: got $v, expected $CurrentSchemaVersion")
          case Right(_) =>
            json.as[Document].left.map(_.getMessage()).map { doc =>
              EnrichedGraph(doc.nodes.toSet, doc.edges.toSet)
            }

end EnrichedGraphJson
