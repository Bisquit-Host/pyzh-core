package net.minecraft.server;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ThreadedTicketLevelPropagator;
import ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue;
import ca.spottedleaf.concurrentutil.executor.queue.PrioritisedTaskQueue;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@LeafTest
class MidTickTasksTest {
    @Test
    void pendingCheckIncludesTicketsBothStatusQueuesAndBothTaskQueues() throws Exception {
        final ChunkHolderManager holders = mock(ChunkHolderManager.class, CALLS_REAL_METHODS);
        final ThreadedTicketLevelPropagator tickets = mock(ThreadedTicketLevelPropagator.class);
        final ArrayDeque<NewChunkHolder> full = new ArrayDeque<>();
        final MultiThreadedQueue<NewChunkHolder> offThread = new MultiThreadedQueue<>();
        set(holders, "ticketLevelPropagator", tickets);
        set(holders, "pendingFullLoadUpdate", full);
        set(holders, "offThreadPendingFullLoadUpdate", offThread);
        final ChunkTaskScheduler scheduler = mock(ChunkTaskScheduler.class, CALLS_REAL_METHODS);
        final PrioritisedTaskQueue tasks = new PrioritisedTaskQueue();
        final ServerLevel world = mock(ServerLevel.class);
        final ServerChunkCache chunks = mock(ServerChunkCache.class);
        final ServerChunkCache.MainThreadExecutor legacy = mock(ServerChunkCache.MainThreadExecutor.class);
        when(world.getChunkSource()).thenReturn(chunks);
        set(chunks, "mainThreadProcessor", legacy);
        set(scheduler, "world", world);
        set(scheduler, "chunkHolderManager", holders);
        set(scheduler, "mainThreadExecutor", tasks);
        assertFalse(scheduler.hasMidTickTasks());
        when(tickets.hasPendingUpdates()).thenReturn(true);
        assertTrue(scheduler.hasMidTickTasks());
        when(tickets.hasPendingUpdates()).thenReturn(false);
        final NewChunkHolder holder = mock(NewChunkHolder.class);
        full.add(holder);
        assertTrue(scheduler.hasMidTickTasks());
        full.clear();
        offThread.add(holder);
        assertTrue(scheduler.hasMidTickTasks());
        offThread.poll();
        when(legacy.hasPendingTasks()).thenReturn(true);
        assertTrue(scheduler.hasMidTickTasks());
        when(legacy.hasPendingTasks()).thenReturn(false);
        final var task = tasks.queueTask(() -> {});
        assertTrue(scheduler.hasMidTickTasks());
        task.cancel();
        assertFalse(scheduler.hasMidTickTasks());
    }

    @Test
    void preservesWorldOrderAndShortCircuitingAcrossWorldChanges() throws Exception {
        final MinecraftServer server = mock(MinecraftServer.class, CALLS_REAL_METHODS);
        final Field levels = MinecraftServer.class.getDeclaredField("levels");
        levels.setAccessible(true);
        levels.set(server, new LinkedHashMap<>());
        final List<ResourceKey<Level>> checked = new ArrayList<>();
        final List<ResourceKey<Level>> polled = new ArrayList<>();
        final ServerLevel overworld = world(Level.OVERWORLD, false, checked, polled);
        final ServerLevel nether = world(Level.NETHER, true, checked, polled);
        final ServerLevel end = world(Level.END, true, checked, polled);
        server.addLevel(overworld);
        server.addLevel(nether);
        server.addLevel(end);
        final Method tick = MinecraftServer.class.getDeclaredMethod("tickMidTickTasks");
        tick.setAccessible(true);
        assertTrue((boolean) tick.invoke(server));
        assertEquals(List.of(Level.OVERWORLD, Level.NETHER), checked);
        assertEquals(List.of(Level.NETHER), polled);

        server.removeLevel(nether);
        checked.clear();
        polled.clear();
        assertTrue((boolean) tick.invoke(server));
        assertEquals(List.of(Level.OVERWORLD, Level.END), checked);
        assertEquals(List.of(Level.END), polled);

        server.addLevel(nether);
        checked.clear();
        polled.clear();
        assertTrue((boolean) tick.invoke(server));
        assertEquals(List.of(Level.OVERWORLD, Level.END), checked);
        assertEquals(List.of(Level.END), polled);
    }

    private static ServerLevel world(final ResourceKey<Level> key, final boolean pending,
                                     final List<ResourceKey<Level>> checked, final List<ResourceKey<Level>> polled) {
        final ServerLevel world = mock(ServerLevel.class);
        final ServerChunkCache chunks = mock(ServerChunkCache.class);
        final ChunkTaskScheduler scheduler = mock(ChunkTaskScheduler.class);
        when(world.dimension()).thenReturn(key);
        when(world.getChunkSource()).thenReturn(chunks);
        when(world.moonrise$getChunkTaskScheduler()).thenReturn(scheduler);
        when(scheduler.hasMidTickTasks()).thenAnswer(invocation -> {
            checked.add(key);
            return pending;
        });
        when(chunks.pollTask()).thenAnswer(invocation -> {
            polled.add(key);
            return true;
        });
        return world;
    }

    private static void set(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
