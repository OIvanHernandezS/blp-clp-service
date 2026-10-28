package com.liverpool.ms_clp_blp.landing.ports;

import java.util.Optional;

/**
 * Outbound port for a brand's baseline flags, shipped with the artefact rather than fetched.
 *
 * <p>Sits between the live store and the service-wide YAML, and exists for one scenario: the store is
 * unreachable. Without it, an outage collapses every brand onto one global set of values — a brand
 * that must never call Jewel would start calling it the moment MongoDB blinked. With it, that brand's
 * baseline is compiled into the image and cannot become unavailable.</p>
 *
 * <p>Deliberately a separate port from {@link BrandFlagsLookupPort} even though the signature matches.
 * They answer different questions — "what did someone publish for this brand" versus "what does this
 * brand fall back to" — and keeping them apart is what lets the evaluated log line name which layer
 * actually answered.</p>
 */
public interface BrandFlagsDefaultsPort {

    /**
     * @param flagId the flag to resolve
     * @param brand  the requesting brand; may be {@code null} or blank when no session resolved
     * @return the brand's packaged baseline, or empty when it ships none for this flag — in which
     *         case the caller falls through to the service-wide configuration
     */
    Optional<Boolean> lookup(String flagId, String brand);
}
