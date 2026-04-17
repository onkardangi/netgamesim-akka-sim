package simcore.enrich

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import simcore.io.{EnrichedGraphJson, NetGameSimJson}
import simcore.model.{EnrichedEdge, EnrichedNode, PlainEdge, PlainGraph, PlainNode}

import scala.io.Source

/** End-to-end: Milestone 3 artifact → parse → Milestone 4 enrich. */
class EnrichmentIntegrationTest extends AnyFlatSpec with Matchers:

  "NetGameSim JSON → enrich" should "produce expected PDFs and edge labels" in {
    val content = Source.fromResource("sample-netgamesim.json")(using scala.io.Codec.UTF8).mkString
    val plain = NetGameSimJson.parseArtifact(content).getOrElse(fail("parse"))
    plain shouldBe PlainGraph(
      Set(PlainNode(0), PlainNode(1), PlainNode(2)),
      Set(PlainEdge(0, 1), PlainEdge(1, 2))
    )

    val cfg = ConfigFactory.parseResources("enrichment-integration.conf")
    val enriched = GraphEnrichment.enrich(plain, cfg).getOrElse(fail("enrich"))

    enriched.nodeCount shouldBe 3
    enriched.edgeCount shouldBe 2

    val u = PdfPreset.uniform(Seq("alpha", "beta", "gamma")).getOrElse(fail("uniform"))
    val z = PdfPreset.zipf(Seq("alpha", "beta", "gamma"), s = 1.0).getOrElse(fail("zipf"))

    enriched.nodes should contain theSameElementsAs Set(
      EnrichedNode(0, u),
      EnrichedNode(1, z),
      EnrichedNode(2, u)
    )
    enriched.edges should contain theSameElementsAs Set(
      EnrichedEdge(0, 1, "beta"),
      EnrichedEdge(1, 2, "gamma")
    )

    val round = EnrichedGraphJson.fromJson(EnrichedGraphJson.toJson(enriched))
    round shouldBe Right(enriched)
  }

end EnrichmentIntegrationTest
