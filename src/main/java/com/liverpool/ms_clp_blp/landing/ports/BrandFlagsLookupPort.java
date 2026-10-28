package com.liverpool.ms_clp_blp.landing.ports;

import java.util.Optional;

/**
 * Outbound port giving {@code landing}'s flag evaluation access to a brand's published overrides.
 *
 * <p>Returns an {@link Optional} rather than a boolean because "this brand turned the flag off" and
 * "this brand says nothing about the flag" are different answers with different consequences: the
 * first is an override, the second must fall through to the configured default. Collapsing them is
 * what would make an unconfigured brand look like a brand with everything disabled.</p>
 *
 * <p>Kept in {@code landing.ports} — rather than the {@code brandflags} sub-slice that implements it — so
 * the flag adapter depends on a landing-owned contract instead of another sub-slice's internals.</p>
 */
public interface BrandFlagsLookupPort {

    /**
     * @param flagId the flag to resolve
     * @param brand  the requesting brand; may be {@code null} or blank when no session resolved
     * @return the brand's override, or empty when it declares none, the brand is unknown, or
     *         per-brand configuration is switched off entirely
     */
    Optional<Boolean> lookup(String flagId, String brand);

    /**
     * The numeric counterpart of {@link #lookup}, with the same "says nothing" semantics.
     *
     * <p>Defaults to empty so an implementation that carries no settings — a test fake, a future
     * store — answers "this brand overrides nothing" instead of failing to compile.</p>
     *
     * @param settingId the setting to resolve
     * @param brand     the requesting brand; may be {@code null} or blank when no session resolved
     * @return the brand's override, or empty when it declares none, the brand is unknown, or
     *         per-brand configuration is switched off entirely
     */
    default Optional<Integer> lookupSetting(String settingId, String brand) {
        return Optional.empty();
    }
}
