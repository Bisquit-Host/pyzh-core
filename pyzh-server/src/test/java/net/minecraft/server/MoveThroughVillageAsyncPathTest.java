package net.minecraft.server;

import java.lang.reflect.Field;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MoveThroughVillageGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.async.path.AsyncPath;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class MoveThroughVillageAsyncPathTest {
    @Test
    void defersUnfinishedSearchAndConsumesItWithoutCallingTheBlockingProcessor() throws Exception {
        final PathfinderMob mob = Mockito.mock(PathfinderMob.class);
        final GroundPathNavigation navigation = Mockito.mock(GroundPathNavigation.class);
        Mockito.when(navigation.canNavigateGround()).thenReturn(true);
        Mockito.when(mob.getNavigation()).thenReturn(navigation);
        Mockito.when(mob.level()).thenReturn(Mockito.mock(ServerLevel.class));
        final MoveThroughVillageGoal goal = new MoveThroughVillageGoal(mob, 1.0, false, 1, () -> false);
        final AsyncPath path = Mockito.mock(AsyncPath.class);
        final Field pending = MoveThroughVillageGoal.class.getDeclaredField("pendingPath");
        pending.setAccessible(true);
        pending.set(goal, path);

        assertFalse(goal.canUse());
        assertSame(path, pending.get(goal));
        Mockito.verify(path, Mockito.never()).process();

        Mockito.when(path.isProcessed()).thenReturn(true);
        assertTrue(goal.canUse());
        assertNull(pending.get(goal));
        Mockito.verify(path, Mockito.never()).process();
    }
}
