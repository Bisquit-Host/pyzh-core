package org.bukkit.craftbukkit.inventory;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.Items;
import org.bukkit.inventory.ItemStack;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.purpurmc.purpur.PurpurConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@LeafTest
public class CraftInventoryContentsTest {
    @ParameterizedTest
    @ValueSource(ints = {27, 54})
    void validatesActualCapacityBeforeChangingAnySlot(int size) {
        SimpleContainer container = new SimpleContainer(size);
        net.minecraft.world.item.ItemStack original = new net.minecraft.world.item.ItemStack(Items.STONE);
        container.setItem(0, original);
        CraftInventory inventory = new CraftInventory(container);

        assertThrows(IllegalArgumentException.class, () -> inventory.setContents(new ItemStack[size + 1]));
        assertSame(original, container.getItem(0));
        assertDoesNotThrow(() -> inventory.setContents(new ItemStack[size]));
        assertTrue(container.isEmpty());
    }

    @Test
    void sixRowEnderChestAcceptsItsOwnContentsAndWritesOnlyVisibleRows() {
        final boolean previous = PurpurConfig.enderChestSixRows;
        try {
            PurpurConfig.enderChestSixRows = true;
            final Player owner = mock(Player.class);
            owner.sixRowEnderchestSlotCount = 27;
            final PlayerEnderChestContainer container = new PlayerEnderChestContainer(owner);
            final net.minecraft.world.item.ItemStack hidden = new net.minecraft.world.item.ItemStack(Items.STONE);
            container.setItem(0, new net.minecraft.world.item.ItemStack(Items.DIRT));
            container.setItem(53, hidden);
            final CraftInventory inventory = new CraftInventory(container);

            final ItemStack[] contents = inventory.getContents();
            assertEquals(54, contents.length);
            assertThrows(IllegalArgumentException.class, () -> inventory.setContents(new ItemStack[55]));
            contents[0] = null;
            contents[53] = null;
            inventory.setContents(contents);
            assertTrue(container.getItem(0).isEmpty());
            assertSame(hidden, container.getItem(53));

            PurpurConfig.enderChestSixRows = false;
            assertThrows(IllegalArgumentException.class, () -> inventory.setContents(contents));
        } finally {
            PurpurConfig.enderChestSixRows = previous;
        }
    }
}
