package simalgorithms.api

trait DistributedAlgorithm:
  def name: String
  def onStart(ctx: NodeContext): Unit
  def onMessage(ctx: NodeContext, msg: AlgorithmMessage): Unit
  def onTick(ctx: NodeContext): Unit = ()

final case class AlgorithmMessage(from: Int, kind: String, payload: String)

trait NodeContext:
  def nodeId: Int
  def neighbors: Set[Int]
  def send(to: Int, kind: String, payload: String): Boolean
  def broadcast(kind: String, payload: String, except: Option[Int] = None): Int
  def emit(event: String): Unit
