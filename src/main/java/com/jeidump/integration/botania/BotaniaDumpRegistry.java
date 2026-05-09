package com.jeidump.integration.botania;

import java.util.List;

import com.jeidump.integration.RecipeDumpIntegration;
import com.jeidump.integration.RecipeDumpModRegistry;


/**
 * Registers all Botania-specific dump integrations.
 */
public final class BotaniaDumpRegistry implements RecipeDumpModRegistry {

    private static final String MOD_ID = "botania";

    @Override
    public String getModId() {
        return MOD_ID;
    }

    @Override
    public void register(List<RecipeDumpIntegration> integrations) {
        integrations.add(new BotaniaManaIntegration());
    }
}