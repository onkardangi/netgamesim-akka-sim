package simcore.enrich

import com.typesafe.config.Config
import simcore.model.*

import scala.jdk.CollectionConverters.*

/** Enriches a [[PlainGraph]] using `sim.enrichment` in a [[com.typesafe.config.Config]]. */
object GraphEnrichment:

  private val Root = "sim.enrichment"

  def enrich(plain: PlainGraph, config: Config): Either[String, EnrichedGraph] =
    if !config.hasPath(Root) then Left(s"missing config path $Root")
    else
      val c = config.getConfig(Root)
      for
        messageTypes <- readMessageTypes(c)
        defaultPdf <- expandPdfConfig(c.getConfig("defaultPdf"), messageTypes)
        perNodePdfs <- readPerNodePdf(c, messageTypes)
        _ <- validatePerNodeIds(perNodePdfs.keySet, plain)
        defaultEdgeLabel <- readLabel(c, "defaultEdgeLabel", messageTypes)
        perEdgeLabels <- readPerEdgeLabels(c, messageTypes)
        _ <- validatePerEdgeLabelKeys(perEdgeLabels.keySet, plain)
        nodes = buildNodes(plain, defaultPdf, perNodePdfs)
        edges <- buildEdges(plain, defaultEdgeLabel, perEdgeLabels)
      yield EnrichedGraph(nodes, edges)

  private def readMessageTypes(c: Config): Either[String, Seq[String]] =
    val list = c.getStringList("messageTypes").asScala.toSeq.map(_.trim).filter(_.nonEmpty)
    if list.isEmpty then Left("sim.enrichment.messageTypes must be non-empty")
    else if list.distinct.size != list.size then Left("sim.enrichment.messageTypes contains duplicates")
    else Right(list)

  /** Expands `preset` (and optional `s` for zipf — see [[PdfPreset.zipf]]). */
  private def expandPdfConfig(cfg: Config, messageTypes: Seq[String]): Either[String, Map[String, Double]] =
    cfg.getString("preset").trim.toLowerCase match
      case "uniform" => PdfPreset.uniform(messageTypes)
      case "zipf" =>
        val s = if cfg.hasPath("s") then cfg.getDouble("s") else 1.0
        PdfPreset.zipf(messageTypes, s)
      case other => Left(s"unknown PDF preset: $other")

  private def readPerNodePdf(parent: Config, messageTypes: Seq[String]): Either[String, Map[Int, Map[String, Double]]] =
    if !parent.hasPath("perNodePdf") then Right(Map.empty)
    else
      val pc = parent.getConfig("perNodePdf")
      val keys = pc.root().keySet().asScala.toSeq
      traverse(keys) { key =>
        val idE = scala.util.Try(key.strip().toInt).toEither.left.map(_ => s"perNodePdf: invalid node id key: $key")
        idE.flatMap { id =>
          expandPdfConfig(pc.getConfig(key), messageTypes).map(m => id -> m)
        }
      }.map(_.toMap)

  private def readLabel(c: Config, path: String, messageTypes: Seq[String]): Either[String, String] =
    val v = c.getString(path).trim
    if !messageTypes.contains(v) then Left(s"$path must be one of messageTypes; got $v")
    else Right(v)

  private def readPerEdgeLabels(c: Config, messageTypes: Seq[String]): Either[String, Map[(Int, Int), String]] =
    if !c.hasPath("perEdgeLabels") then Right(Map.empty)
    else
      val pc = c.getConfig("perEdgeLabels")
      val keys = pc.root().keySet().asScala.toSeq
      traverse(keys) { key =>
        for
          pair <- parseEdgeKey(key)
          label = pc.getString(key).trim
          _ <-
            if messageTypes.contains(label) then Right(())
            else Left(s"perEdgeLabels '$key': label '$label' is not in messageTypes")
        yield pair -> label
      }.map(_.toMap)

  /** Keys are `src_dst` with a single underscore (e.g. `0_1` for 0 → 1). */
  private def parseEdgeKey(key: String): Either[String, (Int, Int)] =
    key.strip() match
      case k if k.contains('_') =>
        val parts = k.split('_')
        if parts.length == 2 then
          scala.util.Try(parts(0).toInt).toEither.left.map(_ => s"perEdgeLabels: bad edge key: $key") flatMap { a =>
            scala.util.Try(parts(1).toInt).toEither.left.map(_ => s"perEdgeLabels: bad edge key: $key") map { b =>
              (a, b)
            }
          }
        else Left(s"perEdgeLabels: expected src_dst, got: $key")
      case k => Left(s"perEdgeLabels: expected src_dst, got: $key")

  private def validatePerNodeIds(ids: Set[Int], plain: PlainGraph): Either[String, Unit] =
    val present = plain.nodes.map(_.id)
    val unknown = ids -- present
    if unknown.nonEmpty then
      Left(s"perNodePdf references unknown node ids: ${unknown.toSeq.sorted.mkString(", ")}")
    else Right(())

  private def validatePerEdgeLabelKeys(
      keys: Set[(Int, Int)],
      plain: PlainGraph
  ): Either[String, Unit] =
    val edgesAsPairs = plain.edges.map(e => (e.source, e.destination))
    val unknown = keys -- edgesAsPairs
    if unknown.nonEmpty then
      val msg = unknown.toSeq
        .sortBy(_._1)
        .map { case (a, b) => s"${a}_${b}" }
        .mkString(", ")
      Left(s"perEdgeLabels reference edges not present in graph: $msg")
    else Right(())

  private def buildNodes(
      plain: PlainGraph,
      defaultPdf: Map[String, Double],
      perNode: Map[Int, Map[String, Double]]
  ): Set[EnrichedNode] =
    plain.nodes.map { n =>
      val pdf = perNode.getOrElse(n.id, defaultPdf)
      EnrichedNode(n.id, pdf)
    }

  private def buildEdges(
      plain: PlainGraph,
      defaultLabel: String,
      perEdge: Map[(Int, Int), String]
  ): Either[String, Set[EnrichedEdge]] =
    traverse(plain.edges.toSeq) { e =>
      val lab = perEdge.getOrElse((e.source, e.destination), defaultLabel)
      Right(EnrichedEdge(e.source, e.destination, lab))
    }.map(_.toSet)

  private def traverse[A, B](xs: Seq[A])(f: A => Either[String, B]): Either[String, Seq[B]] =
    xs.foldLeft(Right(Seq.empty): Either[String, Seq[B]]) { (acc, x) =>
      acc.flatMap(seq => f(x).map(seq :+ _))
    }

end GraphEnrichment
