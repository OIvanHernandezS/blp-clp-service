package com.liverpool.ms_clp_blp.landing.brandflags.ports;

/**
 * Outbound port for invalidating this instance's cached copy of a brand's flags.
 *
 * <p>Deliberately separate from {@code landing.ports.BrandFlagsLookupPort}: reading a flag is something
 * page composition does on every request, while evicting is something only the webhook does. Keeping
 * them apart means {@code landing} core is never handed a way to invalidate a cache it does not own.</p>
 *
 * <p>Scope is one instance. There is no way to address the other Cloud Run instances, so they pick the
 * change up when their own copy expires.</p>
 */
public interface BrandFlagsCachePort {

    /**
     * @param brand the brand whose cached copy is now stale; ignored when {@code null} or blank
     */
    void evict(String brand);
}
