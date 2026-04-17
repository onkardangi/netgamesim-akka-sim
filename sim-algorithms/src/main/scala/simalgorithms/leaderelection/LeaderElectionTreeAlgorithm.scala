package simalgorithms.leaderelection

import simalgorithms.api.{AlgorithmMessage, DistributedAlgorithm, NodeContext}

/** Tree leader election via max-id propagation.
  * Nodes repeatedly propagate higher candidate ids until convergence.
  */
final class LeaderElectionTreeAlgorithm extends DistributedAlgorithm:
  override val name: String = "leader-election-tree"

  private val CandidateKind = "LE_CAND"
  private var bestLeaderId: Int = Int.MinValue

  override def onStart(ctx: NodeContext): Unit =
    bestLeaderId = ctx.nodeId
    ctx.emit(s"algorithm=leader-election-tree node=${ctx.nodeId} bestLeader=$bestLeaderId phase=start")
    ctx.broadcast(CandidateKind, bestLeaderId.toString)

  override def onMessage(ctx: NodeContext, msg: AlgorithmMessage): Unit =
    if msg.kind == CandidateKind then
      parseCandidate(msg.payload).foreach { candidateId =>
        if candidateId > bestLeaderId then
          bestLeaderId = candidateId
          ctx.emit(s"algorithm=leader-election-tree node=${ctx.nodeId} bestLeader=$bestLeaderId phase=update")
          ctx.broadcast(CandidateKind, bestLeaderId.toString, except = Some(msg.from))
      }

  private def parseCandidate(payload: String): Option[Int] =
    scala.util.Try(payload.trim.toInt).toOption
