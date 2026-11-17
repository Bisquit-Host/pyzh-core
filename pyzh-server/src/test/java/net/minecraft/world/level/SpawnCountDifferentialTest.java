package net.minecraft.world.level;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import io.papermc.paper.configuration.WorldConfiguration;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.opt.OptimizeMobSpawning;
import org.dreeam.leaf.world.NatureSpawnChunkMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@LeafTest
class SpawnCountDifferentialTest {
    @Test
    void changingTheReusableStartDuringAnAttemptDoesNotChangeItsPositionsOrRandomCalls() throws Exception {
        final boolean optimized = OptimizeMobSpawning.enabled;
        try {
            OptimizeMobSpawning.enabled = false;
            final ServerLevel level = Mockito.mock(ServerLevel.class);
            final LevelChunk chunk = Mockito.mock(LevelChunk.class);
            Mockito.when(chunk.getBlockStateFinal(0, 64, 0)).thenReturn(Blocks.AIR.defaultBlockState());
            final ChunkGenerator generator = Mockito.mock(ChunkGenerator.class);
            final NatureSpawnChunkMap map = Mockito.mock(NatureSpawnChunkMap.class);
            setField(level, ServerLevel.class, "natureSpawnChunkMap", map);
            final BlockPos.MutableBlockPos reusable = new BlockPos.MutableBlockPos();
            final List<Vec3> positions = new ArrayList<>();
            Mockito.when(map.nearest(eq(level), anyDouble(), anyDouble(), anyDouble())).thenAnswer(call -> {
                positions.add(new Vec3(call.getArgument(1), call.getArgument(2), call.getArgument(3)));
                reusable.set(10000, -64, -10000);
                return Double.POSITIVE_INFINITY;
            });
            for (int seed = 0; seed < 25; seed++) {
                reusable.set(0, 64, 0);
                positions.clear();
                setField(level, Level.class, "random", RandomSource.create(seed));
                NaturalSpawner.spawnCategoryForPosition(MobCategory.MONSTER, level, chunk, reusable,
                    (type, pos, access) -> true, (mob, access) -> {}, 100, null, generator);
                final List<Vec3> actual = List.copyOf(positions);
                final long actualRandom = level.random.nextLong();
                positions.clear();
                setField(level, Level.class, "random", RandomSource.create(seed));
                NaturalSpawner.spawnCategoryForPosition(MobCategory.MONSTER, level, chunk, new BlockPos(0, 64, 0),
                    (type, pos, access) -> true, (mob, access) -> {}, 100, null, generator);
                assertEquals(positions, actual);
                assertEquals(level.random.nextLong(), actualRandom);
            }
        } finally {
            OptimizeMobSpawning.enabled = optimized;
        }
    }

    @Test
    void reusableStartPositionMatchesOriginalValuesAndRandomState() throws Exception {
        final Random random = new Random(0x1a2c47eL);
        final Level level = Mockito.mock(Level.class);
        final LevelChunk chunk = Mockito.mock(LevelChunk.class);
        Mockito.when(chunk.getHeight(eq(Heightmap.Types.WORLD_SURFACE), anyInt(), anyInt()))
            .thenAnswer(call -> 64 + Math.floorMod((int)call.getArgument(1) * 31 + (int)call.getArgument(2), 150));
        Mockito.when(level.getMinY()).thenReturn(-64);
        final BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
        for (int trial = 0; trial < 1000; trial++) {
            final long seed = random.nextLong();
            final ChunkPos chunkPos = new ChunkPos(random.nextInt(4_000_000) - 2_000_000, random.nextInt(4_000_000) - 2_000_000);
            Mockito.when(chunk.getPos()).thenReturn(chunkPos);
            final RandomSource old = RandomSource.create(seed);
            final int x = chunkPos.getMinBlockX() + old.nextInt(16);
            final int z = chunkPos.getMinBlockZ() + old.nextInt(16);
            final int y = Mth.randomBetweenInclusive(old, -64, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1);
            setField(level, Level.class, "random", RandomSource.create(seed));
            final BlockPos actual = NaturalSpawner.getRandomPosWithin(level, chunk, target);
            assertSame(target, actual);
            assertEquals(new BlockPos(x, y, z), actual);
            assertEquals(old.nextLong(), level.random.nextLong());
        }
    }

    @Test
    void matchesOriginalCountsAttributionAndChargeOrderAcrossWorldChanges() throws Exception {
        final Random random = new Random(0x5ab2d61L);
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        final var config = Mockito.mock(WorldConfiguration.class);
        config.entities = Mockito.mock(WorldConfiguration.Entities.class);
        config.entities.spawning = Mockito.mock(WorldConfiguration.Entities.Spawning.class);
        Mockito.when(level.paperConfig()).thenReturn(config);
        final ServerLevel otherLevel = Mockito.mock(ServerLevel.class);
        final var otherConfig = Mockito.mock(WorldConfiguration.class);
        otherConfig.entities = Mockito.mock(WorldConfiguration.Entities.class);
        otherConfig.entities.spawning = Mockito.mock(WorldConfiguration.Entities.Spawning.class);
        Mockito.when(otherLevel.paperConfig()).thenReturn(otherConfig);
        final ServerChunkCache source = Mockito.mock(ServerChunkCache.class);
        Mockito.when(level.getChunkSource()).thenReturn(source);
        final var loaded = new ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable<LevelChunk>();
        setField(source, ServerChunkCache.class, "fullChunks", loaded);
        final ChunkMap map = Mockito.mock(ChunkMap.class, Mockito.CALLS_REAL_METHODS);
        setField(source, ServerChunkCache.class, "chunkMap", map);
        setField(map, ChunkMap.class, "level", level);
        final NearbyPlayers nearby = Mockito.mock(NearbyPlayers.class);
        Mockito.when(level.moonrise$getNearbyPlayers()).thenReturn(nearby);
        final Map<Long, ReferenceList<ServerPlayer>> memberships = new HashMap<>();
        Mockito.when(nearby.getPlayers(any(ChunkPos.class), eq(NearbyPlayers.NearbyMapType.TICK_VIEW_DISTANCE)))
            .thenAnswer(call -> memberships.get(((ChunkPos)call.getArgument(0)).pack()));
        final List<ServerPlayer> players = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final ServerPlayer player = Mockito.mock(ServerPlayer.class);
            setField(player, ServerPlayer.class, "mobCounts", new int[ServerPlayer.MOBCATEGORY_TOTAL_ENUMS]);
            setField(player, ServerPlayer.class, "mobBackoffCounts", new int[ServerPlayer.MOBCATEGORY_TOTAL_ENUMS]);
            players.add(player);
        }
        final Biome biome = Mockito.mock(Biome.class);
        final MobSpawnSettings settings = Mockito.mock(MobSpawnSettings.class);
        Mockito.when(biome.getMobSettings()).thenReturn(settings);
        final EntityType<?>[] types = {EntityTypes.ZOMBIE, EntityTypes.COW, EntityTypes.BAT, EntityTypes.AXOLOTL,
            EntityTypes.SQUID, EntityTypes.COD, EntityTypes.GLOW_SQUID, EntityTypes.ITEM};
        for (int i = 0; i < types.length; i++) {
            if (i % 2 == 0) Mockito.when(settings.getMobSpawnCost(types[i])).thenReturn(new MobSpawnSettings.MobSpawnCost(20, i + 0.5));
        }
        final Map<Long, LevelChunk> chunks = new HashMap<>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                final LevelChunk chunk = Mockito.mock(LevelChunk.class);
                final ChunkPos pos = new ChunkPos(x, z);
                Mockito.when(chunk.getPos()).thenReturn(pos);
                Mockito.when(chunk.getLevel()).thenReturn(level);
                Mockito.when(chunk.getNoiseBiome(anyInt(), anyInt(), anyInt())).thenReturn(Holder.direct(biome));
                chunks.put(pos.pack(), chunk);
            }
        }
        final List<Entity> entities = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            final Entity entity = i % 5 == 0 ? Mockito.mock(Entity.class) : Mockito.mock(Mob.class);
            Mockito.<EntityType<?>>when(entity.getType()).thenReturn(types[i % types.length]);
            Mockito.when(entity.level()).thenReturn(level);
            entities.add(entity);
        }
        final List<SpawnReason> reasons = List.of(SpawnReason.NATURAL, SpawnReason.CHUNK_GEN, SpawnReason.SPAWNER, SpawnReason.CUSTOM);
        for (int trial = 0; trial < 100; trial++) {
            config.entities.spawning.countAllMobsForSpawning = random.nextBoolean();
            otherConfig.entities.spawning.countAllMobsForSpawning = !config.entities.spawning.countAllMobsForSpawning;
            config.entities.spawning.perPlayerMobSpawns = random.nextBoolean();
            final boolean countMobs = random.nextBoolean();
            for (final ServerPlayer player : players) Arrays.fill(player.mobCounts, 0);
            final Map<Long, LevelChunk> initiallyLoaded = new HashMap<>();
            for (final var entry : chunks.entrySet()) {
                if (random.nextBoolean()) {
                    loaded.put(entry.getKey(), entry.getValue());
                    initiallyLoaded.put(entry.getKey(), entry.getValue());
                }
                else loaded.remove(entry.getKey());
                final var inRange = new ReferenceList<>(new ServerPlayer[0]);
                for (final ServerPlayer player : players) if (random.nextBoolean()) inRange.add(player);
                memberships.put(entry.getKey(), inRange);
            }
            // Reassigning positions and membership each trial covers moves, unloads and world transfers.
            for (final Entity entity : entities) {
                final BlockPos pos = new BlockPos(random.nextInt(80) - 40, random.nextInt(384) - 64, random.nextInt(80) - 40);
                Mockito.when(entity.blockPosition()).thenReturn(pos);
                Mockito.when(entity.chunkPosition()).thenReturn(ChunkPos.containing(pos));
                Mockito.when(entity.level()).thenReturn(random.nextInt(9) == 0 ? otherLevel : level);
                entity.spawnReason = reasons.get(random.nextInt(reasons.size()));
                if (entity instanceof Mob mob) {
                    Mockito.when(mob.isPersistenceRequired()).thenReturn(random.nextInt(7) == 0);
                    Mockito.when(mob.requiresCustomPersistence()).thenReturn(random.nextInt(9) == 0);
                }
            }
            final var expectedCounts = new Object2IntOpenHashMap<MobCategory>();
            final int[][] expectedPlayerCounts = new int[players.size()][ServerPlayer.MOBCATEGORY_TOTAL_ENUMS];
            final PotentialCalculator expectedPotential = new PotentialCalculator();
            final List<String> expectedLocal = new ArrayList<>();
            for (int index = 0; index < entities.size(); index++) {
                final Entity entity = entities.get(index);
                if (index % 19 == 0) loaded.remove(ChunkPos.pack(entity.blockPosition()));
                if (entity instanceof Mob mob && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) continue;
                final MobCategory category = entity.getType().getCategory();
                if (category == MobCategory.MISC) continue;
                if (!entity.level().paperConfig().entities.spawning.countAllMobsForSpawning && entity.spawnReason != SpawnReason.NATURAL && entity.spawnReason != SpawnReason.CHUNK_GEN) continue;
                final LevelChunk chunk = loaded.get(ChunkPos.pack(entity.blockPosition()));
                if (chunk == null) continue;
                final var cost = settings.getMobSpawnCost(entity.getType());
                if (cost != null) expectedPotential.addCharge(entity.blockPosition(), cost.charge());
                if (entity instanceof Mob) expectedLocal.add(chunk.getPos() + ":" + category);
                expectedCounts.addTo(category, 1);
                if (countMobs && config.entities.spawning.perPlayerMobSpawns) {
                    final var inRange = memberships.get(entity.chunkPosition().pack());
                    for (int i = 0; i < inRange.size(); i++) {
                        expectedPlayerCounts[players.indexOf(inRange.getRawDataUnchecked()[i])][category.ordinal()]++;
                    }
                }
            }
            final List<String> actualLocal = new ArrayList<>();
            final LocalMobCapCalculator local = Mockito.mock(LocalMobCapCalculator.class);
            Mockito.doAnswer(call -> { actualLocal.add(call.getArgument(0) + ":" + call.getArgument(1)); return null; })
                .when(local).addMob(any(), any());
            for (final long key : chunks.keySet()) loaded.remove(key);
            initiallyLoaded.forEach(loaded::put);
            final Iterable<Entity> removingChunks = () -> new java.util.Iterator<>() {
                private int index;
                @Override
                public boolean hasNext() { return this.index < entities.size(); }
                @Override
                public Entity next() {
                    final Entity entity = entities.get(this.index);
                    if (this.index++ % 19 == 0) loaded.remove(ChunkPos.pack(entity.blockPosition()));
                    return entity;
                }
            };
            final NaturalSpawner.SpawnState state = NaturalSpawner.createStateForChunkSource(289, removingChunks, source, local, countMobs);
            assertEquals(expectedCounts, state.getMobCategoryCounts(), "global counts, trial " + trial);
            assertEquals(expectedLocal, actualLocal, "local cap update order, trial " + trial);
            for (int i = 0; i < players.size(); i++) assertArrayEquals(expectedPlayerCounts[i], players.get(i).mobCounts);
            final var field = NaturalSpawner.SpawnState.class.getDeclaredField("spawnPotential");
            field.setAccessible(true);
            final PotentialCalculator actualPotential = (PotentialCalculator)field.get(state);
            for (int i = 0; i < 10; i++) {
                final BlockPos pos = new BlockPos(random.nextInt(80) - 40, random.nextInt(384) - 64, random.nextInt(80) - 40);
                assertEquals(Double.doubleToLongBits(expectedPotential.getPotentialEnergyChange(pos, 1.25)),
                    Double.doubleToLongBits(actualPotential.getPotentialEnergyChange(pos, 1.25)), "charge accumulation order");
            }
            final LevelChunk chunk = chunks.values().iterator().next();
            final BlockPos spawnPos = new BlockPos(1000, 1000, 1000);
            assertTrue(state.test(EntityTypes.ZOMBIE, spawnPos, chunk));
            final Mob spawned = Mockito.mock(Mob.class);
            Mockito.<EntityType<?>>when(spawned.getType()).thenReturn(EntityTypes.ZOMBIE);
            Mockito.when(spawned.blockPosition()).thenReturn(spawnPos);
            state.run(spawned, chunk);
            expectedCounts.addTo(MobCategory.MONSTER, 1);
            expectedPotential.addCharge(spawnPos, 0.5);
            assertEquals(expectedCounts, state.getMobCategoryCounts());
            assertEquals(Double.doubleToLongBits(expectedPotential.getPotentialEnergyChange(BlockPos.ZERO, 1.25)),
                Double.doubleToLongBits(actualPotential.getPotentialEnergyChange(BlockPos.ZERO, 1.25)));
        }
    }

    private static void setField(final Object target, final Class<?> owner, final String name, final Object value) throws Exception {
        final var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
