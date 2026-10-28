package com.liverpool.ms_clp_blp.landing.brandflags;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import com.liverpool.ms_clp_blp.landing.LandingProperties;
import com.liverpool.ms_clp_blp.landing.adapters.featureflag.FeatureFlagAdapter;
import com.liverpool.ms_clp_blp.landing.brandflags.adapters.cache.BrandFlagsCache;
import com.liverpool.ms_clp_blp.landing.brandflags.adapters.defaults.JsonOverrideBrandFlagsDefaults;
import com.liverpool.ms_clp_blp.landing.brandflags.adapters.mongo.BrandFlagsMongoAdapter;
import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlags;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsRepositoryPort;
import com.liverpool.ms_clp_blp.landing.models.LandingFeatureFlags;
import com.liverpool.ms_clp_blp.landing.ports.FeatureFlagPort;
import com.liverpool.ms_clp_blp.shared.config.RuntimeProfile;
import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Drives a <em>throwing</em> MongoDB through the real adapter, the real cache and the real precedence
 * chain, and asserts the value a page would actually be composed with.
 *
 * <p>Distinct from {@code BrandFlagsMongoAdapterTest}, which proves the adapter returns an empty
 * result, and from {@code BrandFlagsFlowTest}, whose double simulates that empty result directly.
 * Neither shows that a real exception ends up as the configured value on the other side of three
 * collaborators — and "the adapter swallows it" is only half the promise. The half that matters to a
 * reader of the home page is what number comes out.</p>
 */
class MongoFailureFallbackTest {

    private static final String BRAND = "GAP";
    private static final String COLLECTION = "ms_clp_blp_configuration";

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);

    /**
     * The exact failure of a partitioned cluster: server selection expires. It must surface as the
     * brand's override, not as an exception and not as {@code false}.
     */
    @Test
    void aServerSelectionTimeoutResolvesToTheConfiguredValues() {
        failWith(new MongoTimeoutException("no server available"));
        FeatureFlagPort flags = chain("""
                {"jewel_GAP": false}""");

        assertThat(flags.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isFalse();
        assertThat(flags.isEnabled(LandingFeatureFlags.VERTEX, BRAND)).isTrue();
    }

    /** A socket that dies mid-read is a different exception on the same path. */
    @Test
    void aSocketReadTimeoutResolvesToTheConfiguredValues() {
        failWith(new MongoSocketReadTimeoutException(
                "read timed out", new ServerAddress("192.0.2.1", 27017), new java.io.IOException()));

        assertThat(chain("").isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isTrue();
    }

    /** Spring Data translates driver failures into its own hierarchy; that path must behave too. */
    @Test
    void aTranslatedSpringDataFailureResolvesToTheConfiguredValues() {
        failWith(new DataAccessResourceFailureException("no route to host"));

        assertThat(chain("").isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isTrue();
        assertThat(chain("").isEnabled(LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE_V2, BRAND)).isFalse();
    }

    /** Whatever the exception, composing a page must never see it. */
    @Test
    void noMongoFailureEverReachesTheCaller() {
        failWith(new IllegalStateException("state the driver should never be in"));
        FeatureFlagPort flags = chain("");

        assertThatCode(() -> flags.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).doesNotThrowAnyException();
    }

    /**
     * The gap this class exists to close: the cache is the last thing between a repository and the
     * request path, so a repository that breaks its contract and throws must still not fail a page.
     * The port documents "never throw"; a comment is not an enforcement.
     */
    @Test
    void aRepositoryThatBreaksItsContractAndThrowsStillDoesNotFailAPage() {
        BrandFlagsRepositoryPort rogue = new BrandFlagsRepositoryPort() {
            @Override
            public Optional<BrandFlags> findByBrand(String brand) {
                throw new IllegalStateException("this implementation forgot the contract");
            }

            @Override
            public boolean save(BrandFlags brandFlags) {
                return false;
            }

            @Override
            public boolean saveDefaults(BrandFlags brandFlags) {
                return false;
            }

            @Override
            public boolean delete(String brand) {
                return false;
            }
        };
        FeatureFlagPort flags = chain(new BrandFlagsCache(rogue, properties("")), "");

        assertThat(flags.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isTrue();
        assertThat(flags.isEnabled(LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE_V2, BRAND)).isFalse();
    }

    private void failWith(RuntimeException failure) {
        doThrow(failure).when(mongoTemplate).findOne(any(Query.class), any(), eq(COLLECTION));
        doThrow(failure).when(mongoTemplate).findOne(any(Query.class), any(), anyString());
    }

    private FeatureFlagPort chain(String overrideJson) {
        BrandFlagsMongoAdapter adapter =
                new BrandFlagsMongoAdapter(mongoTemplate, properties(""), new RuntimeProfile("qa"));
        return chain(new BrandFlagsCache(adapter, properties("")), overrideJson);
    }

    private static FeatureFlagPort chain(BrandFlagsCache cache, String overrideJson) {
        LandingProperties home = new LandingProperties(Map.of(
                LandingFeatureFlags.JEWEL, true,
                LandingFeatureFlags.VERTEX, true,
                LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE_V2, false), Map.of(), null);
        return new FeatureFlagAdapter(home, cache,
                new JsonOverrideBrandFlagsDefaults(properties(overrideJson), new ObjectMapper()));
    }

    private static BrandFlagsProperties properties(String overrideJson) {
        return new BrandFlagsProperties(true, "ms_clp_blp_flags", "es-mx", COLLECTION,
                Duration.ofMinutes(3), List.of(), overrideJson);
    }
}
