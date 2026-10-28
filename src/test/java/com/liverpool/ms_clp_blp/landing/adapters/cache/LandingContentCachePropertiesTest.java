package com.liverpool.ms_clp_blp.landing.adapters.cache;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LandingContentCachePropertiesTest {

    @Test
    void refreshMustBePositive() {
        assertThatThrownBy(() ->
                new LandingContentCacheProperties(true, Duration.ZERO, Duration.ofMinutes(5), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refresh-after-write");
    }

    @Test
    void hardExpiryMustExceedRefreshAge() {
        assertThatThrownBy(() -> new LandingContentCacheProperties(
                true, Duration.ofMinutes(5), Duration.ofMinutes(5), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expire-after-write");
    }

    @Test
    void maximumSizeMustBePositive() {
        assertThatThrownBy(() -> new LandingContentCacheProperties(
                true, Duration.ofSeconds(30), Duration.ofMinutes(5), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum-size");
    }
}
