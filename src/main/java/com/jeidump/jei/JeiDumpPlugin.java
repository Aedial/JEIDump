package com.jeidump.jei;

import javax.annotation.Nullable;

import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.ingredients.IIngredientRegistry;


/**
 * Captures the {@link IJeiRuntime} given to us once JEI has finished registering everything.
 *
 * JEI hands us the stable registration-time {@link IIngredientRegistry} in
 * {@link #register(IModRegistry)}, while {@link #onRuntimeAvailable(IJeiRuntime)} provides the
 * live {@link IJeiRuntime} for recipe access.
 */
@JEIPlugin
public class JeiDumpPlugin implements IModPlugin {

    @Nullable
    private static IJeiRuntime runtime;
    @Nullable
    private static IIngredientRegistry ingredientRegistry;

    /**
     * Returns the captured JEI runtime, or {@code null} if JEI has not yet finished its post-init.
     * The dumper command checks this and bails out early with a friendly message if it is null.
     */
    @Nullable
    public static IJeiRuntime getRuntime() {
        return runtime;
    }

    /**
     * Returns JEI's ingredient registry captured during plugin registration.
     */
    @Nullable
    public static IIngredientRegistry getIngredientRegistry() {
        return ingredientRegistry;
    }

    @Override
    public void register(IModRegistry registry) {
        ingredientRegistry = registry.getIngredientRegistry();
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }
}
