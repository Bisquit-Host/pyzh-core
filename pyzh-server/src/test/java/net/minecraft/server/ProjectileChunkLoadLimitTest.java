package net.minecraft.server;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.support.environment.LeafTest;
import org.galemc.gale.configuration.GaleWorldConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@LeafTest
class ProjectileChunkLoadLimitTest {
    @Test
    void defaultCapsDelegateMovementWithoutConsultingChunks() {
        final ServerLevel world = world();
        final var caps = world.galeConfig().smallOptimizations.maxProjectileChunkLoads;
        assertEquals(-1, caps.perTick);
        assertEquals(-1, caps.perProjectile.max);
        final Projectile projectile = projectile(world);

        projectile.setPos(1000, 70, 0);

        Mockito.verify(projectile).setPosRaw(1000, 70, 0, true);
        Mockito.verify(world, Mockito.never()).getChunkSource();
        Mockito.verify(world, Mockito.never()).tryReserveProjectileChunkLoad(anyInt());
    }

    @Test
    void perTickBudgetHonorsExactLimitsAndResetsIndependentlyForEachWorld() {
        final int previousTick = MinecraftServer.currentTick;
        try {
            MinecraftServer.currentTick = 100;
            final ServerLevel first = world();
            final ServerLevel second = world();
            assertFalse(first.tryReserveProjectileChunkLoad(0));
            assertTrue(first.tryReserveProjectileChunkLoad(2));
            assertTrue(first.tryReserveProjectileChunkLoad(2));
            assertFalse(first.tryReserveProjectileChunkLoad(2));
            assertTrue(second.tryReserveProjectileChunkLoad(1));
            assertFalse(second.tryReserveProjectileChunkLoad(1));
            MinecraftServer.currentTick++;
            assertTrue(first.tryReserveProjectileChunkLoad(1));
            assertFalse(first.tryReserveProjectileChunkLoad(1));
            assertTrue(second.tryReserveProjectileChunkLoad(1));
        } finally {
            MinecraftServer.currentTick = previousTick;
        }
    }

    @Test
    void lifetimeCapStopsAtItsLimitWithoutConsumingOtherProjectilesBudget() {
        final ServerLevel world = world();
        final var caps = world.galeConfig().smallOptimizations.maxProjectileChunkLoads;
        caps.perTick = 2;
        caps.perProjectile.max = 1;
        final ServerChunkCache chunks = Mockito.mock(ServerChunkCache.class);
        Mockito.when(world.getChunkSource()).thenReturn(chunks);
        final Projectile first = projectile(world);
        final Projectile second = projectile(world);

        first.setPos(32, 70, 0);
        first.setPos(48, 70, 0);
        second.setPos(32, 70, 0);
        second.setPos(48, 70, 0);

        Mockito.verify(first).setPosRaw(32, 70, 0, true);
        Mockito.verify(first, Mockito.never()).setPosRaw(48, 70, 0, true);
        Mockito.verify(second).setPosRaw(32, 70, 0, true);
        Mockito.verify(second, Mockito.never()).setPosRaw(48, 70, 0, true);
    }

    @Test
    void zeroTickCapBlocksUnloadedChunksButAllowsAlreadyLoadedChunks() {
        final ServerLevel world = world();
        world.galeConfig().smallOptimizations.maxProjectileChunkLoads.perTick = 0;
        final ServerChunkCache chunks = Mockito.mock(ServerChunkCache.class);
        Mockito.when(world.getChunkSource()).thenReturn(chunks);
        final Projectile projectile = projectile(world);

        projectile.setPos(32, 70, 0);
        Mockito.verify(projectile, Mockito.never()).setPosRaw(anyDouble(), anyDouble(), anyDouble(), anyBoolean());
        Mockito.when(chunks.getChunkAtIfLoadedImmediately(2, 0)).thenReturn(Mockito.mock(LevelChunk.class));
        projectile.setPos(32, 70, 0);
        Mockito.verify(projectile).setPosRaw(32, 70, 0, true);
    }

    private static ServerLevel world() {
        final ServerLevel world = Mockito.mock(ServerLevel.class);
        final GaleWorldConfiguration config = new GaleWorldConfiguration(null, null);
        config.smallOptimizations = config.new SmallOptimizations();
        config.smallOptimizations.maxProjectileChunkLoads = config.smallOptimizations.new MaxProjectileChunkLoads();
        final var caps = config.smallOptimizations.maxProjectileChunkLoads;
        caps.perProjectile = caps.new PerProjectile();
        Mockito.when(world.galeConfig()).thenReturn(config);
        Mockito.doCallRealMethod().when(world).tryReserveProjectileChunkLoad(anyInt());
        return world;
    }

    private static Projectile projectile(final ServerLevel world) {
        final Projectile projectile = Mockito.mock(Projectile.class);
        Mockito.when(projectile.level()).thenReturn(world);
        Mockito.doCallRealMethod().when(projectile).setPos(anyDouble(), anyDouble(), anyDouble());
        return projectile;
    }
}
