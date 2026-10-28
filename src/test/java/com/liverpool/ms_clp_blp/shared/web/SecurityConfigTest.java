package com.liverpool.ms_clp_blp.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liverpool.ms_clp_blp.shared.constants.CdnConstants;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCategory;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCodes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the inbound HTTP Basic contract of {@link SecurityConfig}.
 *
 * <p>Every other web test authenticates via {@link WithServiceCredential} and therefore never
 * exercises the rejection path — this is the one place that sends no credential, or the wrong one,
 * and asserts what a caller actually receives. It runs against a throwaway controller rather than a
 * real one so the assertions are about the chain and nothing else.</p>
 */
@WebMvcTest(controllers = SecurityConfigTest.ProbeController.class)
@Import({SecurityConfig.class, SecurityConfigTest.ProbeController.class})
@TestPropertySource(properties = {
        TestServiceCredential.USERNAME_PROPERTY,
        TestServiceCredential.PASSWORD_PROPERTY})
class SecurityConfigTest {

    private static final String PROBE_PATH = "/probe";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsARequestWithNoCredential() throws Exception {
        mockMvc.perform(get(PROBE_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        "Basic realm=\"ms-clp-blp\", charset=\"UTF-8\""));
    }

    @Test
    void rejectsAWrongCredential() throws Exception {
        mockMvc.perform(get(PROBE_PATH)
                        .with(httpBasic(TestServiceCredential.USERNAME, "not-the-password")))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The 401 must be RFC 7807 like every other error, not the container's HTML page — a caller that
     * parses {@code application/problem+json} everywhere else would otherwise choke on the one status
     * it is most likely to meet first.
     */
    @Test
    void rendersTheRejectionAsProblemDetail() throws Exception {
        mockMvc.perform(get(PROBE_PATH))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value(ErrorCodes.UNAUTHORIZED))
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNAUTHORIZED))
                .andExpect(jsonPath("$.category").value(ErrorCategory.AUTHENTICATION.name()))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.instance").value(PROBE_PATH));
    }

    @Test
    void admitsTheConfiguredCredential() throws Exception {
        mockMvc.perform(get(PROBE_PATH)
                        .with(httpBasic(TestServiceCredential.USERNAME, TestServiceCredential.PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    /**
     * The OpenAPI surfaces stay public. springdoc is not loaded in a {@code @WebMvcTest}, so what
     * comes back is whatever an unmapped path yields here — the assertion is only that it is not a
     * 401, which is what proves the request reached the dispatcher instead of being turned back by
     * the chain. Asserting a concrete status would pin the unmapped-path behaviour, not the rule.
     */
    @Test
    void leavesTheOpenApiSurfacesPublic() throws Exception {
        assertThat(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getStatus())
                .isNotEqualTo(401);
        assertThat(mockMvc.perform(get("/swagger-ui/index.html")).andReturn().getResponse().getStatus())
                .isNotEqualTo(401);
    }

    /**
     * Akamai's SureRoute probe carries no credential, so the chain must let it through. Asserted as
     * "not 401" for the same reason as the OpenAPI test above: a {@code @WebMvcTest} slice is not
     * proof of what a served static file returns, only of what the chain decided.
     */
    @Test
    void leavesTheSureRouteTestObjectPublic() throws Exception {
        mockMvc.perform(get(CdnConstants.SUREROUTE_TEST_OBJECT_PATH))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SureRoute")));
    }

    /**
     * Opening the path is only half the fix. SureRoute rejects a {@code 404} exactly as it rejected the
     * {@code 401}, so a permitted-but-missing object would leave the service looking fixed while route
     * racing stayed broken — and nothing would page. This pins that the file the URL resolves to is
     * actually packaged.
     */
    @Test
    void shipsTheSureRouteTestObject() {
        assertThat(new ClassPathResource(
                "META-INF/resources" + CdnConstants.SUREROUTE_TEST_OBJECT_PATH).exists()).isTrue();
    }

    /**
     * The regression guard for how this path was opened. Appending it to {@code PUBLIC_PATHS} would
     * have worked — and would also have handed an inert static file the Swagger CSP, because
     * {@code OPENAPI_MATCHER} is derived from that same array. It must answer under the locked-down
     * API policy like every other non-OpenAPI response.
     */
    @Test
    void keepsTheSureRouteTestObjectUnderTheApiCsp() throws Exception {
        mockMvc.perform(get(CdnConstants.SUREROUTE_TEST_OBJECT_PATH))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'"));
    }

    /** Actuator is served on its own port; on this one it stays denied, credential or not. */
    @Test
    void keepsActuatorDeniedOnTheApplicationPort() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .with(httpBasic(TestServiceCredential.USERNAME, TestServiceCredential.PASSWORD)))
                .andExpect(status().isForbidden());
    }

    /**
     * A missing credential must stop the service from starting, not open it. Asserted directly on the
     * factory method because a context that refuses to build is awkward to observe from a slice test —
     * and the message matters as much as the failure: it is what a stalled deploy shows the on-call.
     */
    @Test
    void refusesToBuildWithoutACredential() {
        SecurityConfig config = new SecurityConfig();

        assertThatThrownBy(() -> config.basicAuthUserDetailsService(
                new BasicAuthProperties("  ", TestServiceCredential.PASSWORD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MS_CLP_BLP_SECURITY_USERNAME");

        assertThatThrownBy(() -> config.basicAuthUserDetailsService(
                new BasicAuthProperties(TestServiceCredential.USERNAME, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MS_CLP_BLP_SECURITY_PASSWORD");
    }

    @Test
    void buildsTheSingleServiceAccountWhenConfigured() {
        var users = new SecurityConfig().basicAuthUserDetailsService(
                new BasicAuthProperties(TestServiceCredential.USERNAME, TestServiceCredential.PASSWORD));

        var account = users.loadUserByUsername(TestServiceCredential.USERNAME);
        assertThat(account.getPassword()).isEqualTo("{noop}" + TestServiceCredential.PASSWORD);
        assertThat(account.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_SERVICE");
    }

    /**
     * Stands in for a real endpoint so this test asserts the filter chain and not a slice's wiring.
     */
    @RestController
    static class ProbeController {

        @GetMapping(PROBE_PATH)
        String probe() {
            return "ok";
        }
    }
}
