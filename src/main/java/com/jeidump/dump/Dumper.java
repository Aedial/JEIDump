package com.jeidump.dump;

import java.awt.Rectangle;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.client.Minecraft;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;

import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.gui.IGuiIngredient;
import mezz.jei.api.gui.IGuiIngredientGroup;
import mezz.jei.api.gui.ITooltipCallback;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRegistry;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IIngredientType;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;

import com.jeidump.JeiDump;
import com.jeidump.command.CommandDumpJei;
import com.jeidump.config.JeiDumpConfig;
import com.jeidump.integration.DumpIntegrations;
import com.jeidump.integration.RecipeDumpIntegration;
import com.jeidump.i18n.JeiDumpLocales;


/**
 * Stateful JEI dumper.
 *
 * The work is split into three phases:
 * <ol>
 *   <li>{@link #setup()}: prepares the output directory tree and pre-counts recipes.</li>
 *   <li>{@link #step(int, int)}: processes recipe renders and, once they are done, background
 *       split image work in small batches per call. Returns
 *       {@code true} while there is more work to do; the caller (a Forge {@code ClientTickEvent}
 *       handler) invokes this once per tick to keep the OS event loop alive.</li>
 *   <li>{@link #finish()}: writes locale-aware data files and copies the bundled web frontend.</li>
 * </ol>
 *
 * Output structure (relative to {@code outDir}):
 * <pre>
 *   index.html, assets/style.css, assets/app.js
 *   assets/lang/<locale>.lang, assets/lang/index.json, assets/lang/index.js
 *   data/manifest.json, data/manifest.js
 *   data/locales/<locale>/index.json
 *   data/locales/<locale>/categories/<cat>/background.png (when deduplication wins)
 *   data/locales/<locale>/categories/<cat>/recipe_N.png
 *   data/locales/<locale>/ingredients/<kind>/<id>.png
 * </pre>
 *
 * Per-recipe JSON now also includes:
 * <ul>
 *   <li>{@code img}: the full recipe PNG, or the per-recipe foreground layer when the category
 *       also exposes {@code backgroundImg}.</li>
 *   <li>{@code slots}: array of {@code {x,y,w,h,id,kind,role}} so the frontend can overlay
 *       hotspots that exactly match JEI's layout for hover/tooltip + click navigation. Slots may
 *       point at real JEI ingredients or synthetic recipe details such as Botania mana costs.
 *       Slots may also carry {@code tooltip}/{@code tooltipHtml} overrides when the live JEI
 *       tooltip for that stack differs from the shared ingredient metadata, for example fluids
 *       with different amounts.</li>
 * </ul>
 *
 * Per-category JSON may also include:
 * <ul>
 *   <li>{@code backgroundImg}: category-wide shared background layer, emitted only when splitting
 *       the recipe PNGs actually reduces their total encoded size.</li>
 * </ul>
 *
 * Per-ingredient meta now also includes:
 * <ul>
 *   <li>{@code nameHtml}: ingredient display name with Minecraft formatting codes converted to
 *       HTML spans for the frontend.</li>
 *   <li>{@code tooltip}: array of plain strings (slot-aware JEI NORMAL tooltip, color codes
 *       stripped),
 *       used for search keys and plain-text fallbacks.</li>
 *   <li>{@code tooltipHtml}: array of tooltip lines with Minecraft formatting codes converted to
 *       HTML spans for the frontend.</li>
 *   <li>{@code kind}: ingredient type key, so the frontend can label arbitrary JEI ingredient
 *       kinds without hardcoding item/fluid buckets. Synthetic recipe details can omit
 *       {@code img} when they do not have a standalone icon.</li>
 * </ul>
 *
 * Root metadata also includes:
 * <ul>
 *   <li>{@code generatedAt}: ISO-8601 timestamp captured once when the dump starts, used by the
 *       website footer.</li>
 * </ul>
 */
public class Dumper {

    /** Summary returned to the chat once the dump finishes. */
    public static class Result {
        public int categoryCount;
        public int recipeCount;
        public int iconCount;
    }

    /** Runtime helper/renderer bundle for one JEI ingredient type. */
    private static class IngredientTypeState<T> {
        private final IIngredientType<T> type;
        private final IIngredientHelper<T> helper;
        private final IIngredientRenderer<T> renderer;
        private final String kind;
        private final String labelKey;
        private final File rootDir;
        private int uniqueCount;

        private IngredientTypeState(IIngredientType<T> type, IIngredientHelper<T> helper,
                                    IIngredientRenderer<T> renderer, String kind, String labelKey,
                                    File rootDir) {
            this.type = type;
            this.helper = helper;
            this.renderer = renderer;
            this.kind = kind;
            this.labelKey = labelKey;
            this.rootDir = rootDir;
        }
    }

    /** Present ingredient group on a specific recipe layout. */
    private static class IngredientGroupAccess<T> {
        private final IIngredientType<T> type;
        private final IGuiIngredientGroup<T> group;

        private IngredientGroupAccess(IIngredientType<T> type, IGuiIngredientGroup<T> group) {
            this.type = type;
            this.group = group;
        }
    }

    /** Plain-text and HTML tooltip payload generated from the same JEI tooltip lines. */
    private static class TooltipText {
        private final JsonArray plain = new JsonArray();
        private final JsonArray html = new JsonArray();
    }

    /** Synthetic ingredient-kind metadata for recipe details that are not JEI ingredient types. */
    private static class VirtualIngredientKindState {
        private final String translationKey;
        private final String className;
        private int uniqueCount;

        private VirtualIngredientKindState(String translationKey, String className) {
            this.translationKey = translationKey;
            this.className = className;
        }
    }

    /** Incremental category background split job. */
    private static class BackgroundSplitTask {
        private final JsonObject category;
        private final String backgroundImgPath;
        private final IconRenderer.DeduplicationSession session;

        private BackgroundSplitTask(JsonObject category, String backgroundImgPath,
                                    IconRenderer.DeduplicationSession session) {
            this.category = category;
            this.backgroundImgPath = backgroundImgPath;
            this.session = session;
        }
    }

    private enum WorkPhase {
        RECIPES,
        BACKGROUND_SPLIT,
        READY_TO_FINISH
    }

    private static final String[] RESOURCE_FILES = {
        "assets/jeidump/web/index.html:index.html",
        "assets/jeidump/web/style.css:assets/style.css",
        "assets/jeidump/web/app.js:assets/app.js"
    };

    /**
     * Logical pixels of empty space added on every side of every recipe layout PNG. Some JEI
     * categories (notably modded ones with long titles or arrows that extend past the
     * background) draw outside the box reported by {@code IRecipeCategory#getBackground()};
     * without padding those labels get clipped at the image edge. The frontend hotspots are
     * shifted by the same value so click targets stay aligned.
     */
    private static final int RECIPE_PADDING = 8;

    private final IJeiRuntime runtime;
    private final IIngredientRegistry ingredientRegistry;
    private final File outDir;
    private final ICommandSender sender;
    private final IconRenderer renderer = new IconRenderer();
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final DumpIntegrations integrations = DumpIntegrations.createDefault();
    private final String dumpLocale = JeiDumpLocales.getCurrentLocaleCode();
    private final String generatedAt = Instant.now().toString();
    private final boolean splitRecipeBackgrounds = JeiDumpConfig.splitRecipeBackgrounds;

    /** Real and virtual ingredient metadata keyed by globally unique id. */
    private final Map<String, JsonObject> ingredientMeta = new LinkedHashMap<>();
    /** Inverted index: ingredient id -> list of {cat, idx, role, kind}. */
    private final Map<String, JsonArray> ingredientRecipes = new LinkedHashMap<>();
    /** Duplicate guard for the inverted index, so one recipe card is emitted once per ingredient/role. */
    private final Map<String, Set<String>> ingredientRecipeKeys = new LinkedHashMap<>();
    /** Cached JEI helper/renderer state for each ingredient type actually encountered. */
    private final Map<IIngredientType<?>, IngredientTypeState<?>> ingredientTypes = new LinkedHashMap<>();
    /** Synthetic ingredient kinds for recipe details that JEI draws outside ingredient groups. */
    private final Map<String, VirtualIngredientKindState> virtualIngredientKinds = new LinkedHashMap<>();

    // Cached reflective handle to mezz.jei.gui.ingredients.GuiIngredient#getRect().
    // The interface IGuiIngredient does not expose slot positions, but JEI's only concrete
    // implementation does; reading it is the cleanest way to mirror JEI's hover hotspots
    // without re-deriving them from the recipe category.
    @Nullable
    private static Method getRectMethod;
    private static boolean getRectResolved;

    // Cached reflective handle to mezz.jei.gui.recipes.RecipeLayout#guiIngredientGroups.
    // Reading the populated map avoids creating empty ingredient groups for every registered type
    // on every recipe; if the field layout changes, we fall back to the public API path.
    @Nullable
    private static Field recipeLayoutGroupsField;
    private static boolean recipeLayoutGroupsResolved;

    // Cached reflective handles to JEI's concrete GuiIngredient fields used to rebuild the same
    // slot-specific tooltip data the live overlay would show. The registry-level renderer may use
    // a simpler tooltip mode, which drops per-slot amount lines for fluids and custom ingredients.
    @Nullable
    private static Field guiIngredientRendererField;
    @Nullable
    private static Field guiIngredientTooltipCallbackField;
    @Nullable
    private static Field guiIngredientSlotIndexField;
    @Nullable
    private static Field guiIngredientInputField;
    private static boolean guiIngredientTooltipFieldsResolved;

    @Nullable
    private static IFocus<ItemStack> fallbackFocus;

    // Phase state
    private File dataDir, localesRoot, localeDataDir, catRoot, ingredientRoot;
    @SuppressWarnings("rawtypes")
    private List<IRecipeCategory> categories;
    private int totalRecipes;
    private int catIdx;          // current category index
    private int wrapperIdx;      // current wrapper inside the active category
    private List<IRecipeWrapper> currentWrappers;
    private IRecipeCategory<?> currentCategory;
    private String currentCatId;
    private File currentCatFolder;
    private int currentBgW, currentBgH;
    private JsonObject currentCatObj;
    private JsonArray currentRecipesJson;
    private final JsonArray categoriesJson = new JsonArray();
    private final List<BackgroundSplitTask> backgroundSplitTasks = new ArrayList<>();

    private final Result result = new Result();
    private WorkPhase workPhase = WorkPhase.RECIPES;
    private int backgroundSplitTaskIdx;
    private long dedupSavedBytes;

    public Dumper(IJeiRuntime runtime, IIngredientRegistry ingredientRegistry, File outDir, ICommandSender sender) {
        this.runtime = runtime;
        this.ingredientRegistry = ingredientRegistry;
        this.outDir = outDir;
        this.sender = sender;
    }

    public static Dumper create(IJeiRuntime runtime, IIngredientRegistry ingredientRegistry, File outDir, ICommandSender sender) {
        return new Dumper(runtime, ingredientRegistry, outDir, sender);
    }

    public Result getResult() {
        return result;
    }

    /** Create directories, snapshot categories, pre-count recipes for progress reporting. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void setup() throws IOException {
        dataDir = new File(outDir, "data");
        localesRoot = new File(dataDir, "locales");
        localeDataDir = new File(localesRoot, dumpLocale);
        catRoot = new File(localeDataDir, "categories");
        ingredientRoot = new File(localeDataDir, "ingredients");
        if (!dataDir.mkdirs() && !dataDir.exists()) throw new IOException("Cannot create " + dataDir);
        if (!localesRoot.mkdirs() && !localesRoot.exists()) throw new IOException("Cannot create " + localesRoot);
        if (localeDataDir.exists()) {
            deleteTree(localeDataDir.toPath());
        }
        if (!localeDataDir.mkdirs() && !localeDataDir.exists()) throw new IOException("Cannot create " + localeDataDir);
        catRoot.mkdirs();
        ingredientRoot.mkdirs();
        new File(outDir, "assets").mkdirs();

        IRecipeRegistry rr = runtime.getRecipeRegistry();
        categories = rr.getRecipeCategories();
        result.categoryCount = categories.size();

        for (IRecipeCategory cat : categories) {
            totalRecipes += rr.getRecipeWrappers(cat).size();
        }
        CommandDumpJei.info(sender, "jeidump.command.scan_total", totalRecipes, categories.size());

        catIdx = 0;
        wrapperIdx = 0;
        workPhase = WorkPhase.RECIPES;
        backgroundSplitTaskIdx = 0;
        dedupSavedBytes = 0L;
        backgroundSplitTasks.clear();
        primeCurrentCategory();
    }

    /**
     * Process up to {@code recipeBudget} recipe renders, then up to
     * {@code backgroundSplitBudget} background-split image operations. Returns {@code true} if
     * there is more work to do. The caller is expected to invoke this once per client tick so
     * the OS event loop keeps pumping (avoids "Not Responding" / DWM kill).
     */
    public boolean step(int recipeBudget, int backgroundSplitBudget) {
        if (categories == null) return false;

        if (workPhase == WorkPhase.RECIPES) {
            if (stepRecipes(recipeBudget)) return true;

            flushRecipePhase();
            if (!prepareBackgroundSplitTasks()) {
                workPhase = WorkPhase.READY_TO_FINISH;
                return false;
            }

            workPhase = WorkPhase.BACKGROUND_SPLIT;
        }

        if (workPhase == WorkPhase.BACKGROUND_SPLIT) {
            if (stepBackgroundSplits(backgroundSplitBudget)) return true;

            workPhase = WorkPhase.READY_TO_FINISH;
        }

        return false;
    }

    private boolean stepRecipes(int budget) {
        IRecipeRegistry rr = runtime.getRecipeRegistry();
        int processed = 0;

        while (processed < budget) {
            // Skip empty categories or advance past the end of the current one.
            while (currentCategory != null && wrapperIdx >= currentWrappers.size()) {
                finalizeCurrentCategory();
                catIdx++;
                wrapperIdx = 0;
                if (catIdx >= categories.size()) {
                    return false;
                }
                primeCurrentCategory();
            }
            if (currentCategory == null) return false;

            IRecipeWrapper wrapper = currentWrappers.get(wrapperIdx);
            try {
                IRecipeLayoutDrawable layout = createLayoutWithRetry(rr, currentCategory, wrapper);
                if (layout != null) {
                    File pngFile = new File(currentCatFolder, "recipe_" + wrapperIdx + ".png");
                    renderer.renderRecipeLayout(layout, currentBgW, currentBgH, RECIPE_PADDING, pngFile);

                    // Logical canvas size including the padding band. The PNG file itself is this
                    // size multiplied by IconRenderer.RECIPE_SCALE, but the frontend works in
                    // logical units (positions hotspots in % of the logical canvas).
                    int canvasW = currentBgW + RECIPE_PADDING * 2;
                    int canvasH = currentBgH + RECIPE_PADDING * 2;

                    JsonObject recObj = new JsonObject();
                    // During the final deduplication pass this file may be rewritten in place as
                    // a foreground-only layer if the shared background split is smaller on disk.
                    recObj.addProperty("img", localeDataPath("categories/" + currentCatId + "/recipe_" + wrapperIdx + ".png"));
                    recObj.addProperty("w", canvasW);
                    recObj.addProperty("h", canvasH);
                    // Pixel multiplier baked into the PNG. The frontend uses this to display the
                    // image at integer multiples of the logical size (1x, 2x, ...) instead of
                    // stretching it to fit the card width.
                    recObj.addProperty("scale", JeiDumpConfig.recipeScale);

                    JsonArray inputs = new JsonArray();
                    JsonArray outputs = new JsonArray();
                    JsonArray slots = new JsonArray();

                    collectIngredientSlots(layout, currentCatId, wrapperIdx, inputs, outputs, slots);
                    collectInternalIngredientHotspots(currentCategory, wrapper, currentCatId, wrapperIdx, slots);

                    recObj.add("inputs", inputs);
                    recObj.add("outputs", outputs);
                    recObj.add("slots", slots);
                    currentRecipesJson.add(recObj);
                }
            } catch (Throwable t) {
                JeiDump.LOGGER.warn("Skipping recipe #{} of category {}: {}", wrapperIdx, currentCategory.getUid(), t.toString());
            }

            wrapperIdx++;
            result.recipeCount++;
            processed++;

            // TODO: Make that configurable? If we optimize speed enough, maybe we can afford to go faster and have more recipes between updates.
            // Roughly every 200 recipes, post a progress line. Tied to global count, not per-tick
            // budget, so the message density is independent of the tick budget knob.
            if (result.recipeCount % 200 == 0) {
                CommandDumpJei.progress(sender, result.recipeCount, totalRecipes, currentCategory.getTitle());
            }
        }

        return true;
    }

    /** Write locale-aware dump data + copy bundled web assets. Call after {@link #step(int, int)} returns false. */
    public Result finish() throws IOException {
        flushRecipePhase();

        JsonObject root = buildDataRoot();
        writeJson(root, new File(localeDataDir, "index.json"));
        writeLocaleDataScript(root, new File(localeDataDir, "index.js"), dumpLocale);

        writeDataManifest();
        writeLangBundle();

        for (String pair : RESOURCE_FILES) {
            int colon = pair.indexOf(':');
            String src = pair.substring(0, colon);
            File dst = new File(outDir, pair.substring(colon + 1));
            copyResource(src, dst);
        }

        result.iconCount = countRenderedIngredientIcons();

        long finalDumpBytes = measureTreeBytes(outDir.toPath());
        if (!splitRecipeBackgrounds) {
            CommandDumpJei.info(sender, "jeidump.command.dedup.disabled");
        } else if (dedupSavedBytes > 0L) {
            long originalDumpBytes = finalDumpBytes + dedupSavedBytes;
            String previous = formatMiB(originalDumpBytes);
            String current = formatMiB(finalDumpBytes);
            String percent = String.format(Locale.ROOT, "%.1f", finalDumpBytes * 100.0 / originalDumpBytes);
            CommandDumpJei.info(sender, "jeidump.command.dedup.done", previous, current, percent);
        } else {
            CommandDumpJei.info(sender, "jeidump.command.dedup.none");
        }

        return result;
    }

    private void flushRecipePhase() {
        if (currentCategory == null || wrapperIdx < currentWrappers.size()) return;

        finalizeCurrentCategory();
    }

    // ----- per-category bookkeeping -----

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void primeCurrentCategory() {
        if (catIdx >= categories.size()) {
            currentCategory = null;
            return;
        }
        IRecipeCategory<?> cat = categories.get(catIdx);
        currentCategory = cat;
        currentCatId = sanitize(cat.getUid());
        currentCatFolder = new File(catRoot, currentCatId);
        currentCatFolder.mkdirs();
        currentBgW = safePositive(cat.getBackground().getWidth(), 128);
        currentBgH = safePositive(cat.getBackground().getHeight(), 64);

        currentCatObj = new JsonObject();
        currentCatObj.addProperty("id", currentCatId);
        currentCatObj.addProperty("uid", cat.getUid());
        currentCatObj.addProperty("title", cat.getTitle());
        currentCatObj.addProperty("modName", cat.getModName());
        currentRecipesJson = new JsonArray();

        currentWrappers = (List) runtime.getRecipeRegistry().getRecipeWrappers((IRecipeCategory) cat);
    }

    private void finalizeCurrentCategory() {
        if (currentCategory == null) return;

        currentCatObj.addProperty("recipeCount", currentRecipesJson.size());
        currentCatObj.add("recipes", currentRecipesJson);
        categoriesJson.add(currentCatObj);
        currentCategory = null;
    }

    // ----- ingredient collection -----

    private void collectIngredientSlots(IRecipeLayoutDrawable layout, String catId, int recipeIdx,
                                        JsonArray inputs, JsonArray outputs, JsonArray slots) throws IOException {
        for (IngredientGroupAccess<?> access : getIngredientGroups(layout)) {
            collectIngredientGroupSlots(access, catId, recipeIdx, inputs, outputs, slots);
        }
    }

    /**
     * Export recipe details that JEI draws outside ingredient groups as virtual ingredient
     * hotspots, so the website can search and navigate them like normal ingredients.
     */
    private void collectInternalIngredientHotspots(IRecipeCategory<?> category, IRecipeWrapper wrapper,
                                                   String catId, int recipeIdx, JsonArray slots)
        throws ReflectiveOperationException {
        for (RecipeDumpIntegration.Zone zone : integrations.collectZones(category, wrapper)) {
            String ingredientId = null;
            String kind = null;
            TooltipText tooltipOverride = tooltipFromLines(zone.tooltipLines);

            if (zone.ingredient != null) {
                ingredientId = registerVirtualIngredient(zone.ingredient);
                kind = zone.ingredient.kind;
                tooltipOverride = tooltipOverride == null ? null : buildSlotTooltipOverride(ingredientId, tooltipOverride);

                if (zone.role != null) {
                    addInverted(ingredientId, catId, recipeIdx, zone.role, kind);
                }
            }

            addExtraZone(slots, zone, ingredientId, kind, tooltipOverride);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<IngredientGroupAccess<?>> getIngredientGroups(IRecipeLayoutDrawable layout) {
        List<IngredientGroupAccess<?>> groups = new ArrayList<>();
        Map<IIngredientType<?>, IGuiIngredientGroup<?>> presentGroups = readIngredientGroups(layout);
        if (presentGroups != null) {
            for (Map.Entry<IIngredientType<?>, IGuiIngredientGroup<?>> entry : presentGroups.entrySet()) {
                if (entry.getValue().getGuiIngredients().isEmpty()) continue;

                groups.add(new IngredientGroupAccess(entry.getKey(), entry.getValue()));
            }

            return groups;
        }

        for (IIngredientType type : ingredientRegistry.getRegisteredIngredientTypes()) {
            IGuiIngredientGroup<?> group = layout.getIngredientsGroup(type);
            if (group.getGuiIngredients().isEmpty()) continue;

            groups.add(new IngredientGroupAccess(type, group));
        }

        return groups;
    }

    @SuppressWarnings("unchecked")
    private void collectIngredientGroupSlots(IngredientGroupAccess<?> access, String catId, int recipeIdx,
                                             JsonArray inputs, JsonArray outputs, JsonArray slots) throws IOException {
        collectIngredientGroupSlotsTyped((IngredientGroupAccess<Object>) access, catId, recipeIdx, inputs, outputs, slots);
    }

    private <T> void collectIngredientGroupSlotsTyped(IngredientGroupAccess<T> access, String catId, int recipeIdx,
                                                      JsonArray inputs, JsonArray outputs, JsonArray slots) throws IOException {
        IngredientTypeState<T> state = stateForType(access.type);
        for (IGuiIngredient<T> ingredient : access.group.getGuiIngredients().values()) {
            T primary = firstRenderableIngredient(state, ingredient);
            if (primary == null) continue;

            String primaryId = registerIngredient(state, primary, ingredient);
            (ingredient.isInput() ? inputs : outputs).add(new JsonPrimitive(primaryId));
            addSlot(slots, ingredient, primaryId, state.kind, RECIPE_PADDING,
                buildSlotTooltipOverride(primaryId, buildIngredientTooltip(state, ingredient, primary)));

            Set<String> indexedIds = new LinkedHashSet<>();
            indexedIds.add(primaryId);
            for (T value : expandIngredients(state, ingredient.getAllIngredients())) {
                if (!isRenderableIngredient(state, value)) continue;

                indexedIds.add(registerIngredient(state, value, ingredient));
            }

            String role = ingredient.isInput() ? "in" : "out";
            for (String id : indexedIds) {
                addInverted(id, catId, recipeIdx, role, state.kind);
            }
        }
    }

    /**
     * Build a recipe layout, retrying with a non-null placeholder focus if the first attempt
     * NPEs. JEI's API marks the focus argument {@code @Nullable}, but a handful of mod-supplied
     * recipe wrappers (notably some that piggyback on the vanilla crafting category) call
     * {@code Preconditions.checkNotNull(focus)} inside their {@code setRecipe} and crash. The
     * placeholder focus is a dummy OUTPUT focus on a stick; its only purpose is to be non-null,
     * the wrapper's actual ingredients still come from {@code wrapper.getIngredients}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Nullable
    private static IRecipeLayoutDrawable createLayoutWithRetry(IRecipeRegistry rr, IRecipeCategory<?> cat, IRecipeWrapper wrapper) {
        try {
            return rr.createRecipeLayoutDrawable((IRecipeCategory) cat, wrapper, null);
        } catch (NullPointerException npe) {
            IFocus<ItemStack> focus = getFallbackFocus(rr);
            return rr.createRecipeLayoutDrawable((IRecipeCategory) cat, wrapper, focus);
        }
    }

    /**
     * Build (and cache) a non-null {@link IFocus} using JEI's public recipe-registry API.
     */
    private static IFocus<ItemStack> getFallbackFocus(IRecipeRegistry rr) {
        if (fallbackFocus != null) return fallbackFocus;
        ItemStack placeholder = new ItemStack(Items.STICK);
        fallbackFocus = rr.createFocus(IFocus.Mode.OUTPUT, placeholder);
        return fallbackFocus;
    }

    @SuppressWarnings("unchecked")
    private <T> IngredientTypeState<T> stateForType(IIngredientType<T> type) {
        IngredientTypeState<?> cached = ingredientTypes.get(type);
        if (cached != null) return (IngredientTypeState<T>) cached;

        String kind = kindForType(type);
        File rootDir = new File(ingredientRoot, kind);
        IngredientTypeState<T> created = new IngredientTypeState<>(
            type,
            ingredientRegistry.getIngredientHelper(type),
            ingredientRegistry.getIngredientRenderer(type),
            kind,
            labelKeyForType(type),
            rootDir
        );
        ingredientTypes.put(type, created);
        return created;
    }

    @Nullable
    private static <T> T firstRenderableIngredient(IngredientTypeState<T> state, IGuiIngredient<T> ingredient) {
        T displayed = ingredient.getDisplayedIngredient();
        if (isRenderableIngredient(state, displayed)) return displayed;

        return firstNonNull(expandIngredients(state, ingredient.getAllIngredients()));
    }

    private static <T> boolean isRenderableIngredient(IngredientTypeState<T> state, @Nullable T ingredient) {
        if (ingredient == null) return false;
        if (ingredient instanceof ItemStack && ((ItemStack) ingredient).isEmpty()) return false;

        try {
            return state.helper.isValidIngredient(ingredient);
        } catch (Throwable t) {
            return false;
        }
    }

    private static <T> List<T> expandIngredients(IngredientTypeState<T> state, @Nullable List<T> ingredients) {
        if (ingredients == null || ingredients.isEmpty()) return new ArrayList<>();

        List<T> filtered = new ArrayList<>();
        for (T ingredient : ingredients) {
            if (!isRenderableIngredient(state, ingredient)) continue;

            filtered.add(ingredient);
        }
        if (filtered.isEmpty()) return filtered;

        List<T> expanded = state.helper.expandSubtypes(filtered);
        if (expanded == null || expanded.isEmpty()) return filtered;

        List<T> result = new ArrayList<>();
        for (T ingredient : expanded) {
            if (!isRenderableIngredient(state, ingredient)) continue;

            result.add(ingredient);
        }

        return result.isEmpty() ? filtered : result;
    }

    private <T> String registerIngredient(IngredientTypeState<T> state, T ingredient,
                                          @Nullable IGuiIngredient<T> guiIngredient) throws IOException {
        String id = state.kind + ":" + safeUniqueId(state, ingredient);
        if (ingredientMeta.containsKey(id)) return id;

        if (!state.rootDir.mkdirs() && !state.rootDir.exists()) {
            throw new IOException("Cannot create " + state.rootDir);
        }

        String fileStem = fileStemFor(id);
        renderer.renderIngredientIcon(state.renderer, ingredient, new File(state.rootDir, fileStem + ".png"));

        String displayName = safeDisplayName(state, ingredient);
        TooltipText tooltip = buildIngredientTooltip(state, guiIngredient, ingredient);

        JsonObject meta = new JsonObject();
        meta.addProperty("name", stripFormatting(displayName));
        meta.addProperty("nameHtml", formatMinecraftTextToHtml(displayName));
        meta.addProperty("mod", safeModId(state, ingredient));
        meta.addProperty("img", localeDataPath("ingredients/" + state.kind + "/" + fileStem + ".png"));
        meta.addProperty("kind", state.kind);
        meta.add("tooltip", tooltip.plain);
        meta.add("tooltipHtml", tooltip.html);
        ingredientMeta.put(id, meta);
        state.uniqueCount++;
        return id;
    }

    private static <T> String safeUniqueId(IngredientTypeState<T> state, T ingredient) {
        try {
            return state.helper.getUniqueId(ingredient);
        } catch (Throwable t) {
            JeiDump.LOGGER.warn("Falling back to hashed JEI ingredient id for kind {}: {}", state.kind, t.toString());
            return sanitize(String.valueOf(ingredient)) + "_" + Integer.toHexString(String.valueOf(ingredient).hashCode());
        }
    }

    private static <T> String safeDisplayName(IngredientTypeState<T> state, T ingredient) {
        try {
            return state.helper.getDisplayName(ingredient);
        } catch (Throwable t) {
            return String.valueOf(ingredient);
        }
    }

    private static <T> String safeModId(IngredientTypeState<T> state, T ingredient) {
        try {
            return state.helper.getDisplayModId(ingredient);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <T> List<String> readGuiIngredientTooltip(@Nullable IGuiIngredient<T> guiIngredient,
                                                             T ingredient) {
        if (guiIngredient == null) return null;

        resolveGuiIngredientTooltipFields();
        if (guiIngredientRendererField == null || guiIngredientSlotIndexField == null || guiIngredientInputField == null) {
            return null;
        }

        try {
            if (!guiIngredientRendererField.getDeclaringClass().isInstance(guiIngredient)) return null;

            Object rendererValue = guiIngredientRendererField.get(guiIngredient);
            if (!(rendererValue instanceof IIngredientRenderer)) return null;

            List<String> tooltip = ((IIngredientRenderer<T>) rendererValue)
                .getTooltip(Minecraft.getMinecraft(), ingredient, ITooltipFlag.TooltipFlags.NORMAL);
            if (tooltip == null) return null;

            List<String> lines = new ArrayList<>(tooltip);
            Object callbackValue = guiIngredientTooltipCallbackField == null ? null : guiIngredientTooltipCallbackField.get(guiIngredient);
            if (callbackValue instanceof ITooltipCallback) {
                ((ITooltipCallback<T>) callbackValue).onTooltip(
                    guiIngredientSlotIndexField.getInt(guiIngredient),
                    guiIngredientInputField.getBoolean(guiIngredient),
                    ingredient,
                    lines
                );
            }
            return lines;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void resolveGuiIngredientTooltipFields() {
        if (guiIngredientTooltipFieldsResolved) return;

        guiIngredientTooltipFieldsResolved = true;
        try {
            Class<?> guiIngredientClass = Class.forName("mezz.jei.gui.ingredients.GuiIngredient");
            guiIngredientRendererField = guiIngredientClass.getDeclaredField("ingredientRenderer");
            guiIngredientRendererField.setAccessible(true);
            guiIngredientTooltipCallbackField = guiIngredientClass.getDeclaredField("tooltipCallback");
            guiIngredientTooltipCallbackField.setAccessible(true);
            guiIngredientSlotIndexField = guiIngredientClass.getDeclaredField("slotIndex");
            guiIngredientSlotIndexField.setAccessible(true);
            guiIngredientInputField = guiIngredientClass.getDeclaredField("input");
            guiIngredientInputField.setAccessible(true);
        } catch (Throwable t) {
            JeiDump.LOGGER.warn("Cannot resolve GuiIngredient tooltip internals, falling back to registry renderers: {}", t.toString());
        }
    }

    private static void addTooltipLines(TooltipText tooltip, List<String> lines) {
        for (String line : lines) {
            tooltip.plain.add(new JsonPrimitive(stripFormatting(line)));
            tooltip.html.add(new JsonPrimitive(formatMinecraftTextToHtml(line)));
        }
    }

    @Nullable
    private static TooltipText tooltipFromLines(@Nullable List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;

        TooltipText tooltip = new TooltipText();
        addTooltipLines(tooltip, lines);
        return tooltip;
    }

    private TooltipText buildSlotTooltipOverride(String ingredientId, TooltipText slotTooltip) {
        JsonObject meta = ingredientMeta.get(ingredientId);
        if (meta == null) return slotTooltip;
        if (jsonArraysEqual(meta.getAsJsonArray("tooltip"), slotTooltip.plain)
            && jsonArraysEqual(meta.getAsJsonArray("tooltipHtml"), slotTooltip.html)) {
            return null;
        }

        return slotTooltip;
    }

    private static boolean jsonArraysEqual(@Nullable JsonArray left, @Nullable JsonArray right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        if (left.size() != right.size()) return false;

        for (int index = 0; index < left.size(); index++) {
            if (!left.get(index).equals(right.get(index))) return false;
        }

        return true;
    }

    private static <T> TooltipText buildIngredientTooltip(IngredientTypeState<T> state,
                                                          @Nullable IGuiIngredient<T> guiIngredient,
                                                          T ingredient) {
        TooltipText tooltip = new TooltipText();
        List<String> lines = readGuiIngredientTooltip(guiIngredient, ingredient);

        if (lines != null) {
            addTooltipLines(tooltip, lines);
        }

        if (tooltip.plain.size() > 0) return tooltip;

        try {
            lines = state.renderer.getTooltip(Minecraft.getMinecraft(), ingredient, ITooltipFlag.TooltipFlags.NORMAL);
            if (lines != null) addTooltipLines(tooltip, lines);
        } catch (Throwable t) {
            // Fall back to the helper's display name only.
        }

        if (tooltip.plain.size() == 0) {
            String displayName = safeDisplayName(state, ingredient);
            tooltip.plain.add(new JsonPrimitive(stripFormatting(displayName)));
            tooltip.html.add(new JsonPrimitive(formatMinecraftTextToHtml(displayName)));
        }

        return tooltip;
    }

    private static String stripFormatting(@Nullable String line) {
        if (line == null) return "";

        String stripped = TextFormatting.getTextWithoutFormattingCodes(line);
        return stripped == null ? line : stripped;
    }

    private static String formatMinecraftTextToHtml(@Nullable String line) {
        if (line == null || line.isEmpty()) return "";

        StringBuilder out = new StringBuilder(line.length() + 16);
        StringBuilder segment = new StringBuilder(line.length());
        String colorClass = null;
        boolean obfuscated = false;
        boolean bold = false;
        boolean strikethrough = false;
        boolean underline = false;
        boolean italic = false;

        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '\r') continue;

            if (character == '\n') {
                appendHtmlSegment(out, segment, colorClass, obfuscated, bold, strikethrough, underline, italic);
                out.append("<br>");
                colorClass = null;
                obfuscated = false;
                bold = false;
                strikethrough = false;
                underline = false;
                italic = false;
                continue;
            }

            if (character == '§' && index + 1 < line.length()) {
                appendHtmlSegment(out, segment, colorClass, obfuscated, bold, strikethrough, underline, italic);
                char code = Character.toLowerCase(line.charAt(++index));
                switch (code) {
                    case '0':
                    case '1':
                    case '2':
                    case '3':
                    case '4':
                    case '5':
                    case '6':
                    case '7':
                    case '8':
                    case '9':
                    case 'a':
                    case 'b':
                    case 'c':
                    case 'd':
                    case 'e':
                    case 'f':
                        colorClass = "mc-color-" + code;
                        obfuscated = false;
                        bold = false;
                        strikethrough = false;
                        underline = false;
                        italic = false;
                        break;

                    case 'k':
                        obfuscated = true;
                        break;

                    case 'l':
                        bold = true;
                        break;

                    case 'm':
                        strikethrough = true;
                        break;

                    case 'n':
                        underline = true;
                        break;

                    case 'o':
                        italic = true;
                        break;

                    case 'r':
                        colorClass = null;
                        obfuscated = false;
                        bold = false;
                        strikethrough = false;
                        underline = false;
                        italic = false;
                        break;

                    default:
                        break;
                }
                continue;
            }

            segment.append(character);
        }

        appendHtmlSegment(out, segment, colorClass, obfuscated, bold, strikethrough, underline, italic);
        return out.toString();
    }

    private static void appendHtmlSegment(StringBuilder out, StringBuilder segment, @Nullable String colorClass,
                                          boolean obfuscated, boolean bold, boolean strikethrough,
                                          boolean underline, boolean italic) {
        if (segment.length() == 0) return;

        String escaped = escapeHtml(segment.toString());
        segment.setLength(0);
        if (colorClass == null && !obfuscated && !bold && !strikethrough && !underline && !italic) {
            out.append(escaped);
            return;
        }

        out.append("<span class=\"");
        boolean appendedClass = false;
        if (colorClass != null) {
            out.append(colorClass);
            appendedClass = true;
        }
        if (obfuscated) {
            if (appendedClass) out.append(' ');
            out.append("mc-obfuscated");
            appendedClass = true;
        }
        if (bold) {
            if (appendedClass) out.append(' ');
            out.append("mc-bold");
            appendedClass = true;
        }
        if (strikethrough) {
            if (appendedClass) out.append(' ');
            out.append("mc-strikethrough");
            appendedClass = true;
        }
        if (underline) {
            if (appendedClass) out.append(' ');
            out.append("mc-underline");
            appendedClass = true;
        }
        if (italic) {
            if (appendedClass) out.append(' ');
            out.append("mc-italic");
        }
        out.append("\">").append(escaped).append("</span>");
    }

    private static String escapeHtml(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '&':
                    out.append("&amp;");
                    break;

                case '<':
                    out.append("&lt;");
                    break;

                case '>':
                    out.append("&gt;");
                    break;

                case '"':
                    out.append("&quot;");
                    break;

                case '\'':
                    out.append("&#39;");
                    break;

                default:
                    out.append(character);
                    break;
            }
        }
        return out.toString();
    }

    private String registerVirtualIngredient(RecipeDumpIntegration.VirtualIngredient ingredient) {
        String id = ingredient.id;
        if (ingredientMeta.containsKey(id)) return id;

        JsonObject meta = new JsonObject();
        meta.addProperty("name", ingredient.name);
        meta.addProperty("nameHtml", formatMinecraftTextToHtml(ingredient.name));
        meta.addProperty("mod", ingredient.modName);
        meta.addProperty("kind", ingredient.kind);
        ingredientMeta.put(id, meta);

        VirtualIngredientKindState state = virtualIngredientKinds.get(ingredient.kind);
        if (state == null) {
            state = new VirtualIngredientKindState(ingredient.translationKey, ingredient.className);
            virtualIngredientKinds.put(ingredient.kind, state);
        }
        state.uniqueCount++;
        return id;
    }

    private void addInverted(String id, String catId, int recipeIdx, String role, String kind) {
        String refKey = catId + '\n' + recipeIdx + '\n' + role;
        Set<String> seenKeys = ingredientRecipeKeys.get(id);
        if (seenKeys == null) {
            seenKeys = new LinkedHashSet<>();
            ingredientRecipeKeys.put(id, seenKeys);
        }
        if (!seenKeys.add(refKey)) return;

        JsonArray refs = ingredientRecipes.get(id);
        if (refs == null) {
            refs = new JsonArray();
            ingredientRecipes.put(id, refs);
        }
        JsonObject ref = new JsonObject();
        ref.addProperty("cat", catId);
        ref.addProperty("idx", recipeIdx);
        ref.addProperty("role", role);
        ref.addProperty("kind", kind);
        refs.add(ref);
    }

    private static String fileStemFor(String id) {
        String sanitized = sanitize(id);
        if (sanitized.length() > 80) sanitized = sanitized.substring(0, 80);

        return sanitized + "_" + Integer.toHexString(id.hashCode());
    }

    private static String kindForType(IIngredientType<?> type) {
        if (type == VanillaTypes.ITEM) return "item";
        if (type == VanillaTypes.FLUID) return "fluid";

        return sanitize(type.getIngredientClass().getName()).toLowerCase(Locale.ROOT);
    }

    private static String labelKeyForType(IIngredientType<?> type) {
        if (type == VanillaTypes.ITEM) return "jeidump.web.search.type.item";
        if (type == VanillaTypes.FLUID) return "jeidump.web.search.type.fluid";

        // TODO: Find some way to query the ingredient type name.
        //       It should exist in lang files, but the hard part is getting the translation key without hardcoding it per-type.

        // JEI exposes no generic localized ingredient-type label for custom ingredient classes.
        // Use the localized generic "ingredient" label instead of leaking English class names
        // into every translated UI.
        return "jeidump.web.search.type.ingredient";
    }

    /**
     * Append a slot rect to the per-recipe slots array, if we can read the rect via reflection.
     * The rect is shifted by {@code padding} on both axes because the recipe layout is drawn at
     * {@code (padding, padding)} on the padded canvas; without the offset the frontend hotspots
     * would land in the empty band on the top-left of the image.
     */
    private static void addSlot(JsonArray slots, IGuiIngredient<?> ig, String id, String kind, int padding,
                                @Nullable TooltipText tooltipOverride) {
        Rectangle r = readRect(ig);
        if (r == null) return;
        JsonObject slot = new JsonObject();
        slot.addProperty("x", r.x + padding);
        slot.addProperty("y", r.y + padding);
        slot.addProperty("w", r.width);
        slot.addProperty("h", r.height);
        slot.addProperty("id", id);
        slot.addProperty("kind", kind);
        slot.addProperty("role", ig.isInput() ? "in" : "out");
        if (tooltipOverride != null) {
            slot.add("tooltip", tooltipOverride.plain);
            slot.add("tooltipHtml", tooltipOverride.html);
        }
        slots.add(slot);
    }

    private static void addExtraZone(JsonArray slots, RecipeDumpIntegration.Zone zone, @Nullable String id,
                                     @Nullable String kind, @Nullable TooltipText tooltipOverride) {
        JsonObject slot = new JsonObject();
        slot.addProperty("x", zone.x + RECIPE_PADDING);
        slot.addProperty("y", zone.y + RECIPE_PADDING);
        slot.addProperty("w", zone.width);
        slot.addProperty("h", zone.height);
        if (id != null) {
            slot.addProperty("id", id);
        }
        if (kind != null) {
            slot.addProperty("kind", kind);
        }
        if (zone.role != null) {
            slot.addProperty("role", zone.role);
        }
        if (tooltipOverride != null) {
            slot.add("tooltip", tooltipOverride.plain);
            slot.add("tooltipHtml", tooltipOverride.html);
        }
        slots.add(slot);
    }

    @Nullable
    private static Map<IIngredientType<?>, IGuiIngredientGroup<?>> readIngredientGroups(IRecipeLayoutDrawable layout) {
        if (!recipeLayoutGroupsResolved) {
            recipeLayoutGroupsResolved = true;
            try {
                recipeLayoutGroupsField = Class.forName("mezz.jei.gui.recipes.RecipeLayout").getDeclaredField("guiIngredientGroups");
                recipeLayoutGroupsField.setAccessible(true);
            } catch (Throwable t) {
                JeiDump.LOGGER.warn("Cannot resolve RecipeLayout#guiIngredientGroups, falling back to registered-type scan: {}", t.toString());
            }
        }
        if (recipeLayoutGroupsField == null) return null;

        try {
            if (!recipeLayoutGroupsField.getDeclaringClass().isInstance(layout)) return null;

            Object value = recipeLayoutGroupsField.get(layout);
            if (!(value instanceof Map)) return null;

            Map<IIngredientType<?>, IGuiIngredientGroup<?>> groups = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof IIngredientType) || !(entry.getValue() instanceof IGuiIngredientGroup)) continue;

                groups.put((IIngredientType<?>) entry.getKey(), (IGuiIngredientGroup<?>) entry.getValue());
            }
            return groups;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Reflectively read {@code GuiIngredient#getRect()}. Cached on first call.
     * Returns {@code null} if the implementation doesn't expose it (e.g. a custom IGuiIngredient
     * supplied by another mod).
     */
    @Nullable
    private static Rectangle readRect(IGuiIngredient<?> ig) {
        if (!getRectResolved) {
            getRectResolved = true;
            try {
                getRectMethod = Class.forName("mezz.jei.gui.ingredients.GuiIngredient").getMethod("getRect");
            } catch (Throwable t) {
                JeiDump.LOGGER.warn("Cannot resolve GuiIngredient#getRect, slot hotspots will be missing: {}", t.toString());
            }
        }
        if (getRectMethod == null) return null;
        try {
            // The method is declared on the concrete class; accept any IGuiIngredient that is
            // an instance of that class. Other implementations silently get no rect.
            if (!getRectMethod.getDeclaringClass().isInstance(ig)) return null;
            Object o = getRectMethod.invoke(ig);
            return (o instanceof Rectangle) ? (Rectangle) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static <T> T firstNonNull(@Nullable Collection<T> values) {
        if (values == null) return null;
        for (T value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static int safePositive(int v, int fallback) {
        return v > 0 ? v : fallback;
    }

    private static String sanitize(String s) {
        // Disallow path traversal and any character that's awkward across Windows + Unix filesystems.
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private JsonObject buildDataRoot() {
        JsonObject root = new JsonObject();
        root.addProperty("locale", dumpLocale);
        root.addProperty("generatedAt", generatedAt);
        root.add("categories", categoriesJson);

        JsonObject ingredientKindsRoot = new JsonObject();
        for (IngredientTypeState<?> state : ingredientTypes.values()) {
            if (state.uniqueCount == 0) continue;

            JsonObject kind = new JsonObject();
            kind.addProperty("translationKey", state.labelKey);
            kind.addProperty("className", state.type.getIngredientClass().getName());
            kind.addProperty("count", state.uniqueCount);
            ingredientKindsRoot.add(state.kind, kind);
        }
        for (Map.Entry<String, VirtualIngredientKindState> entry : virtualIngredientKinds.entrySet()) {
            VirtualIngredientKindState state = entry.getValue();
            if (state.uniqueCount == 0) continue;

            JsonObject kind = new JsonObject();
            kind.addProperty("translationKey", state.translationKey);
            kind.addProperty("className", state.className);
            kind.addProperty("count", state.uniqueCount);
            ingredientKindsRoot.add(entry.getKey(), kind);
        }
        root.add("ingredientKinds", ingredientKindsRoot);

        JsonObject ingredientsRoot = new JsonObject();
        for (Map.Entry<String, JsonObject> e : ingredientMeta.entrySet()) {
            ingredientsRoot.add(e.getKey(), e.getValue());
        }
        root.add("ingredients", ingredientsRoot);

        JsonObject ingredientRecipesRoot = new JsonObject();
        for (Map.Entry<String, JsonArray> e : ingredientRecipes.entrySet()) {
            ingredientRecipesRoot.add(e.getKey(), e.getValue());
        }
        root.add("ingredientRecipes", ingredientRecipesRoot);
        return root;
    }

    private boolean prepareBackgroundSplitTasks() {
        backgroundSplitTasks.clear();
        backgroundSplitTaskIdx = 0;
        dedupSavedBytes = 0L;
        if (!splitRecipeBackgrounds) return false;

        for (JsonElement categoryElement : categoriesJson) {
            JsonObject category = categoryElement.getAsJsonObject();
            JsonArray recipes = category.getAsJsonArray("recipes");
            if (recipes == null || recipes.size() < 2) continue;

            List<File> recipeFiles = new ArrayList<>();
            for (JsonElement recipeElement : recipes) {
                JsonObject recipe = recipeElement.getAsJsonObject();
                if (!recipe.has("img")) continue;

                recipeFiles.add(new File(outDir, recipe.get("img").getAsString()));
            }
            if (recipeFiles.size() < 2) continue;

            String categoryId = category.get("id").getAsString();
            String backgroundImgPath = localeDataPath("categories/" + categoryId + "/background.png");
            File backgroundFile = new File(new File(catRoot, categoryId), "background.png");
            backgroundSplitTasks.add(new BackgroundSplitTask(
                category,
                backgroundImgPath,
                renderer.startCategoryBackgroundDeduplication(recipeFiles, backgroundFile)
            ));
        }

        if (backgroundSplitTasks.isEmpty()) return false;

        CommandDumpJei.info(sender, "jeidump.command.dedup.start");
        return true;
    }

    private boolean stepBackgroundSplits(int budget) {
        int remaining = Math.max(1, budget);

        while (remaining > 0 && backgroundSplitTaskIdx < backgroundSplitTasks.size()) {
            BackgroundSplitTask task = backgroundSplitTasks.get(backgroundSplitTaskIdx);
            try {
                IconRenderer.DeduplicationStepResult result = task.session.step();
                if (result.consumedImage) remaining--;
                if (!result.complete) continue;

                if (task.session.wasApplied()) {
                    task.category.addProperty("backgroundImg", task.backgroundImgPath);
                    dedupSavedBytes += task.session.getSavedBytes();
                }
            } catch (IOException e) {
                String categoryId = task.category.get("id").getAsString();
                JeiDump.LOGGER.warn("Failed to deduplicate recipe backgrounds for category {}: {}", categoryId, e.toString());
            }

            backgroundSplitTaskIdx++;
        }

        return backgroundSplitTaskIdx < backgroundSplitTasks.size();
    }

    /** Dump manifest used by the website to decide which locale-specific data bundle to load. */
    private void writeDataManifest() throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("latestDumpLocale", dumpLocale);

        JsonArray availableDataLocales = new JsonArray();
        for (String locale : listAvailableDataLocales()) availableDataLocales.add(locale);
        manifest.add("availableDataLocales", availableDataLocales);

        writeJson(manifest, new File(dataDir, "manifest.json"));
        writeGlobalScript(manifest, new File(dataDir, "manifest.js"), "window.__JEI_DUMP_MANIFEST = ");
    }

    /** Copy the bundled lang files and emit a JS-friendly translation table for the website. */
    private void writeLangBundle() throws IOException {
        File langDir = new File(new File(outDir, "assets"), "lang");
        if (!langDir.mkdirs() && !langDir.exists()) throw new IOException("Cannot create " + langDir);

        JsonObject payload = new JsonObject();
        JsonArray availableLocales = new JsonArray();
        JsonObject translations = new JsonObject();

        for (Map.Entry<String, Properties> entry : JeiDumpLocales.loadBundledLangTables().entrySet()) {
            String locale = entry.getKey();
            availableLocales.add(locale);
            copyResource(JeiDumpLocales.resourcePath(locale), new File(langDir, locale + ".lang"));

            JsonObject table = new JsonObject();
            for (String key : entry.getValue().stringPropertyNames()) {
                table.addProperty(key, entry.getValue().getProperty(key));
            }

            translations.add(locale, table);
        }

        payload.add("availableLocales", availableLocales);
        payload.add("translations", translations);

        writeJson(payload, new File(langDir, "index.json"));
        writeGlobalScript(payload, new File(langDir, "index.js"), "window.__JEI_DUMP_I18N = ");
    }

    private List<String> listAvailableDataLocales() {
        List<String> locales = new ArrayList<>();
        File[] children = localesRoot.listFiles(File::isDirectory);
        if (children == null) {
            locales.add(dumpLocale);
            return locales;
        }

        for (File child : children) {
            if (!new File(child, "index.json").isFile()) continue;
            locales.add(child.getName());
        }

        if (!locales.contains(dumpLocale)) locales.add(dumpLocale);

        JeiDumpLocales.sortLocaleCodes(locales);
        return locales;
    }

    private String localeDataPath(String relativePath) {
        return "data/locales/" + dumpLocale + "/" + relativePath;
    }

    private void writeJson(JsonObject root, File dst) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(dst.toPath(), StandardCharsets.UTF_8)) {
            gson.toJson(root, bw);
        }
    }

    private void writeLocaleDataScript(JsonObject root, File dst, String locale) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(dst.toPath(), StandardCharsets.UTF_8)) {
            bw.write("window.__JEI_DUMP_DATASETS = window.__JEI_DUMP_DATASETS || {};\nwindow.__JEI_DUMP_DATASETS[");
            gson.toJson(locale, bw);
            bw.write("] = ");
            gson.toJson(root, bw);
            bw.write(";\n");
        }
    }

    private void writeGlobalScript(JsonObject root, File dst, String prefix) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(dst.toPath(), StandardCharsets.UTF_8)) {
            bw.write(prefix);
            gson.toJson(root, bw);
            bw.write(";\n");
        }
    }

    private int countRenderedIngredientIcons() {
        int count = 0;
        for (JsonObject meta : ingredientMeta.values()) {
            if (meta.has("img")) count++;
        }
        return count;
    }

    private static String formatMiB(long bytes) {
        return String.format(Locale.ROOT, "%.2f MiB", bytes / 1048576.0d);
    }

    private static long measureTreeBytes(Path root) throws IOException {
        final long[] total = {0L};
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                total[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });

        return total[0];
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Copy a classpath resource to disk. */
    private static void copyResource(String resource, File dst) throws IOException {
        try (InputStream in = Dumper.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing bundled resource: " + resource);
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            Files.copy(in, dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
