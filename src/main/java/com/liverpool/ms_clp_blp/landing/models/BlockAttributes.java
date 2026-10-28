package com.liverpool.ms_clp_blp.landing.models;

import java.util.Map;

/**
 * Lenient readers for raw Contentstack block attributes.
 *
 * <p>CMS values are not consistently typed: checkbox fields arrive as JSON booleans from the current
 * schema but as strings from legacy templates, and numeric fields such as {@code min_of_products} are
 * authored as strings. Every read here fails soft — an absent, blank, or unparseable value yields the
 * neutral result rather than throwing, so unexpected CMS shapes never break composition (Rule 18).</p>
 */
public final class BlockAttributes {

    private BlockAttributes() {
    }

    /**
     * @param attributes raw block attributes (may be null)
     * @param key        attribute name
     * @return the string value, or {@code null} when absent or not a string
     */
    public static String string(Map<String, Object> attributes, String key) {
        if (attributes == null) {
            return null;
        }
        return attributes.get(key) instanceof String value ? value : null;
    }

    /**
     * @param attributes raw block attributes (may be null)
     * @param key        attribute name
     * @return the int value, or {@code null} when absent, blank, or unparseable
     */
    public static Integer integer(Map<String, Object> attributes, String key) {
        if (attributes == null) {
            return null;
        }
        Object value = attributes.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * @param attributes raw block attributes (may be null)
     * @param key        attribute name
     * @param fallback   value to use when the attribute is absent or unparseable
     * @return the int value, or {@code fallback}
     */
    public static int integer(Map<String, Object> attributes, String key, int fallback) {
        Integer value = integer(attributes, key);
        return value == null ? fallback : value;
    }

    /**
     * @param value a raw CMS value
     * @return {@code true} for a JSON {@code true} or the string {@code "true"}; false otherwise
     */
    public static boolean bool(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof String text && Boolean.parseBoolean(text));
    }

    /**
     * Copies one attribute <strong>verbatim</strong> into a projection, skipping it when absent.
     *
     * <p>The counterpart to the readers above, used to build a block's {@code ms_params}: the readers
     * coerce a CMS value into a typed field, this preserves it exactly as published — {@code "6"} stays
     * the string {@code "6"} — so an endpoint receiving only the projection parses it through the very
     * same lenient path as the full block. A key that is absent (or explicitly null) stays absent, so
     * the endpoint keeps applying whatever fallback it applies today.</p>
     *
     * @param target     the projection being built
     * @param attributes raw block attributes (may be null)
     * @param key        attribute name to copy
     */
    public static void copyIfPresent(Map<String, Object> target,
                                     Map<String, Object> attributes,
                                     String key) {
        if (attributes == null) {
            return;
        }
        Object value = attributes.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }
}
