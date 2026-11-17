package net.minecraft.server;

import com.destroystokyo.paper.entity.ai.PaperMobGoals;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.behavior.InsideBrownianWalk;
import net.minecraft.world.entity.ai.behavior.SetClosestHomeAsWalkTarget;
import net.minecraft.world.entity.ai.behavior.VillagerPanicTrigger;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.animal.equine.SkeletonTrapGoal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.bukkit.support.environment.LeafTest;
import org.bukkit.craftbukkit.entity.CraftSkeletonHorse;
import org.dreeam.leaf.config.modules.opt.OptimizeInsideBrownianWalk;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@LeafTest
class MobAiCompatibilityTest {
    @Test
    void villageSectionTiesMatchPaperCubeEncounterOrder() {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        SectionPos center = SectionPos.of(0, 0, 0);
        Mockito.when(level.sectionsToVillage(any())).thenAnswer(invocation -> {
            SectionPos pos = invocation.getArgument(0);
            if (pos.equals(center)) return 6;
            return pos.getY() == -1 && pos.getX() == 1 || pos.getZ() == 1 ? 1 : 3;
        });
        SectionPos expected = SectionPos.cube(center, 1)
            .filter(pos -> level.sectionsToVillage(pos) < level.sectionsToVillage(center))
            .min(java.util.Comparator.comparingInt(level::sectionsToVillage)).orElse(center);
        assertEquals(expected, BehaviorUtils.findSectionClosestToVillage(level, center, 1));
    }

    @Test
    void panicAttemptsStayOnGlobalHundredTickBoundariesAcrossRuns() throws Exception {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        Villager villager = Mockito.mock(Villager.class);
        VillagerPanicTrigger behavior = new VillagerPanicTrigger();
        Method tick = VillagerPanicTrigger.class.getDeclaredMethod("tick", ServerLevel.class, Villager.class, long.class);
        tick.setAccessible(true);
        for (long time = 37; time < 241; time++) tick.invoke(behavior, level, villager, time);
        for (long time = 399; time < 402; time++) tick.invoke(behavior, level, villager, time);
        Mockito.verify(villager).spawnGolemIfNeeded(level, 100L, 3);
        Mockito.verify(villager).spawnGolemIfNeeded(level, 200L, 3);
        Mockito.verify(villager).spawnGolemIfNeeded(level, 400L, 3);
        Mockito.verifyNoMoreInteractions(villager);
    }

    @Test
    void stonecuttersHavePaperPathClassification() {
        assertEquals(PathType.BLOCKED, WalkNodeEvaluator.getPathTypeFromState(Blocks.STONECUTTER.defaultBlockState()));
        assertEquals(PathType.DAMAGING, WalkNodeEvaluator.getPathTypeFromState(Blocks.CACTUS.defaultBlockState()));
        assertEquals(PathType.DAMAGING, WalkNodeEvaluator.getPathTypeFromState(Blocks.SWEET_BERRY_BUSH.defaultBlockState()));
    }

    @Test
    void retrappingKeepsOneRegisteredTrapGoal() throws Exception {
        SkeletonHorse horse = Mockito.mock(SkeletonHorse.class, Mockito.CALLS_REAL_METHODS);
        GoalSelector selector = new GoalSelector();
        SkeletonTrapGoal trap = new SkeletonTrapGoal(horse);
        setField(Mob.class, horse, "goalSelector", selector);
        setField(Mob.class, horse, "targetSelector", new GoalSelector());
        setField(SkeletonHorse.class, horse, "skeletonTrapGoal", trap);
        CraftSkeletonHorse craftHorse = Mockito.mock(CraftSkeletonHorse.class);
        Mockito.when(craftHorse.getHandle()).thenReturn(horse);
        PaperMobGoals api = new PaperMobGoals();
        for (int i = 0; i < 4; i++) {
            horse.setTrap(true);
            assertEquals(1, selector.getAvailableGoals().size());
            assertTrue(api.hasGoal(craftHorse, VanillaGoal.SKELETON_HORSE_SKELETON_TRAP));
            horse.setTrap(false);
            assertTrue(selector.getAvailableGoals().isEmpty());
            assertFalse(api.hasGoal(craftHorse, VanillaGoal.SKELETON_HORSE_SKELETON_TRAP));
        }
        selector.removeGoal(trap);
        assertTrue(selector.getAvailableGoals().isEmpty());
        horse.setTrap(true);
        assertEquals(1, selector.getAvailableGoals().size());
        Goal later = new Goal() {
            @Override public boolean canUse() { return false; }
        };
        selector.addGoal(1, later);
        horse.setTrap(false);
        horse.setTrap(true);
        assertEquals(java.util.List.of(later, trap), selector.getAvailableGoals().stream().map(goal -> goal.getGoal()).toList());
    }

    @Test
    void brownianWalkOnlyConsumesEntityRandomWhenOptedIn() {
        boolean previous = OptimizeInsideBrownianWalk.useEntityRandom;
        try {
            ServerLevel level = Mockito.mock(ServerLevel.class);
            PathfinderMob mob = Mockito.mock(PathfinderMob.class);
            Brain<PathfinderMob> brain = new Brain<>();
            brain.addActivity(net.minecraft.world.entity.schedule.Activity.IDLE,
                com.google.common.collect.ImmutableList.of(com.mojang.datafixers.util.Pair.of(0, InsideBrownianWalk.create(1.0F))),
                java.util.Set.of(), java.util.Set.of());
            Mockito.doReturn(brain).when(mob).getBrain();
            Mockito.when(mob.blockPosition()).thenReturn(BlockPos.ZERO);
            RandomSource random = Mockito.mock(RandomSource.class);
            Mockito.when(mob.getRandom()).thenReturn(random);
            OptimizeInsideBrownianWalk.useEntityRandom = false;
            assertTrue(InsideBrownianWalk.create(1.0F).tryStart(level, mob, 0));
            Mockito.verifyNoInteractions(random);
            OptimizeInsideBrownianWalk.useEntityRandom = true;
            assertTrue(InsideBrownianWalk.create(1.0F).tryStart(level, mob, 0));
            Mockito.verify(random, Mockito.times(26)).nextInt(anyInt());
        } finally {
            OptimizeInsideBrownianWalk.useEntityRandom = previous;
        }
    }

    @Test
    void homeSearchUsesPaperPoiQueryAndPassesDeduplicatedTargetsToNavigation() {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        PathfinderMob mob = Mockito.mock(PathfinderMob.class);
        PoiManager pois = Mockito.mock(PoiManager.class);
        PathNavigation navigation = Mockito.mock(PathNavigation.class);
        RandomSource random = Mockito.mock(RandomSource.class);
        Brain<PathfinderMob> brain = new Brain<>();
        var behavior = SetClosestHomeAsWalkTarget.create(1.0F);
        brain.addActivity(net.minecraft.world.entity.schedule.Activity.IDLE,
            com.google.common.collect.ImmutableList.of(Pair.of(0, behavior)), Set.of(), Set.of());
        Mockito.doReturn(brain).when(mob).getBrain();
        Mockito.when(mob.blockPosition()).thenReturn(BlockPos.ZERO);
        Mockito.when(mob.getNavigation()).thenReturn(navigation);
        Mockito.when(level.getPoiManager()).thenReturn(pois);
        Mockito.when(level.getRandom()).thenReturn(random);
        Mockito.when(level.getGameTime()).thenReturn(100L);
        BlockPos home = new BlockPos(10, 0, 0);
        PoiType type = Mockito.mock(PoiType.class);
        Mockito.when(type.validRange()).thenReturn(2);
        var record = Pair.of(Holder.direct(type), home);
        Mockito.when(pois.findClosest(any(), eq(BlockPos.ZERO), anyInt(), eq(PoiManager.Occupancy.ANY)))
            .thenReturn(Optional.of(home));
        Mockito.when(pois.findAllWithType(any(), any(), eq(BlockPos.ZERO), anyInt(), eq(PoiManager.Occupancy.ANY)))
            .thenAnswer(invocation -> Stream.of(record, record));
        assertTrue(behavior.tryStart(level, mob, 100L));
        Mockito.verify(pois).findAllWithType(any(), any(), eq(BlockPos.ZERO), eq(48), eq(PoiManager.Occupancy.ANY));
        Mockito.verify(navigation).createPath(Set.of(home), 2);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
