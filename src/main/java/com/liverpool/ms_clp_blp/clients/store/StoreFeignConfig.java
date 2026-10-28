package com.liverpool.ms_clp_blp.clients.store;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;

import com.liverpool.ms_clp_blp.clients.OutboundLoggingProperties;
import com.liverpool.ms_clp_blp.clients.feign.OutboundSpanEnrichingInterceptor;

import feign.Client;
import feign.Request;
import okhttp3.ConnectionPool;
import okhttp3.Protocol;

/**
 * Transport configuration for {@link StoreClient} alone.
 *
 * <p><strong>Deliberately not annotated {@code @Configuration}/{@code @Component}.</strong> Feign
 * instantiates a class named in {@code @FeignClient(configuration = ...)} inside that client's own
 * child context, so these beans override the global ones for store-service and nothing else. Annotating it
 * would promote them to the application context and silently retune every other client — the bug
 * {@code ms_pdp_search}'s {@code FeignClientConfig} carries today. This mirrors
 * {@code platform-commons}' {@code JewelFeignConfig}, which documents the same constraint.</p>
 *
 * <p>A dedicated connection pool per external system is the point (Rule 4): a slow
 * store-service cannot starve another pool and stall every landing page.</p>
 */
public class StoreFeignConfig {

	private static final int MAX_IDLE_CONNECTIONS = 10;
	private static final long KEEP_ALIVE_MINUTES = 1L;
	private static final long CONNECT_TIMEOUT_MS = 2000L;
	private static final long WRITE_TIMEOUT_MS = 15_000L;

	private final StoreProperties properties;
	private final OutboundLoggingProperties loggingProperties;

	/**
	 * @param properties        store-service connection settings, resolved from the parent application context
	 * @param loggingProperties outbound logging settings, gating the request body on the outbound span
	 */
	public StoreFeignConfig(StoreProperties properties, OutboundLoggingProperties loggingProperties) {
		this.properties = properties;
		this.loggingProperties = loggingProperties;
	}

	/**
	 * @return per-call timeouts for store-service
	 */
	@Bean
	public Request.Options storeRequestOptions() {
		return new Request.Options(
				CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS,
				properties.timeout().toMillis(), TimeUnit.MILLISECONDS,
				true);
	}

	/**
	 * @return an OkHttp transport with a pool isolated to store-service and retries disabled (ADR-004)
	 */
	@Bean
	public Client storeFeignTransport() {
		okhttp3.OkHttpClient okHttpClient = new okhttp3.OkHttpClient.Builder()
				.protocols(List.of(Protocol.HTTP_1_1))
				.connectionPool(new ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
				.connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
				.readTimeout(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)
				.writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
				.retryOnConnectionFailure(false)
				.addNetworkInterceptor(new OutboundSpanEnrichingInterceptor(loggingProperties))
				.build();
		return new feign.okhttp.OkHttpClient(okHttpClient);
	}
}
