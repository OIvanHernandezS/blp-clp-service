package com.liverpool.ms_clp_blp.landing.ports;

import java.util.Map;
import java.util.Optional;

/**
 * Outbound port for the CMS redirect authored for a category ({@code category_redirect} entries).
 *
 * <p>Best effort by contract, like {@link CategoryPort}: no redirect and an unreachable CMS both
 * answer {@link Optional#empty()}.</p>
 */
public interface CategoryRedirectPort {

    /**
     * @param brand      brand identifier
     * @param locale     content locale
     * @param categoryId the category id the redirect is authored for
     * @return the active redirect entry, verbatim, or empty when none is active
     */
    Optional<Map<String, Object>> findActiveRedirect(String brand, String locale, String categoryId);
}
