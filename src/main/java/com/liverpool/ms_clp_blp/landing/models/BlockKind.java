package com.liverpool.ms_clp_blp.landing.models;

/**
 * How a block type is composed.
 *
 * <ul>
 *   <li>{@link #STATIC} — the CMS content is the whole block; it is passed through verbatim.</li>
 *   <li>{@link #DYNAMIC} — the block needs data from another system; it is resolved eagerly or
 *       advertised as a lazy placeholder, depending on its position and the session.</li>
 * </ul>
 */
public enum BlockKind {
    STATIC,
    DYNAMIC
}
