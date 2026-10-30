package com.liverpool.ms_clp_blp.landing.blocks.productslist;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.JewelModelConfig;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.JewelSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.SalesforceSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.SalesforceV2Settings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.ports.ProductsListPort;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.landing.models.DynamicBlockStatus;
import com.liverpool.ms_clp_blp.landing.models.LandingFeatureFlags;
import com.liverpool.ms_clp_blp.landing.models.LandingSettings;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.landing.ports.FeatureFlagPort;
import com.liverpool.ms_clp_blp.landing.ports.RestrictedProductsPort;
import com.liverpool.ms_clp_blp.landing.ports.RuntimeSettingsPort;
import com.liverpool.ms_clp_blp.shared.errors.DynamicBlockServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.errors.ServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.session.CohortMarkers;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductsListUseCasesTest {

    @Mock
    private ProductsListPort salesforcePort;

    @Mock
    private ProductsListPort jewelPort;

    @Mock
    private ProductsListPort salesforceV2Port;

    @Mock
    private FeatureFlagPort featureFlags;

    @Mock
    private RuntimeSettingsPort runtimeSettings;

    /** No image limit, so tests about anything else see the provider's images untouched. */
    /** A blacklist that restricts nothing — every test that is not about the blacklist uses it. */
    private static final RestrictedProductsPort NO_RESTRICTIONS = brand -> Set.of();

    private static final ProductsListProperties PROPERTIES = new ProductsListProperties(300, 0);

    private ProductsListUseCases service;

    private static final SessionContext SESSION = new SessionContext(true, "LP", "WEB", "es-mx");
    private static final ConditionalShowHide NO_RULE = ConditionalShowHide.NONE;
    private static final String HEADER = "x-ab-carousel";
    private static final ConditionalShowHide SHOW_ON_HEADER =
            new ConditionalShowHide(true, "show", HEADER, null);

    private static final ProductsListQuery SALESFORCE_QUERY = new ProductsListQuery(
            "block-sf", SESSION, new SalesforceSettings("user-123", "CMSTePuedeGustar", null, null, null), NO_RULE);
    private static final ProductsListQuery JEWEL_QUERY = new ProductsListQuery(
            "block-jw", SESSION, new JewelSettings("jml-user", "jml-device",
                    new JewelModelConfig("global-tps-sales", false, false, 1, 20)), NO_RULE);
    private static final SalesforceV2Settings SALESFORCE_V2_SETTINGS = new SalesforceV2Settings(
            null, "sf-device", "home-top-sellers", false, true, false, null, null);
    private static final ProductsListQuery SALESFORCE_V2_QUERY = new ProductsListQuery(
            "block-sf2", SESSION, SALESFORCE_V2_SETTINGS, NO_RULE);

    @BeforeEach
    void setUp() {
        when(salesforcePort.source()).thenReturn(SourceOfData.SALESFORCE);
        when(jewelPort.source()).thenReturn(SourceOfData.JEWEL);
        when(salesforceV2Port.source()).thenReturn(SourceOfData.SALESFORCE_V2);
        when(salesforceV2Port.skipReason(any())).thenReturn(null);
        when(salesforcePort.skipReason(any())).thenReturn(null);
        when(jewelPort.skipReason(any())).thenReturn(null);
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.CONDITIONAL_SHOW_HIDE), any())).thenReturn(true);
        // Every provider on unless a test says otherwise. Without these, the mock's false default would
        // make resolve() answer FEATURE_FLAG_OFF for every block in the class.
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.JEWEL), any())).thenReturn(true);
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE), any())).thenReturn(true);
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.VERTEX), any())).thenReturn(true);
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE_V2), any())).thenReturn(true);
        // No brand overrides: every setting resolves to the configured fallback.
        when(runtimeSettings.intValue(any(), any(), anyInt())).thenAnswer(inv -> inv.getArgument(2));
        service = new ProductsListUseCases(List.of(salesforcePort, jewelPort, salesforceV2Port),
                CircuitBreakerRegistry.ofDefaults(), featureFlags, runtimeSettings, NO_RESTRICTIONS, PROPERTIES);
    }

    /** A use case wired to a hand-written flag port, so a test can say which provider is off in one line. */
    private ProductsListUseCases useCasesWith(FeatureFlagPort flags) {
        return new ProductsListUseCases(List.of(salesforcePort, jewelPort, salesforceV2Port),
                CircuitBreakerRegistry.ofDefaults(), flags, runtimeSettings, NO_RESTRICTIONS, PROPERTIES);
    }

    // ── routing ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void resolve_salesforceSource_routesToSalesforcePortOnly() {
        when(salesforcePort.fetch(any())).thenReturn(resolution("block-sf", 1));

        service.resolve(SALESFORCE_QUERY);

        verify(salesforcePort).fetch(any());
        verify(jewelPort, never()).fetch(any());
    }

    @Test
    void resolve_jewelSource_routesToJewelPortOnly() {
        when(jewelPort.fetch(any())).thenReturn(resolution("block-jw", 1));

        service.resolve(JEWEL_QUERY);

        verify(jewelPort).fetch(any());
        verify(salesforcePort, never()).fetch(any());
    }

    @Test
    void construct_twoPortsClaimSameSource_throws() {
        when(jewelPort.source()).thenReturn(SourceOfData.SALESFORCE);

        assertThatThrownBy(() -> new ProductsListUseCases(
                List.of(salesforcePort, jewelPort), CircuitBreakerRegistry.ofDefaults(), featureFlags, runtimeSettings, NO_RESTRICTIONS, PROPERTIES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate ProductsListPort");
    }

    @Test
    void resolve_noAdapterOwnsSource_throwsDynamicBlockException() {
        service = new ProductsListUseCases(
                List.of(salesforcePort), CircuitBreakerRegistry.ofDefaults(), featureFlags, runtimeSettings, NO_RESTRICTIONS, PROPERTIES);

        assertThatThrownBy(() -> service.resolve(JEWEL_QUERY))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class)
                // getMessage() is the consumer-safe text; the technical reason lives in getDetail().
                .extracting(e -> ((DynamicBlockServiceUnavailableException) e).getDetail())
                .asString().contains("source_of_data");
    }

    @Test
    void resolve_errorReportsProductsListContentType_notTheOldFictionalJewelUid() {
        when(jewelPort.fetch(any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> service.resolve(JEWEL_QUERY))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class)
                .extracting(e -> ((DynamicBlockServiceUnavailableException) e).getBlockType())
                .isEqualTo("products_list");
    }

    // ── skipReason short-circuit ─────────────────────────────────────────────────────────────────

    /**
     * The adapter's reason travels through the use case untouched: it is what the endpoint reports as
     * {@code reason}, so the frontend can tell a legitimately empty carousel from a failed one and stop
     * retrying it (ADR-043).
     */
    @Test
    void resolve_portCannotResolve_returnsEmptyCarryingTheAdapterReason() {
        when(salesforcePort.skipReason(any())).thenReturn(BlockDropReason.MISSING_USER_IDENTITY);

        ProductsListResolution result = service.resolve(SALESFORCE_QUERY);

        assertThat(result.products()).isEmpty();
        assertThat(result.status()).isEqualTo(DynamicBlockStatus.EMPTY);
        assertThat(result.reason()).isEqualTo(BlockDropReason.MISSING_USER_IDENTITY);
        verify(salesforcePort, never()).fetch(any());
    }

    @Test
    void resolve_portCannotResolve_doesNotRecordACircuitBreakerCall() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        service = new ProductsListUseCases(List.of(salesforcePort, jewelPort), registry, featureFlags, runtimeSettings, NO_RESTRICTIONS, PROPERTIES);
        when(salesforcePort.skipReason(any())).thenReturn(BlockDropReason.MISSING_USER_IDENTITY);

        service.resolve(SALESFORCE_QUERY);

        // A guest is a business case, not a call: it must not enter the breaker's failure-rate window.
        assertThat(registry.circuitBreaker(ProductsListUseCases.CB_SALESFORCE).getMetrics()
                .getNumberOfSuccessfulCalls()).isZero();
    }

    // ── conditional_show_hide A/B gate ───────────────────────────────────────────────────────────

    @Test
    void resolve_showRuleAndMarkerTrue_resolvesTheCarousel() {
        when(jewelPort.fetch(any())).thenReturn(resolution("block-jw", 3));

        ProductsListResolution result = service.resolve(jewelQuery(SHOW_ON_HEADER, headerMarker(true)));

        assertThat(result.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
        verify(featureFlags).isEnabled(LandingFeatureFlags.CONDITIONAL_SHOW_HIDE, "LP");
    }

    /**
     * An excluded cohort is decided before the provider is consulted at all, so it neither asks the
     * adapter for its preconditions nor spends a breaker permit — the commons strategies evaluate the
     * rule first for the same reason.
     */
    @Test
    void resolve_showRuleAndMarkerFalse_dropsAsExcludedWithoutTouchingTheProvider() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        service = new ProductsListUseCases(List.of(salesforcePort, jewelPort), registry, featureFlags, runtimeSettings, NO_RESTRICTIONS, PROPERTIES);

        ProductsListResolution result = service.resolve(jewelQuery(SHOW_ON_HEADER, headerMarker(false)));

        assertThat(result.status()).isEqualTo(DynamicBlockStatus.EMPTY);
        assertThat(result.reason()).isEqualTo(BlockDropReason.CONDITIONAL_SHOW_HIDE_EXCLUDED);
        verify(jewelPort, never()).skipReason(any());
        verify(jewelPort, never()).fetch(any());
        assertThat(registry.circuitBreaker(ProductsListUseCases.CB_JEWEL).getMetrics()
                .getNumberOfSuccessfulCalls()).isZero();
    }

    @Test
    void resolve_hideRuleAndMarkerTrue_dropsAsExcluded() {
        ConditionalShowHide hide = new ConditionalShowHide(true, "hide", HEADER, null);

        ProductsListResolution result = service.resolve(jewelQuery(hide, headerMarker(true)));

        assertThat(result.reason()).isEqualTo(BlockDropReason.CONDITIONAL_SHOW_HIDE_EXCLUDED);
    }

    @Test
    void resolve_hideRuleAndMarkerFalse_resolvesTheCarousel() {
        ConditionalShowHide hide = new ConditionalShowHide(true, "hide", HEADER, null);
        when(jewelPort.fetch(any())).thenReturn(resolution("block-jw", 3));

        ProductsListResolution result = service.resolve(jewelQuery(hide, headerMarker(false)));

        assertThat(result.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
    }

    @Test
    void resolve_activeRuleAndNoMarker_dropsAsCohortUnknown() {
        ProductsListResolution result = service.resolve(jewelQuery(SHOW_ON_HEADER, CohortMarkers.NONE));

        assertThat(result.status()).isEqualTo(DynamicBlockStatus.EMPTY);
        assertThat(result.reason()).isEqualTo(BlockDropReason.CONDITIONAL_SHOW_HIDE_COHORT_UNKNOWN);
        verify(jewelPort, never()).fetch(any());
    }

    /** A query without a session carries no markers, like a null request in commons. */
    @Test
    void resolve_activeRuleAndNoSession_dropsAsCohortUnknown() {
        ProductsListQuery query =
                new ProductsListQuery("block-jw", null, JEWEL_QUERY.provider(), SHOW_ON_HEADER);

        assertThat(service.resolve(query).reason())
                .isEqualTo(BlockDropReason.CONDITIONAL_SHOW_HIDE_COHORT_UNKNOWN);
    }

    @Test
    void resolve_ruleOnSalesforceBlock_isHonouredLikeAnyOtherProvider() {
        ProductsListQuery query = new ProductsListQuery("block-sf", sessionWith(headerMarker(false)),
                SALESFORCE_QUERY.provider(), SHOW_ON_HEADER);

        assertThat(service.resolve(query).reason()).isEqualTo(BlockDropReason.CONDITIONAL_SHOW_HIDE_EXCLUDED);
        verify(salesforcePort, never()).fetch(any());
    }

    // ── conditional_show_hide on Salesforce V2: same evaluator as every provider (commons parity) ──

    @Test
    void resolve_v2ShowRuleAndMarkerTrue_callsSalesforceV2() {
        when(salesforceV2Port.fetch(any())).thenReturn(resolution("block-sf2", 3));

        ProductsListResolution result = service.resolve(v2Query(SHOW_ON_HEADER, headerMarker(true)));

        assertThat(result.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
        verify(salesforceV2Port).fetch(any());
    }

    @Test
    void resolve_v2ShowRuleAndMarkerFalse_dropsAsExcludedWithoutCallingIt() {
        ProductsListResolution result = service.resolve(v2Query(SHOW_ON_HEADER, headerMarker(false)));

        assertThat(result.reason()).isEqualTo(BlockDropReason.CONDITIONAL_SHOW_HIDE_EXCLUDED);
        verify(salesforceV2Port, never()).skipReason(any());
        verify(salesforceV2Port, never()).fetch(any());
    }

    /**
     * Only {@code true}/{@code false} are markers: a {@code "1"} never reaches the snapshot, so it reads
     * exactly like a marker that was not sent — cohort unknown, block dropped, no call.
     */
    @Test
    void resolve_v2ActiveRuleAndUnreadableOrMissingMarker_dropsAsCohortUnknownWithoutCallingIt() {
        assertThat(CohortMarkers.parse("1")).isNull();

        ProductsListResolution result = service.resolve(v2Query(SHOW_ON_HEADER, CohortMarkers.NONE));

        assertThat(result.reason()).isEqualTo(BlockDropReason.CONDITIONAL_SHOW_HIDE_COHORT_UNKNOWN);
        verify(salesforceV2Port, never()).fetch(any());
    }

    @Test
    void resolve_v2RuleWithCohortFlagOff_ignoresTheRule() {
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.CONDITIONAL_SHOW_HIDE), any())).thenReturn(false);
        when(salesforceV2Port.fetch(any())).thenReturn(resolution("block-sf2", 3));

        ProductsListResolution result = service.resolve(v2Query(SHOW_ON_HEADER, CohortMarkers.NONE));

        assertThat(result.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
    }

    /**
     * The experiment the rule exists for: one marker swaps Salesforce V1's carousel for V2's. V1 is
     * authored {@code show} and V2 {@code hide} on the same header, so each cohort sees exactly one.
     */
    @Test
    void resolve_v1ShowAndV2HideOnOneMarker_swapTheCarouselPerCohort() {
        ConditionalShowHide hide = new ConditionalShowHide(true, "hide", HEADER, null);
        when(salesforcePort.fetch(any())).thenReturn(resolution("block-sf", 3));
        when(salesforceV2Port.fetch(any())).thenReturn(resolution("block-sf2", 3));

        for (boolean inCohort : new boolean[] {true, false}) {
            SessionContext session = sessionWith(headerMarker(inCohort));
            ProductsListResolution v1 = service.resolve(
                    new ProductsListQuery("block-sf", session, SALESFORCE_QUERY.provider(), SHOW_ON_HEADER));
            ProductsListResolution v2 = service.resolve(
                    new ProductsListQuery("block-sf2", session, SALESFORCE_V2_SETTINGS, hide));

            assertThat(v1.status()).isEqualTo(inCohort ? DynamicBlockStatus.AVAILABLE : DynamicBlockStatus.EMPTY);
            assertThat(v2.status()).isEqualTo(inCohort ? DynamicBlockStatus.EMPTY : DynamicBlockStatus.AVAILABLE);
        }
    }

    @Test
    void resolve_cohortFlagOff_ignoresTheRule() {
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.CONDITIONAL_SHOW_HIDE), any())).thenReturn(false);
        when(jewelPort.fetch(any())).thenReturn(resolution("block-jw", 3));

        ProductsListResolution result = service.resolve(jewelQuery(SHOW_ON_HEADER, CohortMarkers.NONE));

        assertThat(result.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
    }

    /**
     * A block without an applicable rule is the common case, and it must cost no cohort-flag lookup.
     * The provider's own flag is read on every block by design, so the assertion names the one flag
     * this test is about rather than forbidding flag lookups outright.
     */
    @Test
    void resolve_inactiveRule_resolvesWithoutReadingTheCohortFlag() {
        ConditionalShowHide inactive = new ConditionalShowHide(false, "show", HEADER, null);
        when(jewelPort.fetch(any())).thenReturn(resolution("block-jw", 3));

        ProductsListResolution result = service.resolve(jewelQuery(inactive, CohortMarkers.NONE));

        assertThat(result.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
        verify(featureFlags, never()).isEnabled(eq(LandingFeatureFlags.CONDITIONAL_SHOW_HIDE), any());
    }

    // ── thresholds, applied centrally for both providers ─────────────────────────────────────────

    @Test
    void resolve_salesforceBelowMinOfProducts_dropsTheCarousel() {
        ProductsListQuery query = new ProductsListQuery(
                "block-sf", SESSION, new SalesforceSettings("user-123", "action", null, 5, null), NO_RULE);
        when(salesforcePort.fetch(any())).thenReturn(resolution("block-sf", 3));

        ProductsListResolution result = service.resolve(query);

        assertThat(result.products()).isEmpty();
        assertThat(result.status()).isEqualTo(DynamicBlockStatus.EMPTY);
        assertThat(result.reason()).isEqualTo(BlockDropReason.BELOW_MIN_PRODUCTS);
    }

    @Test
    void resolve_salesforceAboveMaxOfProducts_truncates() {
        ProductsListQuery query = new ProductsListQuery(
                "block-sf", SESSION, new SalesforceSettings("user-123", "action", null, null, 2), NO_RULE);
        when(salesforcePort.fetch(any())).thenReturn(resolution("block-sf", 5));

        ProductsListResolution result = service.resolve(query);

        assertThat(result.products()).hasSize(2);
        assertThat(result.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
        assertThat(result.reason()).isNull();
    }

    @Test
    void resolve_jewelBelowMinItems_dropsTheCarousel() {
        // Jewel forwards min/max to its API but the API need not honour them; enforcing here is what
        // makes the two providers behave alike (ADR-029) and diverges from digital_bff on purpose.
        ProductsListQuery query = new ProductsListQuery("block-jw", SESSION,
                new JewelSettings("u", "d", new JewelModelConfig("m", false, false, 8, 20)), NO_RULE);
        when(jewelPort.fetch(any())).thenReturn(resolution("block-jw", 3));

        ProductsListResolution result = service.resolve(query);

        assertThat(result.products()).isEmpty();
        assertThat(result.status()).isEqualTo(DynamicBlockStatus.EMPTY);
        assertThat(result.reason()).isEqualTo(BlockDropReason.BELOW_MIN_PRODUCTS);
    }

    @Test
    void resolve_jewelAboveMaxItems_truncates() {
        ProductsListQuery query = new ProductsListQuery("block-jw", SESSION,
                new JewelSettings("u", "d", new JewelModelConfig("m", false, false, 1, 2)), NO_RULE);
        when(jewelPort.fetch(any())).thenReturn(resolution("block-jw", 6));

        assertThat(service.resolve(query).products()).hasSize(2);
    }

    // ── exception passthrough ────────────────────────────────────────────────────────────────────

    @Test
    void resolve_portThrowsDomainException_rethrownUnchanged() {
        DynamicBlockServiceUnavailableException ex = new DynamicBlockServiceUnavailableException(
                "block-sf", "products_list", "Salesforce down", null);
        when(salesforcePort.fetch(any())).thenThrow(ex);

        assertThatThrownBy(() -> service.resolve(SALESFORCE_QUERY)).isSameAs(ex);
    }

    @Test
    void resolve_portThrowsUnexpectedRuntime_wrappedInDynamicBlockException() {
        when(salesforcePort.fetch(any())).thenThrow(new RuntimeException("unexpected"));

        assertThatThrownBy(() -> service.resolve(SALESFORCE_QUERY))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    // ── circuit breaker, one per provider ────────────────────────────────────────────────────────

    @Test
    void resolve_circuitBreakerOpensAfterThresholdFailures_throwsServiceUnavailableException() {
        service = new ProductsListUseCases(List.of(salesforcePort, jewelPort), tightRegistry(), featureFlags, runtimeSettings, NO_RESTRICTIONS, PROPERTIES);
        when(salesforcePort.fetch(any())).thenThrow(
                new DynamicBlockServiceUnavailableException("block-sf", "products_list", "down", null));

        // Two failures record a 100% failure rate against the 50% threshold; breaker opens.
        assertThatThrownBy(() -> service.resolve(SALESFORCE_QUERY))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class);
        assertThatThrownBy(() -> service.resolve(SALESFORCE_QUERY))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class);

        assertThatThrownBy(() -> service.resolve(SALESFORCE_QUERY))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasCauseInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);
    }

    @Test
    void resolve_circuitBreakerOpen_doesNotCallPort() {
        service = new ProductsListUseCases(List.of(salesforcePort, jewelPort), tightRegistry(), featureFlags, runtimeSettings, NO_RESTRICTIONS, PROPERTIES);
        when(salesforcePort.fetch(any())).thenThrow(
                new DynamicBlockServiceUnavailableException("b", "t", "d", null));

        try { service.resolve(SALESFORCE_QUERY); } catch (Exception ignored) { /* trip */ }
        try { service.resolve(SALESFORCE_QUERY); } catch (Exception ignored) { /* trip */ }

        assertThatThrownBy(() -> service.resolve(SALESFORCE_QUERY))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(salesforcePort, times(2)).fetch(any());
    }

    @Test
    void resolve_salesforceBreakerOpen_jewelStillResolves() {
        service = new ProductsListUseCases(List.of(salesforcePort, jewelPort), tightRegistry(), featureFlags, runtimeSettings, NO_RESTRICTIONS, PROPERTIES);
        when(salesforcePort.fetch(any())).thenThrow(
                new DynamicBlockServiceUnavailableException("b", "t", "d", null));
        when(jewelPort.fetch(any())).thenReturn(resolution("block-jw", 4));

        try { service.resolve(SALESFORCE_QUERY); } catch (Exception ignored) { /* trip */ }
        try { service.resolve(SALESFORCE_QUERY); } catch (Exception ignored) { /* trip */ }

        assertThat(service.resolve(JEWEL_QUERY).products()).hasSize(4);
    }

    // ── batch ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void resolveAll_mixedOutcomes_preservesOrderAndDegradesPerBlock() {
        when(salesforcePort.fetch(any())).thenThrow(
                new DynamicBlockServiceUnavailableException("block-sf", "products_list", "down", null));
        when(jewelPort.fetch(any())).thenReturn(resolution("block-jw", 2));

        List<ProductsListResolution> results =
                service.resolveAll(List.of(SALESFORCE_QUERY, JEWEL_QUERY));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).blockId()).isEqualTo("block-sf");
        assertThat(results.get(0).status()).isEqualTo(DynamicBlockStatus.UNAVAILABLE);
        assertThat(results.get(1).blockId()).isEqualTo("block-jw");
        assertThat(results.get(1).status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
        assertThat(results.get(1).products()).hasSize(2);
    }

    @Test
    void resolveAll_emptyOrNull_returnsEmpty() {
        assertThat(service.resolveAll(List.of())).isEmpty();
        assertThat(service.resolveAll(null)).isEmpty();
    }

    // ── provider kill switch ─────────────────────────────────────────────────────────────────────

    @Test
    void resolve_jewelFlagOff_resolvesEmptyWithoutCallingTheProvider() {
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.JEWEL), any())).thenReturn(false);

        ProductsListResolution result = service.resolve(JEWEL_QUERY);

        assertThat(result.blockId()).isEqualTo("block-jw");
        assertThat(result.status()).isEqualTo(DynamicBlockStatus.EMPTY);
        assertThat(result.reason()).isEqualTo(BlockDropReason.FEATURE_FLAG_OFF);
        assertThat(result.products()).isEmpty();
        verify(jewelPort, never()).fetch(any());
        // Ahead of the adapter's own preconditions, so the wire carries the real reason rather than
        // whichever precondition the adapter happened to miss.
        verify(jewelPort, never()).skipReason(any());
    }

    /** Covers Salesforce and, with it, the legacy entries whose absent source_of_data collapses onto it. */
    @Test
    void resolve_salesforceFlagOff_resolvesEmptyWithoutCallingTheProvider() {
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE), any())).thenReturn(false);

        ProductsListResolution result = service.resolve(SALESFORCE_QUERY);

        assertThat(result.status()).isEqualTo(DynamicBlockStatus.EMPTY);
        assertThat(result.reason()).isEqualTo(BlockDropReason.FEATURE_FLAG_OFF);
        verify(salesforcePort, never()).fetch(any());
    }

    @Test
    void resolve_salesforceV2Source_routesToSalesforceV2PortOnly() {
        when(salesforceV2Port.fetch(any())).thenReturn(resolution("block-sf2", 1));

        service.resolve(SALESFORCE_V2_QUERY);

        verify(salesforceV2Port).fetch(any());
        verify(salesforcePort, never()).fetch(any());
    }

    @Test
    void resolve_salesforceV2FlagOff_resolvesEmptyWithoutCallingTheProvider() {
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE_V2), any())).thenReturn(false);

        ProductsListResolution result = service.resolve(SALESFORCE_V2_QUERY);

        assertThat(result.status()).isEqualTo(DynamicBlockStatus.EMPTY);
        assertThat(result.reason()).isEqualTo(BlockDropReason.FEATURE_FLAG_OFF);
        verify(salesforceV2Port, never()).skipReason(any());
        verify(salesforceV2Port, never()).fetch(any());
    }

    /** V1 and V2 are separate kill switches: switching V2 off must leave V1 carousels resolving. */
    @Test
    void resolve_salesforceV2FlagOff_leavesSalesforceV1Untouched() {
        ProductsListUseCases onlyV2Off =
                useCasesWith((flag, brand) -> !LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE_V2.equals(flag));
        when(salesforcePort.fetch(any())).thenReturn(resolution("block-sf", 3));

        assertThat(onlyV2Off.resolve(SALESFORCE_V2_QUERY).reason())
                .isEqualTo(BlockDropReason.FEATURE_FLAG_OFF);
        assertThat(onlyV2Off.resolve(SALESFORCE_QUERY).status())
                .isEqualTo(DynamicBlockStatus.AVAILABLE);
    }

    @Test
    void resolve_salesforceV2UsesItsOwnBreaker() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        service = new ProductsListUseCases(
                List.of(salesforcePort, jewelPort, salesforceV2Port), registry, featureFlags, runtimeSettings, NO_RESTRICTIONS, PROPERTIES);
        when(salesforceV2Port.fetch(any())).thenReturn(resolution("block-sf2", 1));

        service.resolve(SALESFORCE_V2_QUERY);

        assertThat(registry.circuitBreaker(ProductsListUseCases.CB_SALESFORCE_V2).getMetrics()
                .getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(registry.circuitBreaker(ProductsListUseCases.CB_SALESFORCE).getMetrics()
                .getNumberOfBufferedCalls()).isZero();
    }

    /**
     * The kill switch must sit outside the breaker. Inside it, a provider switched off on purpose would
     * move the failure-rate window of a breaker shared by every carousel of that provider — and an
     * already-open breaker would answer UNAVAILABLE/CIRCUIT_BREAKER_OPEN, inviting the frontend to
     * retry a block nobody wants resolved.
     */
    @Test
    void resolve_flagOff_doesNotConsumeACircuitBreakerPermit() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        service = new ProductsListUseCases(List.of(salesforcePort, jewelPort), registry, featureFlags, runtimeSettings, NO_RESTRICTIONS, PROPERTIES);
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.JEWEL), any())).thenReturn(false);

        service.resolve(JEWEL_QUERY);

        assertThat(registry.circuitBreaker(ProductsListUseCases.CB_JEWEL).getMetrics()
                .getNumberOfBufferedCalls()).isZero();
    }

    /** One provider off must not disturb the others — that is the whole point of separate flags (ADR-029). */
    @Test
    void resolve_oneProviderFlagOff_leavesTheOtherProviderUntouched() {
        ProductsListUseCases onlyJewelOff =
                useCasesWith((flag, brand) -> !LandingFeatureFlags.JEWEL.equals(flag));
        when(salesforcePort.fetch(any())).thenReturn(resolution("block-sf", 3));

        assertThat(onlyJewelOff.resolve(JEWEL_QUERY).reason())
                .isEqualTo(BlockDropReason.FEATURE_FLAG_OFF);
        assertThat(onlyJewelOff.resolve(SALESFORCE_QUERY).status())
                .isEqualTo(DynamicBlockStatus.AVAILABLE);
    }

    /**
     * A kill switch is "off for everyone" and a cohort rule is "off for this shopper", so when both
     * apply the operative answer is the kill switch — and the cohort flag is never even read.
     */
    @Test
    void resolve_flagOffAndCohortRuleActive_reportsFeatureFlagOff() {
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.JEWEL), any())).thenReturn(false);

        ProductsListResolution result = service.resolve(jewelQuery(SHOW_ON_HEADER, headerMarker(false)));

        assertThat(result.reason()).isEqualTo(BlockDropReason.FEATURE_FLAG_OFF);
        verify(featureFlags, never()).isEnabled(eq(LandingFeatureFlags.CONDITIONAL_SHOW_HIDE), any());
    }

    @Test
    void resolve_flagOn_readsTheFlagForTheSessionBrand() {
        when(jewelPort.fetch(any())).thenReturn(resolution("block-jw", 3));

        service.resolve(JEWEL_QUERY);

        verify(featureFlags).isEnabled(LandingFeatureFlags.JEWEL, "LP");
    }

    /** No session means no brand, which resolves the flag to its service-wide value rather than failing. */
    @Test
    void resolve_noSession_evaluatesTheFlagWithANullBrand() {
        ProductsListQuery noSession = new ProductsListQuery(
                "block-jw", null, JEWEL_QUERY.provider(), ConditionalShowHide.NONE);
        when(jewelPort.fetch(any())).thenReturn(resolution("block-jw", 3));

        service.resolve(noSession);

        verify(featureFlags).isEnabled(LandingFeatureFlags.JEWEL, null);
    }

    @Test
    void resolveAll_mixedFlags_emptiesTheOffBlockAndResolvesTheRest() {
        ProductsListUseCases onlyJewelOff =
                useCasesWith((flag, brand) -> !LandingFeatureFlags.JEWEL.equals(flag));
        when(salesforcePort.fetch(any())).thenReturn(resolution("block-sf", 3));

        List<ProductsListResolution> results =
                onlyJewelOff.resolveAll(List.of(JEWEL_QUERY, SALESFORCE_QUERY));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).reason()).isEqualTo(BlockDropReason.FEATURE_FLAG_OFF);
        assertThat(results.get(1).status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
    }

    // ── cacheability ─────────────────────────────────────────────────────────────────────────────

    @Test
    void allSessionIndependent_everyBlockSessionIndependent_isTrue() {
        when(jewelPort.sessionIndependent(any())).thenReturn(true);

        assertThat(service.allSessionIndependent(List.of(JEWEL_QUERY, JEWEL_QUERY))).isTrue();
    }

    @Test
    void allSessionIndependent_onePersonalisedBlock_isFalse() {
        when(jewelPort.sessionIndependent(any())).thenReturn(true);
        when(salesforcePort.sessionIndependent(any())).thenReturn(false);

        assertThat(service.allSessionIndependent(List.of(JEWEL_QUERY, SALESFORCE_QUERY))).isFalse();
    }

    /** Each cohort gets a different answer, so a response carrying such a block can never be shared. */
    @Test
    void allSessionIndependent_blockSplitByCohort_isFalse() {
        when(jewelPort.sessionIndependent(any())).thenReturn(true);

        assertThat(service.allSessionIndependent(
                List.of(JEWEL_QUERY, jewelQuery(SHOW_ON_HEADER, headerMarker(true))))).isFalse();
    }

    @Test
    void allSessionIndependent_ruleWithFlagOff_leavesCacheabilityToTheProvider() {
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.CONDITIONAL_SHOW_HIDE), any())).thenReturn(false);
        when(jewelPort.sessionIndependent(any())).thenReturn(true);

        assertThat(service.allSessionIndependent(
                List.of(jewelQuery(SHOW_ON_HEADER, headerMarker(true))))).isTrue();
    }

    /**
     * The provider's kill switch is deliberately not part of this answer: a switched-off block resolves
     * the same way for every caller, which is the only question this method asks. Freshness is not —
     * and Akamai does not cache {@code /home/blocks/**} anyway (see {@code runtime/edge-cache.md}).
     */
    @Test
    void allSessionIndependent_providerFlagOff_stillAnswersFromTheProvider() {
        when(jewelPort.sessionIndependent(any())).thenReturn(true);
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.JEWEL), any())).thenReturn(false);

        assertThat(service.allSessionIndependent(List.of(JEWEL_QUERY))).isTrue();
    }

    @Test
    void allSessionIndependent_noQueries_isFalse() {
        assertThat(service.allSessionIndependent(List.of())).isFalse();
    }

    // ── image limit, applied centrally for every provider ────────────────────────────────────────

    @Test
    void resolve_configuredLimit_trimsEveryProductsImages() {
        service = withImageLimit(1);
        when(salesforcePort.fetch(any())).thenReturn(resolutionWithImages("block-sf", 2, 3));

        ProductsListResolution result = service.resolve(SALESFORCE_QUERY);

        assertThat(result.products()).allSatisfy(p -> assertThat(p.getImages())
                .extracting(ProductRecommendation.ProductImage::getUrl)
                .containsExactly("img-0"));
    }

    @Test
    void resolve_brandOverride_winsOverConfiguredLimit() {
        service = withImageLimit(1);
        when(runtimeSettings.intValue(eq(LandingSettings.PRODUCTS_LIST_MAX_IMAGES), eq("LP"), eq(1))).thenReturn(2);
        when(jewelPort.fetch(any())).thenReturn(resolutionWithImages("block-jw", 1, 3));

        ProductsListResolution result = service.resolve(JEWEL_QUERY);

        assertThat(result.products().get(0).getImages())
                .extracting(ProductRecommendation.ProductImage::getUrl)
                .containsExactly("img-0", "img-1");
    }

    @Test
    void resolve_zeroLimit_keepsAllImages() {
        service = withImageLimit(0);
        when(salesforcePort.fetch(any())).thenReturn(resolutionWithImages("block-sf", 1, 3));

        ProductsListResolution result = service.resolve(SALESFORCE_QUERY);

        assertThat(result.products().get(0).getImages()).hasSize(3);
    }

    @Test
    void resolve_fewerImagesThanLimitOrNone_leavesProductUntouched() {
        service = withImageLimit(3);
        ProductsListResolution fetched = resolutionWithImages("block-sf", 2, 2);
        fetched.products().get(1).setImages(null);
        when(salesforcePort.fetch(any())).thenReturn(fetched);

        ProductsListResolution result = service.resolve(SALESFORCE_QUERY);

        assertThat(result.products().get(0).getImages()).hasSize(2);
        assertThat(result.products().get(1).getImages()).isNull();
    }

    @Test
    void resolve_emptyResolution_neverEvaluatesTheSetting() {
        service = withImageLimit(1);
        when(salesforcePort.fetch(any())).thenReturn(resolution("block-sf", 0));

        service.resolve(SALESFORCE_QUERY);

        verify(runtimeSettings, never()).intValue(any(), any(), anyInt());
    }

    @Test
    void resolve_severalProducts_evaluatesTheSettingOncePerBlock() {
        service = withImageLimit(1);
        when(salesforcePort.fetch(any())).thenReturn(resolutionWithImages("block-sf", 5, 2));

        service.resolve(SALESFORCE_QUERY);

        verify(runtimeSettings, times(1)).intValue(eq(LandingSettings.PRODUCTS_LIST_MAX_IMAGES), eq("LP"), eq(1));
    }

    // ── product blacklist ─────────────────────────────────────────────────────────────────────────

    @Test
    void resolve_blacklistOn_removesRestrictedProducts_keepingOrder() {
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.CAROUSEL_RESTRICTED_PRODUCTS), any())).thenReturn(true);
        when(salesforcePort.fetch(any())).thenReturn(resolution("block-sf", 4));
        service = withBlacklist(brand -> Set.of("sku-001", "sku-003"));

        ProductsListResolution result = service.resolve(SALESFORCE_QUERY);

        assertThat(result.products()).extracting(ProductRecommendation::getProductId)
                .containsExactly("sku-000", "sku-002");
    }

    @Test
    void resolve_blacklistFlagOff_servesTheCarouselUnfilteredWithoutReadingTheList() {
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.CAROUSEL_RESTRICTED_PRODUCTS), any())).thenReturn(false);
        when(salesforcePort.fetch(any())).thenReturn(resolution("block-sf", 3));
        RestrictedProductsPort blacklist = org.mockito.Mockito.mock(RestrictedProductsPort.class);
        service = withBlacklist(blacklist);

        assertThat(service.resolve(SALESFORCE_QUERY).products()).hasSize(3);
        verify(blacklist, never()).restrictedProductIds(any());
    }

    /** digital_bff did not re-check min_of_products after filtering; neither does this. */
    @Test
    void resolve_blacklistThinsBelowTheMinimum_carouselStillRenders() {
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.CAROUSEL_RESTRICTED_PRODUCTS), any())).thenReturn(true);
        when(jewelPort.fetch(any())).thenReturn(resolution("block-jw", 2));
        service = withBlacklist(brand -> Set.of("sku-000"));

        ProductsListResolution result = service.resolve(JEWEL_QUERY);

        assertThat(result.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
        assertThat(result.products()).extracting(ProductRecommendation::getProductId).containsExactly("sku-001");
    }

    @Test
    void resolve_blacklistEmpty_returnsTheSameResolution() {
        when(featureFlags.isEnabled(eq(LandingFeatureFlags.CAROUSEL_RESTRICTED_PRODUCTS), any())).thenReturn(true);
        when(salesforcePort.fetch(any())).thenReturn(resolution("block-sf", 2));
        service = withBlacklist(brand -> Set.of("not-in-carousel"));

        assertThat(service.resolve(SALESFORCE_QUERY).products()).hasSize(2);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private ProductsListUseCases withBlacklist(RestrictedProductsPort blacklist) {
        return new ProductsListUseCases(List.of(salesforcePort, jewelPort, salesforceV2Port),
                CircuitBreakerRegistry.ofDefaults(), featureFlags, runtimeSettings, blacklist, PROPERTIES);
    }

    private ProductsListUseCases withImageLimit(int maxImages) {
        return new ProductsListUseCases(List.of(salesforcePort, jewelPort, salesforceV2Port),
                CircuitBreakerRegistry.ofDefaults(), featureFlags, runtimeSettings, NO_RESTRICTIONS,
                new ProductsListProperties(300, maxImages));
    }

    private static ProductsListResolution resolutionWithImages(String blockId, int productCount, int imageCount) {
        ProductsListResolution resolution = resolution(blockId, productCount);
        for (ProductRecommendation product : resolution.products()) {
            List<ProductRecommendation.ProductImage> images = new java.util.ArrayList<>(imageCount);
            for (int i = 0; i < imageCount; i++) {
                ProductRecommendation.ProductImage image = new ProductRecommendation.ProductImage();
                image.setType("thumbnailImage");
                image.setUrl("img-" + i);
                images.add(image);
            }
            product.setImages(images);
        }
        return resolution;
    }

    private static ProductsListQuery jewelQuery(ConditionalShowHide rule, CohortMarkers markers) {
        return new ProductsListQuery("block-jw", sessionWith(markers), JEWEL_QUERY.provider(), rule);
    }

    private static ProductsListQuery v2Query(ConditionalShowHide rule, CohortMarkers markers) {
        return new ProductsListQuery("block-sf2", sessionWith(markers), SALESFORCE_V2_SETTINGS, rule);
    }

    private static SessionContext sessionWith(CohortMarkers markers) {
        return new SessionContext(true, "LP", "WEB", "es-mx", Map.of(), null, markers);
    }

    private static CohortMarkers headerMarker(boolean inCohort) {
        return new CohortMarkers(Map.of(HEADER, inCohort), Map.of());
    }

    private static CircuitBreakerRegistry tightRegistry() {
        // Tight CB: 2-call window, ≥50% failure rate opens the breaker, minimum 2 calls.
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .build());
    }

    private static ProductsListResolution resolution(String blockId, int productCount) {
        List<ProductRecommendation> products = new java.util.ArrayList<>(productCount);
        for (int i = 0; i < productCount; i++) {
            products.add(product("sku-%03d".formatted(i), "Product " + i));
        }
        return ProductsListResolution.available(blockId, "Best Sellers", "campaign", products);
    }

    /** Minimal product carrying the published contract; only asserted fields are set. */
    private static ProductRecommendation product(String productId, String name) {
        ProductRecommendation p = new ProductRecommendation();
        p.setProductId(productId);
        p.setName(name);
        return p;
    }
}
