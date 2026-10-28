package com.liverpool.ms_clp_blp.clients.categoryindexer;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import com.liverpool.ms_clp_blp.clients.categoryindexer.dto.CategoryTreeResponse;

/**
 * Declarative client for the category-tree indexer.
 *
 * <p>The lookup is a {@code POST} with an empty body and the brand in the path, exactly as digital_bff
 * called it ({@code POST /search-category/{brand}/{categoryId}}). Timeouts and the connection pool come
 * from {@link CategoryIndexerFeignConfig}; there is no retry here — the breaker is applied by the
 * adapter and retries are deliberately absent (ADR-004).</p>
 */
@FeignClient(
        name = "categoryIndexerClient",
        url = "${category-indexer.base-url}",
        configuration = CategoryIndexerFeignConfig.class)
public interface CategoryIndexerClient {

    /**
     * Reads one category with its children.
     *
     * @param brand      brand identifier ({@code LP}, {@code SB}, …)
     * @param categoryId the category id, as it appears in the storefront URL
     * @return the category tree rooted at that category
     */
    @PostMapping(path = "${category-indexer.search-path}/{brand}/{categoryId}")
    CategoryTreeResponse findCategory(@PathVariable("brand") String brand,
                                      @PathVariable("categoryId") String categoryId);
}
