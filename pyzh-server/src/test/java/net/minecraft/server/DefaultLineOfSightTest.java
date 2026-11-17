package net.minecraft.server;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.sensing.Sensing;
import org.bukkit.support.environment.LeafTest;
import org.galemc.gale.configuration.GaleGlobalConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@LeafTest
class DefaultLineOfSightTest {
    @Test
    void defaultCacheExpiresEverySensingTick() throws Exception {
        final var instance = GaleGlobalConfiguration.class.getDeclaredField("instance");
        instance.setAccessible(true);
        final Object previousConfig = instance.get(null);
        final GaleGlobalConfiguration config = new GaleGlobalConfiguration();
        config.smallOptimizations = config.new SmallOptimizations();
        config.smallOptimizations.reducedIntervals = config.smallOptimizations.new ReducedIntervals();
        try {
            instance.set(null, config);
            final Mob mob = mock(Mob.class);
            final Entity target = mock(Entity.class);
            when(target.getId()).thenReturn(1);
            when(mob.hasLineOfSight(target)).thenReturn(true, false);
            final Sensing sensing = new Sensing(mob);

            assertTrue(sensing.hasLineOfSight(target));
            assertTrue(sensing.hasLineOfSight(target));
            verify(mob).hasLineOfSight(target);

            sensing.tick();
            assertFalse(sensing.hasLineOfSight(target));
            assertFalse(sensing.hasLineOfSight(target));
            verify(mob, times(2)).hasLineOfSight(target);
        } finally {
            instance.set(null, previousConfig);
        }
    }
}
