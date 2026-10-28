package com.liverpool.ms_clp_blp.shared.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The inbound HTTP Basic credential every test that boots {@link SecurityConfig} authenticates with.
 *
 * <p>{@code SecurityConfig} refuses to build its {@code UserDetailsService} when either half is blank,
 * so a test that starts the chain must supply both or the context never comes up. Keeping the values
 * here means the property strings and the pre-encoded header can never drift apart.</p>
 *
 * <p>{@link #USERNAME_PROPERTY} and {@link #PASSWORD_PROPERTY} are compile-time constants so they can
 * be used inside annotations; {@link #AUTHORIZATION_HEADER} is computed and is for tests that go over
 * a real socket ({@code @SpringBootTest(webEnvironment = RANDOM_PORT)}), where {@code @WithMockUser}
 * does not apply because the request is a genuine HTTP call.</p>
 */
public final class TestServiceCredential {

    public static final String USERNAME = "test-service";
    public static final String PASSWORD = "test-secret";

    public static final String USERNAME_PROPERTY = "security.basic.username=" + USERNAME;
    public static final String PASSWORD_PROPERTY = "security.basic.password=" + PASSWORD;

    /** Ready-to-send {@code Authorization} value for the credential above. */
    public static final String AUTHORIZATION_HEADER = "Basic " + Base64.getEncoder()
            .encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));

    private TestServiceCredential() {
    }
}
