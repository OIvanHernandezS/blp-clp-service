package com.liverpool.ms_clp_blp.clients.auth;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;

import com.liverpool.ms_clp_blp.clients.OutboundLoggingProperties;
import com.liverpool.ms_clp_blp.clients.feign.OutboundSpanEnrichingInterceptor;

import feign.Client;
import feign.Request;
import feign.RequestInterceptor;
import okhttp3.ConnectionPool;
import okhttp3.Protocol;

/**
 * Transport configuration for {@link AuthClient} alone.
 *
 * <p>Deliberately not annotated {@code @Configuration}/{@code @Component} so its beans stay in
 * Feign's per-client child context and retune this client only — see {@code VertexFeignConfig} for
 * the full rationale.</p>
 */
public class AuthFeignConfig {

	private static final int MAX_IDLE_CONNECTIONS = 20;
	private static final long KEEP_ALIVE_MINUTES = 1L;
	private static final long WRITE_TIMEOUT_MS = 15_000L;

	private final AuthProperties properties;
	private final OutboundLoggingProperties loggingProperties;

	/**
	 * @param properties        auth connection settings, resolved from the parent application context
	 * @param loggingProperties outbound logging settings, gating the request body on the outbound span
	 */
	public AuthFeignConfig(AuthProperties properties, OutboundLoggingProperties loggingProperties) {
		this.properties = properties;
		this.loggingProperties = loggingProperties;
	}

	/**
	 * @return per-call timeouts matching the superseded {@code authRestClient}
	 */
	@Bean
	public Request.Options authRequestOptions() {
		return new Request.Options(
				properties.connectTimeout().toMillis(), TimeUnit.MILLISECONDS,
				properties.readTimeout().toMillis(), TimeUnit.MILLISECONDS,
				true);
	}

	/**
	 * Registers the per-brand host workaround. The interceptor is inert unless
	 * {@code auth.brand-base-urls} names the request's brand, so this bean changes nothing on its own.
	 *
	 * @return an interceptor re-targeting the exchange at the brand's own origin where one is configured
	 */
	@Bean
	public RequestInterceptor authBrandHostInterceptor() {
		return new AuthBrandHostInterceptor(new AuthBrandHostResolver(properties));
	}

	/**
	 * @return an OkHttp transport with a pool isolated to the auth service and retries disabled (ADR-004)
	 */
	@Bean
	public Client authClient() {
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
