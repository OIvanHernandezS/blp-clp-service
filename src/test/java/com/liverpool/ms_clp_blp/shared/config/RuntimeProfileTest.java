package com.liverpool.ms_clp_blp.shared.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The profile name is half of the per-brand configuration document id ({@code LP_QA}), so getting it
 * wrong silently reads another environment's configuration. These cases pin the three ways that can
 * happen.
 */
class RuntimeProfileTest {

    /** Cloud Run passes exactly one lower-case profile; the store keys are upper case. */
    @Test
    void upperCasesASingleProfile() {
        assertThat(new RuntimeProfile("qa").name()).isEqualTo("QA");
    }

    /**
     * The regression this class exists for: {@code LOG_FORMAT=pretty} adds a profile that names a log
     * format, not an environment. It must never win, in either position.
     */
    @Test
    void ignoresTheNonEnvironmentPrettyProfile() {
        assertThat(new RuntimeProfile("dev,pretty").name()).isEqualTo("DEV");
        assertThat(new RuntimeProfile("pretty,dev").name()).isEqualTo("DEV");
    }

    /** A blank or absent value must resolve to something, never to an empty document id suffix. */
    @Test
    void fallsBackToTheDefaultWhenNoProfileNamesAnEnvironment() {
        assertThat(new RuntimeProfile("").name()).isEqualTo(RuntimeProfile.DEFAULT_PROFILE);
        assertThat(new RuntimeProfile(null).name()).isEqualTo(RuntimeProfile.DEFAULT_PROFILE);
        assertThat(new RuntimeProfile("  ").name()).isEqualTo(RuntimeProfile.DEFAULT_PROFILE);
        assertThat(new RuntimeProfile("pretty").name()).isEqualTo(RuntimeProfile.DEFAULT_PROFILE);
    }

    /** Whitespace around a comma-separated list is a real thing in hand-edited env files. */
    @Test
    void trimsSurroundingWhitespace() {
        assertThat(new RuntimeProfile(" staging , pretty ").name()).isEqualTo("STAGING");
    }
}
