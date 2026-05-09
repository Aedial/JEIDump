package com.jeidump.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;


/**
 * Mod-specific hook for exporting non-slot recipe UI elements.
 * <p>
 * Integrations can expose searchable virtual ingredients such as RF or mana, and can also
 * export plain tooltip-only zones for recipe UI details that should stay non-searchable.
 */
public interface RecipeDumpIntegration {

    List<Zone> collectZones(IRecipeCategory<?> category, IRecipeWrapper wrapper)
        throws ReflectiveOperationException;

    final class Zone {
        public final int x;
        public final int y;
        public final int width;
        public final int height;
        @Nullable
        public final VirtualIngredient ingredient;
        @Nullable
        public final List<String> tooltipLines;
        @Nullable
        public final String role;

        private Zone(int x, int y, int width, int height, @Nullable VirtualIngredient ingredient,
                     @Nullable List<String> tooltipLines, @Nullable String role) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.ingredient = ingredient;
            this.tooltipLines = copyLines(tooltipLines);
            this.role = role;
        }

        public static Zone ingredient(int x, int y, int width, int height, String role,
                                      VirtualIngredient ingredient) {
            return new Zone(x, y, width, height, ingredient, null, role);
        }

        public static Zone ingredient(int x, int y, int width, int height, String role,
                                      VirtualIngredient ingredient, @Nullable List<String> tooltipLines) {
            return new Zone(x, y, width, height, ingredient, tooltipLines, role);
        }

        public static Zone tooltip(int x, int y, int width, int height, List<String> tooltipLines) {
            return new Zone(x, y, width, height, null, tooltipLines, null);
        }
    }

    class VirtualIngredient {
        public final String kind;
        public final String translationKey;
        public final String className;
        public final String id;
        public final String name;
        public final String modName;

        public VirtualIngredient(String kind, String translationKey, String className,
                                 String id, String name, String modName) {
            this.kind = kind;
            this.translationKey = translationKey;
            this.className = className;
            this.id = id;
            this.name = name;
            this.modName = modName;
        }
    }

    @Nullable
    static List<String> copyLines(@Nullable List<String> tooltipLines) {
        if (tooltipLines == null || tooltipLines.isEmpty()) return null;

        return Collections.unmodifiableList(new ArrayList<>(tooltipLines));
    }
}