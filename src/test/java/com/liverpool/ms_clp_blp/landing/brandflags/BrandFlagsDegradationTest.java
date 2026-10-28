package com.liverpool.ms_clp_blp.landing.brandflags;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;

import com.liverpool.ms_clp_blp.landing.models.LandingFeatureFlags;
import com.liverpool.ms_clp_blp.landing.ports.FeatureFlagPort;
import com.liverpool.ms_clp_blp.shared.web.TestServiceCredential;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract this whole feature rests on, exercised against a MongoDB that does not exist.
 *
 * <p>Everything else about per-brand flags is an optimisation; this is the part that must never
 * break. A flag store that is unreachable has to look exactly like a brand with no overrides — the
 * service keeps composing pages from {@code landing.feature-flags}, and Cloud Run keeps the instance in
 * rotation. The first version of this feature failed both: a blank {@code MONGO_URI} stopped the
 * application from starting at all.</p>
 *
 * <p>The port here points at a reserved TEST-NET-1 address (RFC 5737), so nothing can answer and the
 * driver's server-selection timeout is what ends the attempt.</p>
 */
@SpringBootTest(properties = {
        "management.server.port=0",
        // SecurityConfig refuses to build its UserDetailsService when either half is blank, so without
        // these the context never starts. This test asserts nothing about authentication; it only has
        // to boot far enough to resolve a flag.
        TestServiceCredential.USERNAME_PROPERTY,
        TestServiceCredential.PASSWORD_PROPERTY,
        "brand-flags.enabled=true",
        "mongo.uri=mongodb://192.0.2.1:27017/ms_clp_blp_test",
        "mongo.server-selection-timeout=250ms",
        "mongo.socket-timeout=250ms",
        "landing.feature-flags.jewel=true",
        "landing.feature-flags.vertex=false",
        "landing.feature-flags.container-greeting=true",
        // The override variable: the exception, not the rule. GAP differs on one flag; everything
        // else it needs must still come from the service-wide values above.
        "brand-flags.override-json={\"jewel_GAP\": false}"})
class BrandFlagsDegradationTest {

    private static final String BRAND = "GAP";

    @Autowired
    private FeatureFlagPort featureFlags;

    @Autowired
    private HealthEndpoint healthEndpoint;

    /**
     * The store cannot be reached, so every flag must resolve from configuration — including the ones
     * that are off. Reading "unreachable" as "disabled" would blank the home page for every brand the
     * moment the database blinked.
     */
    @Test
    void flagsFallBackToTheConfiguredDefaultsWhenTheStoreIsUnreachable() {
        assertThat(featureFlags.isEnabled(LandingFeatureFlags.VERTEX, BRAND)).isFalse();
        assertThat(featureFlags.isEnabled(LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE_V2, BRAND)).isTrue();
    }

    /**
     * The whole point of the override layer: with the store down, GAP keeps the value it was given
     * rather than collapsing onto the service-wide {@code true}.
     */
    @Test
    void theOverrideVariableStillAppliesWhenTheStoreIsUnreachable() {
        assertThat(featureFlags.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isFalse();
    }

    /**
     * And it applies only to the brand it names. A brand absent from the variable behaves exactly as
     * it did before per-brand configuration existed, which is what makes this safe to roll out one
     * brand at a time.
     */
    @Test
    void aBrandNotNamedInTheOverrideKeepsTheServiceWideValue() {
        assertThat(featureFlags.isEnabled(LandingFeatureFlags.JEWEL, "LP")).isTrue();
        assertThat(featureFlags.isEnabled(LandingFeatureFlags.JEWEL, null)).isTrue();
    }

    /**
     * Readiness must stay UP. The service is serving correct pages; failing the probe would pull
     * healthy instances out of rotation over a dependency the request path is designed to survive.
     */
    @Test
    void readinessStaysUpWithAnUnreachableStore() {
        assertThat(healthEndpoint.healthForPath("readiness").getStatus()).isEqualTo(Status.UP);
    }
}
