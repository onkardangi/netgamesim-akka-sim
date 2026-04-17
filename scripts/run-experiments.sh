#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_GRAPH="$ROOT_DIR/sim-core/src/test/resources/sample-netgamesim.json"
LEADER_GRAPH="$ROOT_DIR/sim-core/src/test/resources/sample-tree-bidir-netgamesim.json"
INJECT="$ROOT_DIR/sim-cli/src/test/resources/sim-cli-injections.csv"
TS="$(date +%Y%m%d-%H%M%S)"
OUT_BASE="$ROOT_DIR/outputs/experiments/$TS"

mkdir -p "$OUT_BASE"

run_profile() {
  local profile_name="$1"
  local conf_path="$2"
  local duration_ms="$3"
  local graph_path="${4:-$DEFAULT_GRAPH}"
  local out_dir="$OUT_BASE/$profile_name"
  echo "Running profile: $profile_name"
  (cd "$ROOT_DIR" && sbt "sim-cli/runMain simcli.SimMain --graph $graph_path --config $conf_path --mode file --inject-file $INJECT --duration-ms $duration_ms --out $out_dir")
  echo "Output: $out_dir"
}

run_profile "small-tree-leader" "$ROOT_DIR/conf/experiments/small-tree-leader.conf" "400" "$LEADER_GRAPH"
run_profile "small-tree-laiyang" "$ROOT_DIR/conf/experiments/small-tree-laiyang.conf" "500"
run_profile "mixed-traffic-laiyang" "$ROOT_DIR/conf/experiments/mixed-traffic-laiyang.conf" "700"

echo "All experiments complete under $OUT_BASE"
