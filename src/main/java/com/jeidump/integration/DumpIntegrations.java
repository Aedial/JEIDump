package com.jeidump.integration;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.fml.common.Loader;

import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;

import com.jeidump.integration.botania.BotaniaDumpRegistry;
import com.jeidump.integration.enderiomachines.EnderIOMachinesDumpRegistry;
import com.jeidump.integration.thermalexpansion.ThermalExpansionDumpRegistry;


/**
 * Activates mod-specific dump integrations only when their owning mod is present.
 */
public final class DumpIntegrations {

    private final List<RecipeDumpIntegration> integrations;

    private DumpIntegrations(List<RecipeDumpIntegration> integrations) {
        this.integrations = integrations;
    }

    public static DumpIntegrations createDefault() {
        List<RecipeDumpIntegration> integrations = new ArrayList<>();

        integrations.add(new ConfiguredTooltipZoneIntegration());
        registerIfLoaded(integrations, new BotaniaDumpRegistry());
        registerIfLoaded(integrations, new EnderIOMachinesDumpRegistry());
        registerIfLoaded(integrations, new ThermalExpansionDumpRegistry());

        return new DumpIntegrations(integrations);
    }

    public boolean maySkipRecipes(IRecipeCategory<?> category) {
        for (RecipeDumpIntegration integration : integrations) {
            if (integration.maySkipRecipe(category)) return true;
        }

        return false;
    }

    public boolean shouldSkipRecipe(IRecipeCategory<?> category, IRecipeWrapper wrapper,
                                    IIngredients ingredients) {
        for (RecipeDumpIntegration integration : integrations) {
            if (!integration.maySkipRecipe(category)) continue;
            if (integration.shouldSkipRecipe(category, wrapper, ingredients)) return true;
        }

        return false;
    }

    public List<RecipeDumpIntegration.Zone> collectZones(IRecipeCategory<?> category,
                                                         IRecipeWrapper wrapper)
        throws ReflectiveOperationException {
        List<RecipeDumpIntegration.Zone> zones = new ArrayList<>();
        for (RecipeDumpIntegration integration : integrations) {
            zones.addAll(integration.collectZones(category, wrapper));
        }
        return zones;
    }

    private static void registerIfLoaded(List<RecipeDumpIntegration> integrations,
                                         RecipeDumpModRegistry registry) {
        if (!Loader.isModLoaded(registry.getModId())) return;

        registry.register(integrations);
    }
}