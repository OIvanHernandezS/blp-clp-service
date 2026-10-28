package com.liverpool.ms_clp_blp.clients.salesforce;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.liverpool.clients.config.SalesforceCarrouselsFeignConfig;
import com.liverpool.dto.salesforce.SalesforceRequest;
import com.liverpool.dto.salesforce.SalesforceResponse;

/**
 * Typed client for the Salesforce (Evergage) campaign-decision endpoint used by {@code products_list}
 * carousels.
 *
 * <h3>Why a typed client</h3>
 * <p>The carousels contract is not a map to project two fields out of: Salesforce answers in three different product shapes, inverts two price attribute names on one
 * of them, and nests the anchor product and category scope several levels deep. Expressing that as map
 * casting is how {@code productToShow} came to be silently unread. This client reuses commons'
 * {@code com.liverpool.dto.salesforce} DTOs — the same wire contract {@code ms_plp_search} and
 * {@code ms_pdp_search} speak — so the shape is checked by the compiler instead of by hope.</p>
 *
 * <h3>Transport and credentials</h3>
 * <p>{@link SalesforceCarrouselsFeignConfig} is commons-owned and supplies both the isolated
 * HTTP/1.1 OkHttp pool and the {@code Authorization: Basic base64(user:pass)} interceptor built from
 * {@code salesforce.auth.*}. No path is declared on the method: the full endpoint path lives in
 * {@code api.salesforce-carrousels.url}, matching commons and {@code ms_plp_search}.</p>
 */
@FeignClient(
		name = "salesforceCarrouselsClient",
		url = "${api.salesforce-carrousels.url}",
		configuration = SalesforceCarrouselsFeignConfig.class)
public interface SalesforceCarrouselsClient {

	/**
	 * Resolves the campaigns configured for the requested action, shopper and context.
	 *
	 * @param request the action, flags, source, catalog anchor and shopper identity to send
	 * @return the campaign decision; the body may be {@code null} when Salesforce returns no content,
	 *         and {@code campaignResponses} may be absent or empty for an unknown action (Salesforce
	 *         answers HTTP 200 in that case, not an error)
	 */
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<SalesforceResponse> post(@RequestBody SalesforceRequest request);
}
