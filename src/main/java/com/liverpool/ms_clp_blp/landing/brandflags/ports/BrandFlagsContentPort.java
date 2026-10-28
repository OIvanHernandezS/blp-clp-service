package com.liverpool.ms_clp_blp.landing.brandflags.ports;

import java.util.Optional;

import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlags;

/**
 * Outbound port for reading a brand's authored feature-flag overrides from the CMS.
 *
 * <p>Used only by the webhook path, never while composing a page: the CMS is the system of record but
 * the durable store is what every instance reads. Keeping the CMS off the request path is what stops
 * a slow content-service from becoming a slow landing page.</p>
 */
public interface BrandFlagsContentPort {

    /**
     * @param brand brand identifier (case-insensitive)
     * @return the authored overrides, or empty when the brand has no entry, the entry declares no
     *         recognised flag, or the CMS could not be reached
     */
    Optional<BrandFlags> fetchForBrand(String brand);
}
