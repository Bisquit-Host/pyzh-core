package net.minecraft.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.async.path.AsyncPathProcessor;
import org.dreeam.leaf.config.modules.async.AsyncPathfinding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class AsyncPathProcessorTest {
    @Test
    void startsConfiguredWorkersBeforeTheQueueFillsAndReleasesIdleOnes() throws Exception {
        final ThreadPoolExecutor previousExecutor = AsyncPathProcessor.PATH_PROCESSING_EXECUTOR;
        final int previousThreads = AsyncPathfinding.asyncPathfindingMaxThreads;
        final int previousQueueSize = AsyncPathfinding.asyncPathfindingQueueSize;
        final int previousKeepalive = AsyncPathfinding.asyncPathfindingKeepalive;
        final CountDownLatch started = new CountDownLatch(4);
        final CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor executor = null;
        try {
            AsyncPathfinding.asyncPathfindingMaxThreads = 4;
            AsyncPathfinding.asyncPathfindingQueueSize = 1024;
            AsyncPathfinding.asyncPathfindingKeepalive = 0;
            AsyncPathProcessor.PATH_PROCESSING_EXECUTOR = null;
            AsyncPathProcessor.init();
            executor = AsyncPathProcessor.PATH_PROCESSING_EXECUTOR;
            assertTrue(executor.allowsCoreThreadTimeOut(), "Idle workers must not stay alive when keepalive is 0");
            for (int i = 0; i < 4; i++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(started.await(5, TimeUnit.SECONDS), "Configured workers should start while the queue has spare capacity");
            assertEquals(0, executor.getQueue().size());
        } finally {
            try {
                release.countDown();
                if (executor != null) {
                    executor.shutdown();
                    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                }
            } finally {
                AsyncPathProcessor.PATH_PROCESSING_EXECUTOR = previousExecutor;
                AsyncPathfinding.asyncPathfindingMaxThreads = previousThreads;
                AsyncPathfinding.asyncPathfindingQueueSize = previousQueueSize;
                AsyncPathfinding.asyncPathfindingKeepalive = previousKeepalive;
            }
        }
    }
}
