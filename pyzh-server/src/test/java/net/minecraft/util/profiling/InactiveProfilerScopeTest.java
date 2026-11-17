package net.minecraft.util.profiling;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@LeafTest
class InactiveProfilerScopeTest {
    @Test
    void inactiveScopeRetainsNestingChecksWithoutActivatingTheLookup() throws Exception {
        final Field field = Profiler.class.getDeclaredField("ACTIVE_PROFILER_COUNT");
        field.setAccessible(true);
        final AtomicInteger count = (AtomicInteger) field.get(null);
        final int before = count.get();
        try (final Profiler.Scope ignored = Profiler.use(InactiveProfiler.INSTANCE)) {
            assertEquals(before, count.get());
            assertSame(InactiveProfiler.INSTANCE, Profiler.get());
            assertThrows(IllegalStateException.class, () -> Profiler.use(InactiveProfiler.INSTANCE));
            final Thread other = new Thread(() -> {
                final ProfilerFiller active = mock(ProfilerFiller.class);
                doAnswer(invocation -> {
                    assertSame(active, Profiler.get());
                    return null;
                }).when(active).startTick();
                try (final Profiler.Scope activeScope = Profiler.use(active)) {
                    assertSame(active, Profiler.get());
                    assertEquals(before + 1, count.get());
                }
                verify(active).endTick();
            });
            final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            other.setUncaughtExceptionHandler((thread, error) -> failure.set(error));
            other.start();
            other.join();
            assertEquals(null, failure.get());
            assertEquals(before, count.get());
            assertSame(InactiveProfiler.INSTANCE, Profiler.get());
        }
        assertEquals(before, count.get());
    }
}
