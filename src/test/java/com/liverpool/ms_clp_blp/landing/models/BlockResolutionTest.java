package com.liverpool.ms_clp_blp.landing.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BlockResolution}'s URL derivation — the single place a block's advertised
 * {@code url} is built, so a placeholder and an eagerly-resolved block can never disagree about how an
 * endpoint is addressed.
 *
 * <p>Both addressing modes are covered here on purpose. The bare-path form has three live catalog
 * entries; the path-variable form ({@link BlockResolution#post}) has had none since ADR-044 removed
 * {@code products_cards} and {@code recommendation_product_list}, which were its only users. The
 * mechanism stays supported because the next per-block endpoint will need it, and testing it here —
 * rather than through {@code LandingPageMapper} with an invented block type — is what keeps it from rotting
 * while unused.</p>
 */
class BlockResolutionTest {

    private static final String BLOCK_ID = "blt0a1b2c3d4e5f6a7";

    // ── addressed by block id in the path ────────────────────────────────────────────────────────

    @Test
    void post_addressesTheBlockByAppendingItsIdToThePath() {
        BlockResolution resolution = BlockResolution.post("/home/blocks/example", "example-flag");

        assertThat(resolution.blockIdInPath()).isTrue();
        assertThat(resolution.urlFor(BLOCK_ID)).isEqualTo("/home/blocks/example/" + BLOCK_ID);
    }

    /** A trailing slash on the configured path must not produce a double slash in the advertised url. */
    @Test
    void post_trailingSlashInThePath_doesNotDoubleUp() {
        assertThat(BlockResolution.post("/home/blocks/example/", "f").urlFor(BLOCK_ID))
                .isEqualTo("/home/blocks/example/" + BLOCK_ID);
    }

    // ── addressed on the bare path ───────────────────────────────────────────────────────────────

    @Test
    void batch_advertisesTheBarePath_becauseTheBlocksTravelInTheBody() {
        BlockResolution resolution = BlockResolution.batch("/blp/blocks/products-list", "products-list");

        assertThat(resolution.blockIdInPath()).isFalse();
        assertThat(resolution.urlFor(BLOCK_ID)).isEqualTo("/blp/blocks/products-list");
    }

    @Test
    void sessionOnly_advertisesTheBarePath_becauseTheBlockIdIsNotAnInput() {
        BlockResolution resolution = BlockResolution.sessionOnly("/home/blocks/greeting", "greeting");

        assertThat(resolution.blockIdInPath()).isFalse();
        assertThat(resolution.urlFor(BLOCK_ID)).isEqualTo("/home/blocks/greeting");
    }

    @Test
    void batch_trailingSlashInThePath_isTrimmed() {
        assertThat(BlockResolution.batch("/home/blocks/shortcuts/", "f").urlFor(BLOCK_ID))
                .isEqualTo("/home/blocks/shortcuts");
    }

    // ── defaults and edge cases ──────────────────────────────────────────────────────────────────

    @Test
    void httpMethod_defaultsToPost_whenAbsentOrBlank() {
        assertThat(new BlockResolution("/p", "f", null, false).httpMethod()).isEqualTo("POST");
        assertThat(new BlockResolution("/p", "f", "  ", false).httpMethod()).isEqualTo("POST");
        assertThat(new BlockResolution("/p", "f", "GET", false).httpMethod()).isEqualTo("GET");
    }

    /** A blank path is returned untouched rather than turned into a bare "/" that resolves nowhere. */
    @Test
    void url_blankOrNullPath_isReturnedUnchanged() {
        assertThat(BlockResolution.url(null, BLOCK_ID, true)).isNull();
        assertThat(BlockResolution.url("", BLOCK_ID, true)).isEmpty();
    }
}
