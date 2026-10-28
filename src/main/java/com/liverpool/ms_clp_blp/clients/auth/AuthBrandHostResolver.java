package com.liverpool.ms_clp_blp.clients.auth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * Answers which origin a brand's token exchange must be sent to.
 *
 * <p>Exists because the auth service routes the exchange by request <em>Host</em> and ignores
 * {@code x-brand-id} — see {@link AuthProperties} for the defect this works around.
 *
 * <h3>Everything is validated once, at construction</h3>
 * <p>{@code RequestTemplate.target(String)} throws {@link IllegalArgumentException} on a value that is
 * not absolute, and that is <em>not</em> a {@code FeignException}: it would sail straight past
 * {@code AuthTokenExchangeAdapter}'s catch and turn a typo in one env var into a 500 on every request
 * for that brand. So the map is normalised and filtered here, at startup, and a bad entry is dropped
 * with a WARN — the brand then falls back to {@code auth.base-url}, which is exactly the behaviour it
 * had before the override existed.</p>
 *
 * <p>Not a {@code @Component}: like {@link AuthFeignConfig}, which builds it, this belongs to Feign's
 * per-client child context and has no business in the application-wide component scan.</p>
 */
public class AuthBrandHostResolver {

	private static final Logger log = LoggerFactory.getLogger(AuthBrandHostResolver.class);

	/** Groups these lines with the rest of the auth integration's telemetry. */
	private static final String UPSTREAM = "auth";

	/** Log field: the brands whose exchange is re-targeted, so a pod says on boot what it will do. */
	private static final String OVERRIDDEN_BRANDS = "overriddenBrands";

	private final Map<String, String> baseUrlByBrand;

	/**
	 * @param properties auth configuration carrying {@code auth.base-url} and the per-brand overrides
	 */
	public AuthBrandHostResolver(AuthProperties properties) {
		this.baseUrlByBrand = normalise(properties);
		LogEvents.at(log, LogEvents.AUTH_BRAND_HOSTS_LOADED)
				.addKeyValue(LogFields.UPSTREAM, UPSTREAM)
				.addKeyValue(LogFields.BRANDS_TOTAL, baseUrlByBrand.size())
				.addKeyValue(OVERRIDDEN_BRANDS, baseUrlByBrand.keySet())
				.log(baseUrlByBrand.isEmpty()
						? "No per-brand auth origin configured; every brand uses auth.base-url"
						: "Per-brand auth origins active for the listed brands");
	}

	/**
	 * @param brand the brand the request carries, as sent in {@code x-brand-id}
	 * @return the origin this brand must be asked on, or empty when it has no override
	 *         and the client's configured {@code url} should stand
	 */
	public Optional<String> baseUrlFor(String brand) {
		if (brand == null || brand.isBlank()) {
			return Optional.empty();
		}
		return Optional.ofNullable(baseUrlByBrand.get(key(brand)));
	}

	// ── private ──────────────────────────────────────────────────────────────────────────────────

	/**
	 * Builds the lookup once: keys upper-cased, values trimmed, and anything unusable discarded.
	 *
	 * @param properties the bound configuration
	 * @return an immutable brand → origin map holding only entries that are safe to hand to Feign
	 */
	private static Map<String, String> normalise(AuthProperties properties) {
		Map<String, String> configured = properties.brandBaseUrls();
		if (configured == null || configured.isEmpty()) {
			return Map.of();
		}
		String baseUrl = trimmed(properties.baseUrl());
		Map<String, String> resolved = new LinkedHashMap<>();
		configured.forEach((brand, url) -> {
			String value = trimmed(url);
			// A blank entry is the default for every brand in application.yaml, and it is what "this
			// brand behaves like the rest" looks like — not something to warn about.
			if (brand == null || brand.isBlank() || value.isEmpty() || value.equals(baseUrl)) {
				return;
			}
			if (isUsableTarget(value)) {
				resolved.put(key(brand), value);
			} else {
				LogEvents.at(log, LogEvents.AUTH_BRAND_HOST_INVALID)
						.addKeyValue(LogFields.UPSTREAM, UPSTREAM)
						.addKeyValue(LogFields.BRAND, key(brand))
						.addKeyValue(LogFields.URI, value)
						.log("Per-brand auth origin is not an absolute URL; the brand falls back to auth.base-url");
			}
		});
		return Map.copyOf(resolved);
	}

	/**
	 * Mirrors what Feign demands of a target: absolute, with a scheme and an authority. Checked here so
	 * the throw never happens on the request path.
	 *
	 * @param value the configured origin
	 * @return {@code true} when Feign will accept it as a target
	 */
	private static boolean isUsableTarget(String value) {
		try {
			URI uri = new URI(value);
			return uri.isAbsolute() && uri.getScheme() != null && uri.getRawAuthority() != null;
		} catch (URISyntaxException e) {
			return false;
		}
	}

	private static String key(String brand) {
		return brand.trim().toUpperCase(Locale.ROOT);
	}

	private static String trimmed(String value) {
		return value == null ? "" : value.trim();
	}
}
