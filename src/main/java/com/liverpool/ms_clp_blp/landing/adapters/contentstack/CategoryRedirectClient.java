package com.liverpool.ms_clp_blp.landing.adapters.contentstack;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.clients.contentstack.CmsProperties;
import com.liverpool.ms_clp_blp.clients.contentstackdirect.ContentStackDirectGateway;
import com.liverpool.ms_clp_blp.landing.ports.CategoryRedirectPort;
import com.liverpool.ms_clp_blp.shared.errors.Causes;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * Outbound adapter reading the active {@code category_redirect} entry of a category straight from
 * Contentstack (Rule 19 reference: content-service {@code getCategoryRedirect}, which digital_bff
 * called for every BLP).
 *
 * <p>Selected by {@code source_category_id} (lower-cased, as authored) and {@code is_active: true}.
 * Best effort: no redirect, a brand Contentstack does not serve and an unreachable stack all answer
 * {@link Optional#empty()}, so a redirect lookup can never fail a page. Not cached here, as
 * digital_bff did not cache it either; the page entry it accompanies is cached.</p>
 */
@Component
public class CategoryRedirectClient implements CategoryRedirectPort {

    private static final Logger log = LoggerFactory.getLogger(CategoryRedirectClient.class);

    private static final String DEFAULT_CONTENT_TYPE = "category_redirect";
    private static final String FIELD_SOURCE_CATEGORY_ID = "source_category_id";
    private static final String FIELD_IS_ACTIVE = "is_active";
    private static final String UPSTREAM = "contentstack";

    private final ContentStackDirectGateway gateway;
    private final CmsProperties properties;

    /**
     * @param gateway    direct Contentstack access
     * @param properties supplies the redirect content type
     */
    public CategoryRedirectClient(ContentStackDirectGateway gateway, CmsProperties properties) {
        this.gateway = gateway;
        this.properties = properties;
    }

    @Override
    public Optional<Map<String, Object>> findActiveRedirect(String brand, String locale, String categoryId) {
        if (!gateway.isAvailableFor(brand)) {
            return Optional.empty();
        }
        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put(FIELD_SOURCE_CATEGORY_ID, categoryId.toLowerCase(Locale.ROOT));
        conditions.put(FIELD_IS_ACTIVE, true);
        try {
            return gateway.fetchByFields(contentType(), locale, conditions, brand)
                    .filter(redirect -> Boolean.TRUE.equals(redirect.get(FIELD_IS_ACTIVE)));
        } catch (RuntimeException e) {
            LogEvents.at(log, LogEvents.CATEGORY_REDIRECT_UNAVAILABLE)
                    .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                    .addKeyValue(LogFields.BRAND, brand)
                    .addKeyValue(LogFields.CATEGORY_ID, categoryId)
                    .addKeyValue(LogFields.DETAIL, e.getClass().getSimpleName() + ": " + Causes.rootMessage(e))
                    .log("Category redirect could not be read; serving the page without one");
            return Optional.empty();
        }
    }

    private String contentType() {
        String configured = properties.redirectContentType();
        return configured == null || configured.isBlank() ? DEFAULT_CONTENT_TYPE : configured;
    }
}
