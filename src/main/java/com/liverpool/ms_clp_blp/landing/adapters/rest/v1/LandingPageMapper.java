package com.liverpool.ms_clp_blp.landing.adapters.rest.v1;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.liverpool.ms_clp_blp.landing.models.DynamicPlaceholder;
import com.liverpool.ms_clp_blp.landing.models.LandingBlock;
import com.liverpool.ms_clp_blp.landing.models.LandingPage;
import com.liverpool.ms_clp_blp.landing.models.ResolvedBlock;
import com.liverpool.ms_clp_blp.landing.models.StaticBlock;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.web.ProductJson;

/**
 * Maps the composed {@link LandingPage} domain object to the REST response, reproducing digital_bff's
 * {@code page-blp}/{@code page-clp} shape as a superset (Rule 19).
 *
 * <p>The root is the raw CMS entry passed through (all entry metadata, including the per-page
 * {@code header}/{@code footer} overrides) with each composed section under its own key —
 * {@code blocks}, and {@code top_content}/{@code bottom_content} when the entry authors them — and the
 * page's {@code categoryData}. Each block is <strong>flat</strong>, keyed by its
 * {@code _content_type_uid} (the frontend's type discriminator) and carrying every CMS field verbatim.
 * Dynamic blocks additionally carry the additive lazy-resolution fields ({@code url},
 * {@code loadStrategy}, {@code status}, …) so the frontend can render the static shell immediately and
 * fetch the resolved detail via {@code url}.</p>
 *
 * <p>Sealed-interface pattern matching (Java 21) keeps the block conversion exhaustive (Rule 15):
 * {@link StaticBlock} (content only), {@link DynamicPlaceholder} (content + lazy endpoint) and
 * {@link ResolvedBlock} (content + eagerly-inlined detail). Ordering is preserved as-is (Rule 18).</p>
 */
@Component
public class LandingPageMapper {

    private static final Logger log = LoggerFactory.getLogger(LandingPageMapper.class);

    /** Log field: the resolver payload key that clashed with a CMS field. */
    private static final String COLLIDING_KEY = "collidingKey";

    private static final String ROOT_KEY_LOCALE = "locale";
    private static final String ROOT_KEY_CATEGORY_DATA = "categoryData";

    private static final String FIELD_RESOLUTION_PATH = "resolutionPath";
    private static final String FIELD_URL = "url";
    private static final String FIELD_HTTP_METHOD = "httpMethod";
    private static final String FIELD_LOAD_STRATEGY = "loadStrategy";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_FALLBACK = "fallback";
    private static final String FIELD_FEATURE_FLAG_ID = "featureFlagId";

    // The one snake_case additive field, by explicit decision (ADR-038): it reads as a sibling of the CMS
    // fields it projects rather than of the camelCase routing metadata. Emitted on every dynamic block —
    // eager, lazy, DISABLED and UNAVAILABLE alike — and always present even when empty, because a missing
    // key stringifies to `undefined` on the frontend while an empty object correctly says "this endpoint
    // needs nothing from you". That makes it a contract element like `url`, not optional metadata like
    // `fallback`/`featureFlagId`, which are omitted when null.
    private static final String FIELD_MS_PARAMS = "ms_params";

    // The eager payload echoes the block id; it is redundant with the block's own `uid` and is dropped
    // when the resolved detail is merged onto the flat block.
    private static final String PAYLOAD_KEY_BLOCK_ID = "blockId";

    // Keys a resolver is expected to own outright: `products` is the resolved payload the whole
    // dynamic-block mechanism exists to deliver, and the CMS block never carries it — digital_bff wrote
    // the resolved list over the block's own `products` the same way.
    private static final java.util.Set<String> PAYLOAD_OVERRIDABLE_KEYS = java.util.Set.of("products");

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    // Self-contained converter for flattening an eager resolver payload (record/POJO) into a map.
    // Modules (e.g. JavaTimeModule) are auto-discovered so payloads with java.time fields convert
    // safely; this avoids coupling to a context ObjectMapper bean, which this app does not expose.
    private static final ObjectMapper PAYLOAD_MAPPER =
            ProductJson.withProductMixins(JsonMapper.builder().findAndAddModules().build());

    private final BlockContentNormalizer contentNormalizer;
    private final CategoryDataMapper categoryDataMapper;

    /**
     * @param contentNormalizer  produces a mutable, metadata-retaining copy of each block's CS content
     * @param categoryDataMapper renders the page's category in digital_bff's key order
     */
    public LandingPageMapper(BlockContentNormalizer contentNormalizer, CategoryDataMapper categoryDataMapper) {
        this.contentNormalizer = contentNormalizer;
        this.categoryDataMapper = categoryDataMapper;
    }

    /**
     * Converts a composed {@link LandingPage} to its REST representation: root passthrough, one key per
     * authored section, then {@code categoryData}.
     *
     * @param page the domain page (entry metadata, sections, category)
     * @return the response wrapping the assembled root object, ready for serialisation
     */
    public LandingPageResponse toResponse(LandingPage page) {
        Map<String, Object> root = new LinkedHashMap<>(page.entryMetadata());
        root.putIfAbsent(ROOT_KEY_LOCALE, page.locale());
        page.sections().forEach((section, blocks) -> root.put(section.responseKey(),
                blocks.stream().map(this::toBlockMap).toList()));
        if (page.categoryData() != null) {
            root.put(ROOT_KEY_CATEGORY_DATA, categoryDataMapper.toMap(page.categoryData()));
        }
        return new LandingPageResponse(root);
    }

    /**
     * Converts a single {@link LandingBlock} to its flat map — sealed pattern match is exhaustive.
     *
     * @param block a block from the composed page
     * @return the flat block map keyed by {@code _content_type_uid}
     */
    private Map<String, Object> toBlockMap(LandingBlock block) {
        return switch (block) {
            case StaticBlock s -> contentNormalizer.normalize(s.blockType(), s.content());
            case ResolvedBlock r -> eagerBlock(r);
            case DynamicPlaceholder d -> lazyBlock(d);
        };
    }

    private Map<String, Object> eagerBlock(ResolvedBlock r) {
        Map<String, Object> block = contentNormalizer.normalize(r.blockType(), r.csContent());
        mergeResolvedPayload(block, r.payload());
        block.put(FIELD_RESOLUTION_PATH, r.resolutionPath());
        block.put(FIELD_URL, r.resolutionUrl());
        block.put(FIELD_HTTP_METHOD, r.httpMethod());
        block.put(FIELD_LOAD_STRATEGY, r.loadStrategy().name());
        block.put(FIELD_STATUS, r.status().name());
        block.put(FIELD_MS_PARAMS, r.msParams());
        return block;
    }

    private Map<String, Object> lazyBlock(DynamicPlaceholder d) {
        Map<String, Object> block = contentNormalizer.normalize(d.blockType(), d.content());
        block.put(FIELD_RESOLUTION_PATH, d.resolutionPath());
        block.put(FIELD_URL, d.resolutionUrl());
        block.put(FIELD_HTTP_METHOD, d.httpMethod());
        block.put(FIELD_LOAD_STRATEGY, d.loadStrategy().name());
        block.put(FIELD_STATUS, d.status().name());
        block.put(FIELD_MS_PARAMS, d.msParams());
        if (d.fallback() != null) {
            block.put(FIELD_FALLBACK, d.fallback());
        }
        if (d.featureFlagId() != null) {
            block.put(FIELD_FEATURE_FLAG_ID, d.featureFlagId());
        }
        return block;
    }

    /**
     * Overlays the eagerly-resolved detail (e.g. a {@code products} array) onto the flat CS block —
     * digital_bff inlines the same fields the block's strategy populates. The payload's echoed
     * {@code blockId} is dropped as redundant with the block's own {@code uid}.
     *
     * @param block   the flat CS block map to enrich
     * @param payload the resolver's payload (a serialisable DTO); ignored when null
     */
    private void mergeResolvedPayload(Map<String, Object> block, Object payload) {
        if (payload == null) {
            return;
        }
        Map<String, Object> resolved = PAYLOAD_MAPPER.convertValue(payload, MAP_TYPE);
        resolved.remove(PAYLOAD_KEY_BLOCK_ID);
        for (Map.Entry<String, Object> entry : resolved.entrySet()) {
            // A resolver must add to the block, never redefine it. This previously let a Salesforce
            // campaign title land on `title` and overwrite the CMS block's own — the resolver now
            // publishes `products_list_title`, and this guard keeps the next one honest.
            if (block.containsKey(entry.getKey()) && !PAYLOAD_OVERRIDABLE_KEYS.contains(entry.getKey())) {
                LogEvents.at(log, LogEvents.PAYLOAD_KEY_COLLISION)
                        .addKeyValue(COLLIDING_KEY, entry.getKey())
                        .log("Resolver payload key collides with a CMS field on this block; keeping the "
                                + "CMS value. Publish it under a distinct name instead.");
                continue;
            }
            block.put(entry.getKey(), entry.getValue());
        }
    }

}
