# Experiments and Reproducibility

This project ships with reproducible experiment profiles and a helper script.

## Profiles

- `conf/experiments/small-tree-leader.conf`: tree leader-election baseline on a small graph.
- `conf/experiments/small-tree-laiyang.conf`: Lai-Yang snapshot baseline with light periodic work traffic.
- `conf/experiments/mixed-traffic-laiyang.conf`: Lai-Yang with heavier periodic traffic for stress behavior.

- Most profiles run against:

- graph: `sim-core/src/test/resources/sample-netgamesim.json`
- injection file: `sim-cli/src/test/resources/sim-cli-injections.csv`

Leader-election uses a bidirectional tree fixture to support convergence checks:

- graph: `sim-core/src/test/resources/sample-tree-bidir-netgamesim.json`

## One-command execution

```bash
./scripts/run-experiments.sh
```

Outputs are written under:

`outputs/experiments/<timestamp>/<profile>/`

Each run folder includes:

- `metrics.json`
- `run-meta.json`

## Manual commands

```bash
sbt "sim-cli/runMain simcli.SimMain --graph sim-core/src/test/resources/sample-netgamesim.json --config conf/experiments/small-tree-leader.conf --mode file --inject-file sim-cli/src/test/resources/sim-cli-injections.csv --duration-ms 400 --out outputs/manual/small-tree-leader"
```

```bash
sbt "sim-cli/runMain simcli.SimMain --graph sim-core/src/test/resources/sample-netgamesim.json --config conf/experiments/small-tree-laiyang.conf --mode file --inject-file sim-cli/src/test/resources/sim-cli-injections.csv --duration-ms 500 --out outputs/manual/small-tree-laiyang"
```

```bash
sbt "sim-cli/runMain simcli.SimMain --graph sim-core/src/test/resources/sample-netgamesim.json --config conf/experiments/mixed-traffic-laiyang.conf --mode file --inject-file sim-cli/src/test/resources/sim-cli-injections.csv --duration-ms 700 --out outputs/manual/mixed-traffic-laiyang"
```

## Reproducibility notes

- `run-meta.json` records run parameters such as graph path, config path, selected algorithms, initiators, seed, and run window.
- deterministic behavior depends on fixed seed plus stable topology and message label constraints.
- for leader-election runs, the graph must be a tree; CLI validates this and fails fast on non-tree topologies.
