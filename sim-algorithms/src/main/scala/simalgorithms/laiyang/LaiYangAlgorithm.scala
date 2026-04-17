package simalgorithms.laiyang

import simalgorithms.api.{AlgorithmMessage, DistributedAlgorithm, NodeContext}

/** A practical Lai-Yang style snapshot plugin using an explicit marker control message.
  * This keeps simulator behavior deterministic while preserving the core idea:
  * first marker flips local color to red and records local snapshot.
  */
final class LaiYangAlgorithm(isInitiator: Boolean) extends DistributedAlgorithm:
  override val name: String = "lai-yang"

  private val MarkerKind = "LY_MARKER"

  private case class State(
      snapshotTaken: Boolean = false,
      appMessagesSeen: Long = 0L,
      markerSeenFrom: Set[Int] = Set.empty,
      preSnapshotFrom: Map[Int, Long] = Map.empty
  )

  /** Algorithm-local snapshot state; mutated only from `onStart` / `onMessage` on the embedding node actor's thread. */
  private var state = State()

  override def onStart(ctx: NodeContext): Unit =
    if isInitiator then
      state = takeSnapshot(state, ctx, triggeredBy = "initiator")
      ctx.broadcast(MarkerKind, s"snapshot-start:${ctx.nodeId}")

  override def onMessage(ctx: NodeContext, msg: AlgorithmMessage): Unit =
    ctx.emit(s"algorithm=lai-yang node=${ctx.nodeId} received kind=${msg.kind} from=${msg.from}")
    state =
      if msg.kind == MarkerKind then onMarker(ctx, msg)
      else onAppMessage(msg)

  private def onMarker(ctx: NodeContext, msg: AlgorithmMessage): State =
    val seen = state.markerSeenFrom + msg.from
    if state.snapshotTaken then state.copy(markerSeenFrom = seen)
    else
      val afterSeen = state.copy(markerSeenFrom = seen)
      val afterSnap = takeSnapshot(afterSeen, ctx, triggeredBy = s"marker-from-${msg.from}")
      ctx.broadcast(MarkerKind, s"snapshot-propagate:${ctx.nodeId}", except = Some(msg.from))
      afterSnap

  private def onAppMessage(msg: AlgorithmMessage): State =
    val app = state.appMessagesSeen + 1
    val pre =
      if state.snapshotTaken then state.preSnapshotFrom
      else
        val prev = state.preSnapshotFrom.getOrElse(msg.from, 0L)
        state.preSnapshotFrom.updated(msg.from, prev + 1)
    state.copy(appMessagesSeen = app, preSnapshotFrom = pre)

  private def takeSnapshot(st: State, ctx: NodeContext, triggeredBy: String): State =
    val incomingSummary =
      st.preSnapshotFrom.toSeq.sortBy(_._1).map { case (from, cnt) => s"$from:$cnt" }.mkString(",")
    ctx.emit(
      s"algorithm=lai-yang node=${ctx.nodeId} snapshotTaken=true trigger=$triggeredBy " +
        s"appSeen=${st.appMessagesSeen} preSnapshotByChannel=[$incomingSummary]"
    )
    st.copy(snapshotTaken = true)
