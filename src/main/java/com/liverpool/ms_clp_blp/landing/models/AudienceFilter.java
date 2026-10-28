package com.liverpool.ms_clp_blp.landing.models;

/**
 * Visibility rule a block declares for the current session (Contentstack {@code audience_filter}).
 *
 * <p>Drives the rule that auth-dependent blocks are session-aware: a {@link #LOGGED}-only block is
 * absent for guests and vice-versa (Rule 18 session awareness).</p>
 */
public enum AudienceFilter {

    /** Visible only to authenticated sessions. */
    LOGGED,

    /** Visible only to guest (unauthenticated) sessions. */
    GUEST,

    /** Visible to everyone. */
    ALL;

    /**
     * Resolves an audience filter from its Contentstack string, defaulting to {@link #ALL}.
     *
     * @param value raw {@code audience_filter} value (may be null/blank)
     * @return the matching filter, or {@link #ALL} when absent/unrecognised
     */
    public static AudienceFilter from(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        return switch (value.toLowerCase()) {
            case "logged" -> LOGGED;
            case "guest" -> GUEST;
            default -> ALL;
        };
    }

    /**
     * Whether a block with this filter is visible to the given session.
     *
     * @param authenticated true if the session is logged in
     * @return true if the block should be rendered for that session
     */
    public boolean visibleFor(boolean authenticated) {
        return switch (this) {
            case ALL -> true;
            case LOGGED -> authenticated;
            case GUEST -> !authenticated;
        };
    }
}
