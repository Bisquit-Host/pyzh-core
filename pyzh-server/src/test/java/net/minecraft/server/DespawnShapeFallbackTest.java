package net.minecraft.server;

import io.papermc.paper.configuration.WorldConfiguration;
import io.papermc.paper.configuration.type.DespawnRange;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.entity.EntityTickList;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.opt.OptimizeDespawn;
import org.dreeam.leaf.world.DespawnMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

@LeafTest
class DespawnShapeFallbackTest {
    @Test
    void cylinderFallsBackWithoutVisitingEntitiesWhileEllipsoidUsesTheOptimizer() {
        final boolean previous = OptimizeDespawn.enabled;
        try {
            OptimizeDespawn.enabled = true;
            final ServerLevel world = Mockito.mock(ServerLevel.class);
            final WorldConfiguration config = Mockito.mock(WorldConfiguration.class);
            config.entities = config.new Entities();
            config.entities.spawning = Mockito.mock(WorldConfiguration.Entities.Spawning.class);
            config.entities.spawning.despawnRanges = Map.of();
            config.entities.spawning.despawnRangeShape = DespawnRange.Shape.CYLINDER;
            Mockito.when(world.paperConfig()).thenReturn(config);
            Mockito.when(world.getDifficulty()).thenReturn(Difficulty.NORMAL);
            final EntityTickList entities = Mockito.mock(EntityTickList.class);
            final DespawnMap map = new DespawnMap();

            assertFalse(map.tick(world, entities));
            Mockito.verifyNoInteractions(entities);
            config.entities.spawning.despawnRangeShape = DespawnRange.Shape.ELLIPSOID;
            assertTrue(map.tick(world, entities));
            Mockito.verify(entities).forEach(any());
        } finally {
            OptimizeDespawn.enabled = previous;
        }
    }
}
