# Entity ticking performance

The spatial-index patches reduce work in Leaf's optimized entity activation, dynamic brain activation, and optimized despawning. The pathfinding patch lets the configured worker count run before the bounded queue fills. These features retain their existing enable/disable settings.

The spatial trees keep the same median partitioning, node order, capped distance calculations, and query implementation. Construction now reserves node storage once and uses balanced recursion instead of allocating a temporary object per node. Recursion depth is logarithmic in the player count. Each tree owns reusable point and index buffers: activation, despawn, and natural spawning call `clear()`, `add()` per player, then `build()`, so a rebuild allocates nothing once the buffers have grown. Only the added points are searchable, so a shrinking population cannot expose stale entries. Player-reference snapshots are still local to each tick.

Pathfinding uses equal core and maximum worker counts. Threads start lazily, and idle workers time out after the keepalive; a keepalive of 0 is treated as one second. Queue capacity and rejection behavior are unchanged.

## Reproducing the spatial benchmark

Apply the project's patches first. Use JDK 25 and run the variants sequentially, without another build or benchmark running:

```bash
scripts/perf/entity-spatial.sh baseline /tmp/pyzh-perf-baseline -p players=1,16,128
scripts/perf/entity-spatial.sh current /tmp/pyzh-perf-current -p players=1,16,128
```

The script downloads pinned JMH dependencies into the ignored `build/perf-libs` directory. Baseline sources come from the pinned Leaf checkout; current sources come from the applied Pyzh tree. It writes JMH JSON and text results into the specified output directory. Additional arguments pass through to JMH.

Each operation builds three trees: 2D activation, 3D brain activation, and 3D despawn. The build-only case also performs one query per tree to consume the result. The combined case performs 1,024 queries per tree after building. Player movement stays within a bounded range. The benchmark calls `build(coords, indices)`, which both variants provide; on Pyzh it copies the points into the tree's buffers, as the callers do with `add()`. Both variants reuse their benchmark input buffers, so this comparison excludes the caller-side allocation savings.

Defaults are two JVM forks, three one-second warmup iterations and five one-second measurement iterations, with the GC profiler enabled. This measures the spatial component, not full server tick time or TPS. The combined case assumes all three features are enabled and use the same eligible player population.

## Local measurements, 2026-09-23

Apple M5 Pro, Zulu JDK 25.0.3, JMH 1.37, FMA flag unset. Baseline Leaf commit: `4b38592a41f9df8fd9b656c21ac1df3f4ada9473`. Values below are means in microseconds per operation; ± is JMH's reported 99.9% confidence interval half-width.

| Three-tree rebuild | Leaf | Pyzh | Allocated bytes, Leaf → Pyzh |
| --- | ---: | ---: | ---: |
| 1 player | 0.032 ± 0.011 | 0.025 ± 0.005 | 96 → ~0 |
| 16 players | 0.950 ± 0.093 | 0.707 ± 0.059 | 2,976 → ~0 |
| 128 players | 10.160 ± 0.586 | 7.485 ± 1.385 | 24,480 → ~0 |

These runs were back to back after the trees took ownership of their point buffers. The 128-player construction mean fell about 26%. This is a small component of the server tick, and the benefit applies when the relevant optimizations are enabled. Tiny nonzero allocation readings on Pyzh are profiler/harness overhead; the warmed tree builder contains no allocation.

With 1,024 queries per tree, the same runs measured 7.85 → 8.90 µs at one player, 86.22 → 101.41 µs at 16, and 373.15 ± 135.60 → 502.72 ± 73.89 µs at 128. Earlier runs of the previous revision went the other way (339.18 ± 70.03 → 254.32 ± 10.39 µs at 128 in a reverse-order check). Query code and tree layout are unchanged, so these swings are measurement noise on this machine; no query-heavy speedup or slowdown is claimed.

Initial exploratory runs with unbounded coordinate drift were discarded. The pathfinding change has a concurrency test, not a live-world throughput measurement.

## Verification

Both patch application steps and `./gradlew build createPaperclipJar` passed after rebuilding and reapplying the patches. The server test reports contain no failures or errors, including both new tests below.

`EntitySpatialIndexTest` compares capped distances and nearest-point results with a linear oracle across empty, growing, shrinking, and duplicate-coordinate populations. Shrinking after a larger population would expose any stale points left in the tree's buffers.

`AsyncPathProcessorTest` holds four tasks at a barrier and verifies that all four start while a 1,024-entry queue still has space. It uses a keepalive of 0 and checks that idle workers can still time out. This checks actual worker concurrency without relying on a timing speedup.
