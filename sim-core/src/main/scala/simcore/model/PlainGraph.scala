package simcore.model

/** Minimal in-memory graph mirroring NetGameSim export (Milestone 3 — no enrichment). */
final case class PlainNode(id: Int)

final case class PlainEdge(source: Int, destination: Int)

final case class PlainGraph(
    nodes: Set[PlainNode],
    edges: Set[PlainEdge]
):
  def nodeCount: Int = nodes.size
  def edgeCount: Int = edges.size
