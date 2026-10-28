package com.liverpool.ms_clp_blp.shared.constants;

/**
 * Fixed values shared across the landing endpoints.
 */
public final class ClpBlpConstants {

    /**
     * Cookie carrying the shopper's favourite store id — the only signal used to fill the
     * {@code selected_store.*} analytics values (digital_bff read it for guests the same way).
     */
    public static final String COOKIE_FAVORITE_STORE = "favoriteStore";

    private ClpBlpConstants() {
    }
}
