package com.liverpool.ms_clp_blp.landing.models;

/**
 * A single block in a composed landing page section, in Contentstack order.
 *
 * <p>Sealed so the rendering/mapping logic can pattern-match exhaustively over the three shapes a
 * block can take: a fully-resolved {@link StaticBlock}, a {@link DynamicPlaceholder} pointing at a
 * dedicated resolution endpoint (lazy), or a {@link ResolvedBlock} whose detail was resolved eagerly
 * during composition.</p>
 *
 * <p>Adding a variant here is intentionally disruptive: it makes every exhaustive {@code switch} stop
 * compiling until it has decided what to do with the new shape, which is how
 * {@code LandingPageMapper} and {@code BffEventDecorator} are kept in step with the model.</p>
 */
public sealed interface LandingBlock
        permits StaticBlock, DynamicPlaceholder, ResolvedBlock {

    /** @return the Contentstack block uid, unique within the page */
    String blockId();

    /** @return the classified block type */
    BlockType blockType();
}
