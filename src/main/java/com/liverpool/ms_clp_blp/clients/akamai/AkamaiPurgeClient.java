package com.liverpool.ms_clp_blp.clients.akamai;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.liverpool.ms_clp_blp.clients.akamai.dto.AkamaiPurgeRequest;
import com.liverpool.ms_clp_blp.clients.akamai.dto.AkamaiPurgeResponse;

/**
 * Declarative client for Akamai's Fast Purge v3 API
 * (<a href="https://techdocs.akamai.com/purge-cache/reference/api">techdocs</a>).
 *
 * <p>EdgeGrid signing, timeouts and the connection pool come from {@link AkamaiFeignConfig}, so no
 * call site handles authentication. As everywhere else in this package there is no retry logic
 * (ADR-004); unlike the request-path clients there is also no circuit breaker, because a purge is
 * operator-triggered and off the request path — see {@code AkamaiCachePurgeAdapter}.</p>
 *
 * <p>One method covers all four purge combinations, because Akamai encodes every one of them in the
 * path rather than the body: {@code action} chooses invalidate vs delete, {@code objectType} chooses
 * URL vs cache tag, and {@code network} chooses staging vs production. Splitting this into four
 * methods would duplicate the same request/response contract four times.</p>
 */
@FeignClient(
		name = "akamaiPurgeClient",
		url = "${akamai.base-url}",
		configuration = AkamaiFeignConfig.class)
public interface AkamaiPurgeClient {

	/**
	 * Submits one partial purge.
	 *
	 * <p>Fast Purge is asynchronous: a 201 means Akamai accepted the request, not that the objects
	 * are already gone from the edge. {@code estimatedSeconds} in the response is Akamai's own
	 * estimate of propagation time.</p>
	 *
	 * @param action     {@code invalidate} (mark stale, edge revalidates with origin) or
	 *                   {@code delete} (evict outright)
	 * @param objectType {@code url} or {@code tag}
	 * @param network    {@code staging} or {@code production}
	 * @param request    the URLs or cache tags to purge
	 * @return the accepted-purge envelope, carrying {@code purgeId} and {@code supportId}
	 */
	@PostMapping(
			path = "/ccu/v3/{action}/{objectType}/{network}",
			consumes = MediaType.APPLICATION_JSON_VALUE)
	AkamaiPurgeResponse purge(
			@PathVariable("action") String action,
			@PathVariable("objectType") String objectType,
			@PathVariable("network") String network,
			@RequestBody AkamaiPurgeRequest request);
}
