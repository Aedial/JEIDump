package com.jeidump.integration.enderiomachines;

import java.util.List;

import com.jeidump.integration.RecipeDumpIntegration;
import com.jeidump.integration.RecipeDumpModRegistry;


/**
 * Registers Ender IO machine dump integrations.
 */
public final class EnderIOMachinesDumpRegistry implements RecipeDumpModRegistry {

    private static final String MOD_ID = "enderiomachines";

    @Override
    public String getModId() {
        return MOD_ID;
    }

    @Override
    public void register(List<RecipeDumpIntegration> integrations) {
        integrations.add(new EnderIOTankRecipeFilterIntegration());
    }
}