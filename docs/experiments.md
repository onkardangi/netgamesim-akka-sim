# Experiments and Reproducibility

This project ships with reproducible experiment profiles and a helper script. For **architecture and design** (how enrichment, runtime, and algorithms fit together), see [design.md](design.md). For a **demo script** (graph, run, algorithm metrics), see [demo.md](demo.md).

## Profiles

- `conf/experiments/small-tree-leader.conf`: tree leader-election baseline on a small graph.
- `conf/experiments/small-tree-laiyang.conf`: Lai-Yang snapshot baseline with light periodic work traffic.
- `conf/experiments/mixed-traffic-laiyang.conf`: Lai-Yang with heavier periodic traffic for stress behavior.
- `conf/experiments/demo-netgamesim-laiyang.conf`: Lai-Yang on an arbitrary NetGameSim graph (all edges `LY_MARKER`; see **Demo with a NetGameSim-generated graph** below).
- `conf/experiments/netgamesim-generated-smoke.conf`: parse/runtime smoke with default `WORK` only (no algorithms).

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

## Demo with a NetGameSim-generated graph

1. **Generate** a JSON `.ngs` from the NetGameSim submodule (`sbt assembly`, then `java -jar …/netmodelsim.jar yourName` with `-DNGSimulator.outputDirectory=…` and `-DNGSimulator.NetModel.statesTotal=…`). Ensure `OutputGraphRepresentation.contentType = json` (default in upstream `application.conf`).
2. **Point the CLI** at that file: `--graph outputs/…/yourName.ngs` plus `--config …` and `--out …`.

**Lai–Yang:** Each directed edge has **one** allowed message kind. The bundled `small-tree-laiyang.conf` uses **`perEdgeLabels`** so some edges carry `WORK` and others `LY_MARKER`. For a random graph you do not know edge ids in advance, so either:

- use **`conf/experiments/demo-netgamesim-laiyang.conf`** (`defaultEdgeLabel = LY_MARKER` on every edge) so markers propagate; skip or trim **WORK** injections/timers (otherwise `WORK` may be **dropped** on `LY_MARKER`-only edges), or  
- copy your graph’s edge list and add a **`perEdgeLabels { "src_dst" = WORK or LY_MARKER, … }`** block (same idea as `small-tree-laiyang.conf`).

**Leader election (`leader-election-tree`):** The CLI requires an **undirected tree**. Typical NetGameSim runs produce **non-tree** graphs, so keep using the tree fixture `sim-core/src/test/resources/sample-tree-bidir-netgamesim.json` for that part of the demo, or supply another tree-shaped JSON. Random NetGameSim graphs will fail validation.

**Smoke config without algorithms:** `conf/experiments/netgamesim-generated-smoke.conf` (default `WORK` only) is useful to check parse/enrichment on an arbitrary file.

## Reproducibility notes

- `run-meta.json` records run parameters such as graph path, config path, selected algorithms, initiators, seed, and run window.
- deterministic behavior depends on fixed seed plus stable topology and message label constraints.
- for leader-election runs, the graph must be a tree; CLI validates this and fails fast on non-tree topologies.
