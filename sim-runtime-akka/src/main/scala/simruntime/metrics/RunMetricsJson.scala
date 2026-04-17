package simruntime.metrics

import io.circe.Encoder
import io.circe.syntax.*

object RunMetricsJson:
  private given Encoder[RunMetrics] =
    Encoder.forProduct11(
      "startedAtEpochMs",
      "endedAtEpochMs",
      "durationMs",
      "messagesByType",
      "sentByEdge",
      "receivedByEdge",
      "droppedByNodeAndType",
      "inFlightEstimateByEdge",
      "algorithmEventsByType",
      "latestLeaderByNode",
      "snapshotTakenByNode"
    )(m =>
      (
        m.startedAtEpochMs,
        m.endedAtEpochMs,
        m.durationMs,
        m.messagesByType,
        m.sentByEdge,
        m.receivedByEdge,
        m.droppedByNodeAndType,
        m.inFlightEstimateByEdge,
        m.algorithmEventsByType,
        m.latestLeaderByNode,
        m.snapshotTakenByNode
      )
    )

  def toJson(metrics: RunMetrics): String =
    metrics.asJson.spaces2
