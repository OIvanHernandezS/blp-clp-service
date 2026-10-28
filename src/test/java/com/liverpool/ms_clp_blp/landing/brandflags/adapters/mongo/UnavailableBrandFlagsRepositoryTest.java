package com.liverpool.ms_clp_blp.landing.brandflags.adapters.mongo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.brandflags.BrandFlagsProperties;
import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlags;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stand-in wired when there is no store. It exists so that "no MONGO_URI" is a degraded service
 * rather than a service that will not start — the failure this whole feature is designed to survive.
 */
class UnavailableBrandFlagsRepositoryTest {

    @Test
    void answersNoOverridesToEveryRead() {
        assertThat(repository(true).findByBrand("GAP")).isEmpty();
    }

    @Test
    void refusesEveryWrite() {
        UnavailableBrandFlagsRepository repository = repository(true);

        assertThat(repository.save(new BrandFlags("GAP", Map.of("jewel", true), Instant.EPOCH, "blt1")))
                .isFalse();
        assertThat(repository.saveDefaults(
                new BrandFlags("GAP", Map.of("jewel", true), Instant.EPOCH, null)))
                .isFalse();
        assertThat(repository.delete("GAP")).isFalse();
    }

    /**
     * Constructing it with the feature off is the expected local/dev case and must be silent;
     * constructing it with the feature on is a misconfiguration and warns. Both must build.
     */
    @Test
    void constructsInBothConfigurations() {
        assertThat(repository(false)).isNotNull();
        assertThat(repository(true)).isNotNull();
    }

    private static UnavailableBrandFlagsRepository repository(boolean enabled) {
        return new UnavailableBrandFlagsRepository(new BrandFlagsProperties(
                enabled, "ms_clp_blp_flags", "es-mx", "ms_clp_blp_configuration",
                Duration.ofMinutes(3), List.of(), ""));
    }
}
