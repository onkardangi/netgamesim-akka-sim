package simruntime.work

/** One unit of WORK traffic tracked in a node's local FIFO queue (terminating workload mode). */
sealed trait WorkUnit:
  /** Payload placed on the wire for `Envelope(kind = "WORK", ...)`. */
  def wirePayload: String

object WorkUnit:

  /** Initial workload from configuration (count per node). */
  final case class Seeded(index: Int) extends WorkUnit:
    def wirePayload: String = s"work-seeded-$index"

  /** Driver / CLI injection (`ExternalInput` with kind WORK). */
  final case class Injected(injectedPayload: String) extends WorkUnit:
    def wirePayload: String = injectedPayload

  /** Inbound WORK from a neighbor — work propagation. */
  final case class Received(fromNode: Int, inboundPayload: String) extends WorkUnit:
    def wirePayload: String = inboundPayload
