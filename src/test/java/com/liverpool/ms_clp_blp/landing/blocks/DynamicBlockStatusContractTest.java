package com.liverpool.ms_clp_blp.landing.blocks;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListBatchResponse;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListMapper;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.DynamicBlockStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one invariant every {@code /blp|clp/blocks/*} resolution endpoint must share, asserted through its
 * real mapper:
 *
 * <blockquote>A response is {@link DynamicBlockStatus#AVAILABLE} <strong>only</strong> when it carries
 * something to draw. Anything else is {@code EMPTY} or {@code UNAVAILABLE}, and always names a
 * {@link BlockDropReason}.</blockquote>
 *
 * <p>This is the regression guard for the bug that motivated ADR-043. A carousel that resolved
 * legitimately to nothing came back {@code AVAILABLE} with no content; the app read the missing content as
 * a transient failure and retried the request forever. The invariant is enforced in each slice's mapper
 * rather than in its use case, because the mapper is the one place every resolution passes through on its
 * way to the wire — so a future code path that forgets to set the status degrades to a correctly-dropped
 * block instead of reopening the retry loop.</p>
 *
 * <p>Lives in {@code landing.blocks} rather than in the products-list sub-slice because it is the
 * contract every block endpoint must keep: {@code products-list} is the only one today, and a new block
 * sub-slice belongs in these providers as soon as it has a mapper.</p>
 */
class DynamicBlockStatusContractTest {

    /** What one endpoint's mapper produced, reduced to the two fields this contract is about. */
    private record Outcome(DynamicBlockStatus status, BlockDropReason reason, boolean hasContent) {
    }

    // ── the invariant ────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} reports EMPTY with a reason and no content")
    @MethodSource("resolvedToNothing")
    void aBlockThatResolvedToNothing_isNeverAvailable(String endpoint, Outcome outcome) {
        assertThat(outcome.status())
                .as("%s must not report AVAILABLE for a block with nothing to draw — that is the "
                        + "value the frontend retries", endpoint)
                .isEqualTo(DynamicBlockStatus.EMPTY);
        assertThat(outcome.reason())
                .as("%s must name the rule that emptied the block, so a support question has an "
                        + "answer without re-running the request", endpoint)
                .isNotNull();
        assertThat(outcome.hasContent())
                .as("%s must not ship content alongside a non-AVAILABLE status", endpoint)
                .isFalse();
    }

    @ParameterizedTest(name = "{0} reports AVAILABLE with content and no reason")
    @MethodSource("resolvedWithContent")
    void aBlockThatResolved_isAvailableAndCarriesNoReason(String endpoint, Outcome outcome) {
        assertThat(outcome.status()).as("%s", endpoint).isEqualTo(DynamicBlockStatus.AVAILABLE);
        assertThat(outcome.reason())
                .as("%s must omit the reason on the happy path; a stale one would read as a problem",
                        endpoint)
                .isNull();
        assertThat(outcome.hasContent()).as("%s", endpoint).isTrue();
    }

    /**
     * The failure the invariant actually guards: a resolution that claims {@code AVAILABLE} while carrying
     * nothing. Every mapper must correct it rather than pass it through — this is the shape of the
     * original bug, so it is asserted directly and not only via the well-behaved paths above.
     */
    @ParameterizedTest(name = "{0} corrects a mislabelled empty resolution")
    @MethodSource("mislabelledAsAvailable")
    void anAvailableResolutionCarryingNothing_isCorrectedToEmpty(String endpoint, Outcome outcome) {
        assertThat(outcome.status())
                .as("%s trusted a resolution that said AVAILABLE with nothing in it", endpoint)
                .isEqualTo(DynamicBlockStatus.EMPTY);
        assertThat(outcome.reason()).as("%s", endpoint)
                .isEqualTo(BlockDropReason.RESOLVER_RETURNED_EMPTY);
        assertThat(outcome.hasContent()).as("%s", endpoint).isFalse();
    }

    // ── the endpoints, each driven through its real mapper ──────────────────────────────────

    private static Stream<Arguments> resolvedToNothing() {
        return Stream.of(
                Arguments.of("products-list", productsList(
                        ProductsListResolution.empty("b1", BlockDropReason.BELOW_MIN_PRODUCTS))),
                Arguments.of("products-list (provider off)", productsList(
                        ProductsListResolution.empty("b1", BlockDropReason.FEATURE_FLAG_OFF))));
    }

    private static Stream<Arguments> resolvedWithContent() {
        return Stream.of(
                Arguments.of("products-list", productsList(ProductsListResolution.available(
                        "b1", "Ofertas", "CMSOfertas", List.of(product())))));
    }

    private static Stream<Arguments> mislabelledAsAvailable() {
        return Stream.of(
                // Each of these is the pre-ADR-043 shape: AVAILABLE, but nothing to render.
                Arguments.of("products-list", productsList(new ProductsListResolution(
                        "b1", "Ofertas", "CMSOfertas", List.of(), DynamicBlockStatus.AVAILABLE, null))));
    }

    // ── mapper drivers: each reduces one endpoint's real response to an Outcome ──────────────────

    private static Outcome productsList(ProductsListResolution resolution) {
        ProductsListBatchResponse.Item item =
                new ProductsListMapper().toBatchResponse(List.of(resolution)).blocks().getFirst();
        return new Outcome(item.status(), item.reason(), item.content() != null);
    }

    private static ProductRecommendation product() {
        ProductRecommendation product = new ProductRecommendation();
        product.setProductId("1043210");
        product.setName("Licuadora Oster");
        return product;
    }
}
