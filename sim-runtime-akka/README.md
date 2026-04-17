# sim-runtime-akka

- Creates one Akka classic actor per graph node.
- Converts enriched edges into neighbor channels for each node actor.
- Enforces edge label constraints when routing outbound messages.
- Publishes `NodeActor.Received` events to the Akka event stream for testing and observability.
- Supports algorithm plugins (currently `lai-yang` and `leader-election-tree`) on top of the same message substrate.

Current routing behavior is deterministic: when multiple neighbors are eligible for a message kind, the actor picks the first eligible neighbor by sorted node id.

## Algorithm message kinds

- `LY_MARKER`: Lai-Yang snapshot control marker; must be allowed on edges participating in snapshot propagation.
- `LE_CAND`: tree leader-election candidate propagation; must be allowed on all tree edges used by election.
- Application traffic such as `WORK`/`PING` should also be explicitly allowed by edge labels when used in the same run.

## Smoke run

From the repo root:

```bash
sbt "sim-runtime-akka/runMain simruntime.RuntimeSmokeMain"
```

Expected output includes a line like:

`received at node=2 from=1 kind=WORK payload=smoke`

## Tests

From the repo root:

```bash
sbt "sim-runtime-akka/test"
```
