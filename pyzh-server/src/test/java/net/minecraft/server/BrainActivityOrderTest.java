package net.minecraft.server;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.schedule.Activity;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

@LeafTest
class BrainActivityOrderTest {
    @Test
    void startLoopChecksLiveActivityOncePerSetLikePaper() {
        for (boolean samePriority : new boolean[] {false, true}) {
            List<String> expected = activitySwitchRun(false, samePriority);
            assertEquals(expected, activitySwitchRun(true, samePriority));
            assertEquals(List.of("start:switch", "start:core-tail", "start:panic", "start:panic-tail"), expected);
        }
    }

    private static List<String> activitySwitchRun(boolean actual, boolean samePriority) {
        Brain<LivingEntity> brain = new Brain<>();
        ServerLevel level = Mockito.mock(ServerLevel.class);
        LivingEntity body = Mockito.mock(LivingEntity.class);
        Mockito.when(body.level()).thenReturn(level);
        List<String> calls = new ArrayList<>();
        Map<Integer, Map<Activity, List<RecordingBehavior>>> paper = new TreeMap<>();
        int priority = samePriority ? 0 : 1;
        List<Pair<Activity, Pair<Integer, RecordingBehavior>>> entries = List.of(
            Pair.of(Activity.CORE, Pair.of(0, new RecordingBehavior("switch", calls,
                () -> brain.setActiveActivityIfPossible(Activity.PANIC)))),
            Pair.of(Activity.CORE, Pair.of(0, new RecordingBehavior("core-tail", calls))),
            Pair.of(Activity.IDLE, Pair.of(priority, new RecordingBehavior("idle", calls))),
            Pair.of(Activity.PANIC, Pair.of(priority, new RecordingBehavior("panic", calls,
                () -> brain.setActiveActivityIfPossible(Activity.WORK)))),
            Pair.of(Activity.PANIC, Pair.of(priority, new RecordingBehavior("panic-tail", calls)))
        );
        brain.addActivity(Activity.WORK, ImmutableList.of(), Set.of(), Set.of());
        for (var entry : entries) {
            brain.addActivity(entry.getFirst(), ImmutableList.of(entry.getSecond()), Set.of(), Set.of());
            paper.computeIfAbsent(entry.getSecond().getFirst(), key -> new HashMap<>())
                .computeIfAbsent(entry.getFirst(), key -> new ArrayList<>()).add(entry.getSecond().getSecond());
        }
        brain.setActiveActivityIfPossible(Activity.IDLE);
        if (actual) {
            brain.tick(level, body);
            calls.removeIf(call -> call.startsWith("tick:"));
        } else {
            // Paper checks membership at each entry, then finishes that set even if its activity changes.
            for (var byActivity : paper.values()) {
                for (var entry : byActivity.entrySet()) {
                    if (brain.isActive(entry.getKey())) {
                        for (var behavior : entry.getValue()) {
                            if (behavior.getStatus() == Behavior.Status.STOPPED) behavior.tryStart(level, body, 0L);
                        }
                    }
                }
            }
        }
        return calls;
    }

    @Test
    void startsTicksAndStopsFollowPaperPriorityAndHashMapOrder() {
        Brain<LivingEntity> brain = new Brain<>();
        ServerLevel level = Mockito.mock(ServerLevel.class);
        LivingEntity body = Mockito.mock(LivingEntity.class);
        Mockito.when(body.level()).thenReturn(level);
        List<String> calls = new ArrayList<>();
        Map<Integer, Map<Activity, List<String>>> paper = new TreeMap<>();
        List<Activity> activities = List.of(Activity.WORK, Activity.IDLE, Activity.CORE, Activity.REST, Activity.PLAY,
            Activity.MEET, Activity.PANIC, Activity.RAID, Activity.HIDE, Activity.FIGHT, Activity.SWIM, Activity.RAM, Activity.SNIFF);
        for (Activity activity : activities) {
            for (int priority : new int[] {5, 0}) {
                String name = priority + ":" + activity.getName();
                brain.addActivity(activity, ImmutableList.of(Pair.of(priority, new RecordingBehavior(name, calls))), Set.of(), Set.of());
                paper.computeIfAbsent(priority, key -> new HashMap<>()).computeIfAbsent(activity, key -> new ArrayList<>()).add(name);
            }
        }
        brain.setCoreActivities(Set.copyOf(activities));
        brain.setActiveActivityIfPossible(Activity.PLAY);
        List<String> order = paper.values().stream().flatMap(map -> map.values().stream()).flatMap(List::stream).toList();
        brain.tick(level, body);
        List<String> expected = new ArrayList<>();
        order.forEach(name -> expected.add("start:" + name));
        order.forEach(name -> expected.add("tick:" + name));
        assertEquals(expected, calls);
        calls.clear();
        brain.tick(level, body);
        assertEquals(order.stream().map(name -> "tick:" + name).toList(), calls);
        calls.clear();
        brain.stopAll(level, body);
        assertEquals(order.stream().map(name -> "stop:" + name).toList(), calls);
        calls.clear();
        brain.addActivity(Activity.CORE, ImmutableList.of(Pair.of(-1, new RecordingBehavior("new", calls))), Set.of(), Set.of());
        List<String> updatedOrder = new ArrayList<>();
        updatedOrder.add("new");
        updatedOrder.addAll(order);
        expected.clear();
        updatedOrder.forEach(name -> expected.add("start:" + name));
        updatedOrder.forEach(name -> expected.add("tick:" + name));
        brain.tick(level, body);
        assertEquals(expected, calls);
        calls.clear();
        brain.removeAllBehaviors();
        brain.tick(level, body);
        assertEquals(List.of(), calls);
    }

    private static final class RecordingBehavior implements BehaviorControl<LivingEntity> {
        private final String name;
        private final List<String> calls;
        private Behavior.Status status = Behavior.Status.STOPPED;
        private final Runnable onStart;

        private RecordingBehavior(String name, List<String> calls) { this(name, calls, () -> {}); }
        private RecordingBehavior(String name, List<String> calls, Runnable onStart) {
            this.name = name;
            this.calls = calls;
            this.onStart = onStart;
        }
        @Override public Behavior.Status getStatus() { return status; }
        @Override public Set<MemoryModuleType<?>> getRequiredMemories() { return Set.of(); }
        @Override public boolean tryStart(ServerLevel level, LivingEntity body, long time) {
            calls.add("start:" + name);
            status = Behavior.Status.RUNNING;
            onStart.run();
            return true;
        }
        @Override public void tickOrStop(ServerLevel level, LivingEntity body, long time) { calls.add("tick:" + name); }
        @Override public void doStop(ServerLevel level, LivingEntity body, long time) { calls.add("stop:" + name); status = Behavior.Status.STOPPED; }
        @Override public String debugString() { return name; }
    }
}
