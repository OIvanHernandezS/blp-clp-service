package com.liverpool.ms_clp_blp.clients;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundLoggingPropertiesTest {

    @Test
    void rulesAreNormalised_andMatchedAsWholePairs() {
        OutboundLoggingProperties properties =
                new OutboundLoggingProperties(false, Arrays.asList(" Auth:401 ", null, "", "store"));

        assertThat(properties.expectedFailures()).containsExactly("auth:401", "store");
        assertThat(properties.isExpected("AUTH", 401)).isTrue();
        assertThat(properties.isExpected("auth", 500)).isFalse();
        assertThat(properties.isExpected("store", 404)).isFalse();
        assertThat(properties.isExpected(null, 401)).isFalse();
        assertThat(properties.isExpected(" ", 401)).isFalse();
    }

    @Test
    void noRules_meansNothingIsExpected() {
        OutboundLoggingProperties properties = new OutboundLoggingProperties(true, null);

        assertThat(properties.expectedFailures()).isEmpty();
        assertThat(properties.isExpected("auth", 401)).isFalse();
    }
}
