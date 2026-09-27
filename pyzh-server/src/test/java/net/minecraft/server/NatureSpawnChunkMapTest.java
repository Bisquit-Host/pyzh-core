package net.minecraft.server;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.world.ChunkCache;
import org.dreeam.leaf.world.NatureSpawnChunkMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@LeafTest
class NatureSpawnChunkMapTest {

    @Test
    void matchesCircularSpawnRangesAcrossNegativeCoordinatesDuplicatesAndPopulationChanges() throws Exception {
        final NatureSpawnChunkMap map = new NatureSpawnChunkMap();
        final LongArrayList[] centers = (LongArrayList[]) field("centersByRadius").get(map);
        final Long2LongOpenHashMap regions = (Long2LongOpenHashMap) field("regions").get(map);
        final Method build = NatureSpawnChunkMap.class.getDeclaredMethod("buildBfs", int.class);
        build.setAccessible(true);
        final Random random = new Random(42);
        final int[] boundaries = {-1875000, -17, -9, -8, -1, 0, 7, 8, 15, 16, 1875000};

        for (int population : new int[]{0, 1, 128, 17, 1, 0}) {
            final LongOpenHashSet expected = new LongOpenHashSet();
            for (int player = 0; player < population; player++) {
                for (int radius = 0; radius <= 8; radius++) {
                    final int x = boundaries[random.nextInt(boundaries.length)];
                    final int z = boundaries[random.nextInt(boundaries.length)];
                    final long center = CoordinateUtils.getChunkKey(x, z);
                    centers[radius].add(center);
                    centers[radius].add(center); // Duplicate players must not duplicate spawn candidates.
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            if (dx * dx + dz * dz <= radius * radius) {
                                expected.add(CoordinateUtils.getChunkKey(x + dx, z + dz));
                            }
                        }
                    }
                }
            }
            for (int radius = 0; radius <= 8; radius++) {
                final LongOpenHashSet expectedRadius = new LongOpenHashSet();
                for (long center : centers[radius]) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            if (dx * dx + dz * dz <= radius * radius) {
                                expectedRadius.add(CoordinateUtils.getChunkKey(CoordinateUtils.getChunkX(center) + dx, CoordinateUtils.getChunkZ(center) + dz));
                            }
                        }
                    }
                }
                regions.clear();
                build.invoke(map, radius);
                assertEquals(expectedRadius, unpack(regions), "radius " + radius);
            }
            regions.clear();
            for (int radius = 0; radius <= 8; radius++) {
                build.invoke(map, radius);
            }
            assertEquals(expected, unpack(regions), "combined radii");
            field("ready").setBoolean(map, true);
            map.clear();
            assertTrue(regions.isEmpty());
            for (LongArrayList center : centers) {
                assertTrue(center.isEmpty());
            }
        }
    }

    private static LongOpenHashSet unpack(final Long2LongOpenHashMap regions) {
        final LongOpenHashSet chunks = new LongOpenHashSet();
        regions.long2LongEntrySet().forEach(entry -> {
            final int baseX = CoordinateUtils.getChunkX(entry.getLongKey()) * 8;
            final int baseZ = CoordinateUtils.getChunkZ(entry.getLongKey()) * 8;
            for (int bit = 0; bit < 64; bit++) {
                if ((entry.getLongValue() & (1L << bit)) != 0) {
                    assertTrue(chunks.add(CoordinateUtils.getChunkKey(baseX + bit % 8, baseZ + bit / 8)));
                }
            }
        });
        return chunks;
    }

    @Test
    void collectsOnlyLoadedEntityTickingChunksIncludingZeroRegionAndBit63() throws Exception {
        final NatureSpawnChunkMap map = new NatureSpawnChunkMap();
        final LongArrayList[] centers = (LongArrayList[]) field("centersByRadius").get(map);
        for (int coordinate : new int[]{-1, 0, 7, 8}) {
            centers[0].add(CoordinateUtils.getChunkKey(coordinate, coordinate));
        }
        final Method build = NatureSpawnChunkMap.class.getDeclaredMethod("buildBfs", int.class);
        build.setAccessible(true);
        build.invoke(map, 0);

        final ChunkCache<LevelChunk> chunks = new ChunkCache<>(Thread.currentThread());
        final LevelChunk negative = chunk(FullChunkStatus.ENTITY_TICKING);
        final LevelChunk lastBit = chunk(FullChunkStatus.ENTITY_TICKING);
        chunks.put(CoordinateUtils.getChunkKey(-1, -1), negative);
        chunks.put(0L, chunk(FullChunkStatus.BLOCK_TICKING));
        chunks.put(CoordinateUtils.getChunkKey(7, 7), lastBit);
        chunks.put(CoordinateUtils.getChunkKey(20, 20), chunk(FullChunkStatus.ENTITY_TICKING));
        // The (8, 8) candidate is intentionally unloaded.
        final Object regions = field("regions").get(map);
        final Method collect = regions.getClass().getDeclaredMethod("collectSpawningChunks", ChunkCache.class, List.class);
        collect.setAccessible(true);
        final List<LevelChunk> result = new ArrayList<>();
        collect.invoke(regions, chunks, result);
        assertEquals(2, result.size());
        assertEquals(Set.of(negative, lastBit), Set.copyOf(result));
    }

    private static LevelChunk chunk(final FullChunkStatus status) {
        final LevelChunk chunk = Mockito.mock(LevelChunk.class);
        final NewChunkHolder holder = Mockito.mock(NewChunkHolder.class);
        Mockito.when(chunk.moonrise$getChunkHolder()).thenReturn(holder);
        Mockito.when(holder.getChunkStatus()).thenReturn(status);
        return chunk;
    }

    private static Field field(final String name) throws Exception {
        final Field field = NatureSpawnChunkMap.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
