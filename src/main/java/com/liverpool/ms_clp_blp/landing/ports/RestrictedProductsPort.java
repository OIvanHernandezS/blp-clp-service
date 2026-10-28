package com.liverpool.ms_clp_blp.landing.ports;

import java.util.Set;

/**
 * Outbound port for the per-brand product blacklist: products that must never be shown in a carousel.
 *
 * <p>Fails open by contract: when the list cannot be read an implementation answers an empty set, so
 * an outage of the list can only show a product that should have been hidden — never hide the whole
 * carousel (Rule 19 reference: digital_bff {@code filterBlacklistedCarouselProducts} and
 * ms_pdp_search's {@code RestrictedProductListService}).</p>
 */
public interface RestrictedProductsPort {

    /**
     * @param brand brand identifier
     * @return the restricted product ids for the brand; never {@code null}
     */
    Set<String> restrictedProductIds(String brand);
}
