package net.minecraft.world.entity.npc.wanderingtrader;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.function.IntPredicate;

@LeafTest
public class WanderingTraderSpawnerTest {

    @Test
    public void findsInteriorFloorBelowCeiling() {
        final LevelReader level = levelWithSolidBlocks(y -> y == 2 || y == 8 || y == 9);
        final BlockPos.MutableBlockPos spawnPos = new BlockPos.MutableBlockPos(0, 10, 0);

        Assertions.assertTrue(WanderingTraderSpawner.findSpawnBelowCeiling(level, spawnPos));
        Assertions.assertEquals(3, spawnPos.getY());
    }

    @Test
    public void rejectsAirOnlyColumnWithinBounds() {
        final LevelReader level = levelWithSolidBlocks(y -> false);
        final BlockPos.MutableBlockPos spawnPos = new BlockPos.MutableBlockPos(0, 10, 0);

        Assertions.assertFalse(WanderingTraderSpawner.findSpawnBelowCeiling(level, spawnPos));
    }

    @Test
    public void rejectsFullySolidColumnWithinBounds() {
        final LevelReader level = levelWithSolidBlocks(y -> true);
        final BlockPos.MutableBlockPos spawnPos = new BlockPos.MutableBlockPos(0, 10, 0);

        Assertions.assertFalse(WanderingTraderSpawner.findSpawnBelowCeiling(level, spawnPos));
    }

    private static LevelReader levelWithSolidBlocks(final IntPredicate solidAtY) {
        final LevelReader level = Mockito.mock(LevelReader.class);
        Mockito.when(level.getMinY()).thenReturn(0);
        Mockito.when(level.getMaxY()).thenReturn(10);
        Mockito.when(level.getBlockState(Mockito.any(BlockPos.class))).thenAnswer(invocation -> {
            final BlockPos pos = invocation.getArgument(0);
            return solidAtY.test(pos.getY()) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });
        return level;
    }
}
