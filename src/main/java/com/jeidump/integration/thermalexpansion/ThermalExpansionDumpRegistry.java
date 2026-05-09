package com.jeidump.integration.thermalexpansion;

import java.util.List;

import com.jeidump.integration.RecipeDumpIntegration;
import com.jeidump.integration.RecipeDumpModRegistry;


/**
 * Registers all Thermal Expansion-specific dump integrations.
 */
public final class ThermalExpansionDumpRegistry implements RecipeDumpModRegistry {

    private static final String MOD_ID = "thermalexpansion";

    @Override
    public String getModId() {
        return MOD_ID;
    }

    @Override
    public void register(List<RecipeDumpIntegration> integrations) {
        integrations.add(new ThermalExpansionMachineEnergyIntegration());
        integrations.add(new ThermalExpansionDynamoEnergyIntegration());
    }
}