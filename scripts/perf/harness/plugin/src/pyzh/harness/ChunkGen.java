package pyzh.harness;

import java.util.Map;
import java.util.zip.CRC32;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameMode;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

/**
 * Creative bots fly in parallel lanes through never-generated terrain at real-time tick rate, faster than
 * generation keeps up, so chunks generated per second is the throughput of worldgen plus load/unload.
 */
final class ChunkGen extends Scenario implements Listener {
    private static final int X = 8000, Z = -2000, Y = 200, LANES = 8, LANE_SPACING = 640;
    private static final double SPEED = Double.parseDouble(System.getProperty("harness.chunkgen.speed", "2.0"));

    private int generated, loaded, unloaded;
    private int generatedAtStart, unloadedAtStart;

    @Override
    String name() {
        return "chunkgen";
    }

    @Override
    Region region() {
        return null;
    }

    @Override
    boolean sprint() {
        return false;
    }

    @Override
    int warmupTicks(boolean quick) {
        return quick ? 200 : 400;
    }

    @Override
    int measureTicks(boolean quick) {
        return quick ? 400 : 800;
    }

    @Override
    boolean naturalSpawning() {
        return true;
    }

    @Override
    void setup(Context ctx) {
        ctx.plugin.getServer().getPluginManager().registerEvents(this, ctx.plugin);
        for (int i = 0; i < LANES; i++) {
            ctx.bots.join("flyer" + i, ctx.world, X + 0.5, Y, Z + i * LANE_SPACING + 0.5, GameMode.CREATIVE).mover = new Movers.Flyer(SPEED, 0);
        }
    }

    @EventHandler
    public void onLoad(ChunkLoadEvent event) {
        loaded++;
        if (event.isNewChunk()) {
            generated++;
        }
    }

    @EventHandler
    public void onUnload(ChunkUnloadEvent event) {
        unloaded++;
    }

    @Override
    void measureStart(Context ctx) {
        generatedAtStart = generated;
        unloadedAtStart = unloaded;
    }

    @Override
    void measureEnd(Context ctx, double wallSeconds, Map<String, Double> metrics) {
        int chunks = generated - generatedAtStart;
        metrics.put("chunks_per_s", chunks / wallSeconds);
        metrics.put("chunk_unloads_per_s", (unloaded - unloadedAtStart) / wallSeconds);
        metrics.put("process_cpu_ms_per_chunk", metrics.get("process_cpu_ms_per_tick") * measureTicks(ctx.quick) / Math.max(1, chunks));
    }

    /**
     * Worldgen output check on chunks no player has been near, so nothing has ticked in them. Features of a
     * chunk also write into its neighbors and the result depends on which neighbor decorates first, so the 3x3
     * around each probe chunk is decorated one chunk at a time in a fixed order before the probe is completed.
     */
    @Override
    void digest(Context ctx, Digest digest) {
        ServerLevel level = ((CraftWorld) ctx.world).getHandle();
        CRC32 crc = new CRC32();
        for (int probe = 0; probe < 4; probe++) {
            int chunkX = (X >> 4) - 64 - 8 * probe, chunkZ = (Z >> 4) - 64;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    level.getChunk(chunkX + dx, chunkZ + dz, ChunkStatus.FEATURES, true);
                }
            }
            ChunkSnapshot snapshot = ctx.world.getChunkAt(chunkX, chunkZ).getChunkSnapshot();
            for (int y = ctx.world.getMinHeight(); y < ctx.world.getMaxHeight(); y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        crc.update(snapshot.getBlockType(x, y, z).ordinal());
                    }
                }
            }
        }
        digest.exact.put("worldgen_crc", crc.getValue());
        digest.stat("chunks_generated", generated - generatedAtStart);
        digest.stat("chunks_generated_total", generated);
        digest.stat("chunks_unloaded", unloaded - unloadedAtStart);
    }
}
