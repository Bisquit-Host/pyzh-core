package net.minecraft.server;

import io.papermc.paper.configuration.WorldConfiguration;
import io.papermc.paper.entity.activation.ActivationRange;
import io.papermc.paper.entity.activation.ActivationType;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.opt.DynamicActivationofBrain;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.spigotmc.SpigotWorldConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@LeafTest
class ActivationRangeIsolationTest {
    @Test
    void parallelWorldsCanQueryActivationRangesConcurrently() throws Exception {
        final boolean previousDab = DynamicActivationofBrain.enabled;
        try {
            DynamicActivationofBrain.enabled = false;
            final ServerLevel first = world();
            final ServerLevel second = world();
            final CyclicBarrier queries = new CyclicBarrier(2);
            Mockito.doReturn(List.of(player(0))).when(first).players();
            Mockito.doReturn(List.of(player(1000))).when(second).players();
            for (final ServerLevel world : List.of(first, second)) {
                Mockito.when(world.getEntities(isNull(Entity.class), any(AABB.class), any())).thenAnswer(invocation -> {
                    queries.await(5, TimeUnit.SECONDS);
                    return List.of();
                });
            }
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var firstPass = executor.submit(() -> ActivationRange.activateEntities(first));
                final var secondPass = executor.submit(() -> ActivationRange.activateEntities(second));
                firstPass.get(10, TimeUnit.SECONDS);
                secondPass.get(10, TimeUnit.SECONDS);
            }
        } finally {
            DynamicActivationofBrain.enabled = previousDab;
        }
    }

    @Test
    void nestedWorldActivationCannotOverwriteAnotherWorldsBoxes() throws Exception {
        final boolean previousDab = DynamicActivationofBrain.enabled;
        final int previousTick = MinecraftServer.currentTick;
        try {
            DynamicActivationofBrain.enabled = false;
            MinecraftServer.currentTick = 100;
            final ServerLevel first = world();
            final ServerLevel second = world();
            final ServerPlayer firstPlayer = player(0);
            final ServerPlayer secondPlayer = player(1000);
            Mockito.doReturn(List.of(firstPlayer)).when(first).players();
            Mockito.doReturn(List.of(secondPlayer)).when(second).players();
            final Entity target = target();
            Mockito.when(first.getEntities(isNull(Entity.class), any(AABB.class), any())).thenAnswer(invocation -> {
                ActivationRange.activateEntities(second);
                return List.of(target);
            });
            Mockito.when(second.getEntities(isNull(Entity.class), any(AABB.class), any())).thenReturn(List.of());

            ActivationRange.activateEntities(first);

            assertEquals(100, target.activatedTick);
            assertEquals(1, target.activatedPriority);
            Mockito.verify(first, Mockito.never()).resetActivationPrioritySet();
            Mockito.verify(target, Mockito.never()).getId();
        } finally {
            DynamicActivationofBrain.enabled = previousDab;
            MinecraftServer.currentTick = previousTick;
        }
    }

    @Test
    void dabKeepsMinimumPriorityAndReusesOnlyItsWorldsScratchSet() throws Exception {
        final boolean previousDab = DynamicActivationofBrain.enabled;
        final double previousStart = DynamicActivationofBrain.startDistanceSquared;
        final double previousMod = DynamicActivationofBrain.activationDistanceMod;
        final boolean previousTypeDab = EntityTypes.ITEM.dabEnabled;
        try {
            DynamicActivationofBrain.enabled = true;
            DynamicActivationofBrain.startDistanceSquared = 0;
            DynamicActivationofBrain.activationDistanceMod = 0;
            EntityTypes.ITEM.dabEnabled = true;
            final ServerLevel world = world();
            final ServerPlayer far = player(0);
            final ServerPlayer near = player(1);
            final Entity target = target();
            Mockito.doReturn(EntityTypes.ITEM).when(target).getType();
            Mockito.when(far.distanceToSqr(target)).thenReturn(16.0);
            Mockito.when(near.distanceToSqr(target)).thenReturn(4.0);
            Mockito.doReturn(List.of(far, near)).when(world).players();
            Mockito.when(world.getEntities(isNull(Entity.class), any(AABB.class), any())).thenReturn(List.of(target));

            ActivationRange.activateEntities(world);
            assertEquals(4, target.activatedPriority);
            final var scratchField = ServerLevel.class.getDeclaredField("activationPriorityReset");
            scratchField.setAccessible(true);
            final Object scratch = scratchField.get(world);
            Mockito.doReturn(List.of(far)).when(world).players();
            ActivationRange.activateEntities(world);
            assertEquals(16, target.activatedPriority);
            assertSame(scratch, world.resetActivationPrioritySet());
            assertNotSame(scratch, world().resetActivationPrioritySet());
        } finally {
            DynamicActivationofBrain.enabled = previousDab;
            DynamicActivationofBrain.startDistanceSquared = previousStart;
            DynamicActivationofBrain.activationDistanceMod = previousMod;
            EntityTypes.ITEM.dabEnabled = previousTypeDab;
        }
    }

    private static ServerLevel world() throws Exception {
        final ServerLevel world = Mockito.mock(ServerLevel.class);
        final SpigotWorldConfig spigot = Mockito.mock(SpigotWorldConfig.class);
        spigot.miscActivationRange = 16;
        spigot.simulationDistance = 10;
        final var spigotField = Level.class.getDeclaredField("spigotConfig");
        spigotField.setAccessible(true);
        spigotField.set(world, spigot);
        final var purpurField = Level.class.getDeclaredField("purpurConfig");
        purpurField.setAccessible(true);
        purpurField.set(world, Mockito.mock(org.purpurmc.purpur.PurpurWorldConfig.class));
        final WorldConfiguration paper = Mockito.mock(WorldConfiguration.class);
        paper.entities = paper.new Entities();
        paper.entities.markers = paper.entities.new Markers();
        Mockito.when(world.paperConfig()).thenReturn(paper);
        Mockito.when(world.getHeight()).thenReturn(384);
        Mockito.doCallRealMethod().when(world).resetActivationPrioritySet();
        return world;
    }

    private static ServerPlayer player(final double x) {
        final ServerPlayer player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getBoundingBox()).thenReturn(new AABB(x, 64, 0, x + 1, 66, 1));
        return player;
    }

    private static Entity target() throws Exception {
        final Entity target = Mockito.mock(Entity.class);
        final var activationType = Entity.class.getDeclaredField("activationType");
        activationType.setAccessible(true);
        activationType.set(target, ActivationType.MISC);
        target.activatedTick = -1;
        Mockito.when(target.getBoundingBox()).thenReturn(new AABB(1, 64, 0, 2, 65, 1));
        return target;
    }
}
