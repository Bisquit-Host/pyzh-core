package net.minecraft.server;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.util.FastBitRadixSort;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

@LeafTest
class StableEntityDistanceSortTest {
    @Test
    void mixedDistancesKeepEncounterOrderForTiesAndLeaveUnusedTailAlone() {
        FastBitRadixSort sorter = new FastBitRadixSort();
        double[][] cases = {
            {4, 1, 16, 4, 1, 9, 4, 1, 16, 9, 4, 1},
            {1, 1, 1, 1, 1, 1, 1, 1},
            {Double.NaN, 4, Double.POSITIVE_INFINITY, 1, 4, Double.NaN, 1, 9}
        };
        int[][] expectedIndices = {
            {1, 4, 7, 11, 0, 3, 6, 10, 5, 9, 2, 8},
            {0, 1, 2, 3, 4, 5, 6, 7},
            {3, 6, 1, 4, 7, 2, 0, 5}
        };
        for (int testCase = 0; testCase < cases.length; testCase++) {
            double[] distances = cases[testCase];
            Object[] actual = new Object[distances.length + 2];
            for (int i = 0; i < distances.length; i++) {
                Entity entity = Mockito.mock(Entity.class);
                Mockito.when(entity.distanceToSqr(0, 0, 0)).thenReturn(distances[i]);
                actual[i] = entity;
            }
            actual[distances.length] = new Object();
            Object[] expected = actual.clone();
            for (int i = 0; i < distances.length; i++) {
                expected[i] = actual[expectedIndices[testCase][i]];
            }
            sorter.sort(actual, distances.length, Vec3.ZERO);
            assertArrayEquals(expected, actual);
        }
    }
}
