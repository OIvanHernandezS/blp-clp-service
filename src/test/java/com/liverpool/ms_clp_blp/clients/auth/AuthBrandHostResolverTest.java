package com.liverpool.ms_clp_blp.clients.auth;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit contract for {@link AuthBrandHostResolver}.
 *
 * <p>The load-bearing cases are the ones about <em>not</em> answering: an empty result means "leave the
 * request alone", and a bad entry must be discarded at construction rather than blowing up on the
 * request path, where the exception would escape {@code AuthTokenExchangeAdapter}'s catch and 500 every
 * request for that brand.</p>
 */
class AuthBrandHostResolverTest {

	private static final String BASE_URL = "https://ogcp-cms-site-d.liverpool.com.mx/auth-service";
	private static final String SB_URL = "https://ogcp-cms-site-d.suburbia.com.mx/auth-service";

	@Test
	void baseUrlFor_returnsTheBrandOrigin_whenOneIsConfigured() {
		AuthBrandHostResolver resolver = resolverWith(Map.of("SB", SB_URL));

		assertThat(resolver.baseUrlFor("SB")).contains(SB_URL);
	}

	@Test
	void baseUrlFor_isEmpty_forABrandWithNoOverride() {
		AuthBrandHostResolver resolver = resolverWith(Map.of("SB", SB_URL));

		assertThat(resolver.baseUrlFor("LP"))
				.as("empty means 'leave the template alone', so the client's own url stands")
				.isEmpty();
	}

	@Test
	void baseUrlFor_isEmpty_whenNothingIsConfigured() {
		assertThat(resolverWith(Map.of()).baseUrlFor("SB")).isEmpty();
		assertThat(resolverWith(null).baseUrlFor("SB")).isEmpty();
	}

	@Test
	void baseUrlFor_isEmpty_forAMissingOrBlankBrand() {
		AuthBrandHostResolver resolver = resolverWith(Map.of("SB", SB_URL));

		assertThat(resolver.baseUrlFor(null)).isEmpty();
		assertThat(resolver.baseUrlFor("  ")).isEmpty();
	}

	/** Brands reach us straight from an inbound header, whose casing nobody controls. */
	@Test
	void baseUrlFor_matchesTheBrandIgnoringCaseAndSurroundingSpace() {
		AuthBrandHostResolver resolver = resolverWith(Map.of("sb", SB_URL));

		assertThat(resolver.baseUrlFor("SB")).contains(SB_URL);
		assertThat(resolver.baseUrlFor(" sb ")).contains(SB_URL);
	}

	/** Every brand is declared in application.yaml; all but a few resolve to an empty env var. */
	@Test
	void blankEntriesAreDropped_soAnUnsetEnvVarMeansNoOverride() {
		Map<String, String> configured = new HashMap<>();
		configured.put("SB", "   ");
		configured.put("WE", "");
		configured.put("GAP", null);

		AuthBrandHostResolver resolver = resolverWith(configured);

		assertThat(resolver.baseUrlFor("SB")).isEmpty();
		assertThat(resolver.baseUrlFor("WE")).isEmpty();
		assertThat(resolver.baseUrlFor("GAP")).isEmpty();
	}

	/** Re-targeting at the URL Feign already holds would be a pointless second target() call. */
	@Test
	void anEntryEqualToTheBaseUrlIsDropped() {
		AuthBrandHostResolver resolver = resolverWith(Map.of("LP", " " + BASE_URL + " "));

		assertThat(resolver.baseUrlFor("LP")).isEmpty();
	}

	/**
	 * The guard that matters most: Feign's {@code target()} rejects a non-absolute URL with an
	 * {@link IllegalArgumentException}, which is not a {@code FeignException} and would therefore
	 * escape the adapter's fallback and fail every request for the brand.
	 */
	@Test
	void unusableUrlsAreDroppedAtConstruction_withoutThrowing() {
		Map<String, String> configured = new HashMap<>();
		configured.put("SB", "ogcp-cms-site-d.suburbia.com.mx/auth-service"); // no scheme
		configured.put("WE", "/auth-service"); // relative
		configured.put("GAP", "https://not a host/auth-service"); // unparseable

		AuthBrandHostResolver resolver = resolverWith(configured);

		assertThat(resolver.baseUrlFor("SB")).isEmpty();
		assertThat(resolver.baseUrlFor("WE")).isEmpty();
		assertThat(resolver.baseUrlFor("GAP")).isEmpty();
	}

	@Test
	void construction_neverFailsOnBadConfiguration() {
		Map<String, String> configured = new HashMap<>();
		configured.put("SB", "not-a-url");
		configured.put("", SB_URL);

		assertThatCode(() -> resolverWith(configured)).doesNotThrowAnyException();
	}

	private static AuthBrandHostResolver resolverWith(Map<String, String> brandBaseUrls) {
		return new AuthBrandHostResolver(new AuthProperties(
				BASE_URL, brandBaseUrls, "/v{version}/auth/exchange-token", 2, List.of(2, 3), "SessionId",
				Duration.ofSeconds(2), Duration.ofSeconds(4)));
	}
}
