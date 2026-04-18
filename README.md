# netgamesim-akka-sim

Distributed graph **simulation on top of Akka Classic**: each node in a graph is an actor, edges define which message kinds may traverse which links, and optional algorithms (Lai–Yang snapshot, tree leader election) run on the same message substrate. Graphs are loaded from **NetGameSim-style JSON** (see below), then **enriched** with per-node PDFs and per-edge labels from Typesafe Config.

This repository is an **SBT multi-project** at the repo root. A **vendored [NetGameSim](https://github.com/0x1DOCD00D/NetGameSim)** tree may appear under `NetGameSim/` / `netgamesim/` as **git submodules** (see `.gitmodules`) for generating new graphs and jars; the Akka simulator itself does not require building NetGameSim to compile or run tests.

## Requirements

- **JDK** 11+ (17 is a good default).
- **SBT** — version **1.8.3** is pinned in `project/build.properties` (any recent SBT launcher can read this).

## Quick start

From the repository root:

```bash
sbt test
```

Smoke the Akka runtime (in-process, no graph files):

```bash
sbt "sim-runtime-akka/runMain simruntime.RuntimeSmokeMain"
```

You should see a **log line** (timestamp and logger name may prefix it) containing:

`received at node=2 from=1 kind=WORK payload=smoke`

### Run bundled experiment profiles

```bash
chmod +x scripts/run-experiments.sh   # once, if needed
./scripts/run-experiments.sh
```

This writes under `outputs/experiments/<timestamp>/<profile>/` with `metrics.json` and `run-meta.json`. See [docs/experiments.md](docs/experiments.md) for profile descriptions and manual `sbt` invocations.

### Run the CLI on a graph + config

```bash
sbt "sim-cli/runMain simcli.SimMain \
  --graph sim-core/src/test/resources/sample-netgamesim.json \
  --config conf/experiments/small-tree-laiyang.conf \
  --mode file \
  --inject-file sim-cli/src/test/resources/sim-cli-injections.csv \
  --duration-ms 500 \
  --out outputs/my-run"
```

- **`--mode file`**: schedule injections from a CSV (`atMs,nodeId,kind,payload` per line).
- **`--mode interactive`**: type `inject <nodeId> <kind> <payload>` or `quit` (no `--inject-file`).
- Omitting **`--out`** defaults to `outputs/run-<timestamp>/`.

**NetGameSim-generated `.ngs`:** After you build `netmodelsim.jar` in the submodule and produce a JSON `.ngs` (see [NetGameSim/README.md](NetGameSim/README.md)), pass its path as **`--graph`**. For **Lai–Yang** on an arbitrary graph use **`conf/experiments/demo-netgamesim-laiyang.conf`**; for a parse-only smoke run use **`conf/experiments/netgamesim-generated-smoke.conf`**. Edge labels and leader-election tree constraints are spelled out in [docs/experiments.md](docs/experiments.md).

**Leader-election** configs require a **tree** topology; use e.g. `sim-core/src/test/resources/sample-tree-bidir-netgamesim.json` (see `scripts/run-experiments.sh`).

## Repository layout

| Path | Role |
|------|------|
| `sim-core` | Plain/enriched graph models, NetGameSim JSON parsing, PDF presets, enrichment from `sim.enrichment` config |
| `sim-algorithms` | Pluggable algorithms: `lai-yang`, `leader-election-tree` (see `AlgorithmRegistry`) |
| `sim-runtime-akka` | `NodeActor`, `GraphRuntimeBuilder`, metrics; one actor per node |
| `sim-cli` | `simcli.SimMain` — load graph, enrich, start runtime, file/interactive injections, write metrics |
| `conf/experiments/` | Example experiment configs |
| `scripts/run-experiments.sh` | Runs all example profiles against test fixtures |
| `NetGameSim/`, `netgamesim/` | Optional NetGameSim submodule(s) for graph generation (same upstream repo; see `.gitmodules`) |
| `src/main/scala/main.scala` | IDE template stub — **not** part of the SBT build |

Algorithm-oriented details (message kinds, routing) are summarized in [sim-runtime-akka/README.md](sim-runtime-akka/README.md).

**Architecture and design** (message protocol, edge enforcement, PDFs, algorithms, graph→actors): [docs/design.md](docs/design.md).

**Project demo (video):** [YouTube — netgamesim-akka-sim walkthrough](https://youtu.be/-F2ZuVzfGS8). 

## Configuration

Experiment files use [HOCON](https://github.com/lightbend/config/blob/main/HOCON.md) with two main blocks:

1. **`sim.enrichment`** — Declares `messageTypes`, `defaultPdf` (either **`preset = uniform` / `zipf`** or explicit **`masses { … }`** with probabilities that **sum to 1.0** within tolerance `PdfMasses.SumTolerance`), `defaultEdgeLabel`, and optional `perEdgeLabels` / `perNodePdf`. Edge keys look like `"0_1"` for an edge from node `0` to `1`.

2. **`sim.runtime`** — `seed`, `algorithms` (e.g. `"lai-yang"`, `"leader-election-tree"`), `algorithmInitiators`, optional `initiators.timers` for periodic traffic, and optional **`terminatingWorkload`** (per-node seeded WORK units + `waitForDrain` for FIFO work-queue / drain semantics; see [docs/design.md](docs/design.md)).

Full examples: `conf/experiments/*.conf`.

## Graph input format

`NetGameSimJson` accepts:

- A single JSON object with top-level **`nodes`** and **`edges`** arrays (preferred), or  
- Legacy **two-line** format: first line = nodes JSON array, second line = edges JSON array.

Edges use **`fromId`** / **`toId`** (and may include nested node refs). See `sim-core/src/test/resources/*.json` and `sim-core/src/main/scala/simcore/io/NetGameSimJson.scala`.

## Optional: NetGameSim submodule and graph generation

If you need to **generate** new `.ngs` / JSON graphs from the upstream toolkit:

```bash
git submodule update --init --recursive
```

Then build from the submodule directory (not from the repo root `build.sbt`). Upstream instructions and options live in [NetGameSim/README.md](NetGameSim/README.md) (JVM memory, `assembly`, Graphviz for some visualization paths, etc.). License for that subtree: [NetGameSim/LICENSE](NetGameSim/LICENSE).

## Building

Root project only:

```bash
sbt compile
```

Dependency resolution uses the resolvers in `build.sbt` / `project/akka.sbt` (including Akka’s published artifacts).

---

For experiment naming, outputs, and reproducibility fields, see [docs/experiments.md](docs/experiments.md). **Project demo:** [YouTube](https://youtu.be/-F2ZuVzfGS8). For a **recorded/live demo** outline, see [docs/demo.md](docs/demo.md).
