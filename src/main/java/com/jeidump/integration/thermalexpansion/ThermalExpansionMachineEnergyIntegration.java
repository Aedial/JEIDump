package com.jeidump.integration.thermalexpansion;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;

import com.jeidump.integration.RecipeDumpIntegration;
import com.jeidump.integration.forge.ForgeRfVirtualIngredient;


/**
 * Exposes Thermal Expansion machine RF bars as virtual ingredients.
 */
public final class ThermalExpansionMachineEnergyIntegration implements RecipeDumpIntegration {

    private static final String BASE_WRAPPER_CLASS =
        "cofh.thermalexpansion.plugins.jei.machine.BaseRecipeWrapper";
    private static final int METER_X = 2;
    private static final int METER_Y = 8;
    private static final int METER_WIDTH = 14;
    private static final int METER_HEIGHT = 42;

    @Nullable
    private static Class<?> baseWrapperClass;
    @Nullable
    private static Field energyField;

    @Override
    public List<Zone> collectZones(IRecipeCategory<?> category, IRecipeWrapper wrapper)
        throws ReflectiveOperationException {
        resolveBaseWrapperMembers();
        if (baseWrapperClass == null || energyField == null) {
            return Collections.emptyList();
        }
        if (!baseWrapperClass.isInstance(wrapper)) return Collections.emptyList();

        int energy = energyField.getInt(wrapper);
        List<String> tooltipLines = wrapper.getTooltipStrings(METER_X + 1, METER_Y + 1);
        if (tooltipLines == null) tooltipLines = Collections.emptyList();

        VirtualIngredient ingredient = new ForgeRfVirtualIngredient();

        return Collections.singletonList(Zone.ingredient(
            METER_X,
            METER_Y,
            METER_WIDTH,
            METER_HEIGHT,
            "in",
            ingredient,
            tooltipLines
        ));
    }

    private static void resolveBaseWrapperMembers()
        throws ClassNotFoundException, NoSuchFieldException {
        if (baseWrapperClass != null && energyField != null) return;

        baseWrapperClass = Class.forName(BASE_WRAPPER_CLASS);
        energyField = baseWrapperClass.getDeclaredField("energy");
        energyField.setAccessible(true);
    }
}