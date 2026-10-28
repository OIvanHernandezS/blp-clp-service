package com.liverpool.ms_clp_blp.landing.models;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A placeholder for a session/runtime-dependent block (Rule 18). The landing endpoint never resolves the
 * dynamic detail itself; instead it returns the block's static Contentstack content and advertises the
 * dedicated endpoint the frontend should call to resolve the dynamic part.
 *
 * <p>For digital_bff parity (Rule 19) the placeholder still carries the block's raw CS {@link #content}
 * (e.g. a {@code products_list}'s title/config, minus the {@code products} array a resolver would
 * populate) so the frontend can render the block's static shell immediately and lazy-fetch only the
 * resolved detail via the advertised endpoint.</p>
 *
 * @param blockId        Contentstack block uid
 * @param blockType      classified type (e.g. {@link BlockType#PRODUCTS_LIST})
 * @param content        the block's raw, unresolved Contentstack content (flat CMS attributes)
 * @param resolutionPath path of the dedicated per-block endpoint that resolves the detail
 * @param fallback       fallback content to render when the block is unavailable
 * @param featureFlagId  id of the flag controlling this block's runtime enablement
 * @param status         why the block is/ isn't resolvable (see {@link DynamicBlockStatus})
 * @param httpMethod     http method the frontend uses against {@code resolutionPath}
 * @param loadStrategy   always {@link LoadStrategy#LAZY} for a placeholder
 * @param blockIdInPath  whether the endpoint is addressed as {@code {resolutionPath}/{blockId}}; false
 *                       for a batch endpoint that takes its blocks in the body (see
 *                       {@link BlockResolution#blockIdInPath()})
 * @param msParams       the parameters to post to {@code resolutionPath} — a projection of
 *                       {@link #content} down to what that endpoint actually reads, keeping the CMS key
 *                       names so it parses identically to the full block. Empty (never null) for the
 *                       per-block endpoints that resolve from the path and ignore the body
 */
public record DynamicPlaceholder(
        String blockId,
        BlockType blockType,
        Map<String, Object> content,
        String resolutionPath,
        Object fallback,
        String featureFlagId,
        DynamicBlockStatus status,
        String httpMethod,
        LoadStrategy loadStrategy,
        boolean blockIdInPath,
        Map<String, Object> msParams) implements LandingBlock {

    public DynamicPlaceholder {
        // Null-tolerant unmodifiable copy: CMS content carries null-valued fields Map.copyOf rejects.
        content = content == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(content));
        msParams = msParams == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(msParams));
    }

    /**
     * @return the URL the frontend should call to resolve this block
     */
    public String resolutionUrl() {
        return BlockResolution.url(resolutionPath, blockId, blockIdInPath);
    }

    /**
     * Returns a copy of this placeholder with a different status, keeping all other fields.
     *
     * @param newStatus the status to set
     * @return a new placeholder instance
     */
    public DynamicPlaceholder withStatus(DynamicBlockStatus newStatus) {
        return new DynamicPlaceholder(
                blockId, blockType, content, resolutionPath, fallback, featureFlagId, newStatus,
                httpMethod, loadStrategy, blockIdInPath, msParams);
    }
}
