package com.jeidump.integration.botania;

import net.minecraft.client.resources.I18n;

import com.jeidump.integration.RecipeDumpIntegration;


/**
 * Shared Botania mana ingredient metadata used by any integration that exposes mana values.
 */
public final class BotaniaManaVirtualIngredient extends RecipeDumpIntegration.VirtualIngredient {

    private static final String KIND = "mana";
    private static final String LABEL_KEY = "jeidump.web.search.type.mana";
    private static final String CLASS_NAME = "internal.botania.Mana";
    private static final String ID = "botania:mana";
    private static final String MOD_ID = "botania";

    public BotaniaManaVirtualIngredient() {
        super(KIND, LABEL_KEY, CLASS_NAME, ID, I18n.format(LABEL_KEY), MOD_ID);
    }
}