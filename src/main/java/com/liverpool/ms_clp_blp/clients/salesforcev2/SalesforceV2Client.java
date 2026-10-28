package com.liverpool.ms_clp_blp.clients.salesforcev2;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.liverpool.clients.config.SalesforceV2FeignConfig;
import com.liverpool.dto.salesforce.v2.SalesforceV2Request;
import com.liverpool.dto.salesforce.v2.SalesforceV2Response;

/**
 * Typed client for the Salesforce Personalization (V2) {@code /personalization/decisions} endpoint used
 * by {@code products_list} carousels with {@code source_of_data = salesforce_v2}.
 *
 * <p>This project declares its own interface rather than consuming commons'
 * {@code com.liverpool.clients.SalesforceV2Client} because scanning that package would also register
 * commons' ContentStack, decomm and EDD clients, whose {@code url} placeholders this project does not
 * define (see {@code MsClpBlpApplication}). The wire contract is still commons': the request and response
 * are its {@code com.liverpool.dto.salesforce.v2} DTOs, and {@link SalesforceV2FeignConfig} supplies the
 * timeouts from {@code api.salesforce-v2.*} and an isolated HTTP/1.1 OkHttp pool, so this client shares
 * no connection with any other.</p>
 *
 * <p>The Feign name differs from commons' {@code salesforceV2Client} on purpose: that is also the name
 * of the {@code Client} bean {@link SalesforceV2FeignConfig} declares, and keeping the two apart avoids
 * any ambiguity in the per-client context.</p>
 */
@FeignClient(
		name = "salesforceV2DecisionsClient",
		url = "${api.salesforce-v2.url}",
		configuration = SalesforceV2FeignConfig.class)
public interface SalesforceV2Client {

	/**
	 * Resolves the requested personalization points for the given shopper.
	 *
	 * @param request the personalization context and the single point to resolve
	 * @return the Salesforce decisions; the body may be {@code null} when Salesforce returns no content
	 */
	@PostMapping(value = "/personalization/decisions", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<SalesforceV2Response> getDecisions(@RequestBody SalesforceV2Request request);
}
