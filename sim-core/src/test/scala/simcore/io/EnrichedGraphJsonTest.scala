package simcore.io

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import simcore.model.{EnrichedEdge, EnrichedGraph, EnrichedNode}

class EnrichedGraphJsonTest extends AnyFlatSpec with Matchers:

  private val sample = EnrichedGraph(
    Set(EnrichedNode(0, Map("a" -> 1.0))),
    Set(EnrichedEdge(0, 1, "a"))
  )

  "EnrichedGraphJson" should "round-trip encode and decode" in {
    val json = EnrichedGraphJson.toJson(sample)
    EnrichedGraphJson.fromJson(json) shouldBe Right(sample)
  }

  it should "reject unsupported schemaVersion" in {
    val bad = """{"schemaVersion":0,"nodes":[],"edges":[]}"""
    EnrichedGraphJson.fromJson(bad).isLeft shouldBe true
    EnrichedGraphJson.fromJson(bad).left.toOption.get should include("unsupported schema version")
    EnrichedGraphJson.fromJson(bad).left.toOption.get should include("expected 1")
  }

  it should "reject missing schemaVersion" in {
    val bad = """{"nodes":[],"edges":[]}"""
    EnrichedGraphJson.fromJson(bad).isLeft shouldBe true
  }

end EnrichedGraphJsonTest
