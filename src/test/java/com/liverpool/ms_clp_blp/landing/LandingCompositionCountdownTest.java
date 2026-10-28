package com.liverpool.ms_clp_blp.landing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.models.BlockDefinition;
import com.liverpool.ms_clp_blp.landing.models.BlockResolution;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionCatalog;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.DynamicPlaceholder;
import com.liverpool.ms_clp_blp.landing.models.LandingBlock;
import com.liverpool.ms_clp_blp.landing.models.LandingDefinition;
import com.liverpool.ms_clp_blp.landing.models.LandingPage;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.landing.models.StaticBlock;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the composition-time rules added for legacy parity: {@code countdown} expiry (BR-B11) and the
 * {@code products_list} dynamic placeholder. Pure domain — no Spring, no I/O.
 */
class LandingCompositionCountdownTest {

    private static final SessionContext WEB = new SessionContext(true, "LP", "WEB", "es-mx");

    private final BlockResolutionCatalog catalog = new BlockResolutionCatalog(Map.of(
            BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.VERTEX),
            BlockResolution.batch("/blp/blocks/products-list", "vertex")));

    private final LandingCompositionService service =
            new LandingCompositionService((flag, brand) -> true, catalog);

    private static BlockDefinition block(String uid, String type, Map<String, Object> attrs) {
        return new BlockDefinition(uid, type, null, true, true, attrs);
    }

    private LandingPage composeOf(BlockDefinition... blocks) {
        return service.compose(LandingDefinition.ofBlocks(PageType.BLP, "es-mx", List.of(blocks)), WEB);
    }

    @Test
    void compose_dropsExpiredCountdown() {
        String past = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        LandingPage page = composeOf(block("c1", "countdown", Map.of("is_active", true, "timer", past)));

        assertThat(page.blocks()).isEmpty();
    }

    @Test
    void compose_dropsInactiveCountdown() {
        String future = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        LandingPage page = composeOf(block("c1", "countdown", Map.of("is_active", false, "timer", future)));

        assertThat(page.blocks()).isEmpty();
    }

    @Test
    void compose_keepsActiveFutureCountdownAsStaticBlock() {
        String future = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        LandingPage page = composeOf(block("c1", "countdown", Map.of("is_active", true, "timer", future)));

        assertThat(page.blocks()).singleElement()
                .isInstanceOfSatisfying(StaticBlock.class,
                        b -> assertThat(b.blockType()).isEqualTo(BlockType.COUNTDOWN));
    }

    @Test
    void compose_unparseableTimer_keepsCountdown() {
        LandingPage page = composeOf(block("c1", "countdown", Map.of("is_active", true, "timer", "not-a-date")));

        assertThat(page.blocks()).hasSize(1);
    }

    @Test
    void compose_productsList_emitsDynamicPlaceholder() {
        LandingPage page = composeOf(new BlockDefinition("p1", "products_list", null, true, true,
                Map.of("source_of_data", "vertex")));

        assertThat(page.blocks()).singleElement()
                .isInstanceOfSatisfying(DynamicPlaceholder.class, p -> {
                    assertThat(p.blockType()).isEqualTo(BlockType.PRODUCTS_LIST);
                    assertThat(p.resolutionPath()).isEqualTo("/blp/blocks/products-list");
                });
    }

    @Test
    void compose_expiredCountdownDoesNotShiftRemainingOrder() {
        String past = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        LandingPage page = composeOf(
                block("b1", "container", Map.of()),
                block("c1", "countdown", Map.of("is_active", true, "timer", past)),
                block("b2", "container", Map.of()));

        List<LandingBlock> blocks = page.blocks();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).isInstanceOfSatisfying(StaticBlock.class, b -> assertThat(b.blockId()).isEqualTo("b1"));
        assertThat(blocks.get(1)).isInstanceOfSatisfying(StaticBlock.class, b -> assertThat(b.blockId()).isEqualTo("b2"));
    }
}
