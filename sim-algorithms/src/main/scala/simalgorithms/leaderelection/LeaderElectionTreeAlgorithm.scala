package simalgorithms.leaderelection

import simalgorithms.api.{AlgorithmMessage, DistributedAlgorithm, NodeContext}

/** Tree leader election via max-id propagation.
  * Nodes repeatedly propagate higher candidate ids until convergence.
  */
final class LeaderElectionTreeAlgorithm extends DistributedAlgorithm:
  override val name: String = "leader-election-tree"

  private val CandidateKind = "LE_CAND"

  private case class State(bestLeaderId: Int = Int.MinValue)

  /** Best candidate seen so far; updated only from `onStart` / `onMessage` on the embedding node actor's thread. */
  private var state = State()

  override def onStart(ctx: NodeContext): Unit =
    state = State(bestLeaderId = ctx.nodeId)
    ctx.emit(s"algorithm=leader-election-tree node=${ctx.nodeId} bestLeader=${state.bestLeaderId} phase=start")
    ctx.broadcast(CandidateKind, state.bestLeaderId.toString)

  override def onMessage(ctx: NodeContext, msg: AlgorithmMessage): Unit =
    if msg.kind == CandidateKind then
      parseCandidate(msg.payload).foreach { candidateId =>
        if candidateId > state.bestLeaderId then
          state = State(bestLeaderId = candidateId)
          ctx.emit(s"algorithm=leader-election-tree node=${ctx.nodeId} bestLeader=${state.bestLeaderId} phase=update")
          ctx.broadcast(CandidateKind, state.bestLeaderId.toString, except = Some(msg.from))
      }

  private def parseCandidate(payload: String): Option[Int] =
    scala.util.Try(payload.trim.toInt).toOption
