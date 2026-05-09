package com.jeidump.i18n;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.Language;

/**
 * Shared locale helpers for the command, dumper and generated website bundle.
 */
public final class JeiDumpLocales {

    public static final String DEFAULT_LOCALE = "en_us";

    private static final String LANG_RESOURCE_ROOT = "assets/jeidump/lang";
    private static final String LOCALE_MANIFEST = LANG_RESOURCE_ROOT + "/locales.txt";

    private JeiDumpLocales() {}

    public static String getCurrentLocaleCode() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.getLanguageManager() == null) {
            return DEFAULT_LOCALE;
        }

        Language currentLanguage = minecraft.getLanguageManager().getCurrentLanguage();
        if (currentLanguage == null) {
            return DEFAULT_LOCALE;
        }

        return normalizeLocaleCode(currentLanguage.getLanguageCode());
    }

    public static String normalizeLocaleCode(String locale) {
        if (locale == null) return DEFAULT_LOCALE;

        String normalized = locale.trim().toLowerCase().replace('-', '_');
        return normalized.isEmpty() ? DEFAULT_LOCALE : normalized;
    }

    public static List<String> listBundledLocales() throws IOException {
        List<String> locales = new ArrayList<>();

        try (InputStream in = JeiDumpLocales.class.getClassLoader().getResourceAsStream(LOCALE_MANIFEST)) {
            if (in != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                        String locale = normalizeLocaleCode(trimmed);
                        if (!locales.contains(locale) && resourceExists(locale)) {
                            locales.add(locale);
                        }
                    }
                }
            }
        }

        if (!locales.contains(DEFAULT_LOCALE) && resourceExists(DEFAULT_LOCALE)) {
            locales.add(DEFAULT_LOCALE);
        }

        if (locales.isEmpty()) locales.add(DEFAULT_LOCALE);

        sortLocaleCodes(locales);
        return locales;
    }

    public static Map<String, Properties> loadBundledLangTables() throws IOException {
        Map<String, Properties> tables = new LinkedHashMap<>();
        for (String locale : listBundledLocales()) {
            Properties props = new Properties();
            try (InputStream in = JeiDumpLocales.class.getClassLoader().getResourceAsStream(resourcePath(locale))) {
                if (in == null) continue;
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }

            tables.put(locale, props);
        }

        return tables;
    }

    public static void sortLocaleCodes(List<String> locales) {
        locales.sort((left, right) -> {
            if (DEFAULT_LOCALE.equals(left)) return -1;
            if (DEFAULT_LOCALE.equals(right)) return 1;
            return left.compareTo(right);
        });
    }

    public static String resourcePath(String locale) {
        return LANG_RESOURCE_ROOT + "/" + normalizeLocaleCode(locale) + ".lang";
    }

    private static boolean resourceExists(String locale) {
        return JeiDumpLocales.class.getClassLoader().getResource(resourcePath(locale)) != null;
    }
}