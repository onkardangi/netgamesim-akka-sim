package simcore.enrich

/** PDF presets are expanded at config-load time into [[Map]]\[String, Double\] over `messageTypes`. */
object PdfPreset:

  /** Uniform over the given message types (equal probability 1/n each). */
  def uniform(messageTypes: Seq[String]): Either[String, Map[String, Double]] =
    if messageTypes.isEmpty then Left("uniform preset requires a non-empty messageTypes list")
    else if messageTypes.distinct.size != messageTypes.size then Left("duplicate entry in messageTypes breaks uniform preset")
    else
      val p = 1.0 / messageTypes.size
      Right(messageTypes.map(_ -> p).toMap)

  /** Zipf over ranks 1 … n in **config list order** (first type = rank 1, most mass when s > 0).
    *
    * For rank r ∈ {1,…,n}, unnormalized weight is 1 / r^s. Probabilities are these weights divided by their sum.
    *
    * @param s
    *   Zipf exponent (standard choice is 1.0). Larger s puts more mass on low ranks (earlier message types in the
    *   list). Must be finite and non-negative.
    */
  def zipf(messageTypes: Seq[String], s: Double): Either[String, Map[String, Double]] =
    if messageTypes.isEmpty then Left("zipf preset requires a non-empty messageTypes list")
    else if messageTypes.distinct.size != messageTypes.size then Left("duplicate entry in messageTypes breaks zipf preset")
    else if s.isNaN || s.isInfinite then Left("zipf parameter s must be finite")
    else if s < 0 then Left("zipf parameter s must be non-negative")
    else
      val n = messageTypes.length
      val ranks = 1 to n
      val weights = ranks.map(r => 1.0 / math.pow(r.toDouble, s))
      val sum = weights.sum
      if sum <= 0 || sum.isNaN then Left("zipf normalization failed (zero or invalid sum)")
      else
        val probs = weights.map(_ / sum)
        Right(messageTypes.zip(probs).toMap)

end PdfPreset
