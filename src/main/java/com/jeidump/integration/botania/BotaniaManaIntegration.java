package com.jeidump.integration.botania;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.resources.I18n;

import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;

import com.jeidump.integration.RecipeDumpIntegration;


/**
 * Exposes Botania mana bars as searchable virtual ingredients.
 */
public final class BotaniaManaIntegration implements RecipeDumpIntegration {

    private static final String MANA_USAGE_KEY = "botaniamisc.manaUsage";
    private static final String MANA_POOL_WRAPPER =
        "vazkii.botania.client.integration.jei.manapool.ManaPoolRecipeWrapper";
    private static final String RUNIC_ALTAR_WRAPPER =
        "vazkii.botania.client.integration.jei.runicaltar.RunicAltarRecipeWrapper";
    private static final int BAR_WIDTH = 102;
    private static final int BAR_HEIGHT = 5;

    @Nullable
    private static Field manaPoolManaField;
    @Nullable
    private static Field runicAltarManaField;

    @Override
    public List<Zone> collectZones(IRecipeCategory<?> category, IRecipeWrapper wrapper)
        throws ReflectiveOperationException {
        String wrapperClassName = wrapper.getClass().getName();
        int mana;
        int x;
        int y;
        if (MANA_POOL_WRAPPER.equals(wrapperClassName)) {
            mana = resolveManaPoolManaField(wrapper.getClass()).getInt(wrapper);
            x = 20;
            y = 50;
        } else if (RUNIC_ALTAR_WRAPPER.equals(wrapperClassName)) {
            mana = resolveRunicAltarManaField(wrapper.getClass()).getInt(wrapper);
            x = 6;
            y = 98;
        } else {
            return Collections.emptyList();
        }

        String label = I18n.format(MANA_USAGE_KEY);
        String tooltipLine = label + ": " + mana;
        VirtualIngredient ingredient = new BotaniaManaVirtualIngredient();

        return Collections.singletonList(Zone.ingredient(
            x,
            y,
            BAR_WIDTH,
            BAR_HEIGHT,
            "in",
            ingredient,
            Collections.singletonList(tooltipLine)
        ));
    }

    private static Field resolveManaPoolManaField(Class<?> wrapperClass) throws NoSuchFieldException {
        if (manaPoolManaField != null) return manaPoolManaField;

        manaPoolManaField = wrapperClass.getDeclaredField("mana");
        manaPoolManaField.setAccessible(true);
        return manaPoolManaField;
    }

    private static Field resolveRunicAltarManaField(Class<?> wrapperClass) throws NoSuchFieldException {
        if (runicAltarManaField != null) return runicAltarManaField;

        runicAltarManaField = wrapperClass.getDeclaredField("manaUsage");
        runicAltarManaField.setAccessible(true);
        return runicAltarManaField;
    }
}