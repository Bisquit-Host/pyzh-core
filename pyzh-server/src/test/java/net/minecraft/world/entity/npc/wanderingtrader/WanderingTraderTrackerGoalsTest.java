package net.minecraft.world.entity.npc.wanderingtrader;

import io.papermc.paper.configuration.WorldConfiguration;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.UseItemGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.async.MultithreadedTracker;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;

@LeafTest
public class WanderingTraderTrackerGoalsTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void effectChecksOnlyConstrainAsyncTrackerGoals(final boolean async) throws Exception {
        final boolean previous = MultithreadedTracker.enabled;
        try {
            MultithreadedTracker.enabled = async;
            final WanderingTrader trader = Mockito.mock(WanderingTrader.class);
            final ServerLevel world = Mockito.mock(ServerLevel.class);
            final WorldConfiguration paper = Mockito.mock(WorldConfiguration.class);
            paper.entities = paper.new Entities();
            paper.entities.behavior = Mockito.mock(WorldConfiguration.Entities.Behavior.class);
            Mockito.when(world.paperConfig()).thenReturn(paper);
            final var purpurField = Level.class.getDeclaredField("purpurConfig");
            purpurField.setAccessible(true);
            final var purpur = Mockito.mock(org.purpurmc.purpur.PurpurWorldConfig.class);
            purpur.milkClearsBeneficialEffects = true;
            purpurField.set(world, purpur);
            Mockito.when(trader.level()).thenReturn(world);
            Mockito.when(trader.getNavigation()).thenReturn(Mockito.mock(PathNavigation.class));
            final GoalSelector goals = Mockito.mock(GoalSelector.class);
            final var goalField = Mob.class.getDeclaredField("goalSelector");
            goalField.setAccessible(true);
            goalField.set(trader, goals);
            Mockito.doCallRealMethod().when(trader).registerGoals();
            trader.registerGoals();
            final ArgumentCaptor<Goal> registered = ArgumentCaptor.forClass(Goal.class);
            Mockito.verify(goals, Mockito.atLeastOnce()).addGoal(anyInt(), registered.capture());
            final var useItems = registered.getAllValues().stream()
                .filter(goal -> goal instanceof UseItemGoal<?>)
                .map(goal -> (UseItemGoal<?>) goal)
                .toList();
            assertEquals(2, useItems.size());
            trader.canDrinkPotion = true;
            trader.canDrinkMilk = true;

            Mockito.when(world.isDarkOutside()).thenReturn(true);
            Mockito.when(trader.isInvisible()).thenReturn(false);
            Mockito.when(trader.hasEffect(MobEffects.INVISIBILITY)).thenReturn(true);
            assertEquals(!async, useItems.getFirst().canUse());
            Mockito.when(trader.hasEffect(MobEffects.INVISIBILITY)).thenReturn(false);
            assertTrue(useItems.getFirst().canUse());

            Mockito.when(world.isBrightOutside()).thenReturn(true);
            Mockito.when(trader.isInvisible()).thenReturn(true);
            assertEquals(!async, useItems.getLast().canUse());
            Mockito.when(trader.hasEffect(MobEffects.INVISIBILITY)).thenReturn(true);
            assertTrue(useItems.getLast().canUse());
        } finally {
            MultithreadedTracker.enabled = previous;
        }
    }
}
