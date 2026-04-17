package simalgorithms

import simalgorithms.api.DistributedAlgorithm
import simalgorithms.leaderelection.LeaderElectionTreeAlgorithm
import simalgorithms.laiyang.LaiYangAlgorithm

object AlgorithmRegistry:
  def create(name: String, isInitiator: Boolean): Either[String, DistributedAlgorithm] =
    name.trim.toLowerCase match
      case "lai-yang" | "laiyang" => Right(new LaiYangAlgorithm(isInitiator))
      case "leader-election-tree" | "leaderelection-tree" | "tree-leader-election" =>
        Right(new LeaderElectionTreeAlgorithm)
      case other => Left(s"unknown algorithm '$other'")
