package simalgorithms.laiyang

import simalgorithms.api.{AlgorithmMessage, DistributedAlgorithm, NodeContext}

import scala.collection.mutable

/** A practical Lai-Yang style snapshot plugin using an explicit marker control message.
  * This keeps simulator behavior deterministic while preserving the core idea:
  * first marker flips local color to red and records local snapshot.
  */
final class LaiYangAlgorithm(isInitiator: Boolean) extends DistributedAlgorithm:
  override val name: String = "lai-yang"

  private val MarkerKind = "LY_MARKER"
  private var isRed = false
  private var snapshotTaken = false
  private var appMessagesSeen = 0L
  private val markerSeenFrom = mutable.Set.empty[Int]
  private val preSnapshotFrom = mutable.Map.empty[Int, Long].withDefaultValue(0L)

  override def onStart(ctx: NodeContext): Unit =
    if isInitiator then
      takeSnapshot(ctx, triggeredBy = "initiator")
      ctx.broadcast(MarkerKind, s"snapshot-start:${ctx.nodeId}")

  override def onMessage(ctx: NodeContext, msg: AlgorithmMessage): Unit =
    ctx.emit(s"algorithm=lai-yang node=${ctx.nodeId} received kind=${msg.kind} from=${msg.from}")
    if msg.kind == MarkerKind then
      markerSeenFrom += msg.from
      if !snapshotTaken then
        takeSnapshot(ctx, triggeredBy = s"marker-from-${msg.from}")
        ctx.broadcast(MarkerKind, s"snapshot-propagate:${ctx.nodeId}", except = Some(msg.from))
    else
      appMessagesSeen += 1
      if !snapshotTaken then
        preSnapshotFrom.update(msg.from, preSnapshotFrom(msg.from) + 1)

  private def takeSnapshot(ctx: NodeContext, triggeredBy: String): Unit =
    snapshotTaken = true
    isRed = true
    val incomingSummary = preSnapshotFrom.toSeq.sortBy(_._1).map { case (from, cnt) => s"$from:$cnt" }.mkString(",")
    ctx.emit(
      s"algorithm=lai-yang node=${ctx.nodeId} snapshotTaken=true trigger=$triggeredBy " +
        s"appSeen=$appMessagesSeen preSnapshotByChannel=[$incomingSummary]"
    )
