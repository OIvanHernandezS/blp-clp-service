package com.liverpool.ms_clp_blp.shared.cachepurge.models;

import java.util.Locale;

/**
 * Which Akamai network a purge applies to.
 *
 * <p>Operator-selectable per call so a purge can be rehearsed on {@link #STAGING} before it is run
 * against live traffic — the only way to verify the credential and the object list without touching
 * the production edge.</p>
 */
public enum PurgeNetwork {

    /** Akamai's staging network. */
    STAGING,

    /** The live edge serving real traffic. */
    PRODUCTION;

    /**
     * @return the lowercase path segment Akamai expects, e.g. {@code production}
     */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses an operator-supplied value.
     *
     * @param value case-insensitive network name; {@code null} or blank yields {@code fallback}
     * @param fallback the configured default to use when nothing was supplied
     * @return the parsed network
     * @throws IllegalArgumentException when a value was supplied but names no network — silently
     *         defaulting a typo to production is exactly the mistake worth failing on
     */
    public static PurgeNetwork parse(String value, PurgeNetwork fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
