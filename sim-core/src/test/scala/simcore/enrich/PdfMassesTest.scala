package simcore.enrich

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PdfMassesTest extends AnyFlatSpec with Matchers:

  private val types = Seq("A", "B", "C")

  "PdfMasses.validate" should "accept masses summing to 1.0 within tolerance" in {
    PdfMasses.validate(Map("A" -> 0.5, "B" -> 0.5), types, "t") shouldBe Right(Map("A" -> 0.5, "B" -> 0.5))
    PdfMasses.validate(Map("A" -> 1.0), types, "t") shouldBe Right(Map("A" -> 1.0))
  }

  it should "reject when sum deviates beyond tolerance" in {
    PdfMasses.validate(Map("A" -> 0.5, "B" -> 0.4), types, "t").isLeft shouldBe true
    PdfMasses.validate(Map("A" -> (1.0 + 2e-5)), types, "t").isLeft shouldBe true
  }

  it should "reject unknown message types" in {
    PdfMasses.validate(Map("Z" -> 1.0), types, "t").isLeft shouldBe true
  }

  it should "reject negative masses" in {
    PdfMasses.validate(Map("A" -> 1.1, "B" -> -0.1), types, "t").isLeft shouldBe true
  }

end PdfMassesTest
