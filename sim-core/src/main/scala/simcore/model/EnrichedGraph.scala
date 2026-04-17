package simcore.model

/** Node with a PDF over message-type names (what the node may emit). */
final case class EnrichedNode(id: Int, pdf: Map[String, Double])

/** Directed edge labeled with a message type (used to filter eligible neighbors after sampling). */
final case class EnrichedEdge(source: Int, destination: Int, messageTypeLabel: String)

final case class EnrichedGraph(
    nodes: Set[EnrichedNode],
    edges: Set[EnrichedEdge]
):
  def nodeCount: Int = nodes.size
  def edgeCount: Int = edges.size
