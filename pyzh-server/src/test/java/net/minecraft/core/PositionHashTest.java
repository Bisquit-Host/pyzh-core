package net.minecraft.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@LeafTest
class PositionHashTest {
    @Test
    void usesVanillaHashForPositionsAndSectionsIncludingOverflow() {
        final int[][] coordinates = {
            {0, 0, 0}, {100, 64, 200}, {-301, -64, 702},
            {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE},
            {30_000_000, 320, -30_000_000}
        };
        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (final int[] xyz : coordinates) {
            final int expected = (xyz[1] + xyz[2] * 31) * 31 + xyz[0];
            assertEquals(expected, new Vec3i(xyz[0], xyz[1], xyz[2]).hashCode());
            assertEquals(expected, new BlockPos(xyz[0], xyz[1], xyz[2]).hashCode());
            assertEquals(expected, SectionPos.of(xyz[0], xyz[1], xyz[2]).hashCode());
            assertEquals(expected, mutable.set(xyz[0], xyz[1], xyz[2]).hashCode());
        }
    }

    @Test
    void preservesVanillaWireNeighborUpdateOrder() {
        assertNeighborOrder(new BlockPos(100, 64, 200), List.of(
            new BlockPos(100, 65, 200), new BlockPos(100, 64, 199),
            new BlockPos(99, 64, 200), new BlockPos(100, 64, 200),
            new BlockPos(100, 63, 200), new BlockPos(100, 64, 201), new BlockPos(101, 64, 200)
        ));
        assertNeighborOrder(new BlockPos(0, 64, 0), List.of(
            new BlockPos(0, 64, 0), new BlockPos(0, 63, 0), new BlockPos(0, 64, 1),
            new BlockPos(1, 64, 0), new BlockPos(0, 65, 0),
            new BlockPos(0, 64, -1), new BlockPos(-1, 64, 0)
        ));
    }

    private static void assertNeighborOrder(final BlockPos wire, final List<BlockPos> expected) {
        final Set<BlockPos> neighbors = new HashSet<>();
        neighbors.add(wire);
        for (final Direction direction : Direction.values()) {
            neighbors.add(wire.relative(direction));
        }
        assertEquals(expected, new ArrayList<>(neighbors));
    }
}
