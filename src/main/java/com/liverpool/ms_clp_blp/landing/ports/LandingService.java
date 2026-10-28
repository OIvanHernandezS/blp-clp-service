package com.liverpool.ms_clp_blp.landing.ports;

import com.liverpool.ms_clp_blp.landing.models.LandingPage;
import com.liverpool.ms_clp_blp.landing.models.LandingPageQuery;

/**
 * Inbound port: compose a BLP or CLP landing page (ordering + static resolution + dynamic
 * placeholders + category data). This is the only entry point the REST adapter depends on
 * (dependencies point inward, Rule 1).
 */
public interface LandingService {

    /**
     * Composes the landing page for the given query.
     *
     * @param query page type, locale, path and session context
     * @return the composed landing page
     */
    LandingPage getLandingPage(LandingPageQuery query);
}
