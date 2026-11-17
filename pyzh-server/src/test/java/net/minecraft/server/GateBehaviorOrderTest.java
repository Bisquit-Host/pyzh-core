package net.minecraft.server;

import com.mojang.datafixers.util.Pair;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import net.minecraft.world.entity.ai.behavior.TriggerGate;
import net.minecraft.world.entity.ai.behavior.declarative.Trigger;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@LeafTest
class GateBehaviorOrderTest {
    @Test
    void triggerGateMatchesOriginalOrderAndShortCircuitForEveryPolicy() {
        for (var policy : GateBehavior.RunningPolicy.values()) {
            for (var order : GateBehavior.OrderPolicy.values()) {
                List<String> actual = new ArrayList<>();
                List<String> expected = new ArrayList<>();
                List<Pair<? extends Trigger<? super LivingEntity>, Integer>> entries = new ArrayList<>();
                for (int id = 0; id < 8; id++) {
                    int entry = id;
                    entries.add(Pair.of((level, body, time) -> {
                        actual.add(time + ":" + entry);
                        return (time + entry) % 3 == 0;
                    }, id + 1));
                }
                var gate = TriggerGate.triggerGate(entries, order, policy);
                for (long tick = 0; tick < 20; tick++) {
                    gate.trigger(null, null, tick);
                    // The original gate discards shuffle's copy and traverses insertion order.
                    for (int id = 0; id < 8; id++) {
                        expected.add(tick + ":" + id);
                        if ((tick + id) % 3 == 0 && policy == GateBehavior.RunningPolicy.RUN_ONE) break;
                    }
                }
                assertEquals(expected, actual);
            }
        }
    }

    @Test
    void reentrantShuffleStillFailsOnTheNextEntry() {
        ShufflingList<BehaviorControl<? super LivingEntity>> list = new ShufflingList<>(false);
        BehaviorControl<LivingEntity> behavior = Mockito.mock(BehaviorControl.class);
        Mockito.when(behavior.getStatus()).thenReturn(Behavior.Status.STOPPED);
        Mockito.when(behavior.tryStart(null, null, 0L)).thenAnswer(call -> { list.shuffle(); return false; });
        list.add(behavior, 1).add(behavior, 1);
        assertThrows(ConcurrentModificationException.class,
            () -> GateBehavior.RunningPolicy.TRY_ALL.apply(list, null, null, 0L));
    }

    @Test
    void gateLifecycleMatchesIteratorTraversalAndShuffleRandomState() throws Exception {
        for (var policy : GateBehavior.RunningPolicy.values()) {
            for (var order : GateBehavior.OrderPolicy.values()) {
                assertEquals(run(false, policy, order), run(true, policy, order));
            }
        }
    }

    private static List<String> run(boolean actual, GateBehavior.RunningPolicy policy, GateBehavior.OrderPolicy order) throws Exception {
        List<String> trace = new ArrayList<>();
        List<Pair<? extends BehaviorControl<? super LivingEntity>, Integer>> entries = new ArrayList<>();
        ShufflingList<BehaviorControl<? super LivingEntity>> reference = new ShufflingList<>(false);
        for (int id = 0; id < 8; id++) {
            Recording behavior = new Recording(id, trace);
            entries.add(Pair.of(behavior, id + 1));
            reference.add(behavior, id + 1);
        }
        GateBehavior<LivingEntity> gate = new GateBehavior<>(Map.of(), Set.of(), order, policy, entries);
        Field field = GateBehavior.class.getDeclaredField("behaviors");
        field.setAccessible(true);
        ShufflingList<?> list = actual ? (ShufflingList<?>) field.get(gate) : reference;
        Field randomField = ShufflingList.class.getDeclaredField("random");
        randomField.setAccessible(true);
        RandomSource random = (RandomSource) randomField.get(list);
        random.setSeed(98123);
        LivingEntity body = Mockito.mock(LivingEntity.class);
        ServerLevel level = Mockito.mock(ServerLevel.class);
        for (int tick = 0; tick < 200; tick++) {
            if (actual) {
                if (gate.getStatus() == Behavior.Status.STOPPED) gate.tryStart(level, body, tick);
                gate.tickOrStop(level, body, tick);
            } else {
                boolean running = false;
                for (var behavior : reference) running |= behavior.getStatus() == Behavior.Status.RUNNING;
                if (!running) {
                    order.apply(reference);
                    for (var behavior : reference) {
                        if (behavior.getStatus() == Behavior.Status.STOPPED && behavior.tryStart(level, body, tick)
                            && policy == GateBehavior.RunningPolicy.RUN_ONE) break;
                    }
                }
                for (var behavior : reference) {
                    if (behavior.getStatus() == Behavior.Status.RUNNING) behavior.tickOrStop(level, body, tick);
                }
            }
        }
        if (actual) gate.doStop(level, body, 201);
        else for (var behavior : reference) {
            if (behavior.getStatus() == Behavior.Status.RUNNING) behavior.doStop(level, body, 201);
        }
        trace.add("random:" + random.nextLong());
        return trace;
    }

    private static final class Recording implements BehaviorControl<LivingEntity> {
        private final int id;
        private final List<String> trace;
        private Behavior.Status status = Behavior.Status.STOPPED;
        private long end;
        Recording(int id, List<String> trace) { this.id = id; this.trace = trace; }
        @Override public Behavior.Status getStatus() { return status; }
        @Override public Set<MemoryModuleType<?>> getRequiredMemories() { return Set.of(); }
        @Override public boolean tryStart(ServerLevel l, LivingEntity b, long t) {
            trace.add(t + ":start:" + id);
            if ((t + id) % 3 == 0) return false;
            status = Behavior.Status.RUNNING;
            end = t + id % 4;
            return true;
        }
        @Override public void tickOrStop(ServerLevel l, LivingEntity b, long t) {
            trace.add(t + ":tick:" + id);
            if (t >= end) doStop(l, b, t);
        }
        @Override public void doStop(ServerLevel l, LivingEntity b, long t) { trace.add(t + ":stop:" + id); status = Behavior.Status.STOPPED; }
        @Override public String debugString() { return Integer.toString(id); }
    }
}
