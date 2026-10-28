package com.liverpool.ms_clp_blp.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code LOG_FORMAT} config-activation behaviour in {@code application.yaml} against
 * regression: unset must keep the GCP-native structured format (cloud default), {@code
 * log-format=pretty} must fall back to the plain console pattern.
 */
class LogFormatConfigActivationTest {

    private static final String GCP_FORMATTER = "com.liverpool.ms_clp_blp.shared.logging.GcpStructuredLogFormatter";

    private final ApplicationContextRunner contextRunner = HermeticConfigRunner.create();

    @Test
    void logFormatUnset_keepsGcpStructuredFormat() {
        contextRunner.run(context -> {
            assertThat(context.getEnvironment().getProperty("logging.structured.format.console"))
                    .isEqualTo(GCP_FORMATTER);
            assertThat(context.getEnvironment().getProperty("logging.outbound.feign-enabled", Boolean.class))
                    .isFalse();
        });
    }

    @Test
    void logFormatPretty_disablesStructuredFormatInFavorOfPattern() {
        contextRunner.withPropertyValues("LOG_FORMAT=pretty").run(context -> {
            assertThat(context.getEnvironment().getProperty("logging.structured.format.console")).isEmpty();
            assertThat(context.getEnvironment().getProperty("logging.pattern.console"))
                    .contains("%clr(")
                    // The message and its structured fields, which is what the pattern is for.
                    .contains("%msg")
                    .contains("%kvp")
                    // Enough correlation to tell two concurrent local requests apart, and no more:
                    // the full traceId/spanId/correlationId/requestId block pushed the message off
                    // the right-hand edge of the console. Cloud keeps all four in the JSON format.
                    .contains("%.-8X{requestId")
                    .doesNotContain("%X{traceId")
                    .doesNotContain("%X{spanId")
                    .doesNotContain("%X{correlationId");
            assertThat(context.getEnvironment().getProperty("spring.output.ansi.enabled")).isEqualTo("always");
            assertThat(context.getEnvironment().getProperty("logging.outbound.feign-enabled", Boolean.class))
                    .isTrue();
        });
    }
}
