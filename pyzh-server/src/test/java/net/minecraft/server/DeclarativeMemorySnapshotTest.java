package net.minecraft.server;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.kinds.IdF;
import com.mojang.datafixers.util.Pair;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.OneShot;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.behavior.declarative.MemoryAccessor;
import net.minecraft.world.entity.ai.behavior.declarative.MemoryCondition;
import net.minecraft.world.entity.ai.memory.MemoryMap;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemorySlot;
import net.minecraft.world.entity.schedule.Activity;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class DeclarativeMemorySnapshotTest {
    private static final MemoryModuleType<Long> VALUE = MemoryModuleType.LAST_SLEPT;
    private static final MemoryModuleType<Long> OTHER = MemoryModuleType.LAST_WOKEN;

    @Test
    void optionalSnapshotsFollowIdentityAndExpiry() {
        MemorySlot<String> slot = MemorySlot.create();
        String first = new String("same");
        String second = new String("same");
        slot.set(first);
        Optional<String> old = slot.optionalValue();
        slot.set(second, 0L);
        assertSame(second, slot.optionalValue().orElseThrow());
        assertSame(first, old.orElseThrow());
        slot.tick();
        assertEquals(Optional.empty(), slot.optionalValue());
        slot.set(first, 1L);
        slot.tick();
        assertSame(first, slot.optionalValue().orElseThrow());
        slot.tick();
        assertEquals(Optional.empty(), slot.optionalValue());
        slot.set(second);
        slot.clear();
        assertEquals(Optional.empty(), slot.optionalValue());
    }

    @Test
    void randomizedSelectionAndLifecycleMatchUncachedAccessors() {
        assertEquals(run(false), run(true));
    }

    private static List<String> run(boolean optimized) {
        List<String> trace = new ArrayList<>();
        Random random = new Random(823749L);
        ServerLevel level = Mockito.mock(ServerLevel.class);
        for (int entity = 0; entity < 4; entity++) {
            TestBrain brain = new TestBrain(entity != 3);
            LivingEntity body = Mockito.mock(LivingEntity.class);
            Mockito.when(body.getBrain()).thenReturn((Brain) brain);
            Mockito.when(body.level()).thenReturn(level);
            for (int kind = 0; kind < 3; kind++) {
                int id = kind;
                OneShot<LivingEntity> behavior = optimized ? declarative(kind, trace) : reference(kind, trace);
                BehaviorControl<LivingEntity> recording = new BehaviorControl<>() {
                    @Override public Behavior.Status getStatus() { return behavior.getStatus(); }
                    @Override public Set<MemoryModuleType<?>> getRequiredMemories() { return Set.of(); }
                    @Override public boolean tryStart(ServerLevel l, LivingEntity b, long t) {
                        boolean result = behavior.tryStart(l, b, t);
                        trace.add(t + ":start:" + id + ":" + result);
                        return result;
                    }
                    @Override public void tickOrStop(ServerLevel l, LivingEntity b, long t) {
                        trace.add(t + ":tick:" + id);
                        behavior.tickOrStop(l, b, t);
                        trace.add(t + ":stop:" + id);
                    }
                    @Override public void doStop(ServerLevel l, LivingEntity b, long t) { behavior.doStop(l, b, t); }
                    @Override public String debugString() { return "recording"; }
                };
                brain.addActivity(Activity.CORE, ImmutableList.of(Pair.of(kind, recording)), Set.of(), Set.of());
            }
            for (int tick = 0; tick < 1000; tick++) {
                int operation = random.nextInt(7);
                if (operation == 0) brain.eraseMemory(VALUE);
                if (operation == 1) brain.clearMemories();
                if (operation == 2) brain.setMemoryWithExpiry(VALUE, (long) random.nextInt(8), random.nextInt(3) - 1);
                if (operation == 3) brain.setMemory(VALUE, Long.valueOf(random.nextInt(8)));
                if (operation == 4) brain.setMemory(OTHER, (long) random.nextInt(8));
                if (operation == 5) brain.eraseMemory(OTHER);
                Mockito.when(level.getGameTime()).thenReturn((long) tick);
                brain.tick(level, body);
                trace.add("memory:" + brain.getMemoryInternal(VALUE) + ":" + brain.getMemoryInternal(OTHER));
            }
            brain.stopAll(level, body);
        }
        return trace;
    }

    private static OneShot<LivingEntity> declarative(int kind, List<String> trace) {
        if (kind == 0) return BehaviorBuilder.create(i -> i.group(i.present(VALUE), i.registered(OTHER)).apply(i,
            (value, other) -> (level, body, time) -> {
                trace.add("present:" + i.get(value) + ":" + i.tryGet(other));
                value.set(i.get(value) + 1);
                return true;
            }));
        if (kind == 1) return BehaviorBuilder.create(i -> i.group(i.absent(VALUE)).apply(i,
            value -> (level, body, time) -> { value.setWithExpiry(5L, 0L); trace.add("absent"); return true; }));
        return BehaviorBuilder.create(i -> i.group(i.registered(VALUE)).apply(i,
            value -> (level, body, time) -> { trace.add("registered:" + i.tryGet(value)); return true; }));
    }

    private static OneShot<LivingEntity> reference(int kind, List<String> trace) {
        return new OneShot<>() {
            @Override public Set<MemoryModuleType<?>> getRequiredMemories() { return Set.of(VALUE); }
            @Override public boolean trigger(ServerLevel level, LivingEntity body, long time) {
                Brain<?> brain = body.getBrain();
                Optional<Long> value = brain.getMemoryInternal(VALUE);
                if (value == null) return false;
                if (kind == 0) {
                    var accessor = new MemoryCondition.Present<>(VALUE).createAccessor(brain, value);
                    if (accessor == null) return false;
                    Optional<Long> other = brain.getMemoryInternal(OTHER);
                    if (other == null) return false;
                    trace.add("present:" + IdF.get(accessor.value()) + ":" + other);
                    accessor.set(IdF.get(accessor.value()) + 1);
                } else if (kind == 1) {
                    var accessor = new MemoryCondition.Absent<>(VALUE).createAccessor(brain, value);
                    if (accessor == null) return false;
                    accessor.setWithExpiry(5L, 0L);
                    trace.add("absent");
                } else trace.add("registered:" + value);
                return true;
            }
        };
    }

    @Test
    void retainedAccessorsStayImmutableAndFollowBrainReplacement() {
        List<MemoryAccessor<IdF.Mu, Long>> snapshots = new ArrayList<>();
        OneShot<LivingEntity> behavior = BehaviorBuilder.create(i -> i.group(i.present(VALUE)).apply(i,
            value -> (level, body, time) -> { snapshots.add(value); return true; }));
        LivingEntity body = Mockito.mock(LivingEntity.class);
        TestBrain first = new TestBrain(true);
        TestBrain second = new TestBrain(true);
        Mockito.when(body.getBrain()).thenReturn((Brain) first);
        first.setMemory(VALUE, 1L);
        behavior.trigger(null, body, 0);
        behavior.trigger(null, body, 0);
        first.setMemory(VALUE, 2L);
        behavior.trigger(null, body, 0);
        Mockito.when(body.getBrain()).thenReturn((Brain) second);
        second.setMemory(VALUE, 2L);
        behavior.trigger(null, body, 0);
        assertEquals(List.of(1L, 1L, 2L, 2L), snapshots.stream().map(a -> IdF.get(a.value())).toList());
        snapshots.get(0).set(3L);
        snapshots.get(3).set(4L);
        assertEquals(Optional.of(3L), first.getMemory(VALUE));
        assertEquals(Optional.of(4L), second.getMemory(VALUE));
    }

    @Test
    void mutationBetweenMemoryReadsKeepsEarlierSnapshot() {
        TestBrain brain = new TestBrain(true);
        LivingEntity body = Mockito.mock(LivingEntity.class);
        Mockito.when(body.getBrain()).thenReturn((Brain) brain);
        List<Long> reads = new ArrayList<>();
        OneShot<LivingEntity> behavior = BehaviorBuilder.create(i -> i.group(
            i.present(VALUE),
            i.ifTriggered((level, entity, time) -> { brain.setMemory(VALUE, time); return true; }),
            i.present(VALUE)
        ).apply(i, (before, ignored, after) -> (level, entity, time) -> {
            reads.add(i.get(before));
            reads.add(i.get(after));
            return true;
        }));
        brain.setMemory(VALUE, 10L);
        assertTrue(behavior.trigger(null, body, 20L));
        assertTrue(behavior.trigger(null, body, 30L));
        assertEquals(List.of(10L, 20L, 20L, 30L), reads);
    }

    @Test
    void sharedNodeWritesOnlyToItsOwnBrainAcrossThreads() throws Exception {
        OneShot<LivingEntity> shared = sharedWriter();
        TestBrain first = new TestBrain(true);
        TestBrain second = new TestBrain(true);
        LivingEntity firstBody = body(first);
        LivingEntity secondBody = body(second);
        first.setMemory(VALUE, 11L);
        second.setMemory(VALUE, 22L);
        for (int tick = 0; tick < 100; tick++) {
            assertTrue(shared.trigger(null, firstBody, 11L));
            assertTrue(shared.trigger(null, secondBody, 22L));
            assertEquals(Optional.of(11L), first.getMemory(VALUE));
            assertEquals(Optional.of(22L), second.getMemory(VALUE));
        }

        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstRun = executor.submit(() -> runShared(shared, firstBody, first, 11L, barrier));
            var secondRun = executor.submit(() -> runShared(shared, secondBody, second, 22L, barrier));
            firstRun.get(30, TimeUnit.SECONDS);
            secondRun.get(30, TimeUnit.SECONDS);
        }
    }

    private static void runShared(OneShot<LivingEntity> shared, LivingEntity body, TestBrain brain, long value, CyclicBarrier barrier) {
        for (int tick = 0; tick < 10_000; tick++) {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                assertTrue(shared.trigger(null, body, value));
                barrier.await(5, TimeUnit.SECONDS);
                assertEquals(Optional.of(value), brain.getMemory(VALUE));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void sharedNodeRetainsNoBrainOrAccessorAfterUse() throws Exception {
        OneShot<LivingEntity> shared = sharedWriter();
        TestBrain removed = new TestBrain(true);
        removed.setMemory(VALUE, 11L);
        assertTrue(shared.trigger(null, body(removed), 11L));
        // Inspect the node's retained graph directly instead of depending on GC timing.
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<Object>();
        pending.add(shared);
        while (!pending.isEmpty()) {
            Object object = pending.removeFirst();
            if (!visited.add(object)) continue;
            assertFalse(object instanceof Brain<?>, "Shared behavior retains a brain");
            assertFalse(object instanceof MemoryAccessor<?, ?>, "Shared behavior retains an accessor");
            Class<?> type = object.getClass();
            if (type.isEnum()) continue;
            if (!type.getName().startsWith("net.minecraft.world.entity.ai.behavior.") && !type.isSynthetic()) continue;
            for (Class<?> current = type; current != Object.class; current = current.getSuperclass()) {
                for (var field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    field.setAccessible(true);
                    Object retained = field.get(object);
                    if (retained != null) pending.add(retained);
                }
            }
        }
    }

    private static OneShot<LivingEntity> sharedWriter() {
        return BehaviorBuilder.create(i -> i.group(i.registered(VALUE)).apply(i,
            value -> (level, body, time) -> {
                assertEquals(Optional.of(time), i.tryGet(value));
                value.setWithExpiry(time, 1200L);
                return true;
            }));
    }

    private static LivingEntity body(TestBrain brain) {
        LivingEntity body = Mockito.mock(LivingEntity.class);
        Mockito.when(body.getBrain()).thenReturn((Brain) brain);
        return body;
    }

    private static final class TestBrain extends Brain<LivingEntity> {
        TestBrain(boolean registered) {
            super(registered ? List.of(VALUE, OTHER) : List.of(), List.of(), List.of(), MemoryMap.EMPTY, RandomSource.create(1));
        }
    }
}
