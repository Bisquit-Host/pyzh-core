package net.minecraft.world.level.block;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.async.SparklyPaperParallelWorldTicking;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class RedstoneSignalStateTest {
    @Test
    void restoresSerialFlagAcrossNestedQueriesAndFailures() throws Exception {
        withParallelTicking(false, RedstoneSignalStateTest::checkNestedQueries);
    }

    @Test
    void restoresFallbackThreadLocalAcrossNestedQueriesAndFailures() throws Exception {
        withParallelTicking(true, RedstoneSignalStateTest::checkNestedQueries);
    }

    @Test
    void restoresWorldThreadFlagAcrossNestedQueriesAndFailures() throws Exception {
        withParallelTicking(true, () -> {
            final FutureTask<Void> task = new FutureTask<>(() -> {
                checkNestedQueries();
                final TickThread.ServerLevelTickThread thread = (TickThread.ServerLevelTickThread) Thread.currentThread();
                assertTrue(thread.redstoneWireShouldSignal);
                thread.redstoneWireShouldSignal = false;
                final Level level = Mockito.mock(Level.class);
                ((RedStoneWireBlock) Blocks.REDSTONE_WIRE).getBlockSignal(level, BlockPos.ZERO);
                assertFalse(thread.redstoneWireShouldSignal);
                return null;
            });
            new TickThread.ServerLevelTickThread(task, "redstone-signal-test").start();
            task.get(10, TimeUnit.SECONDS);
        });
    }

    private static void checkNestedQueries() {
        final RedStoneWireBlock wire = (RedStoneWireBlock) Blocks.REDSTONE_WIRE;
        final BlockState state = wire.defaultBlockState().setValue(RedStoneWireBlock.POWER, 15);
        final Level outer = Mockito.mock(Level.class);
        final Level inner = Mockito.mock(Level.class);
        final Level failing = Mockito.mock(Level.class);
        final IllegalStateException failure = new IllegalStateException("neighbor signal failure");
        Mockito.when(inner.getBestNeighborSignal(BlockPos.ZERO)).thenAnswer(invocation -> {
            assertReaders(wire, state, inner, false);
            return 7;
        });
        Mockito.when(failing.getBestNeighborSignal(BlockPos.ZERO)).thenAnswer(invocation -> {
            assertReaders(wire, state, failing, false);
            throw failure;
        });
        Mockito.when(outer.getBestNeighborSignal(BlockPos.ZERO)).thenAnswer(invocation -> {
            assertReaders(wire, state, outer, false);
            assertEquals(7, wire.getBlockSignal(inner, BlockPos.ZERO));
            assertReaders(wire, state, outer, false);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> wire.getBlockSignal(failing, BlockPos.ZERO)));
            assertReaders(wire, state, outer, false);
            return 13;
        });
        assertReaders(wire, state, outer, true);
        assertEquals(13, wire.getBlockSignal(outer, BlockPos.ZERO));
        assertReaders(wire, state, outer, true);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> wire.getBlockSignal(failing, BlockPos.ZERO)));
        assertReaders(wire, state, outer, true);
    }

    private static void assertReaders(final RedStoneWireBlock wire, final BlockState state, final Level level, final boolean enabled) {
        assertEquals(enabled, wire.isSignalSource(state));
        assertEquals(enabled ? 15 : 0, wire.getSignal(state, level, BlockPos.ZERO, Direction.UP));
        assertEquals(enabled ? 15 : 0, wire.getDirectSignal(state, level, BlockPos.ZERO, Direction.UP));
    }

    private static void withParallelTicking(final boolean enabled, final CheckedRunnable action) throws Exception {
        final boolean previous = SparklyPaperParallelWorldTicking.enabled;
        try {
            SparklyPaperParallelWorldTicking.enabled = enabled;
            action.run();
        } finally {
            SparklyPaperParallelWorldTicking.enabled = previous;
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
