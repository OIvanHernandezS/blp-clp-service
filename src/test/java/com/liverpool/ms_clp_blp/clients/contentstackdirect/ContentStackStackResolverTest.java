package com.liverpool.ms_clp_blp.clients.contentstackdirect;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.liverpool.config.properties.ContentStackConfigBrand;
import com.liverpool.config.properties.ContentStackConfigResolver;
import com.liverpool.config.properties.ContentStackProperties;
import com.liverpool.enums.Sites;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The brand-to-stack mapping, and the four ways a brand can turn out not to be servable.
 *
 * <p>Every "not servable" case has to be an empty {@link java.util.Optional} rather than an exception:
 * they are ordinary outcomes on the failure path, and a throw there would replace the proxy's real
 * error with a less useful one.</p>
 */
class ContentStackStackResolverTest {

    private final ContentStackStackResolver resolver = new ContentStackStackResolver(
            new ContentStackConfigResolver(properties()));

    // ── the mapping ──────────────────────────────────────────────────────────────────────────────

    @Test
    void resolve_liverpoolGetsItsOwnStack() {
        assertThat(resolver.resolve("LP")).hasValueSatisfying(stack -> {
            assertThat(stack.site()).isEqualTo(Sites.LP);
            assertThat(stack.sharedStack()).isFalse();
        });
    }

    /** GAP, BR, TRU and BRU share one stack, so their entries must be told apart by brand. */
    @Test
    void resolve_multiBrandStackIsMarkedShared() {
        assertThat(resolver.resolve("GAP")).hasValueSatisfying(stack -> {
            assertThat(stack.site()).isEqualTo(Sites.MULTI2);
            assertThat(stack.sharedStack()).isTrue();
            assertThat(stack.brandFilter()).isEqualTo("gap");
        });
    }

    @Test
    void resolve_isCaseInsensitive() {
        assertThat(resolver.resolve("gap")).hasValueSatisfying(
                stack -> assertThat(stack.site()).isEqualTo(Sites.MULTI2));
    }

    // ── per-stack overrides ──────────────────────────────────────────────────────────────────────

    /** The stacks do not all run the same branch; a global-only setting cannot express that. */
    @Test
    void resolve_perStackBranchOverridesTheSharedDefault() {
        assertThat(resolver.resolve("GAP")).hasValueSatisfying(
                stack -> assertThat(stack.config().getBranch()).isEqualTo("qa1"));
    }

    @Test
    void resolve_stackWithoutAnOverrideFallsBackToTheSharedBranch() {
        assertThat(resolver.resolve("LP")).hasValueSatisfying(stack -> {
            assertThat(stack.config().getBranch()).isEqualTo("qa");
            assertThat(stack.config().getEnvironment()).isEqualTo("qa");
        });
    }

    // ── the ways to be unservable ────────────────────────────────────────────────────────────────

    /** LVS maps to no Contentstack stack. */
    @Test
    void resolve_brandMappedToNoStackIsNotServable() {
        assertThat(resolver.resolve("LVS")).isEmpty();
        assertThat(resolver.unavailableReason("LVS"))
                .contains(FallbackUnavailableReason.UNMAPPED_BRAND);
    }

    /** The deployed default: a stack whose secrets were never provisioned simply does not fall back. */
    @Test
    void resolve_stackWithBlankCredentialsIsNotServable() {
        assertThat(resolver.resolve("SB")).isEmpty();
        assertThat(resolver.unavailableReason("SB"))
                .contains(FallbackUnavailableReason.MISSING_CREDENTIALS);
    }

    @Test
    void resolve_blankBrandIsNotServable() {
        assertThat(resolver.resolve("")).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
    }

    @Test
    void unavailableReason_isEmptyForAServableBrand() {
        assertThat(resolver.unavailableReason("LP")).isEmpty();
    }

    /**
     * @return properties with LP fully configured, MULTI2 overriding its branch, SB left blank
     */
    private static ContentStackProperties properties() {
        ContentStackProperties properties = new ContentStackProperties();
        properties.setUrl("https://cdn.contentstack.test");
        properties.setBranch("qa");
        properties.setEnvironment("qa");

        Map<Sites, ContentStackConfigBrand> brands = new LinkedHashMap<>();
        brands.put(Sites.LP, brand("api-key-lp", "delivery-token-lp", null, null));
        brands.put(Sites.SB, brand("", "", null, null));
        brands.put(Sites.MULTI2, brand("api-key-multi2", "delivery-token-multi2", "qa1", null));
        properties.setBrands(brands);
        return properties;
    }

    /**
     * @param apiKey      stack api key
     * @param accessToken stack delivery token
     * @param branch      per-stack branch override, or null
     * @param environment per-stack environment override, or null
     * @return the configured stack entry
     */
    private static ContentStackConfigBrand brand(String apiKey, String accessToken,
            String branch, String environment) {
        ContentStackConfigBrand config = new ContentStackConfigBrand();
        config.setApiKey(apiKey);
        config.setAccessToken(accessToken);
        config.setBranch(branch);
        config.setEnvironment(environment);
        return config;
    }
}
