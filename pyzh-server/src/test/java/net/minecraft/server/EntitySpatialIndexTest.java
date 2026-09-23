package net.minecraft.server;

import java.util.Random;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.util.KDTree2D;
import org.dreeam.leaf.util.KDTree3D;
import org.dreeam.leaf.util.LeafConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class EntitySpatialIndexTest {
    @Test
    void matchesLinearSearchAcrossGrowingShrinkingAndEmptyPopulations() {
        final KDTree2D horizontal = new KDTree2D();
        final KDTree3D spatial = new KDTree3D();
        final double[][] coordinates = new double[3][257];
        final Random random = new Random(42);
        for (int size : new int[]{0, 1, 2, 3, 16, 127, 257, 31, 1, 0, 128}) {
            horizontal.clear();
            spatial.clear();
            for (int i = 0; i < size; i++) {
                for (double[] axis : coordinates) {
                    axis[i] = random.nextInt(33) - 16; // Includes duplicate positions and split ties.
                }
                horizontal.add(coordinates[0][i], coordinates[2][i]);
                spatial.add(coordinates[0][i], coordinates[1][i], coordinates[2][i]);
            }
            horizontal.build();
            spatial.build();
            assertEquals(size == 0, horizontal.isEmpty());
            assertEquals(size == 0, spatial.isEmpty());
            for (int query = 0; query < 100; query++) {
                final double x = random.nextInt(65) - 32;
                final double y = random.nextInt(65) - 32;
                final double z = random.nextInt(65) - 32;
                for (double limit : new double[]{0, 1, 32, 1024, Double.POSITIVE_INFINITY}) {
                    double expected2 = size == 0 ? Double.POSITIVE_INFINITY : limit;
                    double expected3 = expected2;
                    for (int i = 0; i < size; i++) {
                        expected2 = Math.min(expected2, squared2(coordinates[0][i] - x, coordinates[2][i] - z));
                        expected3 = Math.min(expected3, squared3(coordinates[0][i] - x, coordinates[1][i] - y, coordinates[2][i] - z));
                    }
                    assertEquals(expected2, horizontal.nearestSqr(x, z, limit));
                    assertEquals(expected3, spatial.nearestSqr(x, y, z, limit));
                    final int nearest2 = horizontal.nearestIdx(x, z, limit);
                    if (size == 0 || expected2 >= limit) {
                        assertEquals(-1, nearest2);
                    } else {
                        assertTrue(nearest2 >= 0 && nearest2 < size);
                        assertEquals(expected2, squared2(coordinates[0][nearest2] - x, coordinates[2][nearest2] - z));
                    }
                    final int nearest = spatial.nearestIdx(x, y, z, limit);
                    if (size == 0 || expected3 >= limit) {
                        assertEquals(-1, nearest);
                    } else {
                        assertTrue(nearest >= 0 && nearest < size);
                        assertEquals(expected3, squared3(coordinates[0][nearest] - x, coordinates[1][nearest] - y, coordinates[2][nearest] - z));
                    }
                }
            }
        }
    }

    private static double squared2(double x, double y) {
        return LeafConstants.ENABLE_FMA ? Math.fma(y, y, x * x) : x * x + y * y;
    }

    private static double squared3(double x, double y, double z) {
        return LeafConstants.ENABLE_FMA ? Math.fma(z, z, Math.fma(y, y, x * x)) : x * x + y * y + z * z;
    }
}
