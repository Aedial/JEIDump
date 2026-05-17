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
 *   <li>Whether locale data is emitted as chunked files or as one monolithic payload</li>
 *   <li>Whether JSON exports should compact redundant slot metadata</li>
 *   <li>Whether recipe backgrounds should be split into shared per-category layers</li>
 *   <li>How many split-pass image operations may run per client tick</li>
 * </ul>
 * </p>
 * <p>
 * Supports in-game modification via the Forge config GUI.
 * </p>
 */
public class JeiDumpConfig {

    public enum ExportFormat {
        HTML("HTML"),
        JSON("JSON");

        private final String serializedName;

        ExportFormat(String serializedName) {
            this.serializedName = serializedName;
        }

        public String getSerializedName() {
            return serializedName;
        }

        public static ExportFormat fromSerializedName(String value) {
            for (ExportFormat format : values()) {
                if (format.serializedName.equalsIgnoreCase(value)) return format;
            }

            return HTML;
        }
    }

    public static final String CATEGORY_GENERAL = "general";
    private static final String[] EXPORT_FORMAT_VALUES = {
        ExportFormat.HTML.getSerializedName(),
        ExportFormat.JSON.getSerializedName()
    };

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
     * Whether the dumper should write PNG captures for recipes and ingredient icons.
     */
    private static boolean captureImages = true;

    /**
     * Output shell format. HTML keeps the static browser UI, JSON exports data files only.
     */
    private static ExportFormat exportFormat = ExportFormat.HTML;

    /**
     * Whether locale data should be emitted as chunked category/resource files instead of one
     * monolithic payload per locale.
     */
    private static boolean chunkDataFiles = true;

    /**
     * Whether JSON exports should omit slot layout geometry and redundant list-backed slot
     * entries that add no tooltip override beyond inputs/outputs.
     */
    private static boolean compactJsonSlots = false;

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

    public static boolean isImageCaptureEnabled() {
        return captureImages;
    }

    public static ExportFormat getExportFormat() {
        return exportFormat;
    }

    public static boolean isChunkDataFilesEnabled() {
        return chunkDataFiles;
    }

    public static boolean isCompactJsonSlotsEnabled() {
        return compactJsonSlots;
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
            "captureImages", true,
            "Capture recipe and ingredient PNG images during the dump. Disable this for a data-only export that keeps tooltips, slots and recipe links but skips image rendering entirely."
        );
        p.setLanguageKey(Tags.MODID + ".config.captureImages");
        captureImages = p.getBoolean();

        p = config.get(CATEGORY_GENERAL,
            "exportFormat", ExportFormat.HTML.getSerializedName(),
            "Choose the dump shell format. html keeps the bundled browser UI, while json writes only the exported data files for external tooling.",
            EXPORT_FORMAT_VALUES
        );
        p.setValidValues(EXPORT_FORMAT_VALUES);
        p.setLanguageKey(Tags.MODID + ".config.exportFormat");
        exportFormat = ExportFormat.fromSerializedName(p.getString());

        p = config.get(CATEGORY_GENERAL,
            "chunkDataFiles", true,
            "Emit locale data as chunked category/resource files for both html and json exports. Disable this to use the monolithic per-locale payload for both formats."
        );
        p.setLanguageKey(Tags.MODID + ".config.chunkDataFiles");
        chunkDataFiles = p.getBoolean();

        p = config.get(CATEGORY_GENERAL,
            "compactJsonSlots", false,
            "When exportFormat is json, omit slot x/y/width/height data and drop ingredient-backed slot entries that add no tooltip override beyond inputs/outputs. HTML exports ignore this and keep full hotspot data."
        );
        p.setLanguageKey(Tags.MODID + ".config.compactJsonSlots");
        compactJsonSlots = p.getBoolean();

        p = config.get(CATEGORY_GENERAL,
            "splitRecipeBackgrounds", false,
            "Extract shared recipe backgrounds into a separate image per category when that reduces the total dump size. " +
            "Disable this to keep every recipe as a standalone PNG. Runs best with a lot of memory allocated, " +
            "as categories that are too large will be left unsplit to avoid out-of-memory crashes. "
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
