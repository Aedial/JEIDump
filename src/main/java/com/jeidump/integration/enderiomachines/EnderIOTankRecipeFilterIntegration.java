package com.jeidump.integration.enderiomachines;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;

import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;

import com.jeidump.integration.RecipeDumpIntegration;


/**
 * Filters JEI's noisy Ender IO tank helper recipes from the dump.
 */
public final class EnderIOTankRecipeFilterIntegration implements RecipeDumpIntegration {

    private static final String CATEGORY_UID = "EIOTank";

    @Override
    public List<Zone> collectZones(IRecipeCategory<?> category, IRecipeWrapper wrapper) {
        return Collections.emptyList();
    }

    @Override
    public boolean maySkipRecipe(IRecipeCategory<?> category) {
        return CATEGORY_UID.equals(category.getUid());
    }

    @Override
    public boolean shouldSkipRecipe(IRecipeCategory<?> category, IRecipeWrapper wrapper,
                                    IIngredients ingredients) {
        if (!CATEGORY_UID.equals(category.getUid())) return false;

        List<ItemStack> inputs = collectPrimaryItemStacks(ingredients.getInputs(VanillaTypes.ITEM));
        List<ItemStack> outputs = collectPrimaryItemStacks(ingredients.getOutputs(VanillaTypes.ITEM));
        if (inputs.size() != 1 || outputs.size() != 1) return false;

        ItemStack input = inputs.get(0);
        ItemStack output = outputs.get(0);
        if (isSingleItemRepairRecipe(input, output)) return true;
        if (isExactSameItemStack(input, output)) return false;

        // The Ender IO tank category is dominated by fill and empty helpers. Any one-to-one
        // recipe that touches a Forge fluid container is navigation noise in the exported dump.
        return isForgeFluidContainer(input) || isForgeFluidContainer(output);
    }

    private static List<ItemStack> collectPrimaryItemStacks(List<List<ItemStack>> slots) {
        List<ItemStack> primaryStacks = new ArrayList<>();
        for (List<ItemStack> slot : slots) {
            ItemStack primary = firstPresentItemStack(slot);
            if (primary == null) continue;

            primaryStacks.add(primary);
        }

        return primaryStacks;
    }

    @Nullable
    private static ItemStack firstPresentItemStack(List<ItemStack> slot) {
        for (ItemStack stack : slot) {
            if (stack == null || stack.isEmpty()) continue;

            return stack;
        }

        return null;
    }

    private static boolean isSingleItemRepairRecipe(ItemStack input, ItemStack output) {
        if (!isRepairableStack(input) || !isRepairableStack(output)) return false;

        return isSameRepairRecipeItem(input, output);
    }

    private static boolean isForgeFluidContainer(ItemStack stack) {
        return !stack.isEmpty() && stack.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, null);
    }

    private static boolean isExactSameItemStack(ItemStack left, ItemStack right) {
        if (left.isEmpty() || right.isEmpty()) return false;
        if (left.getItem() != right.getItem()) return false;
        if (left.getMetadata() != right.getMetadata()) return false;
        if (left.getCount() != right.getCount()) return false;

        return ItemStack.areItemStackTagsEqual(left, right);
    }

    private static boolean isRepairableStack(ItemStack stack) {
        return !stack.isEmpty() && stack.isItemStackDamageable();
    }

    /**
     * Ender IO generates one tank recipe wrapper per durability pair, so once the stack is
     * damageable, matching the underlying item is enough to identify the noisy repair family.
     */
    private static boolean isSameRepairRecipeItem(ItemStack left, ItemStack right) {
        return !left.isEmpty() && !right.isEmpty() && left.getItem() == right.getItem();
    }

}