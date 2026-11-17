package net.minecraft.world.entity;

import java.util.HashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.fixes.MCBugFix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.purpurmc.purpur.PurpurWorldConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@LeafTest
class LivingEntityPaperParityTest {
    @Test
    void damageResetsIdleTimeEvenDuringTheDamageCooldown() {
        LivingEntity entity = Mockito.mock(LivingEntity.class);
        ServerLevel level = Mockito.mock(ServerLevel.class);
        DamageSource source = Mockito.mock(DamageSource.class);
        Mockito.when(entity.getHealth()).thenReturn(20.0F);
        Mockito.when(entity.getUseItem()).thenReturn(ItemStack.EMPTY);
        Mockito.doCallRealMethod().when(entity).hurtServer(level, source, 1.0F);
        entity.noActionTime = 2401;
        entity.invulnerableDuration = 20;
        entity.invulnerableTime = 20;
        entity.lastHurt = 5.0F;

        assertFalse(entity.hurtServer(level, source, 1.0F));
        assertEquals(0, entity.noActionTime);
    }

    @Test
    void blindnessScalesVisibilityOncePerLevelAndSkipsDefaultLookups() throws Exception {
        LivingEntity target = Mockito.mock(LivingEntity.class);
        LivingEntity attacker = Mockito.mock(LivingEntity.class);
        Level level = Mockito.mock(Level.class);
        PurpurWorldConfig config = Mockito.mock(PurpurWorldConfig.class);
        setField(Level.class, level, "purpurConfig", config);
        Mockito.when(target.level()).thenReturn(level);
        Mockito.doCallRealMethod().when(target).getVisibilityPercent(attacker);

        config.mobsBlindnessMultiplier = 1.0;
        assertEquals(1.0, target.getVisibilityPercent(attacker));
        Mockito.verify(attacker, Mockito.never()).hasEffect(MobEffects.BLINDNESS);
        Mockito.verify(attacker, Mockito.never()).getEffect(MobEffects.BLINDNESS);

        config.mobsBlindnessMultiplier = 0.5;
        Mockito.when(attacker.getEffect(MobEffects.BLINDNESS)).thenReturn(new MobEffectInstance(MobEffects.BLINDNESS, 20, 0));
        assertEquals(0.5, target.getVisibilityPercent(attacker));
        Mockito.when(attacker.getEffect(MobEffects.BLINDNESS)).thenReturn(new MobEffectInstance(MobEffects.BLINDNESS, 20, 2));
        assertEquals(0.125, target.getVisibilityPercent(attacker));
        config.mobsBlindnessMultiplier = 2.0;
        assertEquals(8.0, target.getVisibilityPercent(attacker));
    }

    @Test
    void paperDaylightFlagsMigrateAndPurpurFlagsTakePrecedence() throws Exception {
        LivingEntity entity = loadableEntity();
        entity.shouldBurnInDay = true;
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Paper.ShouldBurnInDay", false);
        entity.readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, RegistryHelper.registryAccess(), tag));
        assertFalse(entity.shouldBurnInDay);

        tag.putBoolean("Purpur.ShouldBurnInDay", true);
        entity.readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, RegistryHelper.registryAccess(), tag));
        assertTrue(entity.shouldBurnInDay);

        tag.putBoolean("Paper.ShouldBurnInDay", true);
        tag.putBoolean("Purpur.ShouldBurnInDay", false);
        entity.readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, RegistryHelper.registryAccess(), tag));
        assertFalse(entity.shouldBurnInDay);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void loadedHealthOnlyBypassesTheCapWhenEnabled(boolean enabled) throws Exception {
        boolean previous = MCBugFix.mc17876;
        try {
            MCBugFix.mc17876 = enabled;
            LivingEntity entity = loadableEntity();
            SynchedEntityData data = Mockito.mock(SynchedEntityData.class);
            setField(Entity.class, entity, "entityData", data);
            Mockito.doCallRealMethod().when(entity).setHealth(anyFloat(), anyBoolean());
            Mockito.doCallRealMethod().when(entity).setHealth(anyFloat());
            CompoundTag tag = new CompoundTag();
            tag.putFloat("Health", 40.0F);

            entity.readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, RegistryHelper.registryAccess(), tag));
            Mockito.verify(entity, Mockito.times(enabled ? 0 : 1)).setHealth(40.0F);
            Mockito.verify(data).set(LivingEntity.DATA_HEALTH_ID, enabled ? 40.0F : 20.0F);
        } finally {
            MCBugFix.mc17876 = previous;
        }
    }

    @Test
    void zombieSuppliersKeepPaperMovementSpeedDefaults() {
        for (var supplier : java.util.List.of(Zombie.createAttributes().build(), ZombifiedPiglin.createAttributes().build())) {
            assertEquals((double) 0.23F, supplier.getBaseValue(Attributes.MOVEMENT_SPEED));
            AttributeMap attributes = new AttributeMap(supplier);
            attributes.getInstance(Attributes.MOVEMENT_SPEED).setBaseValue(0.5);
            assertTrue(attributes.resetBaseValue(Attributes.MOVEMENT_SPEED));
            assertEquals((double) 0.23F, attributes.getBaseValue(Attributes.MOVEMENT_SPEED));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removalOnlyClearsCombatStateWhenEnabled(boolean enabled) throws Exception {
        boolean previous = MCBugFix.mc298464;
        try {
            MCBugFix.mc298464 = enabled;
            LivingEntity entity = Mockito.mock(LivingEntity.class);
            CombatTracker tracker = Mockito.mock(CombatTracker.class);
            entity.combatTracker = tracker;
            entity.brain = Mockito.mock(Brain.class);
            Mockito.doCallRealMethod().when(entity).remove(Entity.RemovalReason.UNLOADED_TO_CHUNK, null);

            entity.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK, null);
            Mockito.verify(tracker, Mockito.times(enabled ? 1 : 0)).resetCombatState();
        } finally {
            MCBugFix.mc298464 = previous;
        }
    }

    private static LivingEntity loadableEntity() throws Exception {
        LivingEntity entity = Mockito.mock(LivingEntity.class);
        Mockito.when(entity.getMaxHealth()).thenReturn(20.0F);
        setField(LivingEntity.class, entity, "activeEffects", new HashMap<>());
        setField(LivingEntity.class, entity, "equipment", Mockito.mock(EntityEquipment.class));
        Mockito.doCallRealMethod().when(entity).readAdditionalSaveData(any());
        return entity;
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
