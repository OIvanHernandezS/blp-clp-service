package com.liverpool.ms_clp_blp.clients.blacklist;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.liverpool.ms_clp_blp.clients.blacklist.dto.BlacklistResponse;

/**
 * Declarative client for catalog-ingestion-service's blacklist
 * ({@code GET /catalog-ingestion-service/v1/blacklist/{site}?limit=N}). Timeouts and the connection
 * pool come from {@link BlacklistFeignConfig}; no retry (ADR-004).
 */
@FeignClient(
        name = "blacklistClient",
        url = "${blacklist.base-url}",
        configuration = BlacklistFeignConfig.class)
public interface BlacklistClient {

    /**
     * @param site  the catalog site ({@code LIVERPOOL}, {@code SUBURBIA}, …)
     * @param limit how many ids to return; {@code -1} for all of them
     * @return the blacklisted product ids
     */
    @GetMapping(path = "${blacklist.path}/{site}")
    BlacklistResponse findRestrictedProducts(@PathVariable("site") String site, @RequestParam("limit") int limit);
}
