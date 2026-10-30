package com.liverpool.ms_clp_blp.landing.blocks.productslist.models;

import java.util.LinkedHashMap;
import java.util.Map;

import com.liverpool.ms_clp_blp.landing.models.BlockAttributes;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

/**
 * Per-block Salesforce settings authored in Contentstack, plus the request identity Salesforce
 * personalises on.
 *
 * <p>These are <strong>block</strong> settings, not service settings: each carousel on a live landing
 * page runs its own Salesforce action ({@code CMSOfertasIncreibles}, {@code CMSTePuedeGustar}, …), so
 * resolving them all against one configured default returns the wrong campaign (ADR-022).</p>
 *
 * <p>Owns the CMS field names so the eager resolver and the lazy endpoint cannot drift apart.</p>
 *
 * @param userId        the ATG profile id sent to Salesforce as {@code ID_ATG1}, resolved by
 *                      {@link #resolveUserId(SessionContext, String)} from the {@code x-sf-user-id}
 *                      header (ADR-041, a documented exception to ADR-033); {@code null} whenever the
 *                      session or the header is missing, which makes the block unresolvable
 * @param carousel      the block's {@code salesforce_carousel} action; {@code null} when absent
 * @param categoryId    the block's {@code salesforce_category_id}, which scopes the campaign to one
 *                      category; {@code null} when the carousel is not category scoped
 * @param minOfProducts fewest products worth rendering; {@code null} when absent
 * @param maxOfProducts most products to render; {@code null} when absent
 */
public record SalesforceSettings(
        String userId,
        String carousel,
        String categoryId,
        Integer minOfProducts,
        Integer maxOfProducts) implements ProviderSettings {

    private static final String ATTR_SALESFORCE_CAROUSEL = "salesforce_carousel";
    private static final String ATTR_SALESFORCE_CATEGORY_ID = "salesforce_category_id";
    private static final String ATTR_MIN_OF_PRODUCTS = "min_of_products";
    private static final String ATTR_MAX_OF_PRODUCTS = "max_of_products";

    @Override
    public SourceOfData source() {
        return SourceOfData.SALESFORCE;
    }

    /**
     * Resolves the identity {@code products_list} sends to Salesforce as {@code ID_ATG1}.
     *
     * <p>ADR-041: Salesforce V1 carousels take the ATG profile id from the {@code x-sf-user-id}
     * request header — the same header {@code ms_plp_search} and {@code ms_pdp_search} already send
     * to this provider — so that a landing page resolves the same carousels they do.</p>
     *
     * <p>Two conditions, both mandatory, and both cheap enough to check before any I/O:</p>
     * <ol>
     *   <li><strong>A well-formed {@code SessionId} cookie must have travelled.</strong> Salesforce V1
     *       only serves logged-in shoppers, so a request with no session is not one it can answer. This
     *       is what keeps the header from being an identity on its own.</li>
     *   <li><strong>The header must carry a value.</strong> It <em>is</em> the profile id; without it
     *       there is nothing to personalise on.</li>
     * </ol>
     *
     * <p>Missing either one is a business no-op, not a failure: the carousel resolves to nothing and
     * the block is returned unresolved rather than dropped or errored.</p>
     *
     * @param session        the request's session context; may be {@code null}
     * @param userIdHeader   the configured header name ({@code salesforce.user-id-header})
     * @return the profile id to send, or {@code null} when Salesforce must not be called
     */
    public static String resolveUserId(SessionContext session, String userIdHeader) {
        if (session == null || session.sessionId() == null) {
            return null;
        }
        return session.header(userIdHeader);
    }

    /**
     * Reads the settings out of a block's raw CMS attributes.
     *
     * @param attributes raw block attributes from Contentstack; may be {@code null} when the caller has
     *                   no block context, in which case everything falls back to service config
     * @param userId     the ATG profile id for this request; may be {@code null}
     * @return the parsed settings, never {@code null} (fields are {@code null} when absent)
     */
    public static SalesforceSettings from(Map<String, Object> attributes, String userId) {
        if (attributes == null) {
            return new SalesforceSettings(userId, null, null, null, null);
        }
        return new SalesforceSettings(
                userId,
                BlockAttributes.string(attributes, ATTR_SALESFORCE_CAROUSEL),
                BlockAttributes.string(attributes, ATTR_SALESFORCE_CATEGORY_ID),
                BlockAttributes.integer(attributes, ATTR_MIN_OF_PRODUCTS),
                BlockAttributes.integer(attributes, ATTR_MAX_OF_PRODUCTS));
    }

    /**
     * Projects the same attributes {@link #from} reads into the {@code ms_params} the frontend re-posts.
     *
     * <p>Deliberately sits beside {@code from} and reuses its constants: the two must agree, and keeping
     * them two lines apart is what makes that structural rather than a convention someone has to
     * remember. {@code userId} is absent by design — it travels in the {@code x-sf-user-id} header
     * (ADR-041), never in the request body, so a projected body carries no identity at all.</p>
     *
     * <p>Values are copied verbatim (the CMS authors {@code min_of_products} as a string) and absent keys
     * are omitted, so a body carrying only these keys parses exactly like the full block.</p>
     *
     * @param attributes raw block attributes from Contentstack; may be {@code null}
     * @return the endpoint's parameters, mutable so the caller can add the block-level keys
     */
    public static Map<String, Object> paramsFrom(Map<String, Object> attributes) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (attributes == null) {
            return params;
        }
        BlockAttributes.copyIfPresent(params, attributes, ATTR_SALESFORCE_CAROUSEL);
        BlockAttributes.copyIfPresent(params, attributes, ATTR_SALESFORCE_CATEGORY_ID);
        BlockAttributes.copyIfPresent(params, attributes, ATTR_MIN_OF_PRODUCTS);
        BlockAttributes.copyIfPresent(params, attributes, ATTR_MAX_OF_PRODUCTS);
        return params;
    }
}
