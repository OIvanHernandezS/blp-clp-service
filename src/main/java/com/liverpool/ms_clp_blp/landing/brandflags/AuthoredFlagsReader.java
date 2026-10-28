package com.liverpool.ms_clp_blp.landing.brandflags;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.liverpool.ms_clp_blp.landing.models.LandingFeatureFlags;
import com.liverpool.ms_clp_blp.landing.models.LandingSettings;

/**
 * Reads a map of authored feature flags, whatever authored it.
 *
 * <p>Shared by the two sources that carry per-brand overrides — the CMS entry and the packaged
 * per-brand JSON — because the alternative is two copies of the same allow-list and the same spelling
 * rules, drifting apart until a flag works from one source and silently does nothing from the other.
 * Framework-free, so it can be reasoned about and tested on its own.</p>
 *
 * <p>Three rules, each of them the difference between a published change taking effect and quietly
 * doing nothing:</p>
 * <ul>
 *   <li><strong>Only known ids.</strong> Anything outside {@link LandingFeatureFlags#ALL} is ignored, so
 *       an editor adding a field can never invent a flag.</li>
 *   <li><strong>Underscores and hyphens are the same key.</strong> Flag ids are kebab-case, but a
 *       Contentstack field uid cannot contain a hyphen — the CMS can only ever write
 *       {@code container_shortcuts}. Rejecting that would make the shortcut flags unconfigurable.</li>
 *   <li><strong>Only real booleans.</strong> {@code true}/{@code false}, or those words as a string
 *       for a hand-authored text field. Anything else is not a decision, so it is dropped rather than
 *       guessed at — and a dropped flag falls through to the next layer instead of reading as
 *       {@code false}.</li>
 * </ul>
 */
public final class AuthoredFlagsReader {

    /** Optional group holding the flags; when absent they are read off the document itself. */
    private static final String KEY_FLAGS = "flags";

    private AuthoredFlagsReader() {
    }

    /**
     * @param document an authored document: a CMS entry or a parsed JSON file; may be {@code null}
     * @return the recognised flags, never {@code null}; empty when the document declares none
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Boolean> read(Map<String, Object> document) {
        if (document == null) {
            return Map.of();
        }
        Object group = document.get(KEY_FLAGS);
        Map<String, Object> source =
                group instanceof Map<?, ?> map ? (Map<String, Object>) map : document;
        Map<String, Boolean> flags = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String flagId = canonicalFlagId(key);
            Boolean parsed = booleanValue(value);
            if (flagId != null && parsed != null) {
                flags.put(flagId, parsed);
            }
        });
        return Collections.unmodifiableMap(flags);
    }

    /**
     * @param key an authored field name
     * @return the kebab-case flag id it names, or {@code null} when it names no flag
     */
    public static String canonicalFlagId(String key) {
        if (key == null) {
            return null;
        }
        String candidate = key.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return LandingFeatureFlags.ALL.contains(candidate) ? candidate : null;
    }

    /**
     * Reads a map of numeric settings with the same rules as {@link #read}: only ids in
     * {@link LandingSettings#ALL}, underscores and hyphens alike, and a value that is not a whole number
     * dropped rather than guessed at — so it falls through to the configured value instead of reading
     * as zero.
     *
     * @param settings an authored settings map, typically edited by hand in MongoDB; may be {@code null}
     * @return the recognised settings, never {@code null}; empty when the map declares none
     */
    public static Map<String, Integer> readSettings(Map<String, ?> settings) {
        if (settings == null) {
            return Map.of();
        }
        Map<String, Integer> read = new LinkedHashMap<>();
        settings.forEach((key, value) -> {
            String settingId = canonicalSettingId(key);
            Integer parsed = intValue(value);
            if (settingId != null && parsed != null) {
                read.put(settingId, parsed);
            }
        });
        return Collections.unmodifiableMap(read);
    }

    /**
     * @param key an authored field name
     * @return the kebab-case setting id it names, or {@code null} when it names no setting
     */
    public static String canonicalSettingId(String key) {
        if (key == null) {
            return null;
        }
        String candidate = key.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return LandingSettings.ALL.contains(candidate) ? candidate : null;
    }

    /**
     * Accepts any numeric type because a hand edit in the Mongo shell stores {@code 3} as a double,
     * and a number written as a string for a text field. A fraction or an out-of-range value is not a
     * count, so it is dropped.
     *
     * @param value an authored value
     * @return the whole number it expresses, or {@code null} when it expresses none
     */
    public static Integer intValue(Object value) {
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Number number) {
            return wholeInt(number.doubleValue());
        }
        if (value instanceof String text) {
            try {
                return wholeInt(Double.parseDouble(text.trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Integer wholeInt(double value) {
        boolean whole = value == Math.rint(value)
                && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
        return whole ? (int) value : null;
    }

    /**
     * @param value an authored value
     * @return the boolean it expresses, or {@code null} when it expresses no decision
     */
    public static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (Boolean.TRUE.toString().equalsIgnoreCase(trimmed)) {
                return Boolean.TRUE;
            }
            if (Boolean.FALSE.toString().equalsIgnoreCase(trimmed)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }
}
