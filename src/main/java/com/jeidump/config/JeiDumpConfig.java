package com.jeidump.config;

import java.io.File;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.jeidump.Tags;


/**
 * Configuration for the JEI Dump mod.
 * <p>
 * Provides configurable values for:
 * <ul>
 *   <li>Default number of recipes processed per client tick during a dump</li>
 *   <li>Recipe layout render scale (pixel multiplier for output PNGs)</li>
 *   <li>Whether recipe backgrounds should be split into shared per-category layers</li>
 *   <li>How many split-pass image operations may run per client tick</li>
 * </ul>
 * </p>
 * <p>
 * Supports in-game modification via the Forge config GUI.
 * </p>
 */
public class JeiDumpConfig {

    public static final String CATEGORY_GENERAL = "general";

    private static Configuration config;
    private static File configDir;

    /**
     * Default number of recipes processed per client tick during a dump.
     * Can be overridden per-run via {@code /dumpjei <folder> <recipesPerTick>}.
     */
    public static int defaultRecipesPerTick = 5;

    /**
     * Pixel multiplier applied to recipe layout PNG renders.
     * 1 = native resolution, 3 = 3x (default). Higher values produce crisper images
     * but use more disk space and memory. Requires a new dump to take effect.
     */
    public static int recipeScale = 3;

    /**
     * Whether the dumper should extract shared recipe backgrounds into a separate image per
     * category when that reduces the total dump size.
     */
    public static boolean splitRecipeBackgrounds = false;

    /**
     * Maximum number of background-splitting image operations processed per client tick.
     * Lower values reduce UI hitching during the post-processing phase.
     */
    public static int backgroundSplitImagesPerTick = 20;

    /**
     * Initializes the configuration from the given file.
     *
     * @param configFile The configuration file
     */
    public static void init(File configFile) {
        if (config == null) {
            configDir = configFile.getParentFile();
            config = new Configuration(configFile);
            loadConfig();
        }
    }

    /**
     * Gets the Forge config directory containing the JEI Dump config file.
     */
    public static File getConfigDir() {
        return configDir;
    }

    /**
     * Gets the configuration instance for the GUI.
     *
     * @return The configuration instance
     */
    public static Configuration getConfig() {
        return config;
    }

    /**
     * Loads all configuration values from file.
     */
    public static void loadConfig() {
        config.getCategory(CATEGORY_GENERAL).setLanguageKey(Tags.MODID + ".config.category.general");
        config.addCustomCategoryComment(CATEGORY_GENERAL, "General settings for JEI Dump.");

        Property p = config.get(CATEGORY_GENERAL,
            "defaultRecipesPerTick", 5,
            "Default number of recipes processed per client tick during a dump. " +
            "Higher values are faster but may make the game less responsive. " +
            "Can be overridden per-run with /dumpjei <folder> <recipesPerTick>.",
            1, 200
        );
        p.setLanguageKey(Tags.MODID + ".config.defaultRecipesPerTick");
        defaultRecipesPerTick = p.getInt();

        p = config.get(CATEGORY_GENERAL,
            "recipeScale", 3,
            "Pixel multiplier applied to recipe layout PNG renders. " +
            "1 = native resolution, 2 = 2x, 3 = 3x (default), etc. " +
            "Higher values produce crisper images but use more disk space and memory. " +
            "Requires a new dump to take effect.",
            1, 8
        );
        p.setLanguageKey(Tags.MODID + ".config.recipeScale");
        recipeScale = p.getInt();

        p = config.get(CATEGORY_GENERAL,
            "splitRecipeBackgrounds", false,
            "Extract shared recipe backgrounds into a separate image per category when that reduces the total dump size. " +
            "Disable this to keep every recipe as a standalone PNG. May consume a lot during splitting, enable with caution on bigger modpacks."
        );
        p.setLanguageKey(Tags.MODID + ".config.splitRecipeBackgrounds");
        splitRecipeBackgrounds = p.getBoolean();

        p = config.get(CATEGORY_GENERAL,
            "backgroundSplitImagesPerTick", 20,
            "Maximum number of background-splitting image operations processed per client tick after recipe rendering finishes. " +
            "Lower values reduce hitching but make the split phase take longer.",
            1, 500
        );
        p.setLanguageKey(Tags.MODID + ".config.backgroundSplitImagesPerTick");
        backgroundSplitImagesPerTick = p.getInt();

        if (config.hasChanged()) config.save();
    }

    /**
     * Event handler for config changes from the in-game GUI.
     *
     * @param event The config changed event
     */
    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (event.getModID().equals(Tags.MODID)) loadConfig();
    }
}
