package net.minecraft.util.thread;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@LeafTest
class BlockableEventLoopPendingTasksTest {
    @Test
    void presenceChecksNeverConsumeWorkDuringConcurrentEnqueue() throws Exception {
        final Thread owner = Thread.currentThread();
        final BlockableEventLoop<Runnable> loop = new BlockableEventLoop<>("pending-test", false) {
            @Override
            public Runnable wrapRunnable(final Runnable task) {
                return task;
            }

            @Override
            protected boolean shouldRun(final Runnable task) {
                return true;
            }

            @Override
            protected Thread getRunningThread() {
                return owner;
            }
        };
        final List<Integer> received = new ArrayList<>();
        final Thread producer = new Thread(() -> {
            for (int i = 0; i < 2000; i++) {
                final int index = i;
                loop.schedule(() -> received.add(index));
            }
        });
        assertFalse(loop.hasPendingTasks());
        producer.start();
        while (producer.isAlive() || loop.hasPendingTasks()) {
            if (loop.hasPendingTasks()) {
                loop.pollTask();
            } else {
                Thread.onSpinWait();
            }
        }
        producer.join();
        assertEquals(2000, received.size());
        for (int i = 0; i < received.size(); i++) {
            assertEquals(i, received.get(i));
        }
        assertFalse(loop.hasPendingTasks());
    }
}
