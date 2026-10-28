package com.liverpool.ms_clp_blp.clients.store;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.liverpool.ms_clp_blp.clients.store.dto.StoreDetailsResponse;

/**
 * Declarative client for store-service's store-details lookup
 * ({@code GET /brands/{brand}/stores/{storeId}}). Timeouts and the connection pool come from
 * {@link StoreFeignConfig}; no retry (ADR-004).
 */
@FeignClient(
        name = "storeClient",
        url = "${store-service.base-url}",
        configuration = StoreFeignConfig.class)
public interface StoreClient {

    /**
     * @param brand   brand identifier
     * @param storeId the store id
     * @return the store's details
     */
    @GetMapping(path = "/brands/{brand}/stores/{storeId}")
    StoreDetailsResponse findStore(@PathVariable("brand") String brand, @PathVariable("storeId") String storeId);
}
