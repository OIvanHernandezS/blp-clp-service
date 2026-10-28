package com.liverpool.ms_clp_blp.clients.auth;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The version list is the guard that keeps an unpublished contract (which answers {@code 404}, read as
 * "everyone is a guest") from ever being called, so its startup check is pinned here.
 */
class AuthPropertiesTest {

    @Test
    void defaultVersionOutsideTheSupportedList_failsStartup() {
        assertThatThrownBy(() -> properties(4, List.of(2, 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.default-version=4");
    }

    @Test
    void missingSupportedList_failsStartup() {
        assertThatThrownBy(() -> properties(2, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void supportsVersion_answersFromTheList() {
        AuthProperties props = properties(2, List.of(2, 3));

        assertThat(props.supportsVersion(2)).isTrue();
        assertThat(props.supportsVersion(3)).isTrue();
        assertThat(props.supportsVersion(4)).isFalse();
    }

    private static AuthProperties properties(int defaultVersion, List<Integer> supported) {
        return new AuthProperties("http://auth", Map.of(), "/v{version}/auth/exchange-token",
                defaultVersion, supported, "SessionId", Duration.ofSeconds(2), Duration.ofSeconds(4));
    }
}
