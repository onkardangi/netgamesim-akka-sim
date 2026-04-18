package simcore.enrich

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import simcore.model.{PlainEdge, PlainGraph, PlainNode}

class GraphEnrichmentTest extends AnyFlatSpec with Matchers:

  private val tiny = PlainGraph(
    Set(PlainNode(1), PlainNode(2)),
    Set(PlainEdge(1, 2))
  )

  "GraphEnrichment" should "fail when perNodePdf names a node not in the plain graph" in {
    val cfg = ConfigFactory.parseString("""
      sim.enrichment {
        messageTypes = [x, y]
        defaultPdf { preset = uniform }
        perNodePdf {
          "99" { preset = uniform }
        }
        defaultEdgeLabel = x
      }
      """)
    val err = GraphEnrichment.enrich(tiny, cfg).left.toOption.getOrElse(fail("expected Left"))
    err should include("unknown node ids")
    err should include("99")
  }

  it should "fail when perEdgeLabels references a missing edge" in {
    val cfg = ConfigFactory.parseString("""
      sim.enrichment {
        messageTypes = [x, y]
        defaultPdf { preset = uniform }
        defaultEdgeLabel = x
        perEdgeLabels {
          "9_9" = y
        }
      }
      """)
    val err = GraphEnrichment.enrich(tiny, cfg).left.toOption.getOrElse(fail("expected Left"))
    err should include("not present in graph")
  }

  it should "fail when defaultEdgeLabel is not in messageTypes" in {
    val cfg = ConfigFactory.parseString("""
      sim.enrichment {
        messageTypes = [x, y]
        defaultPdf { preset = uniform }
        defaultEdgeLabel = z
      }
      """)
    GraphEnrichment.enrich(tiny, cfg).isLeft shouldBe true
  }

  it should "accept explicit defaultPdf masses that sum to 1.0" in {
    val cfg = ConfigFactory.parseString("""
      sim.enrichment {
        messageTypes = [x, y]
        defaultPdf {
          masses {
            x = 0.25
            y = 0.75
          }
        }
        defaultEdgeLabel = x
      }
      """)
    val g = GraphEnrichment.enrich(tiny, cfg).getOrElse(fail("expected Right"))
    g.nodes.find(_.id == 1).map(_.pdf).getOrElse(fail("node 1")) shouldBe Map("x" -> 0.25, "y" -> 0.75)
  }

  it should "fail when explicit masses do not sum to 1.0" in {
    val cfg = ConfigFactory.parseString("""
      sim.enrichment {
        messageTypes = [x, y]
        defaultPdf {
          masses {
            x = 0.3
            y = 0.3
          }
        }
        defaultEdgeLabel = x
      }
      """)
    val err = GraphEnrichment.enrich(tiny, cfg).left.toOption.getOrElse(fail("expected Left"))
    err should include("sum to 1.0")
    err should include("±")
  }

  it should "fail when preset and masses are both set" in {
    val cfg = ConfigFactory.parseString("""
      sim.enrichment {
        messageTypes = [x, y]
        defaultPdf {
          preset = uniform
          masses { x = 0.5, y = 0.5 }
        }
        defaultEdgeLabel = x
      }
      """)
    GraphEnrichment.enrich(tiny, cfg).left.toOption.get should include("either")
  }

end GraphEnrichmentTest
