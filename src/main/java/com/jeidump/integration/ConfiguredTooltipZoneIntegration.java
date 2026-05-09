package com.jeidump.integration;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import net.minecraftforge.fml.common.Loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;

import com.jeidump.JeiDump;
import com.jeidump.config.JeiDumpConfig;


/**
 * Loads configured recipe zones that sample the owning mod's JEI tooltip.
 */
public final class ConfiguredTooltipZoneIntegration implements RecipeDumpIntegration {

    private static final Gson GSON = new Gson();

    private final List<TooltipZoneDefinition> definitions;

    public ConfiguredTooltipZoneIntegration() {
        this.definitions = loadDefinitions();
    }

    @Override
    public List<Zone> collectZones(IRecipeCategory<?> category, IRecipeWrapper wrapper)
        throws ReflectiveOperationException {
        if (definitions.isEmpty()) return Collections.emptyList();

        List<Zone> zones = new ArrayList<>();
        for (TooltipZoneDefinition definition : definitions) {
            if (!definition.matches(category, wrapper)) continue;

            List<String> tooltipLines = definition.collectTooltipLines(wrapper);
            if (tooltipLines == null || tooltipLines.isEmpty()) continue;

            zones.add(Zone.tooltip(
                definition.x,
                definition.y,
                definition.width,
                definition.height,
                tooltipLines
            ));
        }
        return zones;
    }

    private static List<TooltipZoneDefinition> loadDefinitions() {
        Path integrationsDir = getIntegrationRootDir();
        if (integrationsDir == null) return Collections.emptyList();
        if (!Files.exists(integrationsDir)) {
            try {
                Files.createDirectories(integrationsDir);
            } catch (IOException e) {
                JeiDump.LOGGER.warn("Cannot create JEI Dump integration config directory {}: {}", integrationsDir, e.toString());
                return Collections.emptyList();
            }

            return Collections.emptyList();
        }

        if (!Files.isDirectory(integrationsDir)) {
            JeiDump.LOGGER.warn("JEI Dump integration config path is not a directory: {}", integrationsDir);
            return Collections.emptyList();
        }

        List<TooltipZoneDefinition> definitions = new ArrayList<>();
        try (Stream<Path> files = Files.walk(integrationsDir)) {
            files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted()
                .forEach(path -> loadDefinitionFile(integrationsDir, path, definitions));
        } catch (IOException e) {
            JeiDump.LOGGER.warn("Cannot read JEI Dump integration config directory {}: {}", integrationsDir, e.toString());
        }

        return definitions;
    }

    @Nullable
    private static Path getIntegrationRootDir() {
        java.io.File configDir = JeiDumpConfig.getConfigDir();
        if (configDir == null) configDir = Loader.instance().getConfigDir();
        if (configDir == null) return null;

        return configDir.toPath().resolve("jeidump").resolve("integrations");
    }

    private static void loadDefinitionFile(Path integrationsDir, Path path,
                                           List<TooltipZoneDefinition> definitions) {
        String modId = getConfiguredModId(integrationsDir, path);
        if (modId == null || !Loader.isModLoaded(modId)) return;

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || root.isJsonNull()) return;

            if (root.isJsonArray()) {
                loadDefinitionArray(path, root.getAsJsonArray(), definitions);
                return;
            }

            if (!root.isJsonObject()) {
                JeiDump.LOGGER.warn("Ignoring JEI Dump integration config {} because it is not a JSON object or array.", path);
                return;
            }

            JsonObject object = root.getAsJsonObject();
            if (object.has("zones") && object.get("zones").isJsonArray()) {
                loadDefinitionArray(path, object.getAsJsonArray("zones"), definitions);
                return;
            }

            TooltipZoneDefinition definition = parseDefinition(path, object);
            if (definition != null) definitions.add(definition);
        } catch (Exception e) {
            JeiDump.LOGGER.warn("Failed to parse JEI Dump integration config {}: {}", path, e.toString());
        }
    }

    @Nullable
    private static String getConfiguredModId(Path integrationsDir, Path path) {
        Path relativePath = integrationsDir.relativize(path);
        if (relativePath.getNameCount() < 2) {
            JeiDump.LOGGER.warn(
                "Ignoring JEI Dump integration config {} because files must be placed under integrations/<modid>/.",
                path
            );

            return null;
        }

        return relativePath.getName(0).toString();
    }

    private static void loadDefinitionArray(Path path, JsonArray zones,
                                            List<TooltipZoneDefinition> definitions) {
        for (JsonElement element : zones) {
            if (!element.isJsonObject()) {
                JeiDump.LOGGER.warn("Ignoring non-object tooltip zone entry in {}.", path);
                continue;
            }

            TooltipZoneDefinition definition = parseDefinition(path, element.getAsJsonObject());
            if (definition != null) definitions.add(definition);
        }
    }

    @Nullable
    private static TooltipZoneDefinition parseDefinition(Path path, JsonObject object) {
        String wrapperClassName = getOptionalString(object, "wrapperClass");
        String categoryUid = getOptionalString(object, "categoryUid");
        if (wrapperClassName == null && categoryUid == null) {
            JeiDump.LOGGER.warn("Ignoring JEI Dump integration config entry in {} because it has no matcher.", path);
            return null;
        }

        try {
            int x = object.get("x").getAsInt();
            int y = object.get("y").getAsInt();
            int width = object.get("width").getAsInt();
            int height = object.get("height").getAsInt();
            if (width <= 0 || height <= 0) {
                JeiDump.LOGGER.warn("Ignoring JEI Dump integration config entry in {} because width/height must be positive.", path);
                return null;
            }

            // Undocumented optional fields for specifying the tooltip query point
            // I do not see a use case for exposing these, they would just confuse users
            // Someone *may* need them, so I didn't remove them entirely ¯\_(ツ)_/¯
            Integer queryX = getOptionalInteger(object, "queryX");
            Integer queryY = getOptionalInteger(object, "queryY");
            if ((queryX == null) != (queryY == null)) {
                JeiDump.LOGGER.warn("Ignoring JEI Dump integration config entry in {} because queryX and queryY must be defined together.", path);
                return null;
            }

            int resolvedQueryX = queryX != null ? queryX : x + width / 2;
            int resolvedQueryY = queryY != null ? queryY : y + height / 2;
            if (resolvedQueryX < x || resolvedQueryX >= x + width ||
                resolvedQueryY < y || resolvedQueryY >= y + height) {
                JeiDump.LOGGER.warn("Ignoring JEI Dump integration config entry in {} because the query point must stay inside the configured zone.", path);
                return null;
            }

            return new TooltipZoneDefinition(wrapperClassName, categoryUid, x, y, width, height,
                resolvedQueryX, resolvedQueryY);
        } catch (Exception e) {
            JeiDump.LOGGER.warn("Ignoring malformed JEI Dump integration config entry in {}: {}", path, e.toString());
            return null;
        }
    }

    @Nullable
    private static String getOptionalString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;

        return object.get(key).getAsString();
    }

    @Nullable
    private static Integer getOptionalInteger(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;

        return object.get(key).getAsInt();
    }

    private static final class TooltipZoneDefinition {
        @Nullable
        private final String wrapperClassName;
        @Nullable
        private final String categoryUid;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final int queryX;
        private final int queryY;

        @Nullable
        private Class<?> wrapperClass;
        private boolean wrapperClassResolved;

        private TooltipZoneDefinition(@Nullable String wrapperClassName, @Nullable String categoryUid,
                                      int x, int y, int width, int height, int queryX, int queryY) {
            this.wrapperClassName = wrapperClassName;
            this.categoryUid = categoryUid;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.queryX = queryX;
            this.queryY = queryY;
        }

        private boolean matches(IRecipeCategory<?> category, IRecipeWrapper wrapper) {
            if (categoryUid != null && !categoryUid.equals(category.getUid())) return false;
            if (wrapperClassName == null) return true;

            Class<?> resolvedWrapperClass = resolveWrapperClass();
            if (resolvedWrapperClass == null) return false;

            return resolvedWrapperClass.isInstance(wrapper);
        }

        @Nullable
        private List<String> collectTooltipLines(IRecipeWrapper wrapper) {
            return wrapper.getTooltipStrings(queryX, queryY);
        }

        @Nullable
        private Class<?> resolveWrapperClass() {
            if (wrapperClassResolved) return wrapperClass;

            wrapperClassResolved = true;
            try {
                wrapperClass = Class.forName(wrapperClassName);
            } catch (ClassNotFoundException e) {
                JeiDump.LOGGER.warn("Ignoring JEI Dump integration wrapperClass {} because it could not be loaded.", wrapperClassName);
                wrapperClass = null;
            }
            return wrapperClass;
        }
    }
}