package simruntime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import simruntime.metrics.{RunMetrics, RunMetricsJson}

class RunMetricsJsonTest extends AnyFlatSpec with Matchers:
  "RunMetricsJson" should "serialize metrics to JSON" in {
    val m = RunMetrics(
      startedAtEpochMs = 1L,
      endedAtEpochMs = 2L,
      durationMs = 1L,
      messagesByType = Map("WORK" -> 3L),
      sentByEdge = Map("1->2:WORK" -> 3L),
      receivedByEdge = Map("1->2:WORK" -> 2L),
      droppedByNodeAndType = Map("1:PING" -> 1L),
      inFlightEstimateByEdge = Map("1->2:WORK" -> 1L),
      algorithmEventsByType = Map("lai-yang:start" -> 1L),
      latestLeaderByNode = Map("1" -> 3),
      snapshotTakenByNode = Map("1" -> true)
    )
    val json = RunMetricsJson.toJson(m)
    json should include("messagesByType")
    json should include("1->2:WORK")
    json should include("latestLeaderByNode")
  }
