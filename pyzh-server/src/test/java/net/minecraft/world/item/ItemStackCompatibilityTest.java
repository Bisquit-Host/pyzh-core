package net.minecraft.world.item;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.Level;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class ItemStackCompatibilityTest {
    @Test
    void typeChangesRetainEquipmentSubscriptionsAndDetachOldComponents() throws Exception {
        final ItemStack stack = new ItemStack(Items.DIAMOND_HELMET);
        final EntityEquipment equipment = new EntityEquipment();
        equipment.set(EquipmentSlot.HEAD, stack);
        equipment.lithium$handleAddedToLevel(Mockito.mock(Level.class));
        equipment.lithium$onEquipmentChangesSent();
        assertFalse(equipment.lithium$hasUnsentEquipmentChanges());
        final Field components = ItemStack.class.getDeclaredField("components");
        components.setAccessible(true);
        final PatchedDataComponentMap oldComponents = (PatchedDataComponentMap)components.get(stack);

        stack.setItem(Items.IRON_HELMET);
        assertTrue(equipment.lithium$hasUnsentEquipmentChanges());
        assertTrue(stack.lithium$isSubscribedWithData(equipment, 0));
        assertEquals(1, stack.getCount());
        equipment.lithium$onEquipmentChangesSent();

        oldComponents.set(DataComponents.DAMAGE, 3);
        assertFalse(equipment.lithium$hasUnsentEquipmentChanges());
        stack.setDamageValue(4);
        assertTrue(equipment.lithium$hasUnsentEquipmentChanges());
        equipment.lithium$onEquipmentChangesSent();
        stack.setItem(Items.DIAMOND_HELMET);
        assertEquals(4, stack.getDamageValue());
        assertTrue(equipment.lithium$hasUnsentEquipmentChanges());
    }

    @Test
    void normalDamageBreaksGlidersAndCallsOnBreak() {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        final ItemStack stack = new ItemStack(Items.ELYTRA);
        stack.setDamageValue(stack.getMaxDamage() - 1);
        final List<Item> broken = new ArrayList<>();

        stack.hurtAndBreak(1, level, null, broken::add);

        assertEquals(0, stack.getCount());
        assertEquals(List.of(Items.ELYTRA), broken);
    }
}
