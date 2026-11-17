package net.minecraft.server;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.util.Util;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class StructureFutureSequencingTest {
    @Test
    void collectsInInputOrderOnTheCompletingThread() throws InterruptedException {
        final CompletableFuture<Integer> first = new CompletableFuture<>();
        final CompletableFuture<Integer> second = new CompletableFuture<>();
        final CompletableFuture<List<Integer>> result = Util.sequence(List.of(first, second), true);
        final AtomicReference<Thread> completionThread = new AtomicReference<>();
        final AtomicBoolean completedInline = new AtomicBoolean();
        final CompletableFuture<Void> observed = result.thenAccept(ignored -> completionThread.set(Thread.currentThread()));
        second.complete(2);
        assertFalse(result.isDone());
        final Thread worker = new Thread(() -> {
            first.complete(1);
            completedInline.set(observed.isDone());
        });
        worker.start();
        worker.join(5_000L);
        assertFalse(worker.isAlive());
        assertTrue(result.isDone());
        assertTrue(observed.isDone());
        assertTrue(completedInline.get(), "Collection must finish inline with the last input");
        assertEquals(List.of(1, 2), result.join());
        assertSame(worker, completionThread.get());
    }

    @Test
    void preservesFailuresAndEmptyAndSingleInputCases() {
        assertEquals(List.of(), Util.sequence(List.of(), true).join());
        assertEquals(List.of(1), Util.sequence(List.of(CompletableFuture.completedFuture(1)), true).join());
        final IllegalStateException failure = new IllegalStateException("failed input");
        final var result = Util.sequence(List.of(CompletableFuture.completedFuture(1), CompletableFuture.<Integer>failedFuture(failure)), true);
        assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
    }
}
