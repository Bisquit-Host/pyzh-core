package io.papermc.paper.inventory.recipe;

import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@VanillaFeature
class ShapelessRecipeOverlapTest {
    @Test
    void matchesWhenGreedyAssignmentPicksTheWrongIngredient() {
        final ShapelessRecipe recipe = new ShapelessRecipe(
            new Recipe.CommonInfo(true),
            new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""),
            new ItemStackTemplate(Items.DIAMOND),
            List.of(Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS), Ingredient.of(Items.OAK_PLANKS))
        );
        assertTrue(matches(recipe, Items.OAK_PLANKS, Items.BIRCH_PLANKS));
        assertTrue(matches(recipe, Items.BIRCH_PLANKS, Items.OAK_PLANKS));
        assertFalse(matches(recipe, Items.BIRCH_PLANKS, Items.BIRCH_PLANKS));
        assertFalse(matches(recipe, Items.OAK_PLANKS, Items.STONE));
    }

    private static boolean matches(final ShapelessRecipe recipe, final Item first, final Item second) {
        return recipe.matches(CraftingInput.of(2, 1, List.of(new ItemStack(first), new ItemStack(second))), null);
    }
}
