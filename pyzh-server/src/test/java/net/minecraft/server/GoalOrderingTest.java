package net.minecraft.server;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.util.map.BinaryGoalSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class GoalOrderingTest {
    @Test
    void flagChangesInsideCanUseKeepPapersEligibilityAndClaimBoundaries() {
        assertEquals(flagChangeScenario(new PaperGoalSelectorReference()), flagChangeScenario(new GoalSelector()));
        assertEquals(List.of("incumbent.continue", "candidate.canUse", "incumbent.stop", "candidate.start", "candidate.tick"),
            flagChangeScenario(new GoalSelector()));
    }

    private static List<String> flagChangeScenario(GoalSelector selector) {
        List<String> calls = new ArrayList<>();
        RecordingGoal incumbent = new RecordingGoal("incumbent", calls);
        incumbent.setFlags(EnumSet.of(Goal.Flag.LOOK));
        incumbent.interruptible = false;
        selector.addGoal(5, incumbent);
        selector.tick();
        calls.clear();
        Goal candidate = new Goal() {
            { setFlags(EnumSet.of(Flag.MOVE)); }
            @Override public boolean canUse() {
                calls.add("candidate.canUse");
                setFlags(EnumSet.of(Flag.LOOK));
                selector.disableControlFlag(Flag.LOOK);
                return true;
            }
            @Override public void start() { calls.add("candidate.start"); }
            @Override public void tick() { calls.add("candidate.tick"); }
        };
        selector.addGoal(0, candidate);
        selector.tick();
        return calls;
    }

    @Test
    void maskFastPathMatchesPaperForEmptyFlagsPrioritiesAndLiveLocks() {
        for (int seed = 0; seed < 32; seed++) {
            assertEquals(maskScenario(new PaperGoalSelectorReference(), seed), maskScenario(new GoalSelector(), seed));
        }
    }

    private static List<String> maskScenario(GoalSelector selector, int seed) {
        List<String> calls = new ArrayList<>();
        List<MaskGoal> goals = new ArrayList<>();
        int[] priorities = {Integer.MAX_VALUE, 7, 0, Integer.MAX_VALUE, -1, Integer.MIN_VALUE};
        for (int id = 0; id < priorities.length; id++) {
            MaskGoal goal = new MaskGoal(id, calls);
            goal.getFlags().clear();
            for (Goal.Flag flag : Goal.Flag.values()) {
                if (((seed + id * 13) & (1 << flag.ordinal())) != 0) goal.addFlag(flag);
            }
            goals.add(goal);
            selector.addGoal(priorities[id], goal);
        }
        for (int tick = 0; tick < 6; tick++) {
            for (int id = 0; id < goals.size(); id++) {
                goals.get(id).eligible = ((seed + tick + id) % 3) != 0;
                goals.get(id).interruptible = ((seed + tick + id) % 4) != 0;
            }
            if ((tick & 1) == 0) selector.disableControlFlag(Goal.Flag.MOVE);
            else selector.enableControlFlag(Goal.Flag.MOVE);
            selector.tick();
        }
        return calls;
    }

    private static final class MaskGoal extends Goal {
        private final int id;
        private final List<String> calls;
        private boolean eligible;
        private boolean interruptible;

        private MaskGoal(int id, List<String> calls) { this.id = id; this.calls = calls; }
        @Override public boolean canUse() { calls.add(id + ".canUse"); return eligible; }
        @Override public boolean canContinueToUse() { calls.add(id + ".continue"); return eligible; }
        @Override public boolean isInterruptable() { calls.add(id + ".interruptible"); return interruptible; }
        @Override public void start() { calls.add(id + ".start"); }
        @Override public void stop() { calls.add(id + ".stop"); }
        @Override public void tick() { calls.add(id + ".tick"); }
    }

    @Test
    void arraySetMatchesPaperRegistrationEqualityAndPredicateOrder() {
        Set<WrappedGoal> paper = new ObjectLinkedOpenHashSet<>();
        BinaryGoalSet actual = new BinaryGoalSet();
        Goal shared = new RecordingGoal("shared", new ArrayList<>());
        List<WrappedGoal> goals = List.of(
            new WrappedGoal(8, shared),
            new WrappedGoal(2, new RecordingGoal("second", new ArrayList<>())),
            new WrappedGoal(8, new RecordingGoal("third", new ArrayList<>())),
            new WrappedGoal(0, shared)
        );
        for (WrappedGoal goal : goals) {
            assertEquals(paper.add(goal), actual.add(goal));
        }
        assertEquals(new ArrayList<>(paper), new ArrayList<>(actual));
        assertTrue(actual.contains(new WrappedGoal(42, shared)));
        List<WrappedGoal> paperVisited = new ArrayList<>();
        List<WrappedGoal> actualVisited = new ArrayList<>();
        paper.removeIf(goal -> { paperVisited.add(goal); return goal.getPriority() == 8; });
        actual.removeIf(goal -> { actualVisited.add(goal); return goal.getPriority() == 8; });
        assertEquals(paperVisited, actualVisited);
        assertEquals(new ArrayList<>(paper), new ArrayList<>(actual));
        assertEquals(paper.remove(goals.get(1)), actual.remove(goals.get(1)));
        assertTrue(actual.isEmpty());
        actual.add(goals.getFirst());
        assertTrue(actual.remove(new WrappedGoal(99, shared)));
        assertNull(actual.elements()[0]);
    }

    @Test
    void selectorStartsThenReplacesInRegistrationOrderAndTicksFirstEqualPriority() {
        List<String> calls = new ArrayList<>();
        GoalSelector selector = new GoalSelector();
        selector.addGoal(8, new RecordingGoal("low", calls));
        selector.addGoal(2, new RecordingGoal("first", calls));
        selector.addGoal(2, new RecordingGoal("tied", calls));
        selector.tick();
        assertEquals(List.of("low.canUse", "low.start", "first.canUse", "low.stop", "first.start", "first.tick"), calls);
        calls.clear();
        selector.tick();
        assertEquals(List.of("first.continue", "first.tick"), calls);
    }

    @Test
    void selectorChecksLiveInterruptibilityAfterEarlierGoalSideEffects() {
        List<String> calls = new ArrayList<>();
        GoalSelector selector = new GoalSelector();
        RecordingGoal incumbent = new RecordingGoal("incumbent", calls);
        selector.addGoal(5, incumbent);
        selector.tick();
        calls.clear();
        selector.addGoal(0, new Goal() {
            @Override
            public boolean canUse() {
                incumbent.interruptible = false;
                return false;
            }
        });
        selector.addGoal(1, new RecordingGoal("challenger", calls));
        selector.tick();
        assertEquals(List.of("incumbent.continue", "incumbent.tick"), calls);
    }

    private static final class RecordingGoal extends Goal {
        private final String name;
        private final List<String> calls;
        private boolean interruptible = true;

        private RecordingGoal(String name, List<String> calls) {
            this.name = name;
            this.calls = calls;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override public boolean canUse() { calls.add(name + ".canUse"); return true; }
        @Override public boolean canContinueToUse() { calls.add(name + ".continue"); return true; }
        @Override public boolean isInterruptable() { return interruptible; }
        @Override public void start() { calls.add(name + ".start"); }
        @Override public void stop() { calls.add(name + ".stop"); }
        @Override public void tick() { calls.add(name + ".tick"); }
    }
}
