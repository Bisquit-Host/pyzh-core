import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Turns a harness JFR recording into a markdown profile. Usage: JfrReport profile.jfr [topN]
 *
 * Main-thread samples are attributed twice by the deepest frame matching a rule set: once against coarse tick
 * phases, once against fine-grained subsystems. Frames of the harness' own planning code are reported and then
 * left out of every percentage; packets the harness injects stay in, since that is real server work.
 */
public final class JfrReport {
    private static final String MAIN = "Server thread";

    /** Coarse phases. Later entries are more specific; the deepest matching frame in a stack decides. */
    private static final String[][] PHASES = {
        {"net.minecraft.server.MinecraftServer.", "server loop (other)"},
        {"net.minecraft.server.MinecraftServer.runAllTasksAtTickStart", "tasks at tick start"},
        {"net.minecraft.server.MinecraftServer.waitUntilNextTick", "between-tick tasks"},
        {"net.minecraft.server.MinecraftServer.recordTaskExecutionTimeWhileWaiting", "between-tick tasks"},
        {"net.minecraft.server.MinecraftServer.tickChildren", "tick (other)"},
        {"net.minecraft.server.players.PlayerList.saveAll", "autosave: players"},
        {"net.minecraft.server.level.ServerLevel.saveIncrementally", "autosave: chunks"},
        {"net.minecraft.server.players.PlayerList.tick", "player list tick"},
        {"net.minecraft.server.network.ServerConnectionListener.tick", "connection tick (player.doTick, keepalive)"},
        {"net.minecraft.server.level.ServerLevel.tick", "level tick (other)"},
        {"net.minecraft.world.ticks.LevelTicks.tick", "scheduled block/fluid ticks"},
        {"net.minecraft.server.level.ServerLevel.tickBlock", "scheduled block/fluid ticks"},
        {"net.minecraft.server.level.ServerLevel.tickFluid", "scheduled block/fluid ticks"},
        {"net.minecraft.server.level.ServerLevel.tickPrecipitation", "chunk tick: random ticks, ice/snow, lightning"},
        {"net.minecraft.server.level.ServerLevel.tickThunder", "chunk tick: random ticks, ice/snow, lightning"},
        {"net.minecraft.server.level.ServerLevel.runBlockEvents", "block events (pistons)"},
        {"net.minecraft.world.level.Level.tickBlockEntities", "block entities"},
        {"net.minecraft.server.level.ServerLevel.tickBlockEntities", "block entities"},
        {"net.minecraft.server.level.ServerChunkCache.tick", "chunk source tick (other)"},
        {"net.minecraft.server.level.ChunkMap.tick", "entity tracker"},
        {"net.minecraft.server.level.ServerLevel.tickChunk", "chunk tick: random ticks, ice/snow, lightning"},
        {"net.minecraft.world.level.NaturalSpawner.", "natural spawning"},
        {"org.dreeam.leaf.world.NatureSpawnChunkMap", "natural spawning"},
        {"net.minecraft.world.level.entity.EntityTickList.forEach", "entity tick"},
        {"net.minecraft.server.level.ServerLevel.tickNonPassenger", "entity tick"},
        {"net.minecraft.server.network.ServerGamePacketListenerImpl.handle", "serverbound packet handlers"},
        {"pyzh.harness.Bots.send", "serverbound packet handlers"},
        {"pyzh.harness.Bots.drainInbox", "serverbound packet handlers"},
    };

    private static final String[][] SUBSYSTEMS = {
        {"net.minecraft.server.MinecraftServer.", "server loop"},
        {"net.minecraft.server.level.ServerLevel.", "level tick glue"},
        {"net.minecraft.server.level.ServerChunkCache.", "chunk source / chunk iteration"},
        {"ca.spottedleaf.moonrise.patches.chunk_system.", "chunk system (load/unload/tickets)"},
        {"ca.spottedleaf.moonrise.patches.chunk_tick_iteration.", "chunk source / chunk iteration"},
        {"net.minecraft.world.level.chunk.", "chunk data access"},
        {"net.minecraft.server.level.ServerLevel.tickChunk", "random ticks / chunk tick"},
        {"net.minecraft.server.level.ServerLevel.optimiseRandomTick", "random ticks / chunk tick"},
        {"net.minecraft.server.waypoints.", "waypoints (locator bar)"},
        {"net.minecraft.world.waypoints.", "waypoints (locator bar)"},
        {"net.minecraft.world.entity.ai.attributes.", "attributes"},
        {"net.minecraft.world.level.block.", "block logic (updates, random ticks)"},
        {"net.minecraft.world.level.redstone.", "redstone / neighbor updates"},
        {"net.minecraft.world.level.block.RedStoneWireBlock", "redstone / neighbor updates"},
        {"net.minecraft.world.level.block.piston.", "pistons"},
        {"net.minecraft.world.ticks.", "scheduled ticks"},
        {"net.minecraft.world.level.material.", "fluids"},
        {"net.minecraft.world.level.block.entity.", "block entities (other)"},
        {"net.minecraft.world.level.block.entity.HopperBlockEntity", "hoppers"},
        {"net.minecraft.world.level.block.entity.Hopper", "hoppers"},
        {"net.minecraft.world.entity.", "entity tick (other)"},
        {"net.minecraft.world.entity.item.ItemEntity", "item entities"},
        {"net.minecraft.world.entity.LivingEntity.travel", "entity movement / collision"},
        {"net.minecraft.world.entity.Entity.move", "entity movement / collision"},
        {"net.minecraft.world.entity.Entity.collide", "entity movement / collision"},
        {"ca.spottedleaf.moonrise.patches.collisions.", "entity movement / collision"},
        {"net.minecraft.world.phys.shapes.", "entity movement / collision"},
        {"net.minecraft.world.entity.LivingEntity.pushEntities", "entity pushing / cramming"},
        {"net.minecraft.world.entity.ai.", "mob AI (other)"},
        {"net.minecraft.world.entity.ai.goal.", "mob AI: goals"},
        {"net.minecraft.world.entity.ai.targeting.", "mob AI: targeting"},
        {"net.minecraft.world.entity.ai.behavior.", "mob AI: brain behaviors"},
        {"net.minecraft.world.entity.ai.Brain", "mob AI: brain behaviors"},
        {"net.minecraft.world.entity.ai.sensing.", "mob AI: sensors"},
        {"net.minecraft.world.entity.ai.village.poi.", "POI"},
        {"net.minecraft.world.entity.ai.navigation.", "pathfinding"},
        {"net.minecraft.world.level.pathfinder.", "pathfinding"},
        {"ca.spottedleaf.moonrise.patches.chunk_system.level.entity.", "entity lookups (AABB queries)"},
        {"net.minecraft.world.level.entity.", "entity lookups (AABB queries)"},
        {"net.minecraft.world.level.NaturalSpawner", "natural spawning"},
        {"net.minecraft.world.level.chunk.ChunkGenerator.getMobsAt", "natural spawning"},
        {"ca.spottedleaf.moonrise.common.misc.NearbyPlayers", "nearby-player maps"},
        {"net.minecraft.world.level.EntityGetter.getNearestPlayer", "nearest-player search"},
        {"net.minecraft.world.entity.EntityFluidInteraction", "entity fluid checks"},
        {"net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase.randomTick", "random ticks / chunk tick"},
        {"net.minecraft.world.level.block.entity.TickingBlockEntity", "block entities (other)"},
        {"net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity", "block entities (other)"},
        {"net.minecraft.world.level.chunk.LevelChunk$RebindableTickingBlockEntityWrapper", "block entities (other)"},
        {"org.dreeam.leaf.world.NatureSpawnChunkMap", "natural spawning"},
        {"org.dreeam.leaf.world.EntityPushState", "entity pushing / cramming"},
        {"org.dreeam.leaf.world.ChunkCache", "chunk data access"},
        {"net.minecraft.world.entity.Entity.pyzh$isOutside", "entity lookups (AABB queries)"},
        {"net.minecraft.world.level.levelgen.", "worldgen"},
        {"net.minecraft.world.level.biome.", "biome lookup"},
        {"ca.spottedleaf.moonrise.patches.starlight.", "lighting"},
        {"net.minecraft.world.level.lighting.", "lighting"},
        {"net.minecraft.server.level.ChunkMap", "chunk map (spawn chunk selection, mob counts)"},
        {"net.minecraft.server.level.ChunkMap$TrackedEntity", "entity tracker"},
        {"net.minecraft.server.level.ChunkMap.newTrackerTick", "entity tracker"},
        {"net.minecraft.server.level.ChunkMap.tick", "entity tracker"},
        {"net.minecraft.server.level.ServerEntity", "entity tracker"},
        {"org.dreeam.leaf.async.tracker.", "entity tracker"},
        {"net.minecraft.network.syncher.", "entity data sync"},
        {"net.minecraft.server.network.", "packet handling (serverbound)"},
        {"net.minecraft.network.", "packet construction / send"},
        {"ca.spottedleaf.moonrise.patches.chunk_system.player.", "player chunk loader / chunk send"},
        {"net.minecraft.nbt.", "NBT / serialization"},
        {"net.minecraft.world.level.chunk.storage.", "NBT / serialization"},
        {"net.minecraft.util.datafix.", "NBT / serialization"},
        {"com.mojang.serialization.", "NBT / serialization"},
        {"org.bukkit.plugin.", "Bukkit event dispatch"},
        {"org.bukkit.event.", "Bukkit event dispatch"},
        {"org.bukkit.craftbukkit.event.", "Bukkit event construction"},
    };

    private static final Object[][] PATTERNS = {
        {"Bukkit events (callEvent / CraftEventFactory)", pred("org.bukkit.plugin.SimplePluginManager.callEvent", "io.papermc.paper.plugin.manager.", "org.bukkit.craftbukkit.event.CraftEventFactory", "org.bukkit.event.Event.callEvent")},
        {"java.util.stream pipelines", pred("java.util.stream.")},
        {"boxing (valueOf)", pred("java.lang.Integer.valueOf", "java.lang.Long.valueOf", "java.lang.Double.valueOf", "java.lang.Float.valueOf", "java.lang.Boolean.valueOf", "java.lang.Short.valueOf", "java.lang.Byte.valueOf")},
        {"Optional", pred("java.util.Optional.")},
        {"config lookups (paper/spigot/gale/leaf/purpur config, game rules)", pred("io.papermc.paper.configuration.", "org.spigotmc.SpigotWorldConfig", "org.spigotmc.SpigotConfig", "org.galemc.gale.configuration.", "org.dreeam.leaf.config.", "org.purpurmc.purpur.PurpurConfig", "org.purpurmc.purpur.PurpurWorldConfig", "net.minecraft.world.level.gamerules.")},
        {"locks / synchronized collections", pred("java.util.concurrent.locks.", "java.util.Collections$Synchronized", "java.lang.Object.wait", "jdk.internal.misc.Unsafe.park")},
        {"ConcurrentHashMap / concurrent queues", pred("java.util.concurrent.ConcurrentHashMap", "java.util.concurrent.ConcurrentLinkedQueue", "java.util.concurrent.ConcurrentLinkedDeque", "java.util.concurrent.CopyOnWrite")},
        {"ThreadLocal", pred("java.lang.ThreadLocal")},
        {"exceptions / stack walking", pred("java.lang.Throwable.", "java.lang.StackWalker", "java.lang.StackTraceElement")},
        {"reflection / method handles", pred("java.lang.reflect.", "jdk.internal.reflect.", "java.lang.invoke.")},
        {"string building / formatting", pred("java.lang.StringBuilder.", "java.lang.String.format", "java.util.Formatter", "java.lang.StringConcatHelper")},
        {"vanilla profiler / metrics hooks", pred("net.minecraft.util.profiling.", "me.lucko.spark.")},
        {"registry / tag lookups", pred("net.minecraft.core.MappedRegistry", "net.minecraft.core.Holder", "net.minecraft.tags.", "net.minecraft.core.HolderSet")},
        {"data components (item stacks)", pred("net.minecraft.core.component.")},
        {"thread checks (ensureTickThread / isSameThread)", pred("ca.spottedleaf.moonrise.common.util.TickThread", "org.spigotmc.AsyncCatcher", "net.minecraft.util.thread.BlockableEventLoop.isSameThread")},
    };

    private static Predicate<String> pred(String... prefixes) {
        return frame -> {
            for (String prefix : prefixes) {
                if (frame.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        };
    }

    private record Stack(List<String> frames, boolean truncated) {
    }

    private static final class Tally {
        final Map<String, double[]> values = new HashMap<>();
        double total;

        void add(String key, double weight) {
            values.computeIfAbsent(key, k -> new double[1])[0] += weight;
        }

        List<Map.Entry<String, double[]>> top(int n) {
            return values.entrySet().stream().sorted(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> -e.getValue()[0])).limit(n).toList();
        }
    }

    private static int topN = 30;

    public static void main(String[] args) throws IOException {
        Locale.setDefault(Locale.ROOT);
        if (args.length > 1) {
            topN = Integer.parseInt(args[1]);
        }
        List<Stack> mainJava = new ArrayList<>(), mainNative = new ArrayList<>();
        Map<String, List<Stack>> workers = new HashMap<>();
        Map<String, int[]> threadSamples = new HashMap<>();
        Tally allocClasses = new Tally(), allocSites = new Tally(), allocSubsystems = new Tally(), allocThreads = new Tally();
        Tally monitors = new Tally(), parks = new Tally();
        double[] monitorMs = new double[1], mainParkMs = new double[1], gcPauseMs = new double[1];
        int[] gcCount = new int[1];
        long[] span = {Long.MAX_VALUE, Long.MIN_VALUE};

        try (RecordingFile file = new RecordingFile(Path.of(args[0]))) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String type = event.getEventType().getName();
                switch (type) {
                    case "jdk.ExecutionSample", "jdk.NativeMethodSample" -> {
                        RecordedThread thread = event.getThread("sampledThread");
                        Stack stack = stack(event.getStackTrace());
                        if (thread == null || stack == null) {
                            break;
                        }
                        span[0] = Math.min(span[0], event.getStartTime().toEpochMilli());
                        span[1] = Math.max(span[1], event.getStartTime().toEpochMilli());
                        boolean java = type.equals("jdk.ExecutionSample");
                        String name = thread.getJavaName() == null ? "(native thread)" : thread.getJavaName();
                        threadSamples.computeIfAbsent(group(name), k -> new int[2])[java ? 0 : 1]++;
                        if (name.equals(MAIN)) {
                            (java ? mainJava : mainNative).add(stack);
                        } else if (java) {
                            workers.computeIfAbsent(group(name), k -> new ArrayList<>()).add(stack);
                        }
                    }
                    case "jdk.ObjectAllocationSample" -> {
                        Stack stack = stack(event.getStackTrace());
                        RecordedThread thread = event.getThread("eventThread");
                        if (stack == null || thread == null) {
                            break;
                        }
                        double weight = event.getLong("weight");
                        String name = thread.getJavaName() == null ? "(native thread)" : thread.getJavaName();
                        allocThreads.add(group(name), weight);
                        allocThreads.total += weight;
                        if (name.equals(MAIN) && !harnessOverhead(stack)) {
                            allocClasses.total += weight;
                            allocClasses.add(event.getClass("objectClass").getName(), weight);
                            allocSites.add(site(stack), weight);
                            allocSubsystems.add(deepest(stack, SUBSYSTEMS, "other / JDK"), weight);
                        }
                    }
                    case "jdk.JavaMonitorEnter" -> {
                        double ms = event.getDuration().toNanos() / 1e6;
                        monitorMs[0] += ms;
                        RecordedThread thread = event.getThread("eventThread");
                        monitors.add((thread == null ? "?" : group(String.valueOf(thread.getJavaName()))) + " waiting on " + event.getClass("monitorClass").getName(), ms);
                    }
                    case "jdk.ThreadPark" -> {
                        RecordedThread thread = event.getThread("eventThread");
                        Stack stack = stack(event.getStackTrace());
                        if (thread != null && MAIN.equals(thread.getJavaName()) && stack != null) {
                            double ms = event.getDuration().toNanos() / 1e6;
                            mainParkMs[0] += ms;
                            parks.add(firstOwn(stack), ms);
                        }
                    }
                    case "jdk.GarbageCollection" -> {
                        gcCount[0]++;
                        gcPauseMs[0] += event.getDuration("sumOfPauses").toNanos() / 1e6;
                    }
                    default -> {
                    }
                }
            }
        }

        StringBuilder out = new StringBuilder();
        double seconds = Math.max(1, span[1] - span[0]) / 1000.0;
        out.append(String.format("Recording window: %.1f s. GC: %d collections, %.0f ms total pause.%n%n", seconds, gcCount[0], gcPauseMs[0]));

        out.append("### Threads (sample counts)\n\n| thread group | Java samples | native samples |\n| --- | ---: | ---: |\n");
        threadSamples.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) -> -e.getValue()[0] - e.getValue()[1])).limit(14)
            .forEach(e -> out.append(String.format("| %s | %d | %d |%n", e.getKey(), e.getValue()[0], e.getValue()[1])));

        List<Stack> all = new ArrayList<>(mainJava);
        all.addAll(mainNative);
        List<Stack> server = all.stream().filter(s -> !harnessOverhead(s)).map(JfrReport::belowHarness).toList();
        int n = server.size();
        long truncated = server.stream().filter(Stack::truncated).count();
        out.append(String.format("%n### Main thread%n%n%d samples (%d Java, %d native); %d of them (%.1f%%) are harness planning/feed code and excluded below. "
                + "%d stacks were truncated at the JFR depth limit.%n", all.size(), mainJava.size(), mainNative.size(), all.size() - n,
            100.0 * (all.size() - n) / Math.max(1, all.size()), truncated));

        table(out, "By tick phase (deepest phase frame)", "phase", count(server, s -> deepest(s, PHASES, "unattributed")), n, 40);
        table(out, "By subsystem (deepest subsystem frame)", "subsystem", count(server, s -> deepest(s, SUBSYSTEMS, "other / JDK")), n, 40);

        Tally self = new Tally();
        Map<String, Tally> callers = new HashMap<>();
        Map<String, Tally> selfBySubsystem = new HashMap<>();
        for (Stack s : server) {
            String top = s.frames.getFirst();
            self.add(top, 1);
            callers.computeIfAbsent(top, k -> new Tally()).add(callerChain(s), 1);
            selfBySubsystem.computeIfAbsent(deepest(s, SUBSYSTEMS, "other / JDK"), k -> new Tally()).add(top, 1);
        }
        out.append("\n#### Self time grouped by subsystem (subsystems above 1.5%, frames above 0.25%)\n");
        for (Map.Entry<String, double[]> group : count(server, s -> deepest(s, SUBSYSTEMS, "other / JDK")).top(40)) {
            if (group.getValue()[0] < 0.015 * n) {
                continue;
            }
            out.append(String.format("%n**%s: %.1f%%**%n%n", group.getKey(), 100 * group.getValue()[0] / n));
            for (Map.Entry<String, double[]> e : selfBySubsystem.get(group.getKey()).top(8)) {
                if (e.getValue()[0] >= 0.0025 * n) {
                    out.append(String.format("- %.2f%% `%s`%n", 100 * e.getValue()[0] / n, shorten(e.getKey())));
                }
            }
        }
        out.append(String.format("%n#### Top %d frames by self time%n%n| self %% | frame | most common callers |%n| ---: | --- | --- |%n", topN));
        for (Map.Entry<String, double[]> e : self.top(topN)) {
            Map.Entry<String, double[]> caller = callers.get(e.getKey()).top(1).getFirst();
            out.append(String.format("| %.2f | `%s` | %s (%.0f%%) |%n", 100 * e.getValue()[0] / n, shorten(e.getKey()), caller.getKey(), 100 * caller.getValue()[0] / e.getValue()[0]));
        }

        Tally inclusive = new Tally();
        for (Stack s : server) {
            s.frames.stream().distinct().filter(JfrReport::own).forEach(f -> inclusive.add(f, 1));
        }
        out.append(String.format("%n#### Top %d server frames by inclusive time%n%n| incl %% | frame |%n| ---: | --- |%n", topN + 20));
        for (Map.Entry<String, double[]> e : inclusive.top(topN + 20)) {
            out.append(String.format("| %.1f | `%s` |%n", 100 * e.getValue()[0] / n, shorten(e.getKey())));
        }

        out.append("\n#### Cross-cutting patterns (share of main-thread samples with such a frame anywhere in the stack)\n\n| incl % | self % | pattern | hottest caller |\n| ---: | ---: | --- | --- |\n");
        for (Object[] pattern : PATTERNS) {
            @SuppressWarnings("unchecked") Predicate<String> match = (Predicate<String>) pattern[1];
            int incl = 0, selfCount = 0;
            Tally hottest = new Tally();
            for (Stack s : server) {
                int index = -1;
                for (int i = 0; i < s.frames.size(); i++) {
                    if (match.test(s.frames.get(i))) {
                        index = i;
                    }
                }
                if (index < 0) {
                    continue;
                }
                incl++;
                selfCount += match.test(s.frames.getFirst()) ? 1 : 0;
                // the frame that called into the outermost pattern frame
                hottest.add(index + 1 < s.frames.size() ? shorten(s.frames.get(index + 1)) : "(root)", 1);
            }
            if (incl > 0) {
                Map.Entry<String, double[]> caller = hottest.top(1).getFirst();
                out.append(String.format("| %.2f | %.2f | %s | `%s` (%.0f%%) |%n", 100.0 * incl / n, 100.0 * selfCount / n, pattern[0], caller.getKey(), 100 * caller.getValue()[0] / incl));
            }
        }

        out.append(String.format("%n### Allocation%n%nSampled allocation by thread: %s.%n", share(allocThreads, 6)));
        out.append("\n#### Main thread: by subsystem\n\n| % of bytes | subsystem |\n| ---: | --- |\n");
        for (Map.Entry<String, double[]> e : allocSubsystems.top(15)) {
            out.append(String.format("| %.1f | %s |%n", 100 * e.getValue()[0] / allocClasses.total, e.getKey()));
        }
        out.append("\n#### Main thread: top allocated classes\n\n| % of bytes | class |\n| ---: | --- |\n");
        for (Map.Entry<String, double[]> e : allocClasses.top(20)) {
            out.append(String.format("| %.1f | `%s` |%n", 100 * e.getValue()[0] / allocClasses.total, e.getKey()));
        }
        out.append("\n#### Main thread: top allocation sites (allocating frame <- nearest server frames)\n\n| % of bytes | site |\n| ---: | --- |\n");
        for (Map.Entry<String, double[]> e : allocSites.top(topN)) {
            out.append(String.format("| %.1f | %s |%n", 100 * e.getValue()[0] / allocClasses.total, e.getKey()));
        }

        out.append(String.format("%n### Locks and waiting%n%nMonitor contention (>= 1 ms events): %.0f ms total.%n", monitorMs[0]));
        for (Map.Entry<String, double[]> e : monitors.top(8)) {
            out.append(String.format("- %.0f ms: %s%n", e.getValue()[0], e.getKey()));
        }
        out.append(String.format("%nMain thread parked (>= 1 ms events): %.0f ms total (%.1f%% of the window).%n", mainParkMs[0], mainParkMs[0] / seconds / 10));
        for (Map.Entry<String, double[]> e : parks.top(8)) {
            out.append(String.format("- %.0f ms: %s%n", e.getValue()[0], e.getKey()));
        }

        out.append("\n### Other threads: top self frames\n");
        workers.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String, List<Stack>> e) -> -e.getValue().size())).limit(6).forEach(e -> {
            if (e.getValue().size() < 50) {
                return;
            }
            Tally tally = new Tally();
            e.getValue().forEach(s -> tally.add(s.frames.getFirst(), 1));
            out.append(String.format("%n#### %s (%d Java samples)%n%nBy subsystem: %s.%n%n| self %% | frame |%n| ---: | --- |%n", e.getKey(), e.getValue().size(),
                share(count(e.getValue(), s -> deepest(s, SUBSYSTEMS, "other / JDK")), 6)));
            for (Map.Entry<String, double[]> f : tally.top(12)) {
                out.append(String.format("| %.2f | `%s` |%n", 100 * f.getValue()[0] / e.getValue().size(), shorten(f.getKey())));
            }
        });
        System.out.print(out);
    }

    private static Stack stack(RecordedStackTrace trace) {
        if (trace == null || trace.getFrames().isEmpty()) {
            return null;
        }
        List<String> frames = new ArrayList<>(trace.getFrames().size());
        for (RecordedFrame frame : trace.getFrames()) {
            frames.add(frame.getMethod().getType().getName() + "." + frame.getMethod().getName());
        }
        return new Stack(frames, trace.isTruncated());
    }

    /** Harness code that is not standing in for a client: path planning, scenario feeds, sampling. */
    private static boolean harnessOverhead(Stack stack) {
        for (String frame : stack.frames) {
            if (frame.startsWith("pyzh.harness.")) {
                return !frame.startsWith("pyzh.harness.Bots.send") && !frame.startsWith("pyzh.harness.Bots.drainInbox")
                    && !frame.startsWith("pyzh.harness.Bots$Bot.on") && !frame.startsWith("pyzh.harness.Bots.lambda");
            }
        }
        return false;
    }

    /**
     * Injected packets run inside the harness' tick-start listener; cutting the stack at the harness frame keeps
     * that event dispatch from being counted as server time spent in Bukkit events.
     */
    private static Stack belowHarness(Stack stack) {
        for (int i = 0; i < stack.frames.size(); i++) {
            if (stack.frames.get(i).startsWith("pyzh.harness.")) {
                return new Stack(stack.frames.subList(0, i + 1), stack.truncated);
            }
        }
        return stack;
    }

    /** Label of the rule matched by the frame closest to the leaf; among rules matching one frame the longest prefix wins. */
    private static String deepest(Stack stack, String[][] rules, String fallback) {
        for (String frame : stack.frames) {
            String label = null;
            int length = -1;
            for (String[] rule : rules) {
                if (rule[0].length() > length && frame.startsWith(rule[0])) {
                    label = rule[1];
                    length = rule[0].length();
                }
            }
            if (label != null) {
                return label;
            }
        }
        return fallback;
    }

    private static boolean own(String frame) {
        return frame.startsWith("net.minecraft.") || frame.startsWith("org.bukkit.") || frame.startsWith("io.papermc.") || frame.startsWith("ca.spottedleaf.")
            || frame.startsWith("org.dreeam.") || frame.startsWith("org.galemc.") || frame.startsWith("org.purpurmc.") || frame.startsWith("org.spigotmc.")
            || frame.startsWith("com.destroystokyo.") || frame.startsWith("org.leavesmc.") || frame.startsWith("com.mojang.");
    }

    private static String firstOwn(Stack stack) {
        return stack.frames.stream().filter(JfrReport::own).findFirst().map(JfrReport::shorten).orElse(shorten(stack.frames.getFirst()));
    }

    private static String callerChain(Stack stack) {
        StringBuilder chain = new StringBuilder();
        for (int i = 1; i < Math.min(4, stack.frames.size()); i++) {
            chain.append(i > 1 ? " <- " : "").append('`').append(shorten(stack.frames.get(i))).append('`');
        }
        return chain.isEmpty() ? "(root)" : chain.toString();
    }

    private static String site(Stack stack) {
        StringBuilder site = new StringBuilder("`" + shorten(stack.frames.getFirst()) + "`");
        int added = 0;
        for (int i = 1; i < stack.frames.size() && added < 2; i++) {
            if (own(stack.frames.get(i))) {
                site.append(" <- `").append(shorten(stack.frames.get(i))).append('`');
                added++;
            }
        }
        return site.toString();
    }

    private static String shorten(String frame) {
        int method = frame.lastIndexOf('.');
        int type = frame.lastIndexOf('.', method - 1);
        return frame.substring(type + 1);
    }

    private static String group(String thread) {
        return thread.replaceAll("[-# ]*\\d+$", "").replaceAll("\\d+", "N");
    }

    private static Tally count(List<Stack> stacks, java.util.function.Function<Stack, String> key) {
        Tally tally = new Tally();
        for (Stack s : stacks) {
            tally.add(key.apply(s), 1);
            tally.total++;
        }
        return tally;
    }

    private static void table(StringBuilder out, String title, String column, Tally tally, int n, int limit) {
        out.append(String.format("%n#### %s%n%n| %% | %s |%n| ---: | --- |%n", title, column));
        for (Map.Entry<String, double[]> e : tally.top(limit)) {
            out.append(String.format("| %.1f | %s |%n", 100 * e.getValue()[0] / n, e.getKey()));
        }
    }

    private static String share(Tally tally, int limit) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, double[]> e : tally.top(limit)) {
            text.append(text.isEmpty() ? "" : ", ").append(String.format("%s %.0f%%", e.getKey(), 100 * e.getValue()[0] / tally.total));
        }
        return text.toString();
    }
}
