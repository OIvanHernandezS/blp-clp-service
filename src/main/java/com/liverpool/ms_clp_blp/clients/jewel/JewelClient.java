package com.liverpool.ms_clp_blp.clients.jewel;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.liverpool.clients.config.JewelFeignConfig;
import com.liverpool.dto.jewel.JewelProduct;

/**
 * Declarative client for the JewelML recommendation engine.
 *
 * <h3>Why the transport config comes from platform-commons</h3>
 * <p>{@code configuration = JewelFeignConfig.class} reuses commons' Jewel transport wholesale, which
 * is the point of the reference rather than a shortcut (ADR-013): besides the timeouts from
 * {@code ApiJewelConfig} and an HTTP/1.1-pinned pool, it installs
 * {@code JewelConnectionResetListener} — a safety valve that evicts the OkHttp pool when more than 2%
 * of calls in a rolling 60-second window time out. That behaviour is proven in {@code ms_plp_search}
 * production and had no equivalent while this integration was on {@code RestClient}.</p>
 *
 * <p>This project declares its own interface rather than consuming commons'
 * {@code com.liverpool.clients.JewelClient} because scanning that package would also register
 * commons' ContentStack, decomm, EDD and Salesforce-V2 clients, whose {@code url} placeholders this
 * project does not define (see {@code MsClpBlpApplication}). Only the parameters ms-clp-blp actually sends
 * are declared here; commons' interface additionally exposes {@code item_id}.</p>
 *
 * <p>Identity parameters are {@code required = false}: Feign omits a query parameter whose value is
 * {@code null}, which is what lets the adapter send {@code user_id}/{@code unique_id} only when the
 * block's model declares it needs them.</p>
 */
@FeignClient(
		name = "jewelClient",
		url = "${api.jewel.url}",
		configuration = JewelFeignConfig.class)
public interface JewelClient {

	/**
	 * Fetches recommendations for one model.
	 *
	 * @param pathIntegrationId  per-brand integration id, in the path
	 * @param queryIntegrationId the same integration id, which Jewel also expects as a query parameter
	 * @param model              the Jewel model name authored on the block
	 * @param minimumItems       minimum products for the response to be usable
	 * @param numberOfPlacements maximum products requested
	 * @param userId             logged-in user id; {@code null} unless the model requires it
	 * @param deviceId           device id; {@code null} unless the model requires it
	 * @return the recommended products, in Jewel's ranking order
	 */
	@GetMapping("/c/p/{integrationId}/l")
	List<JewelProduct> getRecommendations(
			@PathVariable("integrationId") String pathIntegrationId,
			@RequestParam("integration_id") String queryIntegrationId,
			@RequestParam("model") String model,
			@RequestParam("minimum_items") Integer minimumItems,
			@RequestParam("number_of_placements") Integer numberOfPlacements,
			@RequestParam(value = "user_id", required = false) String userId,
			@RequestParam(value = "unique_id", required = false) String deviceId);
}
