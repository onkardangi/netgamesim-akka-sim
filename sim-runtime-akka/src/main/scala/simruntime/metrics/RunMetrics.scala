package simruntime.metrics

final case class RunMetrics(
    startedAtEpochMs: Long,
    endedAtEpochMs: Long,
    durationMs: Long,
    messagesByType: Map[String, Long],
    sentByEdge: Map[String, Long],
    receivedByEdge: Map[String, Long],
    droppedByNodeAndType: Map[String, Long],
    inFlightEstimateByEdge: Map[String, Long],
    algorithmEventsByType: Map[String, Long],
    latestLeaderByNode: Map[String, Int],
    snapshotTakenByNode: Map[String, Boolean]
)

object RunMetrics:
  def edgeKey(from: Int, to: Int, kind: String): String = s"$from->$to:$kind"
  def droppedKey(nodeId: Int, kind: String): String = s"$nodeId:$kind"
