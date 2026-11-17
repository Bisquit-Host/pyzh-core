package net.minecraft.server.level;

import io.papermc.paper.configuration.WorldConfiguration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.async.AsyncMobSpawning;
import org.galemc.gale.configuration.GaleWorldConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.spigotmc.SpigotWorldConfig;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class ChunkSpawningTest {
    @Test
    void failedAsyncCountRecoversSynchronouslyAndCanConsumeTheNextResult() throws Exception {
        final boolean previous = AsyncMobSpawning.enabled;
        try {
            AsyncMobSpawning.enabled = true;
            final ServerLevel level = Mockito.mock(ServerLevel.class);
            final WorldConfiguration config = spawningConfig();
            config.entities.spawning.perPlayerMobSpawns = true;
            Mockito.when(level.paperConfig()).thenReturn(config);
            Mockito.when(level.players()).thenReturn(List.of());
            Mockito.when(level.getAllEntities()).thenReturn(List.of());
            Mockito.when(level.moonrise$getNearbyPlayers()).thenReturn(Mockito.mock(ca.spottedleaf.moonrise.common.misc.NearbyPlayers.class));
            final ServerChunkCache cache = Mockito.mock(ServerChunkCache.class, Mockito.CALLS_REAL_METHODS);
            cache.spawnEnemies = true;
            setField(cache, ServerChunkCache.class, "level", level);
            final var create = ServerChunkCache.class.getDeclaredMethod("createSpawnState", int.class);
            create.setAccessible(true);

            for (int attempt = 0; attempt < 2; attempt++) {
                setField(cache, ServerChunkCache.class, "pendingSpawnState", CompletableFuture.failedFuture(new IllegalStateException("count failed")));
                final NaturalSpawner.SpawnState recovered = (NaturalSpawner.SpawnState) create.invoke(cache, 42);
                assertEquals(42, recovered.getSpawnableChunkCount());
                assertTrue(recovered.getMobCategoryCounts().isEmpty());
            }

            final CompletableFuture<NaturalSpawner.SpawnState> pending = new CompletableFuture<>();
            setField(cache, ServerChunkCache.class, "pendingSpawnState", pending);
            assertNull(create.invoke(cache, 42));
            final NaturalSpawner.SpawnState result = NaturalSpawner.createStateAsync(42,
                List.of(new NaturalSpawner.SpawnCountInput(BlockPos.ZERO, MobCategory.MONSTER, 0L, 0.0)));
            pending.complete(result);
            assertSame(result, create.invoke(cache, 42));
            assertEquals(1, result.getMobCategoryCounts().getInt(MobCategory.MONSTER));
        } finally {
            AsyncMobSpawning.enabled = previous;
        }
    }

    @Test
    void unfinishedCountsStillAdvanceInhabitedTimeAndThunder() throws Exception {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        final ServerChunkCache cache = Mockito.mock(ServerChunkCache.class, Mockito.CALLS_REAL_METHODS);
        setField(cache, ServerChunkCache.class, "level", level);
        final LevelChunk chunk = Mockito.mock(LevelChunk.class);
        Mockito.when(chunk.getPos()).thenReturn(new ChunkPos(0, 0));
        final var tick = ServerChunkCache.class.getDeclaredMethod("tickSpawningChunk", LevelChunk.class, long.class, List.class, NaturalSpawner.SpawnState.class);
        tick.setAccessible(true);
        tick.invoke(cache, chunk, 20L, List.of(MobCategory.MONSTER), null);
        Mockito.verify(chunk).incrementInhabitedTime(20L);
        Mockito.verify(level).tickThunder(chunk);
        Mockito.verifyNoMoreInteractions(level);
    }

    @Test
    void lightningUsesAnIndependentRandomTrialUnlessCountdownIsEnabled() throws Exception {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        final WorldConfiguration config = spawningConfig();
        config.environment = Mockito.mock(WorldConfiguration.Environment.class);
        Mockito.when(level.paperConfig()).thenReturn(config);
        final SpigotWorldConfig spigot = Mockito.mock(SpigotWorldConfig.class);
        spigot.thunderChance = 17;
        setField(level, Level.class, "spigotConfig", spigot);
        final GaleWorldConfiguration gale = Mockito.mock(GaleWorldConfiguration.class);
        gale.smallOptimizations = Mockito.mock(GaleWorldConfiguration.SmallOptimizations.class);
        Mockito.when(level.galeConfig()).thenReturn(gale);
        final RandomSource random = Mockito.mock(RandomSource.class);
        setField(level, Level.class, "random", random);
        Mockito.when(random.nextInt(17)).thenReturn(1);
        Mockito.when(level.isRaining()).thenReturn(true);
        Mockito.when(level.isThundering()).thenReturn(true);
        Mockito.doCallRealMethod().when(level).tickThunder(Mockito.any());
        final LevelChunk chunk = Mockito.mock(LevelChunk.class);
        Mockito.when(chunk.getPos()).thenReturn(new ChunkPos(0, 0));
        level.tickThunder(chunk);
        level.tickThunder(chunk);
        Mockito.verify(random, Mockito.times(2)).nextInt(17);
        Mockito.verify(chunk, Mockito.never()).shouldDoLightning(Mockito.any());

        gale.smallOptimizations.lightningCountdown = true;
        level.tickThunder(chunk);
        Mockito.verify(chunk).shouldDoLightning(random);
        Mockito.verifyNoMoreInteractions(random);
        spigot.thunderChance = 0;
        level.tickThunder(chunk);
        Mockito.verify(chunk).shouldDoLightning(random);
    }

    @Test
    void lightningCountdownIsSeededOnFirstUseInsteadOfStriking() throws Exception {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        final SpigotWorldConfig spigot = Mockito.mock(SpigotWorldConfig.class);
        spigot.thunderChance = 17;
        setField(level, Level.class, "spigotConfig", spigot);
        final LevelChunk chunk = Mockito.mock(LevelChunk.class, Mockito.CALLS_REAL_METHODS);
        setField(chunk, LevelChunk.class, "level", level);
        setField(chunk, LevelChunk.class, "lightningTick", Integer.MIN_VALUE);
        final RandomSource random = Mockito.mock(RandomSource.class);
        Mockito.when(random.nextInt(17)).thenReturn(1);

        assertFalse(chunk.shouldDoLightning(random));
        assertFalse(chunk.shouldDoLightning(random));
        assertFalse(chunk.shouldDoLightning(random));
        assertTrue(chunk.shouldDoLightning(random));
        Mockito.verify(random, Mockito.times(2)).nextInt(17);
    }

    private static WorldConfiguration spawningConfig() {
        final WorldConfiguration config = Mockito.mock(WorldConfiguration.class);
        config.entities = Mockito.mock(WorldConfiguration.Entities.class);
        config.entities.spawning = Mockito.mock(WorldConfiguration.Entities.Spawning.class);
        return config;
    }

    private static void setField(final Object object, final Class<?> owner, final String name, final Object value) throws Exception {
        final var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
