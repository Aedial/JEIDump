package com.jeidump.integration;

import java.util.List;


/**
 * Registers all dump integrations owned by one mod.
 */
public interface RecipeDumpModRegistry {

    String getModId();

    void register(List<RecipeDumpIntegration> integrations);
}