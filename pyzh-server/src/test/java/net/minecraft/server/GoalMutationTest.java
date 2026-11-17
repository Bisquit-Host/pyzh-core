package net.minecraft.server;

import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.PaperMobGoals;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.entity.CraftMob;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class GoalMutationTest {
    private enum Phase { CLEANUP, CAN_USE, START, TICK }
    private enum Mutation { SELF, LATER, PREVIOUS, SELF_AND_LATER, APPEND, APPEND_FROM_LAST }

    @Test
    void mutationsMatchPaperAtEveryCallbackBoundary() {
        for (Phase phase : Phase.values()) {
            for (Mutation mutation : Mutation.values()) {
                List<String> expected = runMutation(new PaperGoalSelectorReference(), phase, mutation);
                assertEquals(expected, runMutation(new GoalSelector(), phase, mutation), phase + " " + mutation);
            }
        }
    }

    private static List<String> runMutation(GoalSelector selector, Phase phase, Mutation mutation) {
        List<String> calls = new ArrayList<>();
        List<CallbackGoal> goals = new ArrayList<>();
        for (int id = 1; id <= 4; id++) {
            CallbackGoal goal = new CallbackGoal(id, calls);
            goals.add(goal);
            selector.addGoal(0, goal);
        }
        if (phase == Phase.CLEANUP) {
            selector.tick();
            calls.clear();
        }
        CallbackGoal source = goals.get(mutation == Mutation.APPEND_FROM_LAST ? 3 : mutation == Mutation.PREVIOUS ? 2 : 0);
        source.phase = phase;
        source.action = switch (mutation) {
            case SELF -> () -> selector.removeGoal(source);
            case LATER -> () -> selector.removeGoal(goals.get(2));
            case PREVIOUS -> () -> selector.removeGoal(goals.getFirst());
            case SELF_AND_LATER -> () -> {
                selector.removeGoal(source);
                selector.removeGoal(goals.get(2));
            };
            case APPEND, APPEND_FROM_LAST -> () -> selector.addGoal(0, new CallbackGoal(5, calls));
        };
        selector.tick();
        selector.tick();
        calls.add("registered:" + selector.getAvailableGoals().stream().map(goal -> goal.getGoal().toString()).toList());
        return calls;
    }

    @Test
    void removingImmediateSuccessorSkipsItAndStillTicksTheRemainingGoals() {
        assertThrows(NullPointerException.class, () -> removeImmediateSuccessor(new PaperGoalSelectorReference()));
        assertEquals(List.of("1.tick", "2.stop", "3.tick"), removeImmediateSuccessor(new GoalSelector()));
    }

    private static List<String> removeImmediateSuccessor(GoalSelector selector) {
        List<String> calls = new ArrayList<>();
        CallbackGoal first = new CallbackGoal(1, calls);
        CallbackGoal next = new CallbackGoal(2, calls);
        CallbackGoal last = new CallbackGoal(3, calls);
        selector.addGoal(0, first);
        selector.addGoal(0, next);
        selector.addGoal(0, last);
        selector.tick();
        calls.clear();
        first.phase = Phase.TICK;
        first.action = () -> selector.removeGoal(next);
        selector.tickRunningGoals(true);
        return calls;
    }

    @Test
    void paperMobGoalsCanRemoveSelfOrALaterGoalDuringTick() throws Exception {
        for (boolean removeSelf : new boolean[] {true, false}) {
            GoalSelector selector = new GoalSelector();
            PaperMobGoals api = new PaperMobGoals();
            Mob handle = Mockito.mock(Mob.class);
            CraftMob mob = Mockito.mock(CraftMob.class);
            Mockito.when(mob.getHandle()).thenReturn(handle);
            Mockito.when(handle.getGoalSelector()).thenReturn(selector);
            var targetSelector = Mob.class.getDeclaredField("targetSelector");
            targetSelector.setAccessible(true);
            targetSelector.set(handle, new GoalSelector());
            List<String> calls = new ArrayList<>();
            ApiGoal first = new ApiGoal("first", GoalType.MOVE, calls);
            ApiGoal middle = new ApiGoal("middle", GoalType.LOOK, calls);
            ApiGoal last = new ApiGoal("last", GoalType.JUMP, calls);
            api.addGoal(mob, 0, first);
            api.addGoal(mob, 0, middle);
            api.addGoal(mob, 0, last);
            selector.tick();
            calls.clear();
            ApiGoal removed = removeSelf ? first : last;
            first.onTick = () -> api.removeGoal(mob, removed);
            selector.tickRunningGoals(true);
            assertEquals(removeSelf ? List.of("first.tick", "first.stop", "middle.tick", "last.tick")
                : List.of("first.tick", "last.stop", "middle.tick"), calls);
            assertFalse(api.hasGoal(mob, removed.getKey()));
            assertEquals(2, selector.getAvailableGoals().size());
        }
    }

    private static final class CallbackGoal extends Goal {
        private final int id;
        private final List<String> calls;
        private Phase phase;
        private Runnable action = () -> {};

        private CallbackGoal(int id, List<String> calls) {
            this.id = id;
            this.calls = calls;
            getFlags().clear();
        }

        private void visit(Phase phase, String name) {
            calls.add(id + "." + name);
            if (this.phase == phase) {
                Runnable callback = action;
                this.phase = null;
                callback.run();
            }
        }

        @Override public boolean canUse() { visit(Phase.CAN_USE, "canUse"); return true; }
        @Override public boolean canContinueToUse() { visit(Phase.CLEANUP, "continue"); return true; }
        @Override public void start() { visit(Phase.START, "start"); }
        @Override public void stop() { calls.add(id + ".stop"); }
        @Override public void tick() { visit(Phase.TICK, "tick"); }
        @Override public int hashCode() { return id; }
        @Override public String toString() { return Integer.toString(id); }
    }

    private static final class ApiGoal implements com.destroystokyo.paper.entity.ai.Goal<org.bukkit.entity.Mob> {
        private final String name;
        private final GoalType type;
        private final List<String> calls;
        private Runnable onTick = () -> {};

        private ApiGoal(String name, GoalType type, List<String> calls) {
            this.name = name;
            this.type = type;
            this.calls = calls;
        }

        @Override public boolean shouldActivate() { return true; }
        @Override public boolean shouldStayActive() { return true; }
        @Override public GoalKey<org.bukkit.entity.Mob> getKey() {
            return GoalKey.of(org.bukkit.entity.Mob.class, new NamespacedKey("test", name));
        }
        @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(type); }
        @Override public void stop() { calls.add(name + ".stop"); }
        @Override public void tick() { calls.add(name + ".tick"); onTick.run(); }
    }
}
