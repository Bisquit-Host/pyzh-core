package pyzh.harness;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Driven by system properties:
 * harness.mode=pregen|run, harness.scenarios=a+b, harness.out=dir, harness.quick, harness.jfr=file.
 */
public final class HarnessPlugin extends JavaPlugin implements Listener {
    private enum Phase { SETTLE, RUN, DONE }

    private final List<Scenario> scenarios = new ArrayList<>();
    private Scenario.Context ctx;
    private Bots bots;
    private World world;
    private Meter meter;
    private Recording recording;
    private Phase phase = Phase.SETTLE;
    private boolean sprint;
    private int warmup;
    private int measure;
    private int settleTicks;
    private int stableTicks;
    private int lastLoaded;
    private int tick;

    @Override
    public void onEnable() {
        String mode = System.getProperty("harness.mode");
        if (mode == null) {
            getLogger().info("harness.mode not set; idle");
            return;
        }
        world = Bukkit.getWorlds().getFirst();
        if (mode.equals("pregen")) {
            Bukkit.getScheduler().runTask(this, this::pregen);
            return;
        }
        boolean quick = Boolean.getBoolean("harness.quick");
        for (String name : System.getProperty("harness.scenarios").split("\\+")) {
            scenarios.add(Scenarios.byName(name));
        }
        sprint = scenarios.stream().allMatch(Scenario::sprint);
        warmup = scenarios.stream().mapToInt(s -> s.warmupTicks(quick)).max().orElseThrow();
        measure = scenarios.stream().mapToInt(s -> s.measureTicks(quick)).max().orElseThrow();
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                bots = new Bots();
                ctx = new Scenario.Context(this, world, bots, quick);
                world.setGameRule(GameRules.SPAWN_MOBS, scenarios.stream().anyMatch(Scenario::naturalSpawning));
                world.setGameRule(GameRules.ADVANCE_WEATHER, false);
                world.setGameRule(GameRules.ADVANCE_TIME, false);
                world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
                world.setGameRule(GameRules.SPAWN_PATROLS, false);
                world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
                world.setStorm(false);
                world.setTime(18000);
                for (Scenario scenario : scenarios) {
                    scenario.setup(ctx);
                }
                Bukkit.getPluginManager().registerEvents(this, this);
            } catch (Throwable t) {
                fail(t);
            }
        });
    }

    private void pregen() {
        List<Scenario.Region> regions = Scenarios.all().stream().map(Scenario::region).filter(r -> r != null).toList();
        int total = regions.stream().mapToInt(r -> (r.maxChunkX() - r.minChunkX() + 1) * (r.maxChunkZ() - r.minChunkZ() + 1)).sum();
        AtomicInteger done = new AtomicInteger();
        long start = System.nanoTime();
        getLogger().info("pregenerating " + total + " chunks");
        for (Scenario.Region region : regions) {
            for (int x = region.minChunkX(); x <= region.maxChunkX(); x++) {
                for (int z = region.minChunkZ(); z <= region.maxChunkZ(); z++) {
                    world.getChunkAtAsync(x, z, true).thenRun(() -> {
                        int count = done.incrementAndGet();
                        if (count % 1000 == 0 || count == total) {
                            getLogger().info("pregen " + count + "/" + total);
                        }
                        if (count == total) {
                            Scenarios.all().forEach(this::describeTerrain);
                            getLogger().info("HARNESS_PREGEN_DONE chunks=" + total + " seconds=" + (System.nanoTime() - start) / 1e9);
                            Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 20);
                        }
                    });
                }
            }
        }
    }

    /** Logged into the template so a seed or layout change that lands a scenario in an ocean or on a peak is visible. */
    private void describeTerrain(Scenario scenario) {
        Scenario.Region region = scenario.region();
        if (region == null) {
            return;
        }
        int centerX = (region.minChunkX() + region.maxChunkX() + 1) * 8, centerZ = (region.minChunkZ() + region.maxChunkZ() + 1) * 8;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, water = 0, samples = 0;
        for (int x = centerX - 128; x <= centerX + 128; x += 16) {
            for (int z = centerZ - 128; z <= centerZ + 128; z += 16) {
                int y = world.getHighestBlockYAt(x, z);
                min = Math.min(min, y);
                max = Math.max(max, y);
                water += world.getBlockAt(x, y, z).isLiquid() ? 1 : 0;
                samples++;
            }
        }
        getLogger().info("HARNESS_TERRAIN " + scenario.name() + " center=" + centerX + "," + centerZ + " biome="
            + world.getBiome(centerX, 64, centerZ).getKey().getKey() + " surface=" + min + ".." + max + " water=" + water * 100 / samples + "%");
    }

    @EventHandler
    public void onTickStart(ServerTickStartEvent event) {
        try {
            if (phase != Phase.DONE) {
                bots.drainInbox();
            }
            long now = System.nanoTime();
            long allocated = Meter.allocated();
            switch (phase) {
                case SETTLE -> settle();
                case RUN -> run(now, allocated);
                case DONE -> {
                }
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    @EventHandler
    public void onTickEnd(ServerTickEndEvent event) {
        if (meter != null) {
            meter.tickEnd();
        }
    }

    /** Real-time ticks until bots have loaded in and chunk loading around them has gone quiet. */
    private void settle() {
        bots.send();
        int loaded = world.getLoadedChunks().length;
        stableTicks = loaded == lastLoaded ? stableTicks + 1 : 0;
        lastLoaded = loaded;
        if (++settleTicks < 60 || !bots.allLoaded() || (stableTicks < 40 && settleTicks < 1200)) {
            return;
        }
        getLogger().info("settled after " + settleTicks + " ticks, " + loaded + " chunks loaded, " + bots.all.size() + " bots");
        long dayTime = scenarios.stream().mapToLong(s -> s.dayTime(ctx.quick)).max().orElse(-1);
        if (dayTime >= 0) {
            world.setGameRule(GameRules.ADVANCE_TIME, true);
            world.setTime(dayTime);
        }
        for (Scenario scenario : scenarios) {
            scenario.start(ctx);
        }
        phase = Phase.RUN;
        tick = 0;
        bots.rejectedMoves = 0;
        bots.relocations = 0;
        if (sprint) {
            Bukkit.getServerTickManager().requestGameToSprint(warmup + measure + 20);
        }
    }

    private void run(long now, long allocated) throws Exception {
        if (meter != null && meter.running() && meter.close(now, allocated)) {
            finish();
            return;
        }
        if (tick == warmup) {
            meter = new Meter(measure, sprint);
            startRecording();
            for (Scenario scenario : scenarios) {
                scenario.measureStart(ctx);
            }
            meter.begin();
        }
        for (Scenario scenario : scenarios) {
            scenario.tick(ctx, tick);
        }
        bots.plan(tick);
        if (tick % Meter.CALIBRATION_INTERVAL == 0) {
            long nanos = Meter.calibrate();
            if (meter != null) {
                meter.addCalibration(nanos);
            }
        }
        tick++;
        if (meter != null) {
            meter.open();
        }
        bots.send();
    }

    private void startRecording() throws Exception {
        String file = System.getProperty("harness.jfr");
        if (file == null) {
            return;
        }
        recording = new Recording(Configuration.getConfiguration("profile"));
        recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(Long.getLong("harness.jfr.periodMs", 1)));
        recording.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(10));
        recording.enable("jdk.ObjectAllocationSample").with("throttle", "1500/s");
        recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(1));
        recording.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(1));
        recording.setDestination(Path.of(file));
        recording.start();
    }

    private void finish() throws IOException {
        phase = Phase.DONE;
        if (recording != null) {
            recording.stop();
            recording.close();
        }
        Bukkit.getServerTickManager().stopSprinting();
        Map<String, Double> metrics = meter.metrics();
        Digest digest = new Digest();
        for (Scenario scenario : scenarios) {
            scenario.measureEnd(ctx, meter.wallSeconds(), metrics);
            scenario.digest(ctx, digest);
        }
        digest.info.put("bots", bots.all.size());
        digest.info.put("bot_deaths", bots.deaths);
        digest.info.put("bot_relocations", bots.relocations);
        digest.info.put("bot_rejected_moves", bots.rejectedMoves);
        digest.info.put("players_online", Bukkit.getOnlinePlayers().size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", System.getProperty("harness.scenarios"));
        result.put("server", Bukkit.getName() + " " + Bukkit.getVersion());
        result.put("quick", ctx.quick);
        result.put("sprint", sprint);
        result.put("jfr", recording != null);
        result.put("warmup_ticks", warmup);
        result.put("measure_ticks", measure);
        result.put("metrics", metrics);
        result.put("digest_exact", digest.exact);
        result.put("digest_statistical", digest.statistical);
        result.put("info", digest.info);
        Path out = Path.of(System.getProperty("harness.out"));
        Files.createDirectories(out);
        Files.writeString(out.resolve("result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        StringBuilder csv = new StringBuilder("span_ns,tick_ns,alloc_bytes\n");
        for (int i = 0; i < meter.spanNanos.length; i++) {
            csv.append(meter.spanNanos[i]).append(',').append(meter.tickNanos[i]).append(',').append(meter.allocBytes[i]).append('\n');
        }
        Files.writeString(out.resolve("ticks.csv"), csv);
        getLogger().info("HARNESS_DONE " + metrics);
        Bukkit.shutdown();
    }

    private void fail(Throwable t) {
        phase = Phase.DONE;
        getLogger().log(java.util.logging.Level.SEVERE, "HARNESS_FAILED", t);
        Bukkit.shutdown();
    }
}
