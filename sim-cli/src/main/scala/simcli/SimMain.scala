package simcli

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.circe.Encoder
import io.circe.syntax.*
import simcore.enrich.GraphEnrichment
import simcore.io.NetGameSimJson
import simcore.model.EnrichedGraph
import simruntime.actor.NodeActor.TimerMode
import simruntime.bootstrap.GraphRuntimeBuilder
import simruntime.metrics.{RunMetrics, RunMetricsJson}

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import scala.concurrent.ExecutionContext
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Try

object SimMain:
  def main(args: Array[String]): Unit =
    parseArgs(args.toList) match
      case Left(err) =>
        System.err.println(err)
        printUsage()
      case Right(cli) =>
        val graphContent = readFile(cli.graphPath)
        val config = ConfigFactory.parseFile(cli.configPath.toFile).resolve()

        val result =
          for
            plain <- NetGameSimJson.parseArtifact(graphContent)
            enriched <- GraphEnrichment.enrich(plain, config)
          yield enriched

        result match
          case Left(err) =>
            System.err.println(s"Failed: $err")
          case Right(enriched) =>
            println(s"Parsed graph nodes=${enriched.nodeCount}, edges=${enriched.edgeCount}")
            val algorithmNames = configuredAlgorithms(config)
            validateAlgorithmGraphConstraints(algorithmNames, enriched) match
              case Left(err) =>
                System.err.println(s"Failed: $err")
                return
              case Right(_) => ()
            val initiators = configuredAlgorithmInitiators(config)
            val runtime = GraphRuntimeBuilder.start(
              enriched,
              systemName = "sim-cli-run",
              algorithmNames = algorithmNames,
              initiatorNodes = initiators
            )
            try
              configureTimers(runtime, config) match
                case Left(err) => System.err.println(s"Timer configuration warning: $err")
                case Right(count) => println(s"Configured timer initiators: $count")

              cli.mode match
                case CliMode.File =>
                  val injectPath = cli.injectFile.getOrElse {
                    throw new IllegalArgumentException("--inject-file is required in file mode")
                  }
                  val injections = parseInjectionFile(injectPath)
                  runFileMode(runtime, injections, cli.durationMs.getOrElse(1500L))
                case CliMode.Interactive =>
                  runInteractiveMode(runtime)
              val outDir = cli.outDir.getOrElse(defaultOutDir())
              writeOutputs(runtime, outDir, cli, algorithmNames, initiators, config)
            finally runtime.shutdown()

  enum CliMode:
    case File, Interactive

  final case class CliArgs(
      graphPath: Path,
      configPath: Path,
      mode: CliMode,
      injectFile: Option[Path],
      durationMs: Option[Long],
      outDir: Option[Path]
  )
  final case class TimerInitiator(node: Int, tickEveryMs: Long, mode: TimerMode, fixedMsg: Option[String])
  final case class Injection(atMs: Long, nodeId: Int, kind: String, payload: String)
  final case class RunMeta(
      runId: String,
      graphPath: String,
      configPath: String,
      mode: String,
      algorithms: List[String],
      algorithmInitiators: List[Int],
      runtimeSeed: Option[Long],
      durationMsConfigured: Option[Long],
      injectFile: Option[String],
      metricsStartedAtEpochMs: Long,
      metricsEndedAtEpochMs: Long
  )

  private given Encoder[RunMeta] =
    Encoder.forProduct11(
      "runId",
      "graphPath",
      "configPath",
      "mode",
      "algorithms",
      "algorithmInitiators",
      "runtimeSeed",
      "durationMsConfigured",
      "injectFile",
      "metricsStartedAtEpochMs",
      "metricsEndedAtEpochMs"
    )(m =>
      (
        m.runId,
        m.graphPath,
        m.configPath,
        m.mode,
        m.algorithms,
        m.algorithmInitiators,
        m.runtimeSeed,
        m.durationMsConfigured,
        m.injectFile,
        m.metricsStartedAtEpochMs,
        m.metricsEndedAtEpochMs
      )
    )

  private def parseArgs(args: List[String]): Either[String, CliArgs] =
    def nextValue(flag: String, xs: List[String]): Either[String, String] =
      xs.sliding(2).collectFirst { case List(`flag`, value) => value } match
        case Some(value) => Right(value)
        case None => Left(s"missing required argument $flag")

    for
      graph <- nextValue("--graph", args)
      conf <- nextValue("--config", args)
      modeRaw <- nextValue("--mode", args)
      mode <- parseMode(modeRaw)
      inject <- parseOptionalFileArg(args, "--inject-file")
      duration <- parseOptionalLong(args, "--duration-ms")
      out <- parseOptionalPathArg(args, "--out")
      graphPath <- existingFile(graph, "--graph")
      configPath <- existingFile(conf, "--config")
    yield CliArgs(graphPath, configPath, mode, inject, duration, out)

  private def parseMode(raw: String): Either[String, CliMode] =
    raw.trim.toLowerCase match
      case "file" => Right(CliMode.File)
      case "interactive" => Right(CliMode.Interactive)
      case other => Left(s"unsupported --mode '$other' (expected file|interactive)")

  private def parseOptionalLong(args: List[String], flag: String): Either[String, Option[Long]] =
    args.sliding(2).collectFirst { case List(`flag`, value) => value } match
      case None => Right(None)
      case Some(v) =>
        Try(v.toLong).toEither.left.map(_ => s"$flag must be an integer").map(Some(_))

  private def parseOptionalFileArg(args: List[String], flag: String): Either[String, Option[Path]] =
    args.sliding(2).collectFirst { case List(`flag`, value) => value } match
      case None => Right(None)
      case Some(v) => existingFile(v, flag).map(Some(_))

  private def parseOptionalPathArg(args: List[String], flag: String): Either[String, Option[Path]] =
    args.sliding(2).collectFirst { case List(`flag`, value) => value } match
      case None => Right(None)
      case Some(v) => Right(Some(Path.of(v)))

  private def existingFile(raw: String, flag: String): Either[String, Path] =
    val p = Path.of(raw)
    if Files.exists(p) && Files.isRegularFile(p) then Right(p)
    else Left(s"$flag path does not exist or is not a file: $raw")

  private def readFile(path: Path): String =
    val src = Source.fromFile(path.toFile)(using scala.io.Codec.UTF8)
    try src.mkString
    finally src.close()

  private def configureTimers(runtime: simruntime.bootstrap.RunningRuntime, config: Config): Either[String, Int] =
    if !config.hasPath("sim.runtime.initiators.timers") then Right(0)
    else
      val timerCfgs = config.getConfigList("sim.runtime.initiators.timers").asScala.toList
      val timersE = traverse(timerCfgs)(parseTimerConfig)
      timersE.flatMap { timers =>
        traverse(timers) { t =>
          runtime.configureTimer(
            nodeId = t.node,
            tickEvery = scala.concurrent.duration.DurationLong(t.tickEveryMs).millis,
            mode = t.mode,
            fixedMsg = t.fixedMsg
          )
        }.map(_ => timers.size)
      }

  private def configuredAlgorithms(config: Config): Set[String] =
    if !config.hasPath("sim.runtime.algorithms") then Set.empty
    else config.getStringList("sim.runtime.algorithms").asScala.map(_.trim).filter(_.nonEmpty).toSet

  private def configuredAlgorithmInitiators(config: Config): Set[Int] =
    if !config.hasPath("sim.runtime.algorithmInitiators") then Set.empty
    else config.getIntList("sim.runtime.algorithmInitiators").asScala.map(_.toInt).toSet

  private[simcli] def validateAlgorithmGraphConstraints(
      algorithmNames: Set[String],
      graph: EnrichedGraph
  ): Either[String, Unit] =
    val normalized = algorithmNames.map(_.trim.toLowerCase)
    if normalized.exists(a => a == "leader-election-tree" || a == "leaderelection-tree" || a == "tree-leader-election")
    then
      if isUndirectedTree(graph) then Right(())
      else Left("leader-election-tree requires the graph topology to be a tree")
    else Right(())

  private[simcli] def isUndirectedTree(graph: EnrichedGraph): Boolean =
    val nodeIds = graph.nodes.map(_.id)
    if nodeIds.isEmpty then true
    else
      val undirectedEdges = graph.edges.map { e =>
        val a = math.min(e.source, e.destination)
        val b = math.max(e.source, e.destination)
        (a, b)
      }.filter { case (a, b) => a != b }
      if undirectedEdges.size != nodeIds.size - 1 then false
      else
        val adjacency = undirectedEdges.foldLeft(Map.empty[Int, Set[Int]].withDefaultValue(Set.empty)) {
          case (acc, (a, b)) =>
            acc.updated(a, acc(a) + b).updated(b, acc(b) + a)
        }
        val start = nodeIds.head
        val seen = bfs(start, adjacency)
        seen == nodeIds

  private def bfs(start: Int, adjacency: Map[Int, Set[Int]]): Set[Int] =
    @annotation.tailrec
    def loop(queue: List[Int], seen: Set[Int]): Set[Int] =
      queue match
        case Nil => seen
        case x :: rest =>
          val next = adjacency.getOrElse(x, Set.empty).diff(seen)
          loop(rest ++ next.toList, seen ++ next)
    loop(List(start), Set(start))

  private def parseTimerConfig(c: Config): Either[String, TimerInitiator] =
    val node = c.getInt("node")
    val tickEveryMs = c.getLong("tickEveryMs")
    val modeRaw = c.getString("mode").trim.toLowerCase
    val mode = modeRaw match
      case "fixed" => Right(TimerMode.Fixed)
      case "pdf" => Right(TimerMode.Pdf)
      case other => Left(s"unknown timer mode '$other'")
    val fixed = if c.hasPath("fixedMsg") then Some(c.getString("fixedMsg")) else None
    mode.map(m => TimerInitiator(node, tickEveryMs, m, fixed))

  private def runFileMode(
      runtime: simruntime.bootstrap.RunningRuntime,
      injections: List[Injection],
      durationMs: Long
  ): Unit =
    given ExecutionContext = runtime.system.dispatcher
    injections.foreach { inj =>
      runtime.system.scheduler.scheduleOnce(
        scala.concurrent.duration.DurationLong(inj.atMs).millis
      ) {
        runtime.inject(inj.nodeId, inj.kind, inj.payload) match
          case Left(err) => System.err.println(s"injection failed: $err")
          case Right(_) => ()
      }
    }
    println(s"Scheduled ${injections.size} injections. Waiting ${durationMs}ms.")
    Thread.sleep(durationMs)

  private def runInteractiveMode(runtime: simruntime.bootstrap.RunningRuntime): Unit =
    println("Interactive mode. Commands: inject <nodeId> <kind> <payload> | quit")
    Iterator
      .continually(scala.io.StdIn.readLine())
      .takeWhile(line => line != null && line.trim.toLowerCase != "quit")
      .foreach { line =>
        parseInteractiveCommand(line) match
          case Left(err) => System.err.println(err)
          case Right(inj) =>
            runtime.inject(inj.nodeId, inj.kind, inj.payload) match
              case Left(err) => System.err.println(err)
              case Right(_) => println(s"injected to node=${inj.nodeId} kind=${inj.kind}")
      }

  private[simcli] def writeOutputs(
      runtime: simruntime.bootstrap.RunningRuntime,
      outDir: Path,
      cli: CliArgs,
      algorithmNames: Set[String],
      initiators: Set[Int],
      config: Config
  ): Unit =
    runtime.finalizeMetrics() match
      case Left(err) => System.err.println(s"could not finalize metrics: $err")
      case Right(metrics) =>
        Files.createDirectories(outDir)
        val metricsPath = outDir.resolve("metrics.json")
        Files.writeString(metricsPath, RunMetricsJson.toJson(metrics), StandardCharsets.UTF_8)
        println(s"Wrote metrics to $metricsPath")
        val runMeta = buildRunMeta(cli, algorithmNames, initiators, config, metrics)
        val metaPath = outDir.resolve("run-meta.json")
        Files.writeString(metaPath, runMeta.asJson.spaces2, StandardCharsets.UTF_8)
        println(s"Wrote run metadata to $metaPath")

  private[simcli] def buildRunMeta(
      cli: CliArgs,
      algorithmNames: Set[String],
      initiators: Set[Int],
      config: Config,
      metrics: RunMetrics
  ): RunMeta =
    RunMeta(
      runId = s"run-${metrics.startedAtEpochMs}",
      graphPath = cli.graphPath.toString,
      configPath = cli.configPath.toString,
      mode = cli.mode.toString.toLowerCase,
      algorithms = algorithmNames.toList.sorted,
      algorithmInitiators = initiators.toList.sorted,
      runtimeSeed = if config.hasPath("sim.runtime.seed") then Some(config.getLong("sim.runtime.seed")) else None,
      durationMsConfigured = cli.durationMs,
      injectFile = cli.injectFile.map(_.toString),
      metricsStartedAtEpochMs = metrics.startedAtEpochMs,
      metricsEndedAtEpochMs = metrics.endedAtEpochMs
    )

  private def defaultOutDir(): Path =
    Path.of("outputs", s"run-${System.currentTimeMillis()}")

  private[simcli] def parseInteractiveCommand(line: String): Either[String, Injection] =
    val parts = line.trim.split("\\s+", 4).toList
    parts match
      case "inject" :: node :: kind :: payload :: Nil =>
        Try(node.toInt).toEither.left
          .map(_ => s"invalid node id '$node'")
          .map(n => Injection(0L, n, kind, payload))
      case _ => Left("invalid command. expected: inject <nodeId> <kind> <payload> | quit")

  private[simcli] def parseInjectionFile(path: Path): List[Injection] =
    val src = Source.fromFile(path.toFile)(using scala.io.Codec.UTF8)
    try
      src.getLines().zipWithIndex.filter(_._1.trim.nonEmpty).map { (line, idx) =>
        parseInjectionLine(line, idx + 1)
      }.toList
    finally src.close()

  private def parseInjectionLine(line: String, lineNo: Int): Injection =
    val parts = line.split(",", 4).map(_.trim).toList
    parts match
      case at :: node :: kind :: payload :: Nil =>
        val atMs = Try(at.toLong).getOrElse(throw new IllegalArgumentException(s"line $lineNo invalid atMs: '$at'"))
        val nodeId = Try(node.toInt).getOrElse(throw new IllegalArgumentException(s"line $lineNo invalid nodeId: '$node'"))
        if atMs < 0 then throw new IllegalArgumentException(s"line $lineNo atMs must be >= 0")
        Injection(atMs, nodeId, kind, payload)
      case _ =>
        throw new IllegalArgumentException(s"line $lineNo expected format: atMs,nodeId,kind,payload")

  private def traverse[A, B](xs: Seq[A])(f: A => Either[String, B]): Either[String, List[B]] =
    xs.foldLeft(Right(List.empty): Either[String, List[B]]) { (acc, x) =>
      acc.flatMap(list => f(x).map(list :+ _))
    }

  private def printUsage(): Unit =
    println(
      "Usage: sim-cli/runMain simcli.SimMain --graph <path> --config <path> --mode <file|interactive> " +
        "[--inject-file <path>] [--duration-ms <ms>] [--out <dir>]"
    )
