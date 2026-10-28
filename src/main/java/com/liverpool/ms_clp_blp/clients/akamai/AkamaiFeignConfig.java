package com.liverpool.ms_clp_blp.clients.akamai;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;

import com.akamai.edgegrid.signer.EdgeGridV1Signer;
import com.liverpool.ms_clp_blp.clients.OutboundLoggingProperties;
import com.liverpool.ms_clp_blp.clients.feign.OutboundSpanEnrichingInterceptor;

import feign.Client;
import feign.Request;
import feign.RequestInterceptor;
import okhttp3.ConnectionPool;
import okhttp3.Protocol;

/**
 * Transport configuration for {@link AkamaiPurgeClient} alone.
 *
 * <p><strong>Deliberately not annotated {@code @Configuration}/{@code @Component}</strong>, for the
 * reason spelled out at length on {@code VertexFeignConfig}: Feign instantiates a class named in
 * {@code @FeignClient(configuration = ...)} inside that client's own child context, so these beans
 * override the global ones for Akamai and nothing else. Annotating it would promote them to the
 * application context and silently retune every other client.</p>
 *
 * <p>Its own connection pool, like every other system here (Rule 4) — and more pointedly than usual:
 * a purge is typically fired <em>during</em> an incident, so it must not be queueing behind whichever
 * dependency is currently unhealthy.</p>
 *
 * <p>No {@code Retryer} (ADR-004) and no {@code ErrorDecoder}, so a raw {@code FeignException}
 * reaches {@code AkamaiCachePurgeAdapter}, which owns the translation to a domain error. Not retrying
 * matters more here than elsewhere: Fast Purge is rate limited, and a blind retry of a request the
 * edge already accepted spends quota to no effect.</p>
 */
public class AkamaiFeignConfig {

	private static final int MAX_IDLE_CONNECTIONS = 5;
	private static final long KEEP_ALIVE_MINUTES = 1L;
	private static final long WRITE_TIMEOUT_MS = 10_000L;

	private final AkamaiProperties properties;
	private final OutboundLoggingProperties loggingProperties;

	/**
	 * @param properties        Akamai connection settings, resolved from the parent application context
	 * @param loggingProperties outbound logging settings, gating the request body on the outbound span
	 */
	public AkamaiFeignConfig(AkamaiProperties properties, OutboundLoggingProperties loggingProperties) {
		this.properties = properties;
		this.loggingProperties = loggingProperties;
	}

	/**
	 * @return the interceptor applying the EdgeGrid signature to every purge call
	 */
	@Bean
	public RequestInterceptor akamaiEdgeGridInterceptor() {
		return new AkamaiEdgeGridInterceptor(properties, new EdgeGridV1Signer());
	}

	/**
	 * @return per-call timeouts for purge submissions
	 */
	@Bean
	public Request.Options akamaiRequestOptions() {
		return new Request.Options(
				properties.connectTimeout().toMillis(), TimeUnit.MILLISECONDS,
				properties.readTimeout().toMillis(), TimeUnit.MILLISECONDS,
				true);
	}

	/**
	 * @return an OkHttp transport with a pool isolated to Akamai and retries disabled (ADR-004). The
	 *         pool is smaller than the request-path clients' 20: purges are rare and serial, so
	 *         idle connections here would be held open for nothing.
	 */
	@Bean
	public Client akamaiHttpClient() {
		okhttp3.OkHttpClient okHttpClient = new okhttp3.OkHttpClient.Builder()
				.protocols(List.of(Protocol.HTTP_1_1))
				.connectionPool(new ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
				.connectTimeout(properties.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
				.readTimeout(properties.readTimeout().toMillis(), TimeUnit.MILLISECONDS)
				.writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
				.retryOnConnectionFailure(false)
				.addNetworkInterceptor(new OutboundSpanEnrichingInterceptor(loggingProperties))
				.build();
		return new feign.okhttp.OkHttpClient(okHttpClient);
	}
}
