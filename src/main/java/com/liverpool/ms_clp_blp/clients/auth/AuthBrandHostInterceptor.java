package com.liverpool.ms_clp_blp.clients.auth;

import java.util.Collection;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Re-targets a token exchange at the brand's own origin when one is configured.
 *
 * <p>The auth service routes by request <em>Host</em> and ignores {@code x-brand-id}
 * ({@link AuthProperties} has the full account), and {@code @FeignClient(url = "${auth.base-url}")} is
 * resolved once at startup — one host for the whole JVM. This interceptor is what makes the host
 * per-request without giving the port a transport concern to carry.
 *
 * <h3>Three Feign details this relies on, all verified against feign-core 13.6.1</h3>
 * <ul>
 *   <li>{@code RequestTemplate.target(String)} stores {@code scheme + "://" + rawAuthority + rawPath},
 *       so an origin that carries a path prefix ({@code https://host/auth-service}) keeps it and
 *       {@code /v2/auth/exchange-token} is still appended after it.</li>
 *   <li>{@code Target.HardCodedTarget.apply} is
 *       {@code if (input.url().indexOf("http") != 0) input.target(url())}, and interceptors run
 *       <em>before</em> it. Once an absolute target is set here the client's configured {@code url} is
 *       not reapplied; leave the template alone and it wins as usual. That is why
 *       {@link AuthBrandHostResolver#baseUrlFor(String)} returning empty means "do nothing" rather
 *       than "re-target at the base URL".</li>
 *   <li>{@code RequestTemplate.headers} is a {@code TreeMap(String.CASE_INSENSITIVE_ORDER)}, so the
 *       header lookup below cannot be broken by casing.</li>
 * </ul>
 *
 * <p>Re-targeting re-parses the URL, but query templates are preserved, so the session id keeps the
 * percent-encoding {@link AuthClient} depends on — {@code AuthClientWireTest} pins that against a real
 * socket, because a regression there degrades every user to guest without erroring.</p>
 */
public class AuthBrandHostInterceptor implements RequestInterceptor {

	private final AuthBrandHostResolver resolver;

	/**
	 * @param resolver decides which origin a brand must be asked on
	 */
	public AuthBrandHostInterceptor(AuthBrandHostResolver resolver) {
		this.resolver = resolver;
	}

	/**
	 * Points the request at the brand's origin, or leaves it untouched when the brand has no override.
	 *
	 * @param template the request Feign is about to send
	 */
	@Override
	public void apply(RequestTemplate template) {
		resolver.baseUrlFor(brandOf(template)).ifPresent(template::target);
	}

	/**
	 * @param template the outgoing request
	 * @return the value of {@code x-brand-id}, or {@code null} when the header is absent or empty
	 */
	private static String brandOf(RequestTemplate template) {
		Collection<String> values = template.headers().get(AuthClient.BRAND_HEADER);
		return values == null || values.isEmpty() ? null : values.iterator().next();
	}
}
