package com.liverpool.ms_clp_blp.shared.session;

import java.util.Locale;
import java.util.Map;

/**
 * The A/B cohort markers this request carried: every request header and cookie whose value reads as
 * {@code true} or {@code false}.
 *
 * <p>A {@code conditional_show_hide} rule names its marker in Contentstack, so the name is only known
 * once the block is being resolved — often on a virtual thread, where the servlet request is no longer
 * reachable. The markers are therefore snapshotted up front, like the {@code x-*} header snapshot on
 * {@link SessionContext}. Only the two readable values are kept: the rule understands nothing else,
 * so a header or cookie carrying any other value is indistinguishable from one that was not sent. That
 * is also what makes the snapshot safe to hold — a session token or a bearer credential can never be
 * the literal word {@code true}, so nothing secret is captured.</p>
 *
 * <p>Mirrors {@code ConditionalShowHideEvaluator} in {@code platform_commons}: values are compared
 * case-insensitively after trimming, header names are case-insensitive, cookie names are exact, and
 * when a cookie name repeats only its <em>first</em> occurrence counts.</p>
 *
 * @param headers readable header markers, keyed by lower-cased header name
 * @param cookies readable cookie markers, keyed by exact cookie name
 */
public record CohortMarkers(Map<String, Boolean> headers, Map<String, Boolean> cookies) {

    /** A request that carried no readable marker at all. */
    public static final CohortMarkers NONE = new CohortMarkers(Map.of(), Map.of());

    /** The marker value that places the shopper in the cohort. */
    static final String VALUE_TRUE = "true";

    /** The marker value that switches the experiment off for the shopper. */
    static final String VALUE_FALSE = "false";

    public CohortMarkers {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        cookies = cookies == null ? Map.of() : Map.copyOf(cookies);
    }

    /**
     * Reads a marker from the named request header.
     *
     * @param name the header name, matched case-insensitively; may be {@code null} or blank
     * @return the marker, or {@code null} when no name is given, the header was not sent, or its value
     *         was neither {@code true} nor {@code false}
     */
    public Boolean header(String name) {
        return name == null || name.isBlank() ? null : headers.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Reads a marker from the named cookie.
     *
     * @param name the cookie name, matched exactly; may be {@code null} or blank
     * @return the marker, or {@code null} when no name is given, the cookie was not sent, or the first
     *         cookie with that name carried an unreadable value
     */
    public Boolean cookie(String name) {
        return name == null || name.isBlank() ? null : cookies.get(name);
    }

    /**
     * Parses a raw header or cookie value into the rule's tri-state.
     *
     * <p>Deliberately stricter than {@link Boolean#parseBoolean(String)}, which reads every unrecognised
     * value as {@code false} and would let a malformed marker silently pick an experiment arm.</p>
     *
     * @param value the raw value; may be {@code null}
     * @return {@code TRUE} for {@code true}, {@code FALSE} for {@code false} (both case-insensitive and
     *         trimmed), {@code null} for anything else
     */
    public static Boolean parse(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (VALUE_TRUE.equalsIgnoreCase(normalized)) {
            return Boolean.TRUE;
        }
        return VALUE_FALSE.equalsIgnoreCase(normalized) ? Boolean.FALSE : null;
    }
}
