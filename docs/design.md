# Design: netgamesim-akka-sim

This document explains how the pieces fit together: data models, configuration, the Akka runtime, and pluggable algorithms. It complements [experiments.md](experiments.md), which focuses on runnable profiles and outputs.

## End-to-end assembly

1. **Input** — A NetGameSim-style **JSON** artifact (nodes + edges) is read from disk (`--graph`). See `simcore.io.NetGameSimJson`.
2. **Enrichment** — Typesafe **HOCON** (`--config`, blocks `sim.enrichment` and `sim.runtime`) adds **per-edge labels** and **per-node PDFs** over discrete message types. See `simcore.enrich.GraphEnrichment`.
3. **Runtime** — `simruntime.bootstrap.GraphRuntimeBuilder` creates one **Akka Classic** actor per node, wires **outgoing edges** as `Map[destinationId, ActorRef]`, and sends `NodeActor.Init` with neighbors, allowed message kinds per edge, PDF, algorithm names, initiator flag, and **runtime seed** for PDF randomness.
4. **Execution** — `simcli.SimMain` configures **timer initiators** from config, schedules **file-based or interactive injections**, then either **sleeps for `--duration-ms`** or, when `sim.runtime.terminatingWorkload { waitForDrain = true }` is set, **polls until** in-flight counts are zero and every node’s **WORK queue** is empty (see below), capped by the same duration. Finally it writes **metrics** and **run metadata**.
5. **Algorithms** — Implementations in `sim-algorithms` register under `AlgorithmRegistry` and run inside `NodeActor` on the same **envelope** traffic as the rest of the simulation.

```text
JSON file  →  PlainGraph  →  EnrichedGraph  →  ActorSystem + NodeActor × N
                     ↑
              HOCON (sim.enrichment, sim.runtime)
```

## Message protocol (runtime)

### NodeActor messages (`simruntime.actor.NodeActor`)

| Message | Role |
|--------|------|
| `Init` | Neighbor `ActorRef`s, allowed kinds per outgoing edge, PDF, algorithm names, initiator flag, **runtime seed**, optional **WORK queue** (`workQueueEnabled`, `initialWorkSeedCount`). |
| `GetWorkQueueDepth` | Query reply (`Int`): local FIFO `WORK` queue depth for terminating-workload drain detection. |
| `ConfigureTimer` | Periodic ticks (`Timers`); mode **fixed** (constant kind) or **pdf** (sample kind from node PDF). |
| `ExternalInput` | Driver-injected traffic (CLI); treated like other outbound sends after kind is chosen. |
| `Envelope` | Inbound: `from`, `kind`, `payload` — this is what algorithms observe via `AlgorithmMessage`. |

Published to the **event stream** (for metrics/tests): `Sent`, `Received`, `Dropped`, `Initialized`, `AlgorithmEvent`, etc.

### Algorithm substrate (`simalgorithms.api`)

Algorithms do not use raw `Any` for network traffic: **`AlgorithmMessage(from, kind, payload)`** wraps what arrived in an `Envelope`. They use **`NodeContext`** to `send`, `broadcast`, and **`emit`** structured strings (parsed by `MetricsCollectorActor` for snapshot/leader fields).

Control kinds used in-tree include e.g. **`LY_MARKER`** (Lai–Yang) and **`LE_CAND`** (leader election); they must be **allowed on the edges** you use, same as application kinds (`WORK`, …).

## Edge label enforcement

- Each **directed** edge in the enriched graph carries a **single label** in the current model (`EnrichedEdge.messageTypeLabel`), interpreted as the **allowed message kind** for that edge (the handout also allows multi-kind edges; we model one primary label per edge in JSON enrichment).
- **`NodeActor`** keeps `allowedOnEdge: Map[neighborId, Set[String]]` built from all labels on parallel edges to the same destination.
- On send, if the kind is **not** in the allowed set for that neighbor, the send is refused and a **`Dropped`** event is published (and metrics record it).

Serialization of labels is part of **`EnrichedGraphJson`** (edges include source, destination, `messageTypeLabel`).

## PDF sampling and presets

- **Configuration** — Under `sim.enrichment`, `messageTypes` lists the discrete alphabet. Each of `defaultPdf` and `perNodePdf.<id>` must use **either**:
  - **`preset = uniform`** or **`preset = zipf`** (optional **`s`** for Zipf) — masses are **computed and normalized** by `PdfPreset` (always valid PMFs).
  - **`masses { TYPE = p, ... }`** — explicit masses; keys must be subset of `messageTypes`, all **non-negative**, and must **sum to 1.0** within **`PdfMasses.SumTolerance`** (**1e-6**). Otherwise enrichment **fails fast** (no silent renormalization of user-supplied numbers).
- **Implementation** — `PdfPreset` for presets; **`PdfMasses.validate`** for explicit `masses`.
- **Runtime** — Timer **pdf** mode and similar paths sample a kind using **`scala.util.Random`** seeded from **`mixRuntimeSeed(sim.runtime.seed, nodeId)`** so experiments can fix **`sim.runtime.seed`** in HOCON and get reproducible PDF draws per node.
- **Routing** — After a kind is chosen, **routing** still respects **edge labels**; if no neighbor accepts that kind, the traffic is **dropped** and recorded.

## Algorithm plugin API

Plugins implement **`DistributedAlgorithm`**: `name`, `onStart`, `onMessage`, optional `onTick`. **`AlgorithmRegistry`** maps configured names (e.g. `"lai-yang"`, `"leader-election-tree"`) to concrete classes.

- **Initiation** — `algorithmInitiators` in config marks which nodes run as **initiators** for algorithms that need it (e.g. Lai–Yang starter).
- **Integration** — On each `Envelope`, the runtime builds **`AlgorithmMessage`** and invokes **`onMessage`** for each loaded plugin; timer ticks call **`onTick`**.
- **Observability** — **`emit("algorithm=… …")`** feeds **`MetricsCollectorActor`** via the event stream (counts, snapshot/leader snapshots in `RunMetrics`).

## Graph → actors (mapping rules)

1. **One actor per node id** — `system.actorOf(NodeActor.props(id), s"node-$id")`.
2. **Outgoing channels** — For each edge `(source → dest)`, the source actor stores **`dest -> ActorRef` of `node-dest`**.
3. **Init order** — All node actors are created first; then **`Init`** is sent so every `ActorRef` in neighbor maps is valid.
4. **Timers and injections** — After `Init`, **SimMain** may send **`ConfigureTimer`** to configured nodes and schedule **`ExternalInput`** on the driver’s clock.

### Terminating workload (FIFO work queue)

For rubrics that ask for **duration or a terminating computation**, the default is **fixed duration** (`--duration-ms`). Optionally, enable **`sim.runtime.terminatingWorkload`** with **`perNodeWorkUnits`** (initial seeded work units per node id; legacy key **`perNodeTokens`** is still read) and **`waitForDrain = true`**. When drain-wait is on, **SimMain** blocks until **in-flight edge counts are zero** and every node reports **`GetWorkQueueDepth == 0`**, or until **`--duration-ms`** elapses (whichever comes first).

**Semantics:** `GraphRuntimeBuilder` sets `workQueueEnabled` when `sim.runtime.terminatingWorkload.enabled` is true. Each node then keeps a **FIFO queue** of **`simruntime.work.WorkUnit`** values (`Seeded` from config, `Injected` from driver `ExternalInput(WORK)`, `Received` from inbound `Envelope(WORK)`). **Outbound** `WORK` (injections, timers, routing, or algorithm `NodeContext.send`/`broadcast`) **dequeues** one unit and uses its **`wirePayload`** on the wire (the `payload` argument on `send`/`broadcast` is not used for `WORK` in this mode—only the queued unit matters). **Inbound** `Envelope(WORK)` **enqueues** a `Received` unit, then **drains** repeatedly: while the queue is non-empty, either **send** one unit on the first eligible `WORK` edge (sorted neighbor id), or **complete locally** (dequeue with no outgoing `WORK` edge — sink / dead-end). Non-`WORK` kinds are unchanged. With this model, work **propagates** along the graph and is **disposed** at leaves, so finite seeded load can reach a quiescent state suitable for drain-wait.

Example profile: `conf/experiments/terminating-workload.conf`.

## Metrics and shutdown

- **`MetricsCollectorActor`** subscribes to **`Sent` / `Received` / `Dropped` / `AlgorithmEvent`** and aggregates **`RunMetrics`** (counts by type and edge, in-flight estimates, algorithm-parsed fields).
- **CLI** finalizes metrics after the run window, writes **`metrics.json`** and **`run-meta.json`**, then **`shutdown()`** terminates the **`ActorSystem`**.

## Related files

| Topic | Location |
|-------|----------|
| JSON parse | `sim-core/.../NetGameSimJson.scala` |
| Enrichment | `sim-core/.../GraphEnrichment.scala`, `PdfPreset.scala` |
| Enriched JSON | `sim-core/.../EnrichedGraphJson.scala` |
| Builder | `sim-runtime-akka/.../GraphRuntimeBuilder.scala` |
| Node actor | `sim-runtime-akka/.../NodeActor.scala` |
| WORK queue model | `sim-runtime-akka/.../work/WorkUnit.scala` |
| Metrics | `sim-runtime-akka/.../metrics/` |
| Algorithms | `sim-algorithms/.../AlgorithmRegistry.scala`, `laiyang/`, `leaderelection/` |
| CLI | `sim-cli/.../SimMain.scala` |
