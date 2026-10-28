package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1;

import java.util.List;

import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.DynamicBlockStatus;

/**
 * Maps resolved {@link ProductsListResolution} domain objects to the REST response DTOs.
 *
 * <p>Provider-agnostic: Jewel resolutions simply carry no campaign title or id, and those fields are
 * omitted rather than emitted empty, so the Jewel payload is exactly
 * {@code {blockId, products}} while the Salesforce one adds {@code products_list_title} /
 * {@code productsListId} (ADR-029).</p>
 */
@Component
public class ProductsListMapper {

    /**
     * @param resolution the resolved carousel
     * @return the REST view; a blank campaign title or id is omitted rather than emitted empty, so a
     *         block without one keeps its own CMS {@code title}
     */
    public ProductsListResponse toResponse(ProductsListResolution resolution) {
        return new ProductsListResponse(
                resolution.blockId(),
                blankToNull(resolution.title()),
                blankToNull(resolution.campaignName()),
                resolution.products());
    }

    /**
     * Wraps resolutions into the batch envelope, preserving order.
     *
     * <p>An {@code EMPTY} entry and an {@code UNAVAILABLE} one both carry a {@code null} content, and
     * both name their {@code reason}: the difference is that the first is terminal — the frontend drops
     * the block — while the second is worth retrying.</p>
     *
     * @param resolutions one resolution per requested block, in request order
     * @return the batch response
     */
    public ProductsListBatchResponse toBatchResponse(List<ProductsListResolution> resolutions) {
        List<ProductsListBatchResponse.Item> items = resolutions.stream()
                .map(this::toItem)
                .toList();
        return new ProductsListBatchResponse(items);
    }

    /**
     * Enforces the contract's one invariant: <strong>{@code AVAILABLE} implies content</strong>.
     *
     * <p>The domain already sets {@code EMPTY} wherever it resolves to nothing, so the check below is
     * normally redundant. It stays because the failure it guards against is the bug this endpoint was
     * reported for: an {@code AVAILABLE} entry with no {@code content} reads to a client as a transient
     * failure and gets retried forever. A future path that forgets to set the status degrades to a
     * correctly-dropped block instead of an infinite retry loop, and this is the one place every
     * resolution passes through (ADR-043).</p>
     */
    private ProductsListBatchResponse.Item toItem(ProductsListResolution resolution) {
        if (resolution.status() == DynamicBlockStatus.AVAILABLE && resolution.isEmpty()) {
            return new ProductsListBatchResponse.Item(resolution.blockId(),
                    DynamicBlockStatus.EMPTY, BlockDropReason.RESOLVER_RETURNED_EMPTY, null);
        }
        boolean renderable = resolution.status() == DynamicBlockStatus.AVAILABLE;
        return new ProductsListBatchResponse.Item(
                resolution.blockId(),
                resolution.status(),
                resolution.reason(),
                renderable ? toResponse(resolution) : null);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
