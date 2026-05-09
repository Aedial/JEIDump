package com.jeidump.integration.forge;

import net.minecraft.client.resources.I18n;

import com.jeidump.integration.RecipeDumpIntegration;


/**
 * Shared RF ingredient metadata used by any integration that exposes Forge-owned RF values.
 */
public final class ForgeRfVirtualIngredient extends RecipeDumpIntegration.VirtualIngredient {

    private static final String KIND = "rf";
    private static final String LABEL_KEY = "jeidump.web.search.type.rf";
    private static final String CLASS_NAME = "internal.forge.RF";
    private static final String ID = "forge:rf";
    private static final String MOD_ID = "forge";

    public ForgeRfVirtualIngredient() {
        super(KIND, LABEL_KEY, CLASS_NAME, ID, I18n.format(LABEL_KEY), MOD_ID);
    }
}