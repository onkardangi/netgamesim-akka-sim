package simcore.enrich

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PdfPresetTest extends AnyFlatSpec with Matchers:

  "PdfPreset.uniform" should "assign equal mass over messageTypes order" in {
    val m = PdfPreset.uniform(Seq("a", "b", "c")).getOrElse(fail("uniform"))
    m("a") shouldBe (1.0 / 3.0 +- 1e-12)
    m("b") shouldBe (1.0 / 3.0 +- 1e-12)
    m("c") shouldBe (1.0 / 3.0 +- 1e-12)
    m.values.sum shouldBe 1.0 +- 1e-12
  }

  it should "reject empty or duplicate message types" in {
    PdfPreset.uniform(Seq.empty).isLeft shouldBe true
    PdfPreset.uniform(Seq("x", "x")).isLeft shouldBe true
  }

  /** Zipf with s = 1.0 over three types: weights 1, 1/2, 1/3 → normalized 6/11, 3/11, 2/11. */
  "PdfPreset.zipf" should "use ranks in list order with documented s" in {
    val types = Seq("t1", "t2", "t3")
    val m = PdfPreset.zipf(types, s = 1.0).getOrElse(fail("zipf"))
    m("t1") shouldBe (6.0 / 11.0 +- 1e-12)
    m("t2") shouldBe (3.0 / 11.0 +- 1e-12)
    m("t3") shouldBe (2.0 / 11.0 +- 1e-12)
    m.values.sum shouldBe 1.0 +- 1e-12
  }

  it should "default s behavior: s = 0 gives uniform weights" in {
    val types = Seq("a", "b")
    val m = PdfPreset.zipf(types, s = 0.0).getOrElse(fail("zipf s=0"))
    m("a") shouldBe 0.5 +- 1e-12
    m("b") shouldBe 0.5 +- 1e-12
  }

  it should "reject invalid s" in {
    PdfPreset.zipf(Seq("a"), s = -1.0).isLeft shouldBe true
    PdfPreset.zipf(Seq("a"), s = Double.NaN).isLeft shouldBe true
  }

end PdfPresetTest
