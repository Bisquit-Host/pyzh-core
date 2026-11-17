package net.minecraft.world.inventory;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.fixes.MCBugFix;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@LeafTest
class ResultSlotCraftedCountTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void combinerShiftClickPassesThePaperRemainderOrTheFullCraft(boolean enabled) throws Exception {
        ItemCombinerMenu menu = Mockito.mock(ItemCombinerMenu.class);
        Mockito.when(menu.getResultSlot()).thenReturn(1);
        Mockito.doCallRealMethod().when(menu).quickMoveStack(any(), eq(1));
        checkShiftClick(menu, enabled);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stonecutterShiftClickPassesThePaperRemainderOrTheFullCraft(boolean enabled) throws Exception {
        StonecutterMenu menu = Mockito.mock(StonecutterMenu.class);
        Mockito.doCallRealMethod().when(menu).quickMoveStack(any(), eq(1));
        checkShiftClick(menu, enabled);
    }

    private static void checkShiftClick(AbstractContainerMenu menu, boolean enabled) throws Exception {
        boolean previous = MCBugFix.mc65198;
        try {
            MCBugFix.mc65198 = enabled;
            Player player = Mockito.mock(Player.class);
            Slot slot = Mockito.mock(Slot.class);
            ItemStack stack = new ItemStack(Items.IRON_INGOT, 4);
            Mockito.when(slot.hasItem()).thenReturn(true);
            Mockito.when(slot.getItem()).thenReturn(stack);
            var slots = AbstractContainerMenu.class.getDeclaredField("slots");
            slots.setAccessible(true);
            slots.set(menu, NonNullList.of(slot, slot, slot));
            Mockito.doAnswer(invocation -> {
                stack.shrink(1);
                return true;
            }).when(menu).moveItemStackTo(same(stack), eq(2), eq(38), eq(true));

            assertEquals(4, menu.quickMoveStack(player, 1).getCount());
            var crafted = org.mockito.ArgumentCaptor.forClass(ItemStack.class);
            Mockito.verify(slot).onTake(Mockito.same(player), crafted.capture());
            assertEquals(enabled ? 4 : 3, crafted.getValue().getCount());
            if (!enabled) assertSame(stack, crafted.getValue());
        } finally {
            MCBugFix.mc65198 = previous;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialCraftCountsFollowTheConfiguredPolicy(boolean enabled) {
        boolean previous = MCBugFix.mc65198;
        try {
            MCBugFix.mc65198 = enabled;
            Player player = Mockito.mock(Player.class);
            ResultSlot slot = new ResultSlot(player, Mockito.mock(CraftingContainer.class), Mockito.mock(Container.class), 0, 0, 0);
            ItemStack carried = Mockito.mock(ItemStack.class);
            Mockito.when(carried.getCount()).thenReturn(4);
            slot.onSwapCraft(1);

            slot.checkTakeAchievements(carried);
            Mockito.verify(carried).onCraftedBy(player, enabled ? 4 : 1);
            slot.checkTakeAchievements(carried);
            Mockito.verify(carried, Mockito.times(1)).onCraftedBy(player, enabled ? 4 : 1);
        } finally {
            MCBugFix.mc65198 = previous;
        }
    }
}
