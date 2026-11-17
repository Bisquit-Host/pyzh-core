# Server performance harness

Runs a real server jar through seven fixed workloads with fake players, measures the cost of a tick without the 50 ms sleep, and records a behavior digest so an optimization that changes gameplay shows up. Works on any Paper-derived jar for the same Minecraft version: checked on Pyzh, Leaf 26.2-131 and Paper 26.2-135.

Requires JDK 25 and bash. Nothing is installed outside the ignored `build/` directory.

## Running

```bash
# one pass over all scenarios, about 6.5 minutes; results in OUT/<scenario>/run-N/ plus OUT/summary.txt
scripts/perf/harness/run.sh pyzh-server/build/libs/pyzh-paperclip-26.2.local-SNAPSHOT.jar OUT

# before/after: runs A and B alternately per scenario (A B, B A, A B, ...), then compares
scripts/perf/harness/ab.sh before.jar after.jar OUT --runs 6

# compare two existing result directories
scripts/perf/harness/compare.sh OUT/A OUT/B
```

Options for `run.sh` and `ab.sh`:

| option | meaning |
| --- | --- |
| `--scenario a,b` | only these scenarios, each in its own server process. `a+b` runs them together in one process |
| `--runs N` | measured runs per scenario (`run.sh` default 1, `ab.sh` default 3 per jar) |
| `--first-run N` | number runs from N, to add runs to an existing output directory |
| `--quick` | shorter warmup and window: 20 to 60 s per scenario, noisier |
| `--jfr` | one extra profiled run per scenario: `run-jfr/profile.jfr` and a generated `run-jfr/profile.md`. Never part of the statistics |
| `--config DIR` | server config directory instead of `config/default` |

`HARNESS_JVM_FLAGS` replaces the pinned JVM flags (`-Xms4G -Xmx4G -XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC`). `HARNESS_PORT` changes the port the server binds (default 25599). `HARNESS_LAUNCH_PREFIX` is put in front of the `java` command, for pinning cores on Linux (`HARNESS_LAUNCH_PREFIX="taskset -c 2-9"`). `fetch-upstream.sh` downloads pinned Paper and Leaf jars into `build/perf-harness/upstream/` for cross-fork runs.

Only one harness run can be active per checkout (`build/perf-harness/lock`). Do not run anything else heavy on the machine while measuring.

## How it measures

1. The launcher jar is unpacked once per jar hash into `build/perf-harness/dist/` and the plugin in `plugin/` is compiled against that jar.
2. A world template with pre-generated terrain for every scenario region is built once per seed (`build/perf-harness/template-<seed>-v<layout>`) and copied fresh for every run, so worldgen is outside the measured window everywhere except `chunkgen`. Bump `layout_version` in `lib.sh` after changing a scenario's `region()` or the seed.
3. Each run starts a server with the pinned config, builds the scenario at fixed coordinates, joins the fake players, waits in real time until chunk loading goes quiet, then starts a vanilla tick sprint: a fixed number of warmup ticks followed by a fixed number of measured ticks. `chunkgen` runs at normal tick rate instead, because its throughput is set by worker threads, not by the tick loop.
4. A measured tick spans from the moment the harness finished its own per-tick work to the moment it starts the next tick's. It includes the injected player packets, the tick itself and the tasks the server runs between ticks. Harness path planning and scenario feeds are excluded from time and allocation.

Per run: `result.json` (metrics, digest), `ticks.csv` (per-tick span, tick and main-thread allocation), `server.log`.

| metric | meaning |
| --- | --- |
| `mspt_mean`, `p50`, `p95`, `p99`, `max` | milliseconds per measured tick |
| `main_cpu_ms_per_tick` | main thread CPU time per tick |
| `alloc_kb_per_tick` | bytes allocated by the main thread per tick |
| `process_cpu_ms_per_tick` | CPU time of the whole JVM per tick, including workers and GC |
| `gc_count`, `gc_ms` | collections and collector time inside the window |
| `chunks_per_s`, `process_cpu_ms_per_chunk` | `chunkgen` only: chunks generated per wall second and JVM CPU per generated chunk |
| `cpu_calibration_us` | median duration of a fixed arithmetic loop timed on the main thread every 20 ticks. It measures the core the run got, not the server: when it differs between runs, so will mspt. Informational, never decides a comparison |

## Scenarios

Each lives in its own region of the overworld, thousands of blocks from the others.

| scenario | what it builds | what it exercises |
| --- | --- | --- |
| `idle-survival` | 20 survival players on a 5x4 grid 160 blocks apart over plains and forest at midnight, every fourth one patrolling | natural spawning and despawning, mob caps, random ticks, ambient mob AI, entity tracking |
| `mob-farm` | 180 hostiles in a pit under the player, four pens of 75 animals, a 28-block drop shaft fed one hostile per 10 ticks, a cow crusher fed one cow per 40 ticks, hoppers and chests under both | targeting, pathfinding that cannot succeed, collision and pushing, entity cramming, death, drops, hopper pickup |
| `villagers` | 120 villagers in 1x1 trading cells with job sites, two breeder rooms with 12 fed villagers, 24 beds, a bell and a caged zombie in view | brain AI across work, meet, idle and rest hours, POI claims, breeding, panic, golem spawning |
| `items-hoppers` | sixteen 32-hopper chains chest to chest, a 512-hopper open floor, sixteen observer-clocked droppers feeding stepped water streams that end over hoppers | hopper transfer and pull, item entities in flowing water, merging, container access |
| `redstone` | 16 repeater-ring clocks each driving 12 sticky pistons and a pulse counter, 16 comparator subtraction clocks, 32 two-tick observer clocks each driving a piston, dust and a lamp | dust updates, pistons and moving blocks, block events, scheduled ticks, comparators, droppers |
| `chunkgen` | 8 creative players flying 2 blocks per tick through never-generated terrain in separate lanes | worldgen, lighting, chunk load, send, unload and save throughput |
| `players-moving` | 100 survival players walking as a crowd around a 96-block circuit over natural terrain | move packet handling, entity tracker with every player seeing every other, chunk send and unload, natural spawning around a crowd |

Natural spawning is on in `idle-survival`, `players-moving` and `chunkgen`, off in the built scenarios so they measure their subsystem. Time is frozen at midnight except in `villagers`, where the clock runs from 4000 to 19000 in the measured window. Weather is clear.

### Fake players

Neither Leaf nor Leaves ships a bot facility in this tree (only a statistics stub), so the plugin makes its own. A fake player is a plain `ServerPlayer` joined through `PlayerList.placeNewPlayer` on a real `Connection` whose channel is an in-JVM netty `LocalChannel`. The server ticks it through `ServerConnectionListener` like any other connection, so spawning, activation, despawn, tracking and chunk loading treat it as a player. Outbound packets are built and handed to the event loop as usual, then dropped there. The plugin answers keepalives, teleports, deaths and chunk batches through the real serverbound handlers and injects a move packet and a client-tick-end packet per tick, so `handleMovePlayer` and its checks run as they do for a client. Survival bots carry Resistance V and Saturation: mobs target them, nothing kills them.

## Behavior digest

`result.json` holds `digest_exact` and `digest_statistical`. Exact fields are driven only by tick-deterministic logic and must be identical in every run and every jar; a difference is a behavior change. Statistical fields depend on game randomness and are compared as distributions.

| scenario | exact | statistical |
| --- | --- | --- |
| `idle-survival` | bot count | entity counts by type, natural spawns, deaths |
| `mob-farm` | hostiles and cows fed | entity counts by type, deaths by type, items by type in the shaft and crusher chests |
| `villagers` | villagers placed | villagers, babies, employed, sleeping, bred, golems spawned, deaths, entity counts |
| `items-hoppers` | items left in chain sources, items arrived in chain sinks | items in stream sources and sinks, item entities |
| `redstone` | ring pulses counted by droppers, sampled comparator power, lamps lit, pistons extended, CRC of every block state in the build | none |
| `chunkgen` | CRC of the block types in four probe chunks no player came near, decorated in a fixed order | chunks generated and unloaded |
| `players-moving` | bot count | entity counts by type, natural spawns, deaths |

`info` in `result.json` reports harness health and is not compared: bot deaths, relocations (a walker that got stuck and was teleported to its goal) and moves the server rejected. Rejected moves should stay at 0.

Counters for spawns, deaths and breeding come from Bukkit event listeners, registered only in the scenarios that use them (`idle-survival`, `mob-farm`, `villagers`, `players-moving`; chunk load and unload events in `chunkgen`). `items-hoppers` and `redstone` register none.

## Comparing

`compare.sh` prints, per scenario and metric, the means, the relative delta and the 95% and 99% half-widths of a Welch confidence interval for that delta. `~` is inside the 95% bound, `?` between the two, `DIFFERENT` beyond the 99% bound. Statistical digest fields are flagged when they differ beyond a 99.9% bound, with variance floored at the Poisson level so rare events do not flag by accident. Exact fields are flagged on any difference. The exit code is 3 when anything is flagged.

The bounds come from the run-to-run spread of the directories being compared, so they are only as good as the number of runs. Two runs per side give bounds too wide to be useful; use five or more to resolve a few percent.

## Measured noise

Six runs of one jar (three per side, interleaved), full mode, Apple M5 Pro shared with other work:

| scenario | mspt | run-to-run noise (CV) | main-thread alloc noise |
| --- | ---: | ---: | ---: |
| idle-survival | 7.37 | 5.1% | 2.0% |
| mob-farm | 2.25 | 6.0% | 6.7% |
| villagers | 0.99 | 2.3% | 1.5% |
| items-hoppers | 0.55 | 4.9% | 0.2% |
| redstone | 2.81 | 1.7% | 0.2% |
| players-moving | 9.51 | 4.5% | 0.3% |
| chunkgen | 203 chunks/s | 12% (4% without one stalled run) | not applicable |

`compare.sh` on the two halves reports no significant difference and no digest drift. At this noise three runs per side bound a delta to about ±5% (redstone) to ±18% (mob-farm). A ±2.5% bound needs about 5 runs per side for redstone, 8 for villagers and 25 to 45 for the others on this machine. An uninterrupted collection earlier the same night had 1.0 to 3.2% noise on the sprinted scenarios, so most of it is machine state: core type, frequency, other processes. The same scenarios also ran 10 to 20% slower late in the session than early. Compare only runs taken interleaved in one session.

What helps:

- Use `ab.sh`, not two separate `run.sh` invocations. It alternates the order each run, because the run before a measurement changes it: with a fixed order `chunkgen` was 5% faster for whichever jar ran second.
- On Linux pin the server to fixed cores with `HARNESS_LAUNCH_PREFIX`.
- Keep the machine otherwise idle. On a machine shared between agents, wrap the whole command in whatever lock serializes heavy work there.
- Look at `alloc_kb_per_tick` first. In the deterministic scenarios it repeats to 0.2 to 0.3%, so a change in work done shows there before it shows in time.
- `cpu_calibration_us` tells you whether two runs got the same CPU speed.

`-Xbatch` was tried and did not reduce the spread.

## Limitations

- No network stack. Packets are constructed and handed to a netty event loop on the main thread, as on a real server, but never encoded, compressed or written, so netty-thread cost is not measured. The fake loop has the server loops' task queue but polls instead of sleeping until woken: a send costs the main thread the queue insert and never a thread wake-up. That matches a loop kept awake by encoding work and understates a mostly idle one, so a change that only removes wake-ups (lazy execute, flush consolidation) will not show a gain here.
- Chunks decorated in parallel are not reproducible block for block: features write into neighbor chunks and the result depends on which neighbor decorates first. Only the `chunkgen` probe chunks, decorated in a fixed order, are compared exactly.
- Fake clients do not simulate physics. Walkers resolve block collisions themselves and send positions the server accepts, but they do not sprint-jump, swim properly, fight, break or place blocks, open containers or chat. Inventory, crafting, combat and block-interaction packet paths are not exercised.
- A tick sprint runs ticks back to back, so work that finishes on other threads in wall time (async mob spawning, chunk loads, async saves) lands a different number of ticks later than at 20 TPS. Wall-clock based logic (keepalive, autosave interval estimate) behaves accordingly.
- The game is not deterministic run to run. Entity and level random sources are seeded from the clock, and the statistical digest fields are the consequence.
- Only the overworld is used. Nether, End, raids, weather, explosions, large fluid flows, minecarts, portals and command blocks are not covered.
- Scenario scale is fixed and modest. Costs that grow faster than linearly with player or entity count show up smaller here than on a busy server.
- JFR samples at most a few threads per period. Main-thread percentages are reliable; comparing absolute sample counts between the main thread and a pool of busy workers is not.
- Results depend on the machine's core layout. Worker pool sizes follow the core count, and on Apple silicon the scheduler may move the main thread between core types.
