package com.liverpool.ms_clp_blp.shared.cachepurge;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeAction;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeNetwork;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for how configuration becomes purge targets.
 *
 * <p>Built by direct construction rather than by binding, like {@code LandingPropertiesTest}: what is
 * under test is the normalisation, not Spring's binder.</p>
 *
 * <p>The trailing-slash case is the one that matters operationally. A stray
 * {@code CACHE_PURGE_SITE_BASE_URL=https://ms-crp-home-service.liverpool.com.mx/} would otherwise
 * produce {@code …liverpool.com.mx//home} — a URL Akamai accepts happily and that matches nothing, so
 * the purge succeeds and the edge keeps serving stale content.</p>
 */
class CachePurgePropertiesTest {

    private static final String SITE = "https://ms-crp-home-service.liverpool.com.mx";
    private static final String SITE_HOST = "ms-crp-home-service.liverpool.com.mx";
    private static final List<String> PATHS = List.of("/home", "/global-data", "/loyalty");

    @Test
    void pathUrls_joinsEachPathOntoTheOrigin() {
        assertThat(properties(SITE, PATHS).pathUrls()).containsExactly(
                SITE + "/home", SITE + "/global-data", SITE + "/loyalty");
    }

    @Test
    void pathUrls_trailingSlashOnTheOrigin_doesNotProduceADoubleSlash() {
        assertThat(properties(SITE + "/", PATHS).pathUrls())
                .allSatisfy(url -> assertThat(url).doesNotContain("mx//"))
                .containsExactly(SITE + "/home", SITE + "/global-data", SITE + "/loyalty");
    }

    @Test
    void pathUrls_pathWithoutALeadingSlash_stillProducesAValidUrl() {
        assertThat(properties(SITE, List.of("home")).pathUrls()).containsExactly(SITE + "/home");
    }

    @Test
    void pathUrls_blankAndNullEntries_areDropped() {
        assertThat(properties(SITE, Arrays.asList("/home", "  ", null)).pathUrls())
                .containsExactly(SITE + "/home");
    }

    @Test
    void pathUrls_noOriginConfigured_isEmptyRatherThanRelativeUrls() {
        assertThat(properties("", PATHS).pathUrls()).isEmpty();
        assertThat(properties(null, PATHS).pathUrls()).isEmpty();
    }

    @Test
    void pathUrls_noPathsConfigured_isEmpty() {
        assertThat(properties(SITE, null).pathUrls()).isEmpty();
        assertThat(properties(SITE, List.of()).pathUrls()).isEmpty();
    }

    @Test
    void absoluteUrl_joinsOneEntryTheSameWayAsThePaths() {
        assertThat(properties(SITE + "/", PATHS).absoluteUrl(" global-data ")).contains(SITE + "/global-data");
        assertThat(properties("", PATHS).absoluteUrl("/home")).isEmpty();
    }

    @Test
    void cacheTags_areStrippedAndBlanksDropped() {
        CachePurgeProperties properties = new CachePurgeProperties(SITE, PATHS,
                Arrays.asList(" ms-clp-blp ", "", null), List.of(), PurgeAction.INVALIDATE,
                PurgeNetwork.PRODUCTION, true);

        assertThat(properties.cacheTags()).containsExactly("ms-clp-blp");
    }

    @Test
    void cacheTags_noneConfigured_isEmpty() {
        assertThat(new CachePurgeProperties(SITE, PATHS, null, List.of(), PurgeAction.INVALIDATE,
                PurgeNetwork.PRODUCTION, true).cacheTags()).isEmpty();
    }

    /** Never "any host": the credential can purge other properties of the account. */
    @Test
    void purgeableHosts_noAllowListConfigured_fallsBackToTheSitesOwnHost() {
        CachePurgeProperties properties = properties(SITE, PATHS);

        assertThat(properties.purgeableHosts()).containsExactly(SITE_HOST);
        assertThat(properties.allowsHost("MS-CRP-HOME-SERVICE.liverpool.com.mx")).isTrue();
        assertThat(properties.allowsHost("www.liverpool.com.mx")).isFalse();
        assertThat(properties.allowsHost(null)).isFalse();
    }

    @Test
    void purgeableHosts_configuredAllowList_replacesTheSiteHost() {
        CachePurgeProperties properties = new CachePurgeProperties(SITE, PATHS, List.of("ms-clp-blp"),
                Arrays.asList(" www.liverpool.com.mx ", "", null), PurgeAction.INVALIDATE,
                PurgeNetwork.PRODUCTION, true);

        assertThat(properties.purgeableHosts()).containsExactly("www.liverpool.com.mx");
        assertThat(properties.allowsHost(SITE_HOST)).isFalse();
    }

    @Test
    void purgeableHosts_neitherConfigured_allowsNothing() {
        assertThat(properties("", PATHS).purgeableHosts()).isEmpty();
        assertThat(properties("not a url", PATHS).purgeableHosts()).isEmpty();
    }

    /** A missing default must never escalate to an eviction. */
    @Test
    void defaultAction_unset_fallsBackToInvalidateRatherThanDelete() {
        assertThat(new CachePurgeProperties(SITE, PATHS, List.of(), List.of(), null, null, true)
                .defaultAction()).isEqualTo(PurgeAction.INVALIDATE);
    }

    @Test
    void defaultNetwork_unset_fallsBackToProduction() {
        assertThat(new CachePurgeProperties(SITE, PATHS, List.of(), List.of(), null, null, true)
                .defaultNetwork()).isEqualTo(PurgeNetwork.PRODUCTION);
    }

    @Test
    void configuredDefaults_areHonouredWhenPresent() {
        CachePurgeProperties properties = new CachePurgeProperties(SITE, PATHS, List.of(), List.of(),
                PurgeAction.DELETE, PurgeNetwork.STAGING, true);

        assertThat(properties.defaultAction()).isEqualTo(PurgeAction.DELETE);
        assertThat(properties.defaultNetwork()).isEqualTo(PurgeNetwork.STAGING);
    }

    private static CachePurgeProperties properties(String siteBaseUrl, List<String> paths) {
        return new CachePurgeProperties(siteBaseUrl, paths, List.of("ms-clp-blp"), List.of(),
                PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION, true);
    }
}
