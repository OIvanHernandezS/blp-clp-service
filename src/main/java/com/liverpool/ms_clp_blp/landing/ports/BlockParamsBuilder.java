package com.liverpool.ms_clp_blp.landing.ports;

import java.util.Map;
import java.util.Set;

import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;

/**
 * Strategy that projects one dynamic block's CMS attributes down to the parameters its resolution
 * endpoint actually reads — the {@code ms_params} object advertised on every dynamic block.
 *
 * <p>This is the <strong>lazy</strong> counterpart to {@link BlockResolver}, which is scoped to eager
 * composition. They are deliberately separate contracts: {@code ms_params} is needed precisely where no
 * resolver runs — on a lazy placeholder, on a block whose feature flag is off ({@code DISABLED}), and on
 * one whose eager resolution failed ({@code UNAVAILABLE}). A block may register a builder without a
 * resolver, or the reverse.</p>
 *
 * <p>Each dynamic block sub-slice provides at most one implementation (in its {@code adapters} package)
 * keyed by {@link #paramKeys()}; the composition service reaches them through
 * {@link com.liverpool.ms_clp_blp.landing.models.BlockParamsRegistry}, so the core never imports a block class
 * (Open/Closed). A key with no builder yields an empty object, which is the correct answer for the three
 * per-block endpoints that ignore the request body entirely.</p>
 *
 * <p><strong>Four invariants every implementation must hold:</strong></p>
 * <ol>
 *   <li><strong>Projection, not translation.</strong> The result keeps the block's original Contentstack
 *       key names and nesting, so the endpoint parses a slimmed body with exactly the same code that
 *       parses a full one. This is what lets a frontend that still posts the whole block and one that
 *       posts only {@code ms_params} coexist during rollout.</li>
 *   <li><strong>CMS-only and session-free.</strong> Identity never belongs here: Salesforce and Jewel
 *       personalise on request headers (ADR-041), never on the request body. Putting identity in
 *       {@code ms_params} would make it part of a body the edge may cache and share.</li>
 *   <li><strong>Verbatim values.</strong> Copy what the CMS published without coercing it — {@code "6"}
 *       stays the string {@code "6"} — so the endpoint's lenient readers
 *       ({@link com.liverpool.ms_clp_blp.landing.models.BlockAttributes}) see the same input either way.</li>
 *   <li><strong>Omit absent keys; never emit null.</strong> An absent key must stay absent so the
 *       endpoint applies the same service-level fallback it applies today.</li>
 * </ol>
 */
public interface BlockParamsBuilder {

    /**
     * Declares which {@code (content type, source_of_data)} combinations this strategy describes.
     *
     * <p>Every key returned here must also exist in the
     * {@link com.liverpool.ms_clp_blp.landing.models.BlockResolutionCatalog}, or the block can never be
     * classified as dynamic and the builder is dead code. Two strategies must not claim the same key;
     * {@code LandingConfig} fails startup if they do. Both rules are pinned by
     * {@code BlockResolutionContractTest}.</p>
     *
     * @return the non-empty set of routing keys this strategy owns
     */
    Set<BlockResolutionKey> paramKeys();

    /**
     * Projects a block's attributes onto the parameters its endpoint reads.
     *
     * <p>{@code blockId} is passed explicitly rather than read from {@code attributes.get("uid")} so the
     * block's identity does not depend on the CMS-ingest step that backfills that attribute.</p>
     *
     * @param blockId    the Contentstack uid of the block being described
     * @param attributes the block's raw Contentstack attributes
     * @return the parameters to advertise, never {@code null}; empty when the endpoint reads nothing
     */
    Map<String, Object> paramsFor(String blockId, Map<String, Object> attributes);
}
