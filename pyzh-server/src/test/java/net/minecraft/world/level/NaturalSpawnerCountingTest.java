package net.minecraft.world.level;

import io.papermc.paper.configuration.WorldConfiguration;
import io.papermc.paper.configuration.type.number.IntOr;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.opt.OptimizeMobSpawning;
import org.dreeam.leaf.config.modules.opt.ThrottleNaturalMobSpawning;
import org.dreeam.leaf.world.NatureSpawnChunkMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@LeafTest
class NaturalSpawnerCountingTest {
    @Test
    void countCaptureResolvesEntityAndBiomeStateBeforeWorkerAggregation() throws Exception {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        final WorldConfiguration config = Mockito.mock(WorldConfiguration.class);
        config.entities = Mockito.mock(WorldConfiguration.Entities.class);
        config.entities.spawning = Mockito.mock(WorldConfiguration.Entities.Spawning.class);
        Mockito.when(level.paperConfig()).thenReturn(config);
        final ServerChunkCache source = Mockito.mock(ServerChunkCache.class);
        Mockito.when(level.getChunkSource()).thenReturn(source);
        final var chunks = new ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable<LevelChunk>();
        setField(source, ServerChunkCache.class, "fullChunks", chunks);
        final LevelChunk chunk = Mockito.mock(LevelChunk.class);
        chunks.put(0L, chunk);
        final Biome biome = Mockito.mock(Biome.class);
        final MobSpawnSettings settings = Mockito.mock(MobSpawnSettings.class);
        Mockito.when(chunk.getNoiseBiome(anyInt(), anyInt(), anyInt())).thenReturn(Holder.direct(biome));
        Mockito.when(biome.getMobSettings()).thenReturn(settings);
        Mockito.when(settings.getMobSpawnCost(EntityTypes.ZOMBIE)).thenReturn(new MobSpawnSettings.MobSpawnCost(10.0, 2.0));
        final Mob mob = Mockito.mock(Mob.class);
        Mockito.<EntityType<?>>when(mob.getType()).thenReturn(EntityTypes.ZOMBIE);
        Mockito.when(mob.blockPosition()).thenReturn(new BlockPos(2, 64, 3));
        mob.spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL;
        final Mob persistent = Mockito.mock(Mob.class);
        Mockito.when(persistent.isPersistenceRequired()).thenReturn(true);
        final List<NaturalSpawner.SpawnCountInput> inputs = NaturalSpawner.captureSpawnCounts(List.of(mob, persistent), level);
        assertEquals(List.of(new NaturalSpawner.SpawnCountInput(new BlockPos(2, 64, 3), MobCategory.MONSTER, 0L, 2.0)), inputs);
        Mockito.when(mob.blockPosition()).thenReturn(new BlockPos(32, 0, 32));
        Mockito.clearInvocations(level, source, chunk, biome, settings, mob, persistent);
        final NaturalSpawner.SpawnState result = NaturalSpawner.createStateAsync(42, inputs);
        assertEquals(1, result.getMobCategoryCounts().getInt(MobCategory.MONSTER));
        Mockito.verifyNoInteractions(level, source, chunk, biome, settings, mob, persistent);
    }

    @Test
    void asyncAggregationOnlyNeedsCapturedValuesAndCopiesMutablePositions() {
        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(2, 64, 3);
        final NaturalSpawner.SpawnCountInput first = new NaturalSpawner.SpawnCountInput(mutable, MobCategory.MONSTER, 5L, 1.0);
        mutable.set(900, 0, 900);
        final NaturalSpawner.SpawnState state = NaturalSpawner.createStateAsync(42, List.of(first,
            new NaturalSpawner.SpawnCountInput(BlockPos.ZERO, MobCategory.MONSTER, 5L, 0.0),
            new NaturalSpawner.SpawnCountInput(BlockPos.ZERO, MobCategory.CREATURE, 9L, 0.0)));
        assertEquals(new BlockPos(2, 64, 3), first.pos());
        assertEquals(42, state.getSpawnableChunkCount());
        assertEquals(2, state.getMobCategoryCounts().getInt(MobCategory.MONSTER));
        assertEquals(1, state.getMobCategoryCounts().getInt(MobCategory.CREATURE));
        assertEquals(2, state.chunkCap.get(5L)[MobCategory.MONSTER.ordinal()]);
        assertEquals(1, state.chunkCap.get(9L)[MobCategory.CREATURE.ordinal()]);
    }

    @Test
    void defaultSpawningPreservesPaperRandomCallsAndItsZeroSizeGroup() throws Exception {
        final boolean previous = OptimizeMobSpawning.enabled;
        try {
            OptimizeMobSpawning.enabled = false;
            final ServerLevel level = Mockito.mock(ServerLevel.class);
            final RandomSource random = Mockito.mock(RandomSource.class);
            setField(level, Level.class, "random", random);
            final NatureSpawnChunkMap map = Mockito.mock(NatureSpawnChunkMap.class);
            setField(level, ServerLevel.class, "natureSpawnChunkMap", map);
            Mockito.when(map.nearest(eq(level), anyDouble(), anyDouble(), anyDouble())).thenReturn(Double.POSITIVE_INFINITY);
            final LevelChunk chunk = Mockito.mock(LevelChunk.class);
            Mockito.when(chunk.getBlockStateFinal(0, 64, 0)).thenReturn(Blocks.AIR.defaultBlockState());
            final ChunkGenerator generator = Mockito.mock(ChunkGenerator.class);
            NaturalSpawner.spawnCategoryForPosition(MobCategory.MONSTER, level, chunk, new BlockPos(0, 64, 0),
                (type, pos, access) -> true, (mob, access) -> {}, 100, null, generator);
            Mockito.verify(random, Mockito.times(3)).nextFloat();
            Mockito.verifyNoMoreInteractions(random);

            Mockito.clearInvocations(random);
            Mockito.when(random.nextFloat()).thenReturn(0.5F);
            NaturalSpawner.spawnCategoryForPosition(MobCategory.MONSTER, level, chunk, new BlockPos(0, 64, 0),
                (type, pos, access) -> true, (mob, access) -> {}, 100, null, generator);
            final var order = Mockito.inOrder(random);
            for (int group = 0; group < 3; group++) {
                order.verify(random).nextFloat();
                order.verify(random, Mockito.times(8)).nextInt(6);
            }
            order.verifyNoMoreInteractions();
        } finally {
            OptimizeMobSpawning.enabled = previous;
        }
    }

    @Test
    void failedAttemptsAreOnlyMaintainedForAnEnabledThrottle() throws Exception {
        final boolean previous = ThrottleNaturalMobSpawning.enabled;
        final long[] previousAttempts = ThrottleNaturalMobSpawning.failedAttempts;
        final int[] previousChances = ThrottleNaturalMobSpawning.spawnChance;
        try {
            ThrottleNaturalMobSpawning.enabled = false;
            final ServerLevel level = Mockito.mock(ServerLevel.class);
            final WorldConfiguration config = Mockito.mock(WorldConfiguration.class);
            config.entities = Mockito.mock(WorldConfiguration.Entities.class);
            config.entities.spawning = Mockito.mock(WorldConfiguration.Entities.Spawning.class);
            config.entities.spawning.spawningThrottle = Mockito.mock(WorldConfiguration.Entities.Spawning.SpawningThrottle.class);
            config.entities.spawning.spawningThrottle.failedAttemptsThreshold = IntOr.Disabled.DISABLED;
            Mockito.when(level.paperConfig()).thenReturn(config);
            assertFalse(NaturalSpawner.usesFailedSpawnAttempts(level));
            final ServerChunkCache source = Mockito.mock(ServerChunkCache.class);
            Mockito.when(level.getChunkSource()).thenReturn(source);
            Mockito.when(source.getGenerator()).thenReturn(Mockito.mock(ChunkGenerator.class));
            final RandomSource random = Mockito.mock(RandomSource.class);
            setField(level, Level.class, "random", random);
            final LevelChunk chunk = Mockito.mock(LevelChunk.class);
            Mockito.when(chunk.getPos()).thenReturn(new ChunkPos(0, 0));
            final long[] attempts = new long[ServerPlayer.MOBCATEGORY_TOTAL_ENUMS];
            attempts[MobCategory.MONSTER.ordinal()] = 7L;
            setField(chunk, ChunkAccess.class, "failedSpawnAttempts", attempts);
            final LocalMobCapCalculator caps = Mockito.mock(LocalMobCapCalculator.class);
            Mockito.when(caps.canSpawn(MobCategory.MONSTER, chunk.getPos())).thenReturn(true);
            final NaturalSpawner.SpawnState state = NaturalSpawner.createState(42, List.of(), (key, output) -> {}, caps);
            NaturalSpawner.spawnForChunk(level, chunk, state, List.of(MobCategory.MONSTER));
            assertEquals(7L, attempts[MobCategory.MONSTER.ordinal()]);

            ThrottleNaturalMobSpawning.enabled = true;
            ThrottleNaturalMobSpawning.failedAttempts = new long[ServerPlayer.MOBCATEGORY_TOTAL_ENUMS];
            java.util.Arrays.fill(ThrottleNaturalMobSpawning.failedAttempts, -1L);
            ThrottleNaturalMobSpawning.spawnChance = new int[ServerPlayer.MOBCATEGORY_TOTAL_ENUMS];
            assertTrue(NaturalSpawner.usesFailedSpawnAttempts(level));
            NaturalSpawner.spawnForChunk(level, chunk, state, List.of(MobCategory.MONSTER));
            assertEquals(8L, attempts[MobCategory.MONSTER.ordinal()]);
            ThrottleNaturalMobSpawning.enabled = false;
            config.entities.spawning.perPlayerMobSpawns = true;
            config.entities.spawning.spawningThrottle.failedAttemptsThreshold = new IntOr.Disabled(java.util.OptionalInt.of(8));
            assertTrue(NaturalSpawner.usesFailedSpawnAttempts(level));
        } finally {
            ThrottleNaturalMobSpawning.enabled = previous;
            ThrottleNaturalMobSpawning.failedAttempts = previousAttempts;
            ThrottleNaturalMobSpawning.spawnChance = previousChances;
        }
    }

    private static void setField(final Object object, final Class<?> owner, final String name, final Object value) throws Exception {
        final var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
