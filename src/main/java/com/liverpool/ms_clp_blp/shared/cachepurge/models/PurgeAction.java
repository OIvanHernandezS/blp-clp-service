package com.liverpool.ms_clp_blp.shared.cachepurge.models;

import java.util.Locale;

/**
 * What Akamai should do with the matched objects.
 *
 * <p>The distinction is operationally significant, which is why the operator chooses it per call
 * rather than inheriting a build-time decision: {@link #INVALIDATE} marks objects stale so the edge
 * revalidates with origin and keeps serving until origin confirms a change, while {@link #DELETE}
 * evicts them outright so the next request must reach origin. Delete is the stronger guarantee and
 * the one that can spike origin load.</p>
 */
public enum PurgeAction {

    /** Mark stale; the edge revalidates with origin. The safe default. */
    INVALIDATE,

    /** Evict from cache entirely; the next request goes to origin. */
    DELETE;

    /**
     * @return the lowercase path segment Akamai expects, e.g. {@code invalidate}
     */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses an operator-supplied value.
     *
     * @param value case-insensitive action name; {@code null} or blank yields {@code fallback}
     * @param fallback the configured default to use when nothing was supplied
     * @return the parsed action
     * @throws IllegalArgumentException when a value was supplied but names no action — a typo must
     *         be a 400, never a silent purge with the wrong semantics
     */
    public static PurgeAction parse(String value, PurgeAction fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
