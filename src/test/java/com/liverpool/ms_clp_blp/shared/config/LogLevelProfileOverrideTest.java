package com.liverpool.ms_clp_blp.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@code LOG_LEVEL_APP} against regression: each {@code application-{profile}.yaml} used to
 * redefine {@code logging.level.com.liverpool.ms_clp_blp} as a hardcoded literal, which silently
 * shadowed the base {@code application.yaml}'s {@code ${LOG_LEVEL_APP:INFO}} placeholder once a
 * profile was active — so the env var had zero effect in qa/staging/prod (Cloud Run always launches
 * with an active profile). Every profile file must resolve the env var, not just the base document.
 */
class LogLevelProfileOverrideTest {

    private final ApplicationContextRunner contextRunner = HermeticConfigRunner.create();

    @Test
    void prodProfile_defaultsToInfo_whenLogLevelAppUnset() {
        contextRunner.withPropertyValues("spring.profiles.active=prod").run(context ->
                assertThat(context.getEnvironment().getProperty("logging.level.com.liverpool.ms_clp_blp"))
                        .isEqualTo("INFO"));
    }

    @Test
    void prodProfile_honorsLogLevelAppOverride() {
        contextRunner.withPropertyValues("spring.profiles.active=prod", "LOG_LEVEL_APP=DEBUG").run(context ->
                assertThat(context.getEnvironment().getProperty("logging.level.com.liverpool.ms_clp_blp"))
                        .isEqualTo("DEBUG"));
    }

    @Test
    void qaProfile_honorsLogLevelAppOverride() {
        contextRunner.withPropertyValues("spring.profiles.active=qa", "LOG_LEVEL_APP=TRACE").run(context ->
                assertThat(context.getEnvironment().getProperty("logging.level.com.liverpool.ms_clp_blp"))
                        .isEqualTo("TRACE"));
    }

    @Test
    void stagingProfile_honorsLogLevelAppOverride() {
        contextRunner.withPropertyValues("spring.profiles.active=staging", "LOG_LEVEL_APP=WARN").run(context ->
                assertThat(context.getEnvironment().getProperty("logging.level.com.liverpool.ms_clp_blp"))
                        .isEqualTo("WARN"));
    }

    @Test
    void devProfile_defaultsToDebug_whenLogLevelAppUnset() {
        contextRunner.withPropertyValues("spring.profiles.active=dev").run(context ->
                assertThat(context.getEnvironment().getProperty("logging.level.com.liverpool.ms_clp_blp"))
                        .isEqualTo("DEBUG"));
    }

    @Test
    void devProfile_honorsLogLevelAppOverride() {
        contextRunner.withPropertyValues("spring.profiles.active=dev", "LOG_LEVEL_APP=INFO").run(context ->
                assertThat(context.getEnvironment().getProperty("logging.level.com.liverpool.ms_clp_blp"))
                        .isEqualTo("INFO"));
    }
}
