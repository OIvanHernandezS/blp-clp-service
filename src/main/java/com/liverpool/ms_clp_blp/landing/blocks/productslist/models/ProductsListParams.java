package com.liverpool.ms_clp_blp.landing.blocks.productslist.models;

import java.util.LinkedHashMap;
import java.util.Map;

import com.liverpool.ms_clp_blp.landing.models.BlockAttributes;
import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;

/**
 * Projects a {@code products_list} block down to the parameters its resolution endpoint actually reads —
 * the block's {@code ms_params}.
 *
 * <p>{@code POST /blp|clp/blocks/products-list} is the one block endpoint that is genuinely body-driven: a
 * request without block context could only fall back to service defaults and would resolve the wrong
 * campaign or nothing at all (ADR-022, ADR-024). But of the ~200 fields the CMS publishes on such a
 * block, it reads five at most — everything else (events, backgrounds, spacing, publish metadata) is
 * weight on the wire.</p>
 *
 * <p>The projection <strong>keeps the Contentstack key names and nesting</strong>, so
 * {@code ProductsListControllerV1} and the {@code *Settings.from(...)} readers parse a projected body
 * with exactly the code that parses a full one — a frontend still posting whole blocks and one posting
 * only {@code ms_params} both work, which is what makes the rollout safe.</p>
 *
 * <p>Routing mirrors {@code ProductsListControllerV1.settingsFor}: the same exhaustive switch over
 * {@link SourceOfData}, with no {@code default} arm, so adding a provider fails compilation here rather
 * than silently advertising the wrong parameters. Each arm delegates to the settings record that owns
 * those CMS field names, so a field added to a reader and forgotten here is caught by the round-trip
 * test rather than shipped.</p>
 */
public final class ProductsListParams {

    /** The block's Contentstack uid; the endpoint echoes it back on every resolution. */
    public static final String ATTR_UID = "uid";

    /** Selects the backing provider; without it every carousel would route to one adapter (ADR-021). */
    public static final String ATTR_SOURCE_OF_DATA = "source_of_data";

    /**
     * The audience the block was authored for ({@code guest} / {@code logged}).
     *
     * <p>Unlike the other keys here, the endpoint does <strong>not</strong> read this one today: audience
     * is enforced at composition time, where a block for the wrong audience is dropped before it ever
     * becomes a placeholder ({@code LandingCompositionService.isVisible}). It is projected because it is
     * the one piece of block context a caller needs to tell two otherwise-identical carousels apart —
     * the live page ships guest and logged variants of the same campaign — and because a resolution
     * request carrying it can be reasoned about without re-reading the page it came from.</p>
     *
     * <p>It is <em>not</em> an identity signal and must never be treated as one: it says what the CMS
     * author targeted, not who is calling. Whether the session is authenticated comes from the validated
     * {@code SessionId} (ADR-033), server-side, and nothing here can override it.</p>
     */
    public static final String ATTR_AUDIENCE_FILTER = "audience_filter";

    /**
     * The block's A/B rule, read by the endpoint to decide which cohort sees the carousel.
     *
     * <p>Projected for every provider because the rule is provider-agnostic, and because a block with an
     * active rule is never inlined into {@code GET /blp|clp/**} while the flag is on — the resolution request
     * is the <em>only</em> place it can be honoured, so a projection without it would serve both arms of
     * the experiment to everyone.</p>
     */
    public static final String ATTR_CONDITIONAL_SHOW_HIDE = ConditionalShowHide.ATTR_CONDITIONAL_SHOW_HIDE;

    private ProductsListParams() {
    }

    /**
     * Builds the {@code ms_params} for one {@code products_list} block.
     *
     * <p>{@code audience_filter} is copied only when the block carries it. A block authored for every
     * audience omits the field, and one that carries an explicit {@code null} is treated the same way —
     * neither states a target, so projecting the key would invent one. {@code conditional_show_hide}
     * follows the same rule and is copied verbatim, inactive or not: whether it applies is the
     * endpoint's decision, taken with the same reader either way.</p>
     *
     * @param blockId    the block's Contentstack uid; omitted from the result when {@code null}
     * @param attributes the block's raw Contentstack attributes; may be {@code null}
     * @return the parameters to advertise, never {@code null}
     */
    public static Map<String, Object> of(String blockId, Map<String, Object> attributes) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (blockId != null) {
            params.put(ATTR_UID, blockId);
        }
        BlockAttributes.copyIfPresent(params, attributes, ATTR_SOURCE_OF_DATA);
        BlockAttributes.copyIfPresent(params, attributes, ATTR_AUDIENCE_FILTER);
        BlockAttributes.copyIfPresent(params, attributes, ATTR_CONDITIONAL_SHOW_HIDE);
        params.putAll(providerParams(attributes));
        return params;
    }

    /**
     * Delegates to the settings record that owns the CMS field names for this block's provider.
     *
     * <p>{@code UNSUPPORTED} yields nothing and is effectively unreachable: a source with no catalog
     * entry never becomes a dynamic block advertising this endpoint, it stays a static pass-through
     * (ADR-021). It is handled explicitly rather than by a {@code default} so the switch stays
     * exhaustive.</p>
     */
    private static Map<String, Object> providerParams(Map<String, Object> attributes) {
        SourceOfData source = SourceOfData.from(
                attributes == null ? null : attributes.get(ATTR_SOURCE_OF_DATA));
        return switch (source) {
            case JEWEL -> JewelModelConfig.paramsFrom(attributes);
            case SALESFORCE, NONE -> SalesforceSettings.paramsFrom(attributes);
            case SALESFORCE_V2 -> SalesforceV2Settings.paramsFrom(attributes);
            case VERTEX, GROUPBY -> VertexSettings.paramsFrom(attributes);
            case UNSUPPORTED -> Map.of();
        };
    }
}
