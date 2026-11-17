package net.minecraft.world.item;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Field;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.fixes.ProjectileConsumption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.purpurmc.purpur.PurpurWorldConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@LeafTest
class ProjectileConsumptionTest {
    private boolean previous;

    @BeforeEach
    void saveConfig() {
        previous = ProjectileConsumption.enabled;
    }

    @AfterEach
    void restoreConfig() {
        ProjectileConsumption.enabled = previous;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledCrossbowShotsUsePaperChargeAndSoundOrderUnlessEnabled(final boolean enabled) {
        ProjectileConsumption.enabled = enabled;
        final Shot shot = new Shot();
        final CrossbowItem crossbow = shot.crossbow();
        final ChargedProjectiles charge = ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW)));
        shot.weapon.set(DataComponents.CHARGED_PROJECTILES, charge);
        Mockito.when(shot.event.isCancelled()).thenReturn(true);

        try (final var enchantments = Mockito.mockStatic(EnchantmentHelper.class);
             final var events = Mockito.mockStatic(CraftEventFactory.class)) {
            events.when(() -> CraftEventFactory.callEntityShootBowEvent(same(shot.shooter), same(shot.weapon), any(ItemStack.class), same(shot.arrow), eq(InteractionHand.MAIN_HAND), eq(1.0F), eq(true)))
                .thenAnswer(invocation -> {
                    shot.order.add("event");
                    assertEquals(!enabled, shot.weapon.get(DataComponents.CHARGED_PROJECTILES).isEmpty());
                    return shot.event;
                });
            crossbow.performShooting(shot.level, shot.shooter, InteractionHand.MAIN_HAND, shot.weapon, 1, 0, null);
        }

        assertEquals(enabled ? List.of("event") : List.of("sound", "event"), shot.order);
        assertEquals(!enabled, shot.weapon.get(DataComponents.CHARGED_PROJECTILES).isEmpty());
        Mockito.verify(shot.bukkitArrow).remove();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacementProjectilesHonorOptInNonConsumption(final boolean enabled) {
        ProjectileConsumption.enabled = enabled;
        final Shot shot = new Shot();
        final CrossbowItem crossbow = shot.crossbow();
        shot.weapon.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
        Mockito.when(shot.event.getProjectile()).thenReturn(Mockito.mock(org.bukkit.entity.Projectile.class));
        Mockito.when(shot.event.shouldConsumeItem()).thenReturn(false);

        try (final var enchantments = Mockito.mockStatic(EnchantmentHelper.class);
             final var events = Mockito.mockStatic(CraftEventFactory.class);
             final var projectiles = Mockito.mockStatic(Projectile.class)) {
            events.when(() -> CraftEventFactory.callEntityShootBowEvent(any(), any(), any(), any(), any(), anyFloat(), anyBoolean()))
                .thenAnswer(invocation -> {
                    shot.order.add("event");
                    return shot.event;
                });
            crossbow.performShooting(shot.level, shot.shooter, InteractionHand.MAIN_HAND, shot.weapon, 1, 0, null);
            projectiles.verifyNoInteractions();
        }

        assertEquals(enabled ? List.of("event", "sound") : List.of("sound", "event"), shot.order);
        assertEquals(!enabled, shot.weapon.get(DataComponents.CHARGED_PROJECTILES).isEmpty());
        if (enabled) assertEquals(AbstractArrow.Pickup.CREATIVE_ONLY, shot.arrow.pickup);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void crossbowKeepsEntryConsumptionFlagAcrossReload(final boolean enabled) {
        ProjectileConsumption.enabled = enabled;
        final Shot shot = new Shot();
        final CrossbowItem crossbow = shot.crossbow();
        shot.weapon.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
        Mockito.when(shot.event.shouldConsumeItem()).thenReturn(true);
        Mockito.when(shot.event.getProjectile()).thenReturn(Mockito.mock(org.bukkit.entity.Projectile.class));
        Mockito.doAnswer(invocation -> {
            ProjectileConsumption.enabled = !enabled;
            return invocation.callRealMethod();
        }).when(crossbow).shoot(any(), any(), any(), any(), anyList(), anyFloat(), anyFloat(), anyBoolean(), any(), anyFloat(), anyBoolean());

        try (final var enchantments = Mockito.mockStatic(EnchantmentHelper.class);
             final var events = Mockito.mockStatic(CraftEventFactory.class)) {
            events.when(() -> CraftEventFactory.callEntityShootBowEvent(any(), any(), any(), any(), any(), anyFloat(), eq(true)))
                .thenAnswer(invocation -> {
                    shot.order.add("event");
                    assertEquals(!enabled, shot.weapon.get(DataComponents.CHARGED_PROJECTILES).isEmpty());
                    return shot.event;
                });
            crossbow.performShooting(shot.level, shot.shooter, InteractionHand.MAIN_HAND, shot.weapon, 1, 0, null);
        }

        assertEquals(enabled ? List.of("event", "sound") : List.of("sound", "event"), shot.order);
        assertTrue(shot.weapon.get(DataComponents.CHARGED_PROJECTILES).isEmpty());
        Mockito.verify(shot.weapon).hurtAndBreak(1, shot.shooter, EquipmentSlot.MAINHAND);
    }

    @ParameterizedTest
    @CsvSource({
        "false, false, false, 1",
        "true, false, false, 1",
        "false, true, false, 1",
        "false, false, true, 1",
        "false, false, false, 3"
    })
    void defaultBowUsesPaperConsumptionCancellationAndMultishot(
        final boolean cancelled, final boolean infinity, final boolean creative, final int projectileCount
    ) throws Exception {
        ProjectileConsumption.enabled = false;
        final Shot shot = new Shot();
        final PurpurWorldConfig config = Mockito.mock(PurpurWorldConfig.class);
        final Field purpurConfig = Level.class.getDeclaredField("purpurConfig");
        purpurConfig.setAccessible(true);
        purpurConfig.set(shot.level, config);
        Mockito.when(shot.level.getRandom()).thenReturn(RandomSource.create(1));
        final BowItem bow = Mockito.mock(BowItem.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(shot.arrow).when(bow).createProjectile(any(), any(), any(), any(), anyBoolean());
        final ItemStack weapon = Mockito.spy(new ItemStack(Items.BOW));
        Mockito.doNothing().when(weapon).hurtAndBreak(anyInt(), any(LivingEntity.class), any(EquipmentSlot.class));
        final ItemStack ammo = new ItemStack(Items.ARROW, 2);
        Mockito.when(shot.shooter.getProjectile(weapon)).thenReturn(ammo);
        Mockito.when(shot.shooter.getUsedItemHand()).thenReturn(InteractionHand.MAIN_HAND);
        Mockito.when(shot.shooter.hasInfiniteMaterials()).thenReturn(creative);
        Mockito.when(shot.event.isCancelled()).thenReturn(cancelled);
        Mockito.when(shot.event.shouldConsumeItem()).thenReturn(false);
        Mockito.when(shot.event.getProjectile()).thenReturn(Mockito.mock(org.bukkit.entity.Projectile.class));
        final int remainingAmmo = infinity || creative ? 2 : 1;

        try (final var enchantments = Mockito.mockStatic(EnchantmentHelper.class);
             final var events = Mockito.mockStatic(CraftEventFactory.class);
             final var projectiles = Mockito.mockStatic(Projectile.class)) {
            enchantments.when(() -> EnchantmentHelper.processProjectileCount(same(shot.level), same(weapon), same(shot.shooter), eq(1)))
                .thenAnswer(invocation -> {
                    ProjectileConsumption.enabled = true;
                    return projectileCount;
                });
            enchantments.when(() -> EnchantmentHelper.processAmmoUse(same(shot.level), same(weapon), any(ItemStack.class), eq(1)))
                .thenReturn(infinity ? 0 : 1);
            events.when(() -> CraftEventFactory.callEntityShootBowEvent(same(shot.shooter), same(weapon), any(ItemStack.class), same(shot.arrow), eq(InteractionHand.MAIN_HAND), eq(1.0F), eq(true)))
                .thenAnswer(invocation -> {
                    assertEquals(remainingAmmo, ammo.getCount());
                    final ItemStack projectile = invocation.getArgument(2);
                    assertEquals(infinity || creative || !shot.order.isEmpty(), projectile.has(DataComponents.INTANGIBLE_PROJECTILE));
                    shot.order.add("event");
                    return shot.event;
                });
            assertTrue(bow.releaseUsing(weapon, shot.level, shot.shooter, 71980));
            projectiles.verifyNoInteractions();
        }

        assertEquals(remainingAmmo, ammo.getCount());
        final int fired = cancelled ? 1 : projectileCount;
        assertEquals(fired + 1, shot.order.size());
        assertEquals("sound", shot.order.getLast());
        Mockito.verify(shot.arrow, Mockito.times(fired)).shootFromRotation(shot.shooter, 0, 0, 0, 3, 0);
        Mockito.verify(weapon, Mockito.times(cancelled ? 0 : projectileCount)).hurtAndBreak(1, shot.shooter, EquipmentSlot.MAINHAND);
        Mockito.verify(shot.event, Mockito.never()).shouldConsumeItem();
    }

    @Test
    void nestedDrawCannotChangeBowConsumptionAndMultishotConsumesOnlyOnce() {
        ProjectileConsumption.enabled = true;
        final Shot shot = new Shot();
        final BowItem bow = Mockito.mock(BowItem.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(shot.arrow).when(bow).createProjectile(any(), any(), any(), any(), anyBoolean());
        Mockito.doNothing().when(bow).shootProjectile(any(), any(), anyInt(), anyFloat(), anyFloat(), anyFloat(), any(), anyBoolean());
        Mockito.when(shot.event.shouldConsumeItem()).thenReturn(true);
        Mockito.when(shot.event.getProjectile()).thenReturn(Mockito.mock(org.bukkit.entity.Projectile.class));
        final ItemStack weapon = Mockito.spy(new ItemStack(Items.BOW));
        Mockito.doNothing().when(weapon).hurtAndBreak(anyInt(), any(LivingEntity.class), any(EquipmentSlot.class));
        final ItemStack ammo = new ItemStack(Items.ARROW, 2);
        final ItemStack first = new ItemStack(Items.ARROW);
        final ItemStack second = new ItemStack(Items.ARROW);
        second.set(DataComponents.INTANGIBLE_PROJECTILE, Unit.INSTANCE);

        try (final var enchantments = Mockito.mockStatic(EnchantmentHelper.class);
             final var events = Mockito.mockStatic(CraftEventFactory.class)) {
            enchantments.when(() -> EnchantmentHelper.processProjectileCount(any(), any(), any(), anyInt())).thenReturn(1);
            events.when(() -> CraftEventFactory.callEntityShootBowEvent(any(), same(weapon), any(), any(), any(), anyFloat(), eq(true)))
                .thenAnswer(invocation -> {
                    ProjectileWeaponItem.draw(weapon, new ItemStack(Items.ARROW), shot.shooter, false);
                    return shot.event;
                });
            assertTrue(bow.shoot(shot.level, shot.shooter, InteractionHand.MAIN_HAND, weapon,
                new ProjectileWeaponItem.UnrealizedDrawResult(List.of(first, second), ammo), 1, 0, false, null, 1, true));
        }

        assertEquals(1, ammo.getCount());
        Mockito.verify(weapon, Mockito.times(2)).hurtAndBreak(1, shot.shooter, EquipmentSlot.MAINHAND);
    }

    @Test
    void nestedCrossbowChargeReplacementIsNotOverwritten() {
        ProjectileConsumption.enabled = true;
        final Shot shot = new Shot();
        final CrossbowItem crossbow = shot.crossbow();
        shot.weapon.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
        final ChargedProjectiles replacement = ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.FIREWORK_ROCKET)));
        Mockito.when(shot.event.shouldConsumeItem()).thenReturn(true);
        Mockito.when(shot.event.getProjectile()).thenReturn(Mockito.mock(org.bukkit.entity.Projectile.class));

        try (final var enchantments = Mockito.mockStatic(EnchantmentHelper.class);
             final var events = Mockito.mockStatic(CraftEventFactory.class)) {
            events.when(() -> CraftEventFactory.callEntityShootBowEvent(any(), any(), any(), any(), any(), anyFloat(), anyBoolean()))
                .thenAnswer(invocation -> {
                    shot.weapon.set(DataComponents.CHARGED_PROJECTILES, replacement);
                    return shot.event;
                });
            crossbow.performShooting(shot.level, shot.shooter, InteractionHand.MAIN_HAND, shot.weapon, 1, 0, null);
        }
        assertSame(replacement, shot.weapon.get(DataComponents.CHARGED_PROJECTILES));
    }

    private static final class Shot {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        final Player shooter = Mockito.mock(Player.class);
        final AbstractArrow arrow = Mockito.mock(AbstractArrow.class);
        final org.bukkit.craftbukkit.entity.CraftAbstractArrow bukkitArrow = Mockito.mock(org.bukkit.craftbukkit.entity.CraftAbstractArrow.class);
        final EntityShootBowEvent event = Mockito.mock(EntityShootBowEvent.class);
        final ItemStack weapon = Mockito.spy(new ItemStack(Items.CROSSBOW));
        final List<String> order = new ArrayList<>();

        Shot() {
            Mockito.when(shooter.level()).thenReturn(level);
            Mockito.when(shooter.getRandom()).thenReturn(RandomSource.create(1));
            Mockito.when(shooter.getViewVector(1.0F)).thenReturn(new Vec3(0, 0, 1));
            Mockito.when(shooter.getUpVector(1.0F)).thenReturn(new Vec3(0, 1, 0));
            Mockito.when(arrow.getBukkitEntity()).thenReturn(bukkitArrow);
            Mockito.when(event.getProjectile()).thenReturn(bukkitArrow);
            Mockito.doNothing().when(weapon).hurtAndBreak(anyInt(), any(LivingEntity.class), any(EquipmentSlot.class));
            Mockito.doAnswer(invocation -> {
                order.add("sound");
                return null;
            }).when(level).playSound(isNull(), anyDouble(), anyDouble(), anyDouble(), any(SoundEvent.class), nullable(SoundSource.class), anyFloat(), anyFloat());
        }

        CrossbowItem crossbow() {
            final CrossbowItem crossbow = Mockito.mock(CrossbowItem.class, Mockito.CALLS_REAL_METHODS);
            Mockito.doReturn(arrow).when(crossbow).createProjectile(any(), any(), any(), any(), anyBoolean());
            return crossbow;
        }
    }
}
