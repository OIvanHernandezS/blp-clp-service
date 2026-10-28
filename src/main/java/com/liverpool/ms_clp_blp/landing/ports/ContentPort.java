package com.liverpool.ms_clp_blp.landing.ports;

import com.liverpool.ms_clp_blp.landing.models.ContentQuery;
import com.liverpool.ms_clp_blp.landing.models.LandingDefinition;

/**
 * Outbound port for retrieving a raw landing page definition from the CMS. The only gateway through
 * which Contentstack page content enters the domain — no SDK or HTTP type leaks past it (Rule 2).
 */
public interface ContentPort {

    /**
     * Fetches the raw landing page definition for one category.
     *
     * @param query brand, locale, page type and category id
     * @return the raw definition, with its sections flattened but not composed
     */
    LandingDefinition fetchDefinition(ContentQuery query);
}
