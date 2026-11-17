package net.minecraft.world.entity.animal.equine;

import java.lang.reflect.Field;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.purpurmc.purpur.PurpurWorldConfig;

import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@LeafTest
class HorseAttributeInitializationTest {
    @Test
    void defaultInitializationLeavesAttributesAndRandomUntouched() throws Exception {
        AbstractHorse horse = Mockito.mock(AbstractHorse.class);
        ServerLevel level = levelWithConfig(false);
        Mockito.when(horse.level()).thenReturn(level);
        Mockito.doCallRealMethod().when(horse).initAttributes();
        horse.initAttributes();
        Mockito.verify(horse, Mockito.never()).getAttribute(any());
        Mockito.verify(horse, Mockito.never()).generateMaxHealth(any(RandomSource.class));
        Mockito.verify(horse, Mockito.never()).generateSpeed(any(RandomSource.class));
        Mockito.verify(horse, Mockito.never()).generateJumpStrength(any(RandomSource.class));
    }

    @Test
    void defaultSkeletonSpawnUsesThreeJumpDrawsAndKeepsSupplierHealthAndSpeed() throws Exception {
        ServerLevel level = levelWithConfig(false);
        RandomSource random = Mockito.mock(RandomSource.class);
        Mockito.when(level.getRandom()).thenReturn(random);
        Mockito.when(level.getLevel()).thenReturn(level);
        Mockito.when(random.nextDouble()).thenReturn(0.1, 0.4, 0.9);
        SkeletonHorse horse = Mockito.mock(SkeletonHorse.class);
        Mockito.when(horse.level()).thenReturn(level);
        AttributeInstance jump = Mockito.mock(AttributeInstance.class);
        AttributeInstance followRange = Mockito.mock(AttributeInstance.class);
        Mockito.when(followRange.hasModifier(any())).thenReturn(true);
        Mockito.when(horse.getAttribute(Attributes.JUMP_STRENGTH)).thenReturn(jump);
        Mockito.when(horse.getAttribute(Attributes.FOLLOW_RANGE)).thenReturn(followRange);
        Mockito.doCallRealMethod().when(horse).randomizeAttributes(random);
        Mockito.doCallRealMethod().when(horse).finalizeSpawn(eq(level), any(), any(), any());
        horse.finalizeSpawn(level, Mockito.mock(DifficultyInstance.class), EntitySpawnReason.TRIGGERED, null);
        Mockito.verify(jump).setBaseValue(0.4F + 0.1 * 0.2 + 0.4 * 0.2 + 0.9 * 0.2);
        Mockito.verify(random, Mockito.times(3)).nextDouble();
        Mockito.verify(horse, Mockito.never()).getAttribute(Attributes.MAX_HEALTH);
        Mockito.verify(horse, Mockito.never()).getAttribute(Attributes.MOVEMENT_SPEED);
    }

    @Test
    void ordinaryHorseSpawnKeepsPaperHealthSpeedAndJumpDrawOrder() throws Exception {
        ServerLevel level = levelWithConfig(false);
        RandomSource random = Mockito.mock(RandomSource.class);
        Mockito.when(level.getRandom()).thenReturn(random);
        Mockito.when(level.getLevel()).thenReturn(level);
        Mockito.when(random.nextInt(8)).thenReturn(3);
        Mockito.when(random.nextInt(9)).thenReturn(4);
        Mockito.when(random.nextDouble()).thenReturn(0.1, 0.2, 0.3, 0.4, 0.5, 0.6);
        Horse horse = Mockito.mock(Horse.class);
        Mockito.when(horse.level()).thenReturn(level);
        AttributeInstance health = Mockito.mock(AttributeInstance.class);
        AttributeInstance speed = Mockito.mock(AttributeInstance.class);
        AttributeInstance jump = Mockito.mock(AttributeInstance.class);
        AttributeInstance followRange = Mockito.mock(AttributeInstance.class);
        Mockito.when(followRange.hasModifier(any())).thenReturn(true);
        Mockito.when(horse.getAttribute(Attributes.MAX_HEALTH)).thenReturn(health);
        Mockito.when(horse.getAttribute(Attributes.MOVEMENT_SPEED)).thenReturn(speed);
        Mockito.when(horse.getAttribute(Attributes.JUMP_STRENGTH)).thenReturn(jump);
        Mockito.when(horse.getAttribute(Attributes.FOLLOW_RANGE)).thenReturn(followRange);
        Mockito.doCallRealMethod().when(horse).randomizeAttributes(random);
        Mockito.doCallRealMethod().when(horse).finalizeSpawn(eq(level), any(), any(), any());
        horse.finalizeSpawn(level, Mockito.mock(DifficultyInstance.class), EntitySpawnReason.TRIGGERED, null);
        var order = Mockito.inOrder(health, speed, jump);
        order.verify(health).setBaseValue(22.0);
        order.verify(speed).setBaseValue((0.45F + 0.1 * 0.3 + 0.2 * 0.3 + 0.3 * 0.3) * 0.25);
        order.verify(jump).setBaseValue(0.4F + 0.4 * 0.2 + 0.5 * 0.2 + 0.6 * 0.2);
        Mockito.verify(random, Mockito.times(6)).nextDouble();
    }

    @Test
    void configuredAttributesSkipSpawnRandomization() throws Exception {
        ServerLevel level = levelWithConfig(true);
        RandomSource random = Mockito.mock(RandomSource.class);
        Mockito.when(level.getRandom()).thenReturn(random);
        Mockito.when(level.getLevel()).thenReturn(level);
        SkeletonHorse horse = Mockito.mock(SkeletonHorse.class);
        Mockito.when(horse.level()).thenReturn(level);
        AttributeInstance followRange = Mockito.mock(AttributeInstance.class);
        Mockito.when(followRange.hasModifier(any())).thenReturn(true);
        Mockito.when(horse.getAttribute(Attributes.FOLLOW_RANGE)).thenReturn(followRange);
        Mockito.doCallRealMethod().when(horse).finalizeSpawn(eq(level), any(), any(), any());
        horse.finalizeSpawn(level, Mockito.mock(DifficultyInstance.class), EntitySpawnReason.TRIGGERED, null);
        Mockito.verify(horse, Mockito.never()).randomizeAttributes(any());
    }

    @Test
    void zombieHorseSpawnKeepsPaperHealthAndUsesItsOwnJumpAndSpeedGenerators() throws Exception {
        ServerLevel level = levelWithConfig(false);
        RandomSource random = Mockito.mock(RandomSource.class);
        Mockito.when(level.getRandom()).thenReturn(random);
        Mockito.when(level.getLevel()).thenReturn(level);
        Mockito.when(random.nextDouble()).thenReturn(0.2);
        ZombieHorse horse = Mockito.mock(ZombieHorse.class);
        Mockito.when(horse.level()).thenReturn(level);
        AttributeInstance jump = Mockito.mock(AttributeInstance.class);
        AttributeInstance speed = Mockito.mock(AttributeInstance.class);
        AttributeInstance followRange = Mockito.mock(AttributeInstance.class);
        Mockito.when(followRange.hasModifier(any())).thenReturn(true);
        Mockito.when(horse.getAttribute(Attributes.JUMP_STRENGTH)).thenReturn(jump);
        Mockito.when(horse.getAttribute(Attributes.MOVEMENT_SPEED)).thenReturn(speed);
        Mockito.when(horse.getAttribute(Attributes.FOLLOW_RANGE)).thenReturn(followRange);
        Mockito.doCallRealMethod().when(horse).randomizeAttributes(random);
        Mockito.doCallRealMethod().when(horse).finalizeSpawn(eq(level), any(), any(), any());
        horse.finalizeSpawn(level, Mockito.mock(DifficultyInstance.class), EntitySpawnReason.TRIGGERED, null);
        assertEquals(25.0, ZombieHorse.createAttributes().build().getBaseValue(Attributes.MAX_HEALTH));
        Mockito.verify(horse, Mockito.never()).getAttribute(Attributes.MAX_HEALTH);
        Mockito.verify(jump).setBaseValue(0.5 + 0.2 * 0.06666666666666667 + 0.2 * 0.06666666666666667 + 0.2 * 0.06666666666666667);
        Mockito.verify(speed).setBaseValue((9.0 + 0.2 + 0.2 + 0.2) / 42.16F);
        Mockito.verify(random, Mockito.times(6)).nextDouble();
    }

    private static ServerLevel levelWithConfig(boolean configured) throws Exception {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        PurpurWorldConfig config = Mockito.mock(PurpurWorldConfig.class);
        config.configuredHorseAttributes = configured;
        Field field = Level.class.getDeclaredField("purpurConfig");
        field.setAccessible(true);
        field.set(level, config);
        return level;
    }
}
