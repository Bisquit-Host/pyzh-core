package net.minecraft.server;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.purpurmc.purpur.PurpurWorldConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;

@LeafTest
class StonecutterPathfindingTest {
    @Test
    void enabledDamageKeepsLeafsPrecedenceOverLaterTagsButNotTrapdoors() throws Exception {
        var holder = Blocks.STONECUTTER.builtInRegistryHolder();
        var originalTags = holder.tags().toList();
        var bindTags = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
        bindTags.setAccessible(true);
        try {
            var cases = Map.of(BlockTags.FENCES, PathType.FENCE, BlockTags.FIRE, PathType.FIRE,
                BlockTags.SPELEOTHEMS, PathType.DAMAGE_CAUTIOUS, BlockTags.TRAPDOORS, PathType.TRAPDOOR);
            for (var entry : cases.entrySet()) {
                var tags = new HashSet<>(originalTags);
                tags.add(entry.getKey());
                bindTags.invoke(holder, tags);
                refreshStonecutterTags();
                assertEquals(entry.getValue(), context(0.0F, new PathTypeCache()).getPathTypeFromState(0, 0, 0));
                assertEquals(entry.getValue() == PathType.TRAPDOOR ? PathType.TRAPDOOR : PathType.DAMAGING,
                    context(1.0F, new PathTypeCache()).getPathTypeFromState(0, 0, 0));
            }
        } finally {
            bindTags.invoke(holder, originalTags);
            refreshStonecutterTags();
        }
    }

    private static void refreshStonecutterTags() {
        for (var state : Blocks.STONECUTTER.getStateDefinition().getPossibleStates()) {
            state.initTagFlag();
            state.initPathType();
        }
    }

    @Test
    void enabledStonecutterDamageDoesNotLoadUnloadedNodes() throws Exception {
        PathfindingContext context = context(1.0F, new PathTypeCache());
        ServerLevel level = (ServerLevel) context.level();
        Mockito.doReturn(null).when(level).getBlockStateIfLoaded(any());
        assertEquals(PathType.BLOCKED, context.getPathTypeFromState(4, 2, 3));
        Mockito.verify(level, Mockito.never()).getBlockState(any());
    }

    @Test
    void cachedAndUncachedPathsUseWorldDamageWithoutContaminatingOtherWorlds() throws Exception {
        for (boolean cached : new boolean[] {false, true}) {
            PathTypeCache cache = cached ? new PathTypeCache() : null;
            PathfindingContext safe = context(0.0F, cache);
            PathfindingContext dangerous = context(1.0F, cache);
            for (int repeat = 0; repeat < 2; repeat++) {
                assertEquals(PathType.BLOCKED, safe.getPathTypeFromState(0, 0, 0));
                assertEquals(PathType.DAMAGING, dangerous.getPathTypeFromState(0, 0, 0));
                assertEquals(PathType.WALKABLE, WalkNodeEvaluator.checkNeighbourBlocks(safe, 1, 0, 0, PathType.WALKABLE));
                assertEquals(PathType.DAMAGING_IN_NEIGHBOR, WalkNodeEvaluator.checkNeighbourBlocks(dangerous, 1, 0, 0, PathType.WALKABLE));
            }
        }
    }

    private static PathfindingContext context(float damage, PathTypeCache cache) throws Exception {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        PurpurWorldConfig config = Mockito.mock(PurpurWorldConfig.class);
        config.stonecutterDamage = damage;
        Field field = Level.class.getDeclaredField("purpurConfig");
        field.setAccessible(true);
        field.set(level, config);
        Mockito.when(level.getPathTypeCache()).thenReturn(cache);
        Mockito.when(level.getBlockStateIfLoaded(any())).thenAnswer(invocation ->
            invocation.getArgument(0).equals(BlockPos.ZERO) ? Blocks.STONECUTTER.defaultBlockState() : Blocks.AIR.defaultBlockState());
        Mockito.when(level.getBlockState(any())).thenAnswer(invocation -> level.getBlockStateIfLoaded(invocation.getArgument(0)));
        Mob mob = Mockito.mock(Mob.class);
        Mockito.when(mob.level()).thenReturn(level);
        Mockito.when(mob.blockPosition()).thenReturn(BlockPos.ZERO);
        return new PathfindingContext(level, mob);
    }
}
