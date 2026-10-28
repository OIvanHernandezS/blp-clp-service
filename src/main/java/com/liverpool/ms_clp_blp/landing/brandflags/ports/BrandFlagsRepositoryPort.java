package com.liverpool.ms_clp_blp.landing.brandflags.ports;

import java.util.Optional;

import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlags;

/**
 * Outbound port for the durable store of per-brand feature-flag overrides.
 *
 * <p>The store is shared by every instance, which is what lets a change published once reach all of
 * them. It is <em>not</em> on the critical path: implementations must translate any failure into an
 * empty result rather than an exception, because a page that cannot be composed when the flag store
 * is down is strictly worse than a page composed from the YAML defaults.</p>
 */
public interface BrandFlagsRepositoryPort {

    /**
     * @param brand brand identifier (case-insensitive)
     * @return the brand's overrides, or empty when this brand has none stored <em>or</em> the store
     *         could not be reached — the caller cannot and should not tell the two apart
     */
    Optional<BrandFlags> findByBrand(String brand);

    /**
     * Writes the brand's overrides, replacing whatever was there. Idempotent.
     *
     * @param brandFlags the snapshot to persist
     * @return true when the write landed; false when the store rejected or could not take it
     */
    boolean save(BrandFlags brandFlags);

    /**
     * Writes a document stamped as default-seeded rather than CMS-authored, used only after {@link
     * #findByBrand(String)} already found nothing for the brand. A plain upsert, not a conditional
     * one — calling it when a document already exists would overwrite it.
     *
     * @param brandFlags the {@code landing.feature-flags} snapshot to persist
     * @return true when the write landed; false when the store rejected or could not take it
     */
    boolean saveDefaults(BrandFlags brandFlags);

    /**
     * Removes the brand's overrides, so it falls back to the YAML defaults. Used when the CMS entry is
     * unpublished or deleted. Idempotent: removing what is not there is a success.
     *
     * @param brand brand identifier (case-insensitive)
     * @return true when the store confirmed the removal
     */
    boolean delete(String brand);
}
