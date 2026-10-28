package com.liverpool.ms_clp_blp.shared.cachepurge.models;

import java.util.Locale;

/**
 * How the objects in a purge are identified.
 *
 * <p>Akamai treats these as different endpoints rather than different payloads, so the two can never
 * be mixed in one request — purging the three endpoint paths and the {@code ms-clp-blp} tag is
 * necessarily two calls.</p>
 */
public enum PurgeTargetKind {

    /** Absolute URLs, e.g. {@code https://ms-crp-clp-blp-service.liverpool.com.mx/blp/es-mx/tienda/esteelauder/catst26617625}. */
    URL,

    /** {@code Edge-Cache-Tag} values, e.g. {@code ms-clp-blp}. */
    TAG;

    /**
     * @return the lowercase path segment Akamai expects, e.g. {@code tag}
     */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
