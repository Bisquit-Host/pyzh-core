package net.minecraft.world.level;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class BlockInsideOrderTest {
    private record Visit(BlockPos pos, int step) {}

    @Test
    void stationaryBoxVisitsXThenYThenZAndStopsImmediately() {
        final AABB box = new AABB(0.1, 0.1, 0.1, 1.9, 1.9, 1.9);
        final List<Visit> visits = new ArrayList<>();
        assertFalse(BlockGetter.forEachBlockIntersectedBetween(Vec3.ZERO, Vec3.ZERO, box, (pos, step) -> {
            visits.add(new Visit(pos.immutable(), step));
            return visits.size() < 3;
        }));
        assertEquals(List.of(
            new Visit(new BlockPos(0, 0, 0), 0),
            new Visit(new BlockPos(1, 0, 0), 0),
            new Visit(new BlockPos(0, 1, 0), 0)
        ), visits);
    }

    @Test
    void movingBoxesMatchPaperOrderStepsAndEarlyExit() throws Exception {
        final List<Vec3> movements = List.of(
            new Vec3(3.25, 0, 0), new Vec3(-3.25, 0, 0),
            new Vec3(0, 3.25, 0), new Vec3(0, -3.25, 0),
            new Vec3(0, 0, 3.25), new Vec3(0, 0, -3.25),
            new Vec3(2.25, -1.75, 0.5), new Vec3(-2.25, 1.75, -0.5)
        );
        final List<AABB> boxes = List.of(
            new AABB(0.1, 0.1, 0.1, 1.9, 1.9, 1.9),
            new AABB(-1, -1, -1, 1, 1, 1),
            new AABB(-0.000001, 0, -0.000001, 0.6, 1.8, 0.6)
        );
        for (final Vec3 movement : movements) {
            for (final AABB box : boxes) {
                final List<Visit> expected = paperVisits(movement, box);
                final List<Visit> actual = new ArrayList<>();
                assertTrue(BlockGetter.forEachBlockIntersectedBetween(Vec3.ZERO, movement, box, (pos, step) -> {
                    actual.add(new Visit(pos.immutable(), step));
                    return true;
                }));
                assertEquals(expected, actual, () -> "movement=" + movement + ", box=" + box);
                actual.clear();
                final int stopAfter = Math.max(1, expected.size() / 2);
                assertFalse(BlockGetter.forEachBlockIntersectedBetween(Vec3.ZERO, movement, box, (pos, step) -> {
                    actual.add(new Visit(pos.immutable(), step));
                    return actual.size() < stopAfter;
                }));
                assertEquals(expected.subList(0, stopAfter), actual);
            }
        }
    }

    // Paper's start/sweep/target traversal, reusing its unchanged DDA rather than duplicating it in the test.
    private static List<Visit> paperVisits(final Vec3 movement, final AABB box) throws Exception {
        final List<Visit> visits = new ArrayList<>();
        final LongSet visited = new LongOpenHashSet();
        for (final BlockPos pos : BlockPos.betweenCornersInDirection(box.move(movement.scale(-1)), movement)) {
            visits.add(new Visit(pos.immutable(), 0));
            visited.add(pos.asLong());
        }
        final Method sweep = BlockGetter.class.getDeclaredMethod("addCollisionsAlongTravel",
            LongSet.class, Vec3.class, AABB.class, BlockGetter.BlockStepVisitor.class);
        sweep.setAccessible(true);
        final BlockGetter.BlockStepVisitor visitor = (pos, step) -> {
            visits.add(new Visit(pos.immutable(), step));
            return true;
        };
        final int steps = (int) sweep.invoke(null, visited, movement, box, visitor);
        for (final BlockPos pos : BlockPos.betweenCornersInDirection(box, movement)) {
            if (visited.add(pos.asLong())) visits.add(new Visit(pos.immutable(), steps + 1));
        }
        return visits;
    }
}
