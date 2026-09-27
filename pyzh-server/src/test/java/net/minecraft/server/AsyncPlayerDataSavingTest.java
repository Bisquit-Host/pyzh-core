package net.minecraft.server;

import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.async.AsyncPlayerDataSaving;
import org.dreeam.leaf.config.modules.async.AsyncPlayerDataSave;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@LeafTest
public class AsyncPlayerDataSavingTest {

    @Test
    public void coordinatesPlayerAndLevelPersistenceWithoutForegroundBlocking() throws Exception {
        final boolean previousEnabled = AsyncPlayerDataSave.enabled;
        final CountDownLatch samePlayerRelease = new CountDownLatch(1);
        final CountDownLatch otherPlayerRelease = new CountDownLatch(1);
        final CountDownLatch failedPlayerRelease = new CountDownLatch(1);
        final CountDownLatch shutdownPlayerRelease = new CountDownLatch(1);
        final CountDownLatch shutdownLevelRelease = new CountDownLatch(1);
        final CountDownLatch jsonRelease = new CountDownLatch(1);
        Thread shutdownThread = null;

        try {
            AsyncPlayerDataSave.enabled = true;
            AsyncPlayerDataSaving.init();

            final UUID samePlayer = UUID.randomUUID();
            final CountDownLatch samePlayerStarted = new CountDownLatch(1);
            final List<Integer> saveOrder = new CopyOnWriteArrayList<>();
            final CompletableFuture<Void> firstSave = AsyncPlayerDataSaving.submitPlayer(samePlayer, new byte[]{1}, data -> {
                samePlayerStarted.countDown();
                await(samePlayerRelease);
                saveOrder.add((int) data[0]);
            });
            Assertions.assertTrue(samePlayerStarted.await(5L, TimeUnit.SECONDS));

            final byte[] secondSnapshot = new byte[]{2};
            final long submissionStart = System.nanoTime();
            final CompletableFuture<Void> secondSave = AsyncPlayerDataSaving.submitPlayer(samePlayer, secondSnapshot, data -> saveOrder.add((int) data[0]));
            final long submissionNanos = System.nanoTime() - submissionStart;
            Assertions.assertTrue(submissionNanos < TimeUnit.SECONDS.toNanos(2L));
            secondSnapshot[0] = 99;
            final byte[] pendingSnapshot = AsyncPlayerDataSaving.getPendingPlayerData(samePlayer).orElseThrow();
            Assertions.assertArrayEquals(new byte[]{2}, pendingSnapshot);
            pendingSnapshot[0] = 88;
            Assertions.assertArrayEquals(new byte[]{2}, AsyncPlayerDataSaving.getPendingPlayerData(samePlayer).orElseThrow());
            Assertions.assertFalse(secondSave.isDone());

            samePlayerRelease.countDown();
            firstSave.get(5L, TimeUnit.SECONDS);
            secondSave.get(5L, TimeUnit.SECONDS);
            Assertions.assertEquals(List.of(1, 2), saveOrder);

            final UUID blockedPlayer = UUID.randomUUID();
            final CountDownLatch otherPlayerStarted = new CountDownLatch(1);
            final CompletableFuture<Void> blockedSave = AsyncPlayerDataSaving.submitPlayer(blockedPlayer, new byte[]{3}, data -> {
                otherPlayerStarted.countDown();
                await(otherPlayerRelease);
            });
            Assertions.assertTrue(otherPlayerStarted.await(5L, TimeUnit.SECONDS));
            final AtomicReference<String> independentThread = new AtomicReference<>();
            final CompletableFuture<Void> independentSave = AsyncPlayerDataSaving.submitPlayer(UUID.randomUUID(), new byte[]{4}, data -> independentThread.set(Thread.currentThread().getName()));
            Assertions.assertFalse(independentSave.isDone());
            otherPlayerRelease.countDown();
            blockedSave.get(5L, TimeUnit.SECONDS);
            independentSave.get(5L, TimeUnit.SECONDS);
            Assertions.assertTrue(independentThread.get().startsWith("Leaf Player IO Thread-"));

            final UUID failingPlayer = UUID.randomUUID();
            final CountDownLatch failedPlayerStarted = new CountDownLatch(1);
            final AtomicBoolean recoveryRan = new AtomicBoolean();
            final CompletableFuture<Void> failedSave = AsyncPlayerDataSaving.submitPlayer(failingPlayer, new byte[]{5}, data -> {
                failedPlayerStarted.countDown();
                await(failedPlayerRelease);
                throw new IllegalStateException("expected test failure");
            });
            Assertions.assertTrue(failedPlayerStarted.await(5L, TimeUnit.SECONDS));
            final CompletableFuture<Void> recoverySave = AsyncPlayerDataSaving.submitPlayer(failingPlayer, new byte[]{6}, data -> recoveryRan.set(true));
            failedPlayerRelease.countDown();
            final ExecutionException saveFailure = Assertions.assertThrows(ExecutionException.class, () -> failedSave.get(5L, TimeUnit.SECONDS));
            Assertions.assertInstanceOf(IllegalStateException.class, saveFailure.getCause());
            recoverySave.get(5L, TimeUnit.SECONDS);
            Assertions.assertTrue(recoveryRan.get());

            final AtomicReference<String> levelThread = new AtomicReference<>();
            AsyncPlayerDataSaving.submitLevel(() -> levelThread.set(Thread.currentThread().getName())).get(5L, TimeUnit.SECONDS);
            Assertions.assertEquals("Leaf Level IO Thread", levelThread.get());

            final java.nio.file.Path statsFile = java.nio.file.Path.of("stats", "json-order-test.json");
            final CountDownLatch jsonStarted = new CountDownLatch(1);
            final List<Integer> jsonWrites = new CopyOnWriteArrayList<>();
            final CompletableFuture<Void> firstJson = AsyncPlayerDataSaving.submitJson(statsFile, jsonValue(1), json -> {
                jsonStarted.countDown();
                await(jsonRelease);
                jsonWrites.add(json.getAsJsonObject().get("v").getAsInt());
            });
            Assertions.assertTrue(jsonStarted.await(5L, TimeUnit.SECONDS));
            final CompletableFuture<Void> secondJson = AsyncPlayerDataSaving.submitJson(statsFile.toAbsolutePath(), jsonValue(2), json -> jsonWrites.add(json.getAsJsonObject().get("v").getAsInt()));
            final com.google.gson.JsonElement pendingJson = AsyncPlayerDataSaving.getPendingJson(statsFile).orElseThrow();
            Assertions.assertEquals(2, pendingJson.getAsJsonObject().get("v").getAsInt());
            pendingJson.getAsJsonObject().addProperty("v", 88);
            Assertions.assertEquals(2, AsyncPlayerDataSaving.getPendingJson(statsFile).orElseThrow().getAsJsonObject().get("v").getAsInt());
            jsonRelease.countDown();
            firstJson.get(5L, TimeUnit.SECONDS);
            secondJson.get(5L, TimeUnit.SECONDS);
            Assertions.assertEquals(List.of(1, 2), jsonWrites);
            Assertions.assertTrue(AsyncPlayerDataSaving.getPendingJson(statsFile).isEmpty());

            AsyncPlayerDataSave.enabled = false;
            final AtomicBoolean syncJsonWritten = new AtomicBoolean();
            AsyncPlayerDataSaving.submitJson(statsFile, jsonValue(3), json -> syncJsonWritten.set(true));
            Assertions.assertTrue(syncJsonWritten.get(), "disabled async saving writes before returning");
            Assertions.assertTrue(AsyncPlayerDataSaving.getPendingJson(statsFile).isEmpty());
            AsyncPlayerDataSave.enabled = true;

            final CountDownLatch shutdownPlayerStarted = new CountDownLatch(1);
            final CountDownLatch shutdownLevelStarted = new CountDownLatch(1);
            final AtomicBoolean shutdownPlayerCompleted = new AtomicBoolean();
            final AtomicBoolean shutdownLevelCompleted = new AtomicBoolean();
            final CompletableFuture<Void> shutdownPlayerSave = AsyncPlayerDataSaving.submitPlayer(UUID.randomUUID(), new byte[]{7}, data -> {
                shutdownPlayerStarted.countDown();
                await(shutdownPlayerRelease);
                shutdownPlayerCompleted.set(true);
            });
            final CompletableFuture<Void> shutdownLevelSave = AsyncPlayerDataSaving.submitLevel(() -> {
                shutdownLevelStarted.countDown();
                await(shutdownLevelRelease);
                shutdownLevelCompleted.set(true);
            });
            Assertions.assertTrue(shutdownPlayerStarted.await(5L, TimeUnit.SECONDS));
            Assertions.assertTrue(shutdownLevelStarted.await(5L, TimeUnit.SECONDS));

            final CountDownLatch shutdownEntered = new CountDownLatch(1);
            final CountDownLatch shutdownReturned = new CountDownLatch(1);
            shutdownThread = new Thread(() -> {
                shutdownEntered.countDown();
                AsyncPlayerDataSaving.shutdownAndAwait();
                shutdownReturned.countDown();
            }, "Async persistence shutdown test");
            shutdownThread.start();
            Assertions.assertTrue(shutdownEntered.await(5L, TimeUnit.SECONDS));

            CompletableFuture<Void> rejectedSave;
            // Poll cheaply rather than blocking for the whole budget in one iteration: a single
            // get() with the full deadline could consume it entirely, so on a slow machine the
            // loop never observed the executor transitioning into rejecting submissions.
            final long rejectionDeadline = System.nanoTime() + REJECTION_TIMEOUT.toNanos();
            do {
                rejectedSave = AsyncPlayerDataSaving.submitPlayer(UUID.randomUUID(), new byte[]{8}, data -> {
                });
                if (!rejectedSave.isCompletedExceptionally()) {
                    try {
                        rejectedSave.get(100L, TimeUnit.MILLISECONDS);
                    } catch (final TimeoutException | ExecutionException ignored) {
                        // Still draining, or already rejected; the loop condition decides.
                    }
                }
            } while (!rejectedSave.isCompletedExceptionally() && System.nanoTime() < rejectionDeadline);
            final CompletableFuture<Void> finalRejectedSave = rejectedSave;
            final ExecutionException rejection = Assertions.assertThrows(ExecutionException.class, () -> finalRejectedSave.get(5L, TimeUnit.SECONDS));
            Assertions.assertInstanceOf(RejectedExecutionException.class, rejection.getCause());
            Assertions.assertFalse(shutdownReturned.await(200L, TimeUnit.MILLISECONDS));

            shutdownPlayerRelease.countDown();
            shutdownLevelRelease.countDown();
            shutdownPlayerSave.get(5L, TimeUnit.SECONDS);
            shutdownLevelSave.get(5L, TimeUnit.SECONDS);
            shutdownThread.join(5_000L);
            Assertions.assertFalse(shutdownThread.isAlive());
            Assertions.assertTrue(shutdownPlayerCompleted.get());
            Assertions.assertTrue(shutdownLevelCompleted.get());

            final CompletableFuture<Void> rejectedLevelSave = AsyncPlayerDataSaving.submitLevel(() -> {
            });
            final ExecutionException levelRejection = Assertions.assertThrows(ExecutionException.class, () -> rejectedLevelSave.get(5L, TimeUnit.SECONDS));
            Assertions.assertInstanceOf(RejectedExecutionException.class, levelRejection.getCause());
        } finally {
            samePlayerRelease.countDown();
            otherPlayerRelease.countDown();
            failedPlayerRelease.countDown();
            shutdownPlayerRelease.countDown();
            shutdownLevelRelease.countDown();
            jsonRelease.countDown();
            if (shutdownThread == null || shutdownThread.isAlive()) {
                AsyncPlayerDataSaving.shutdownAndAwait();
            }
            if (shutdownThread != null) {
                shutdownThread.join(5_000L);
            }
            AsyncPlayerDataSave.enabled = previousEnabled;
        }
    }

    /**
     * How long the rejection loop may wait for the executor to start rejecting submissions.
     */
    private static final Duration REJECTION_TIMEOUT = Duration.ofSeconds(30L);

    /**
     * Deadlock guard for workers parked on a test latch. It must comfortably exceed the slowest
     * path the main thread can take while a worker is parked - notably {@link #REJECTION_TIMEOUT}
     * - otherwise the guard fires while the test is still making progress and reports a spurious
     * failure. A healthy run releases these latches in milliseconds; this bound is never reached.
     */
    private static final Duration LATCH_TIMEOUT = REJECTION_TIMEOUT.plusSeconds(30L);

    private static com.google.gson.JsonElement jsonValue(final int value) {
        final com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("v", value);
        return json;
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(LATCH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
