package simcli

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.typesafe.config.ConfigFactory
import simcore.model.{EnrichedEdge, EnrichedGraph, EnrichedNode}
import simruntime.metrics.RunMetrics

import java.nio.file.{Files, Path}

class SimMainTest extends AnyFlatSpec with Matchers:

  "SimMain.parseInteractiveCommand" should "parse valid inject commands" in {
    val parsed = SimMain.parseInteractiveCommand("inject 2 WORK hello-world")
    parsed shouldBe Right(SimMain.Injection(0L, 2, "WORK", "hello-world"))
  }

  it should "reject malformed interactive commands" in {
    SimMain.parseInteractiveCommand("inject").isLeft shouldBe true
    SimMain.parseInteractiveCommand("foo 1 WORK x").isLeft shouldBe true
  }

  "SimMain.parseInjectionFile" should "parse CSV injection rows" in {
    val tmp = Files.createTempFile("simcli-inject", ".csv")
    Files.writeString(tmp, "0,1,WORK,a\n25,2,PING,b\n")
    try
      val rows = SimMain.parseInjectionFile(tmp)
      rows shouldBe List(
        SimMain.Injection(0L, 1, "WORK", "a"),
        SimMain.Injection(25L, 2, "PING", "b")
      )
    finally Files.deleteIfExists(tmp)
  }

  it should "fail for invalid CSV rows" in {
    val tmp = Files.createTempFile("simcli-inject-bad", ".csv")
    Files.writeString(tmp, "bad,row\n")
    try
      val ex = the[IllegalArgumentException] thrownBy SimMain.parseInjectionFile(tmp)
      ex.getMessage should include("line 1")
    finally Files.deleteIfExists(tmp)
  }

  "SimMain.validateAlgorithmGraphConstraints" should "accept tree topology for leader election" in {
    val tree = EnrichedGraph(
      nodes = Set(EnrichedNode(1, Map.empty), EnrichedNode(2, Map.empty), EnrichedNode(3, Map.empty)),
      edges = Set(
        EnrichedEdge(1, 2, "LE_CAND"),
        EnrichedEdge(2, 1, "LE_CAND"),
        EnrichedEdge(2, 3, "LE_CAND"),
        EnrichedEdge(3, 2, "LE_CAND")
      )
    )
    SimMain.validateAlgorithmGraphConstraints(Set("leader-election-tree"), tree) shouldBe Right(())
  }

  it should "reject non-tree topology for leader election" in {
    val cyclic = EnrichedGraph(
      nodes = Set(
        EnrichedNode(1, Map.empty),
        EnrichedNode(2, Map.empty),
        EnrichedNode(3, Map.empty)
      ),
      edges = Set(
        EnrichedEdge(1, 2, "LE_CAND"),
        EnrichedEdge(2, 3, "LE_CAND"),
        EnrichedEdge(3, 1, "LE_CAND")
      )
    )
    SimMain.validateAlgorithmGraphConstraints(Set("leader-election-tree"), cyclic).isLeft shouldBe true
  }

  "SimMain.configuredRuntimeSeed" should "read sim.runtime.seed when present" in {
    SimMain.configuredRuntimeSeed(ConfigFactory.parseString("sim.runtime.seed = 99")) shouldBe 99L
  }

  it should "default to 0 when absent" in {
    SimMain.configuredRuntimeSeed(ConfigFactory.empty) shouldBe 0L
  }

  "SimMain.buildRunMeta" should "include reproducibility fields" in {
    val cli = SimMain.CliArgs(
      graphPath = Path.of("graph.ngs"),
      configPath = Path.of("conf/test.conf"),
      mode = SimMain.CliMode.File,
      injectFile = Some(Path.of("inject.csv")),
      durationMs = Some(500),
      outDir = Some(Path.of("out"))
    )
    val cfg = ConfigFactory.parseString("sim.runtime.seed = 42")
    val metrics = RunMetrics(
      startedAtEpochMs = 1000L,
      endedAtEpochMs = 1500L,
      durationMs = 500L,
      messagesByType = Map.empty,
      sentByEdge = Map.empty,
      receivedByEdge = Map.empty,
      droppedByNodeAndType = Map.empty,
      inFlightEstimateByEdge = Map.empty,
      algorithmEventsByType = Map.empty,
      latestLeaderByNode = Map.empty,
      snapshotTakenByNode = Map.empty
    )
    val meta = SimMain.buildRunMeta(cli, Set("lai-yang"), Set(0), cfg, metrics)
    meta.runId shouldBe "run-1000"
    meta.runtimeSeed shouldBe Some(42L)
    meta.algorithms shouldBe List("lai-yang")
    meta.algorithmInitiators shouldBe List(0)
  }
