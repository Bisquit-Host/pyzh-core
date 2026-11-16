package net.minecraft.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import ru.turbovadim.pyzh.io.CoalescingSaveQueue;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class CoalescingSaveQueueTest {
    @Test
    void retainsNewestSnapshotAndCompletesSupersededSavesOnlyAfterItsWrite() {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final CoalescingSaveQueue<String, Integer> queue = new CoalescingSaveQueue<>(tasks::add);
        final List<Integer> written = new ArrayList<>();
        final var first = queue.submit("player", 1, written::add);
        final var second = queue.submit("player", 2, written::add);
        final var third = queue.submit("player", 3, written::add);
        final var other = queue.submit("other", 4, written::add);
        final var drained = queue.drain();
        assertEquals(3, queue.latest("player").orElseThrow());
        assertEquals(2, tasks.size(), "Only one task per key is queued");

        tasks.removeFirst().run();
        assertTrue(first.isDone());
        assertFalse(second.isDone());
        assertFalse(third.isDone());
        assertFalse(drained.isDone());
        tasks.removeFirst().run();
        assertTrue(other.isDone());
        tasks.removeFirst().run();

        assertEquals(List.of(1, 4, 3), written);
        second.join();
        third.join();
        drained.join();
        assertTrue(queue.latest("player").isEmpty());
        assertTrue(tasks.isEmpty());
    }

    @Test
    void failedWriteDoesNotPreventNewerSnapshotAndCancellationDoesNotCancelPersistence() {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final CoalescingSaveQueue<String, Integer> queue = new CoalescingSaveQueue<>(tasks::add);
        final List<Integer> written = new ArrayList<>();
        final var failed = queue.submit("player", 1, value -> { throw new IllegalStateException("disk failure"); });
        final var cancelled = queue.submit("player", 2, written::add);
        final var latest = queue.submit("player", 3, written::add);
        cancelled.cancel(false);
        final var drained = queue.drain();

        tasks.removeFirst().run();
        assertTrue(failed.isCompletedExceptionally());
        assertFalse(drained.isDone());
        tasks.removeFirst().run();
        latest.join();
        drained.join();
        assertEquals(List.of(3), written);
        assertTrue(cancelled.isCancelled());
    }

    @Test
    void rejectedExecutorDoesNotLeavePendingReadsOrShutdownWaiting() {
        final CoalescingSaveQueue<String, Integer> queue = new CoalescingSaveQueue<>(task -> {
            throw new RejectedExecutionException("stopped");
        });
        final var rejected = queue.submit("player", 1, value -> fail("Writer must not run"));
        assertTrue(rejected.isCompletedExceptionally());
        assertTrue(queue.latest("player").isEmpty());
        queue.drain().join();
    }
}
