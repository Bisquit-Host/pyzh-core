package net.minecraft.server;

import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.async.AsyncPlayerDataSaving;
import org.dreeam.leaf.config.modules.async.AsyncPlayerDataSave;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

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
    @TempDir java.nio.file.Path directory;

    @Test
    public void coordinatesPlayerAndLevelPersistenceWithoutForegroundBlocking() throws Exception {
        final boolean previousEnabled = AsyncPlayerDataSave.enabled;
        final CountDownLatch samePlayerRelease = new CountDownLatch(1);
        final CountDownLatch otherPlayerRelease = new CountDownLatch(1);
        final CountDownLatch failedPlayerRelease = new CountDownLatch(1);
        final CountDownLatch shutdownPlayerRelease = new CountDownLatch(1);
        final CountDownLatch shutdownLevelRelease = new CountDownLatch(1);
        final CountDownLatch jsonRelease = new CountDownLatch(1);
        final CountDownLatch compressionRelease = new CountDownLatch(1);
        Thread shutdownThread = null;

        try {
            AsyncPlayerDataSave.enabled = true;
            AsyncPlayerDataSaving.init();

            final UUID samePlayer = UUID.randomUUID();
            final CountDownLatch samePlayerStarted = new CountDownLatch(1);
            final List<Integer> saveOrder = new CopyOnWriteArrayList<>();
            final CompletableFuture<Void> firstSave = AsyncPlayerDataSaving.submitPlayer(samePlayer, playerValue(1), data -> {
                samePlayerStarted.countDown();
                await(samePlayerRelease);
                saveOrder.add(data.getIntOr("v", 0));
            });
            Assertions.assertTrue(samePlayerStarted.await(5L, TimeUnit.SECONDS));

            final CompoundTag secondSnapshot = playerValue(2);
            final long submissionStart = System.nanoTime();
            final CompletableFuture<Void> secondSave = AsyncPlayerDataSaving.submitPlayer(samePlayer, secondSnapshot, data -> saveOrder.add(data.getIntOr("v", 0)));
            final long submissionNanos = System.nanoTime() - submissionStart;
            Assertions.assertTrue(submissionNanos < TimeUnit.SECONDS.toNanos(2L));
            secondSnapshot.putInt("v", 99);
            final CompoundTag pendingSnapshot = AsyncPlayerDataSaving.getPendingPlayerData(samePlayer).orElseThrow();
            Assertions.assertEquals(playerValue(2), pendingSnapshot);
            pendingSnapshot.putInt("v", 88);
            Assertions.assertEquals(playerValue(2), AsyncPlayerDataSaving.getPendingPlayerData(samePlayer).orElseThrow());
            Assertions.assertFalse(secondSave.isDone());

            samePlayerRelease.countDown();
            firstSave.get(5L, TimeUnit.SECONDS);
            secondSave.get(5L, TimeUnit.SECONDS);
            Assertions.assertEquals(List.of(1, 2), saveOrder);

            final UUID blockedPlayer = UUID.randomUUID();
            final CountDownLatch otherPlayerStarted = new CountDownLatch(1);
            final CompletableFuture<Void> blockedSave = AsyncPlayerDataSaving.submitPlayer(blockedPlayer, playerValue(3), data -> {
                otherPlayerStarted.countDown();
                await(otherPlayerRelease);
            });
            Assertions.assertTrue(otherPlayerStarted.await(5L, TimeUnit.SECONDS));
            final AtomicReference<String> independentThread = new AtomicReference<>();
            final CompletableFuture<Void> independentSave = AsyncPlayerDataSaving.submitPlayer(UUID.randomUUID(), playerValue(4), data -> independentThread.set(Thread.currentThread().getName()));
            Assertions.assertFalse(independentSave.isDone());
            otherPlayerRelease.countDown();
            blockedSave.get(5L, TimeUnit.SECONDS);
            independentSave.get(5L, TimeUnit.SECONDS);
            Assertions.assertTrue(independentThread.get().startsWith("Leaf Player IO Thread-"));

            // Exercise the real .dat writer while I/O is blocked: reloads see the owned NBT snapshot,
            // then the same data is compressed to disk without retaining the caller's mutable tag.
            final CountDownLatch compressionBlocked = new CountDownLatch(1);
            final var compressionBlocker = AsyncPlayerDataSaving.submitPlayer(UUID.randomUUID(), playerValue(0), data -> {
                compressionBlocked.countDown();
                await(compressionRelease);
            });
            Assertions.assertTrue(compressionBlocked.await(5L, TimeUnit.SECONDS));
            final LevelStorageSource.LevelStorageAccess storageAccess = Mockito.mock(LevelStorageSource.LevelStorageAccess.class);
            Mockito.when(storageAccess.getLevelPath(LevelResource.PLAYER_DATA_DIR)).thenReturn(this.directory);
            final PlayerDataStorage storage = new PlayerDataStorage(storageAccess, Mockito.mock(com.mojang.datafixers.DataFixer.class));
            final UUID storedPlayer = UUID.randomUUID();
            final CompoundTag original = playerValue(21);
            final CompoundTag nested = playerValue(22);
            original.put("nested", nested);
            storage.save("save-test", storedPlayer, storedPlayer.toString(), original);
            original.putInt("v", 99);
            nested.putInt("v", 99);
            final CompoundTag owned = AsyncPlayerDataSaving.getPendingPlayerData(storedPlayer).orElseThrow();
            Assertions.assertEquals(21, owned.getIntOr("v", 0));
            Assertions.assertEquals(22, owned.getCompoundOrEmpty("nested").getIntOr("v", 0));
            final java.nio.file.Path storedFile = this.directory.resolve(storedPlayer + ".dat");
            Assertions.assertFalse(java.nio.file.Files.exists(storedFile));

            final com.mojang.datafixers.DataFixer advancementFixer = Mockito.mock(com.mojang.datafixers.DataFixer.class);
            Mockito.doAnswer(invocation -> invocation.getArgument(1)).when(advancementFixer).update(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
            final ServerAdvancementManager advancementManager = Mockito.mock(ServerAdvancementManager.class);
            Mockito.when(advancementManager.tree()).thenReturn(new net.minecraft.advancements.AdvancementTree());
            final net.minecraft.advancements.Advancement advancement = Mockito.mock(net.minecraft.advancements.Advancement.class);
            Mockito.when(advancement.requirements()).thenReturn(net.minecraft.advancements.AdvancementRequirements.allOf(List.of("a")));
            final var holder = new net.minecraft.advancements.AdvancementHolder(net.minecraft.resources.Identifier.fromNamespaceAndPath("pyzh", "pending_save"), advancement);
            Mockito.when(advancementManager.get(holder.id())).thenReturn(holder);
            final java.nio.file.Path advancementFile = this.directory.resolve("advancements.json");
            final var advancementPlayer = Mockito.mock(net.minecraft.server.level.ServerPlayer.class);
            final var playerList = Mockito.mock(net.minecraft.server.players.PlayerList.class);
            final PlayerAdvancements advancements = new PlayerAdvancements(advancementFixer, playerList, advancementManager, advancementFile, advancementPlayer);
            final var progress = advancements.getOrStartProgress(holder);
            advancements.save();
            final java.lang.reflect.Field pendingSave = PlayerAdvancements.class.getDeclaredField("pyzh$pendingSave");
            pendingSave.setAccessible(true);
            final Object unchanged = pendingSave.get(advancements);
            advancements.save();
            Assertions.assertSame(unchanged, pendingSave.get(advancements), "unchanged pending progress must not be serialized or submitted again");
            progress.getCriterion("a").grant();
            advancements.save();
            progress.getCriterion("a").revoke();
            advancements.save();
            progress.getCriterion("a").grant();
            advancements.save();
            final CompletableFuture<?> newestAdvancements = (CompletableFuture<?>) pendingSave.get(advancements);
            final PlayerAdvancements rejoined = new PlayerAdvancements(advancementFixer, playerList, advancementManager, advancementFile, advancementPlayer);
            Assertions.assertTrue(rejoined.getOrStartProgress(holder).getCriterion("a").isDone(), "rejoin reads the newest coalesced tree");

            final var afterCompression = AsyncPlayerDataSaving.submitPlayer(UUID.randomUUID(), playerValue(0), data -> {});
            compressionRelease.countDown();
            compressionBlocker.get(5L, TimeUnit.SECONDS);
            afterCompression.get(5L, TimeUnit.SECONDS);
            Assertions.assertEquals(owned, NbtIo.readCompressed(storedFile, NbtAccounter.unlimitedHeap()));
            newestAdvancements.get(5L, TimeUnit.SECONDS);
            final var advancementMarker = java.nio.file.attribute.FileTime.fromMillis(1_000L);
            java.nio.file.Files.setLastModifiedTime(advancementFile, advancementMarker);
            advancements.save();
            Assertions.assertEquals(advancementMarker, java.nio.file.Files.getLastModifiedTime(advancementFile), "superseded completions must not dirty the newest saved version");

            final UUID failingPlayer = UUID.randomUUID();
            final CountDownLatch failedPlayerStarted = new CountDownLatch(1);
            final AtomicBoolean recoveryRan = new AtomicBoolean();
            final CompletableFuture<Void> failedSave = AsyncPlayerDataSaving.submitPlayer(failingPlayer, playerValue(5), data -> {
                failedPlayerStarted.countDown();
                await(failedPlayerRelease);
                throw new IllegalStateException("expected test failure");
            });
            Assertions.assertTrue(failedPlayerStarted.await(5L, TimeUnit.SECONDS));
            final CompletableFuture<Void> recoverySave = AsyncPlayerDataSaving.submitPlayer(failingPlayer, playerValue(6), data -> recoveryRan.set(true));
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
            final UUID shutdownPlayer = UUID.randomUUID();
            final CompletableFuture<Void> shutdownPlayerSave = AsyncPlayerDataSaving.submitPlayer(shutdownPlayer, playerValue(7), data -> {
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
            final AtomicReference<CompoundTag> lastShutdownSnapshot = new AtomicReference<>();
            final var supersededShutdownSave = AsyncPlayerDataSaving.submitPlayer(shutdownPlayer, playerValue(8), data -> Assertions.fail("Superseded snapshot must not be written"));
            final var lastShutdownSave = AsyncPlayerDataSaving.submitPlayer(shutdownPlayer, playerValue(9), lastShutdownSnapshot::set);

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
                rejectedSave = AsyncPlayerDataSaving.submitPlayer(UUID.randomUUID(), playerValue(8), data -> {
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
            supersededShutdownSave.get(5L, TimeUnit.SECONDS);
            lastShutdownSave.get(5L, TimeUnit.SECONDS);
            shutdownLevelSave.get(5L, TimeUnit.SECONDS);
            shutdownThread.join(5_000L);
            Assertions.assertFalse(shutdownThread.isAlive());
            Assertions.assertTrue(shutdownPlayerCompleted.get());
            Assertions.assertTrue(shutdownLevelCompleted.get());
            Assertions.assertEquals(playerValue(9), lastShutdownSnapshot.get());

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
            compressionRelease.countDown();
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

    private static CompoundTag playerValue(final int value) {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("v", value);
        return tag;
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
