package com.liverpool.ms_clp_blp.clients.vertex;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;

import com.liverpool.ms_clp_blp.clients.OutboundLoggingProperties;
import com.liverpool.ms_clp_blp.clients.feign.OutboundSpanEnrichingInterceptor;

import feign.Client;
import feign.Request;
import feign.RequestInterceptor;
import feign.auth.BasicAuthRequestInterceptor;
import okhttp3.ConnectionPool;
import okhttp3.Protocol;

/**
 * Transport configuration for {@link VertexClient} alone.
 *
 * <p><strong>Deliberately not annotated {@code @Configuration}/{@code @Component}.</strong> Feign
 * instantiates a class named in {@code @FeignClient(configuration = ...)} inside that client's own
 * child context, so these beans override the global ones for Vertex and nothing else. Annotating it
 * would promote them to the application context and silently retune every other client — the bug
 * {@code ms_pdp_search}'s {@code FeignClientConfig} carries today. This mirrors
 * {@code platform-commons}' {@code JewelFeignConfig}, which documents the same constraint.</p>
 *
 * <p>A dedicated connection pool per external system is the point (Rule 4): a slow
 * {@code ms_plp_search} cannot starve the content-service pool and stall every landing page.</p>
 */
public class VertexFeignConfig {

	private static final int MAX_IDLE_CONNECTIONS = 20;
	private static final long KEEP_ALIVE_MINUTES = 1L;
	private static final long CONNECT_TIMEOUT_MS = 2000L;
	private static final long WRITE_TIMEOUT_MS = 15_000L;

	private final VertexProperties properties;
	private final OutboundLoggingProperties loggingProperties;

	/**
	 * @param properties        Vertex connection settings, resolved from the parent application context
	 * @param loggingProperties outbound logging settings, gating the request body on the outbound span
	 */
	public VertexFeignConfig(VertexProperties properties, OutboundLoggingProperties loggingProperties) {
		this.properties = properties;
		this.loggingProperties = loggingProperties;
	}

	/**
	 * Applies HTTP Basic credentials to every Vertex call.
	 *
	 * <p>Kept at the client rather than on the interface method so no call site can forget it, matching
	 * how the superseded {@code vertexRestClient} bean set the header as a builder default.
	 *
	 * @return an interceptor stamping the {@code Authorization} header
	 */
	@Bean
	public RequestInterceptor vertexAuthInterceptor() {
		return new BasicAuthRequestInterceptor(properties.username(), properties.password());
	}

	/**
	 * @return per-call timeouts matching the superseded {@code vertexRestClient}
	 */
	@Bean
	public Request.Options vertexRequestOptions() {
		return new Request.Options(
				CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS,
				properties.timeout().toMillis(), TimeUnit.MILLISECONDS,
				true);
	}

	/**
	 * @return an OkHttp transport with a pool isolated to Vertex and retries disabled (ADR-004)
	 */
	@Bean
	public Client vertexClient() {
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
