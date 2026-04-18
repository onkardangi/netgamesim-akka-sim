package simcore.enrich

/** Validation for user-supplied discrete PDFs (explicit masses per message type). */
object PdfMasses:

  /** Absolute tolerance: `sum(p) - 1.0` must satisfy `abs(...) <= SumTolerance`. */
  val SumTolerance: Double = 1e-6

  /** Validates non-negative masses, keys ⊆ messageTypes, and sum ≈ 1.0. */
  def validate(
      masses: Map[String, Double],
      messageTypes: Seq[String],
      context: String
  ): Either[String, Map[String, Double]] =
    val allowed = messageTypes.toSet
    val unknown = masses.keySet -- allowed
    if unknown.nonEmpty then
      Left(s"$context: PDF masses reference unknown message types: ${unknown.toSeq.sorted.mkString(", ")} (allowed: ${messageTypes.mkString(", ")})")
    else if masses.values.exists(_.isNaN) || masses.values.exists(_.isInfinite) then
      Left(s"$context: PDF masses must be finite numbers")
    else if masses.values.exists(_ < 0.0) then
      Left(s"$context: PDF masses must be non-negative")
    else if masses.isEmpty then Left(s"$context: PDF masses must be non-empty")
    else
      val sum = masses.values.sum
      if math.abs(sum - 1.0) > SumTolerance then
        Left(
          s"$context: PDF masses must sum to 1.0 within ±$SumTolerance (got sum=$sum). " +
            "Presets (uniform/zipf) normalize automatically; explicit masses must already sum to 1."
        )
      else Right(masses)

end PdfMasses
