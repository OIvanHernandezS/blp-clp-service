package com.liverpool.ms_clp_blp.clients.contentstackdirect;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.liverpool.clients.ContentStackClient;
import com.liverpool.config.properties.ContentStackConfigResolver;
import com.liverpool.config.properties.ContentStackProperties;
import com.liverpool.ms_clp_blp.clients.OutboundLoggingProperties;
import com.liverpool.ms_clp_blp.clients.feign.OutboundSpanEnrichingInterceptor;

import feign.Request;
import feign.Retryer;
import okhttp3.ConnectionPool;
import okhttp3.Protocol;

/**
 * Wires the direct Contentstack Delivery API client reused from {@code platform-commons}.
 *
 * <h3>Why the client is built here instead of being scanned</h3>
 * <p>{@code MsClpBlpApplication} pins {@code @EnableFeignClients} to this project's own package and
 * must not be widened — commons ships several Feign interfaces whose {@code url} placeholders this
 * project does not define. The obvious-looking escape hatch, {@code @EnableFeignClients(clients =
 * ContentStackClient.class)}, is a trap: a non-empty {@code clients} attribute makes
 * {@code FeignClientsRegistrar} skip classpath scanning altogether, which would silently unregister
 * every one of ms-clp-blp's own clients. {@code FeignClientBuilder} registers exactly one interface and
 * leaves the scan alone, while still going through Feign's normal child-context machinery — so the
 * Spring MVC contract, the Boot 4 encoder/decoder and the global logging beans from
 * {@code clients/feign} are all inherited.</p>
 *
 * <h3>Why the pool is its own</h3>
 * <p>Deliberately not shared with {@code ContentstackFeignConfig}'s. This client exists to be used
 * when the content-service is unhealthy, which is exactly when that pool is saturated; sharing one
 * would tie the fallback's fate to the failure it is meant to survive.</p>
 */
@Configuration
@EnableConfigurationProperties(ContentStackProperties.class)
public class ContentStackDirectConfig {

	/** Smaller than the proxy client's pool: this path only carries traffic while the proxy is down. */
	private static final int MAX_IDLE_CONNECTIONS = 20;
	private static final long KEEP_ALIVE_MINUTES = 1L;

	/** Feign context id; also the prefix of this client's log lines. */
	private static final String CLIENT_NAME = "contentStackDirect";

	/**
	 * Registers commons' brand-to-stack resolver, which ms-clp-blp does not component-scan.
	 *
	 * @param properties per-stack Contentstack credentials bound from {@code api.content-stack}
	 * @return the resolver mapping a {@code Sites} constant onto one stack's credentials
	 */
	@Bean
	public ContentStackConfigResolver contentStackConfigResolver(ContentStackProperties properties) {
		return new ContentStackConfigResolver(properties);
	}

	/**
	 * Builds the Contentstack Delivery API client with a transport isolated from the proxy client's.
	 *
	 * @param applicationContext context supplying Feign's shared contract, codecs and logger
	 * @param properties         Contentstack credentials, read here only for the stack-wide base URL
	 * @param directProperties   timeouts for this transport
	 * @param loggingProperties  outbound logging settings, gating the request body on the outbound span
	 * @return the single {@link ContentStackClient} bean for the fallback path
	 */
	@Bean
	public ContentStackClient contentStackClient(ApplicationContext applicationContext,
			ContentStackProperties properties,
			ContentStackDirectProperties directProperties,
			OutboundLoggingProperties loggingProperties) {
		long connectMs = directProperties.connectTimeout().toMillis();
		long callMs = directProperties.callTimeout().toMillis();
		okhttp3.OkHttpClient okHttpClient = new okhttp3.OkHttpClient.Builder()
				.protocols(List.of(Protocol.HTTP_1_1))
				.connectionPool(new ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
				.connectTimeout(connectMs, TimeUnit.MILLISECONDS)
				.readTimeout(callMs, TimeUnit.MILLISECONDS)
				.callTimeout(callMs, TimeUnit.MILLISECONDS)
				.retryOnConnectionFailure(false)
				.addNetworkInterceptor(new OutboundSpanEnrichingInterceptor(loggingProperties))
				.build();
		Request.Options options = new Request.Options(
				connectMs, TimeUnit.MILLISECONDS, callMs, TimeUnit.MILLISECONDS, true);
		return new org.springframework.cloud.openfeign.FeignClientBuilder(applicationContext)
				.forType(ContentStackClient.class, CLIENT_NAME)
				.url(properties.getUrl())
				// No retries here either (ADR-004): this is already the retry.
				.customize(builder -> builder
						.client(new feign.okhttp.OkHttpClient(okHttpClient))
						.options(options)
						.retryer(Retryer.NEVER_RETRY))
				.build();
	}
}
