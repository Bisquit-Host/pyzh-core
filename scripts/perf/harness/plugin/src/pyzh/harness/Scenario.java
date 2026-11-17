package pyzh.harness;

import java.util.Map;
import java.util.SplittableRandom;
import org.bukkit.World;

abstract class Scenario {
    /** Chunk rectangle, inclusive. */
    record Region(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        static Region around(int blockX, int blockZ, int radiusBlocks) {
            return new Region((blockX - radiusBlocks) >> 4, (blockZ - radiusBlocks) >> 4, (blockX + radiusBlocks) >> 4, (blockZ + radiusBlocks) >> 4);
        }
    }

    static final class Context {
        final HarnessPlugin plugin;
        final World world;
        final Bots bots;
        final Build build;
        final boolean quick;

        Context(HarnessPlugin plugin, World world, Bots bots, boolean quick) {
            this.plugin = plugin;
            this.world = world;
            this.bots = bots;
            this.build = new Build(world);
            this.quick = quick;
        }

        SplittableRandom random(long salt) {
            return new SplittableRandom(0x9E3779B97F4A7C15L ^ salt);
        }
    }

    /** View distance plus margin, in blocks, around every bot position that must be pre-generated. */
    static final int VIEW_MARGIN = 12 * 16;

    abstract String name();

    /** Terrain the template world pre-generates for this scenario; null when it must stay ungenerated. */
    abstract Region region();

    /** Sprinting runs ticks back to back, so wall time per tick is the cost of the tick. */
    boolean sprint() {
        return true;
    }

    abstract int warmupTicks(boolean quick);

    abstract int measureTicks(boolean quick);

    boolean naturalSpawning() {
        return false;
    }

    /** Day time set when the run starts; negative keeps the clock frozen at midnight. */
    long dayTime(boolean quick) {
        return -1;
    }

    /** Builds structures and joins bots. Runs before the settle period, outside the measured window. */
    abstract void setup(Context ctx);

    /** First tick of the run (warmup start): kick clocks, register counters. */
    void start(Context ctx) {
    }

    /** Harness-driven per-tick input (mob feeds and the like). Its cost is excluded from the measured tick. */
    void tick(Context ctx, int tick) {
    }

    void measureStart(Context ctx) {
    }

    void measureEnd(Context ctx, double wallSeconds, Map<String, Double> metrics) {
    }

    abstract void digest(Context ctx, Digest digest);
}
