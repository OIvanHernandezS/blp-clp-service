package com.liverpool.ms_clp_blp.landing.models;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A dynamic block that was resolved eagerly during {@code GET /blp|clp/**}: its detail is inlined, and the
 * resolution endpoint is still advertised so the frontend can refresh it if needed (Rule 18).
 *
 * <p>Sibling to {@link DynamicPlaceholder}: same dynamic block type, but the resolved {@link #payload}
 * is present and {@link #loadStrategy} is {@link LoadStrategy#EAGER}. Emitted only when the block's
 * index is below the eager offset, its feature flag is on, and a
 * {@link com.liverpool.ms_clp_blp.landing.ports.BlockResolver} exists and succeeds — otherwise the
 * composition service falls back to a placeholder.</p>
 *
 * <p>For digital_bff parity (Rule 19) it also carries the block's raw CS {@link #csContent} so the REST
 * layer can present a flat block: the static CMS fields overlaid with the eagerly-resolved detail.</p>
 *
 * @param blockId        Contentstack block uid
 * @param blockType      classified dynamic type
 * @param csContent      the block's raw Contentstack content (flat CMS attributes)
 * @param payload        resolved, serialisable detail (same shape the lazy endpoint would return)
 * @param resolutionPath path of the dedicated endpoint (for optional client-side refresh)
 * @param httpMethod     http method for {@code resolutionPath}
 * @param loadStrategy   always {@link LoadStrategy#EAGER}
 * @param status         resolution status ({@link DynamicBlockStatus#AVAILABLE} for a success)
 * @param blockIdInPath  whether the endpoint is addressed as {@code {resolutionPath}/{blockId}}; false
 *                       for a batch endpoint that takes its blocks in the body (see
 *                       {@link BlockResolution#blockIdInPath()})
 * @param msParams       the parameters to post to {@code resolutionPath}. Carried even though the detail
 *                       is already inlined: this block still advertises its endpoint for refresh, and a
 *                       URL advertised without the body it requires would resolve the wrong campaign or
 *                       nothing at all (ADR-022). Emitting it on eager and lazy blocks alike also spares
 *                       the frontend a branch on {@code loadStrategy}
 */
public record ResolvedBlock(
        String blockId,
        BlockType blockType,
        Map<String, Object> csContent,
        Object payload,
        String resolutionPath,
        String httpMethod,
        LoadStrategy loadStrategy,
        DynamicBlockStatus status,
        boolean blockIdInPath,
        Map<String, Object> msParams) implements LandingBlock {

    public ResolvedBlock {
        // Null-tolerant unmodifiable copy: CMS content carries null-valued fields Map.copyOf rejects.
        csContent = csContent == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(csContent));
        msParams = msParams == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(msParams));
    }

    /**
     * @return the URL the frontend can call to refresh this block
     */
    public String resolutionUrl() {
        return BlockResolution.url(resolutionPath, blockId, blockIdInPath);
    }
}
