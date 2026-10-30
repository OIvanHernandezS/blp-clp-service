package com.liverpool.ms_clp_blp.shared.web;

import java.util.Arrays;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.liverpool.ms_clp_blp.shared.constants.CdnConstants;

/**
 * Baseline security posture for a stateless, gateway-fronted composition service (Rule 5 + security).
 *
 * <p>ms-clp-blp holds no user identity: the login/guest signal is the caller's {@code x-is-logged-in}
 * declaration, used only for block visibility and the edge-cache slot (see
 * {@code shared.session.SessionContextAdapter}). This chain therefore focuses on what this service owns: no server session, CSRF disabled (no browser form/session
 * state), and hardened response headers.</p>
 *
 * <p>Actuator endpoints are served on a dedicated management port ({@code MANAGEMENT_PORT}, default
 * 8081) and are therefore unreachable via the API gateway. The {@code /actuator/**} deny rule below
 * is a belt-and-suspenders guard for environments where management and application ports coincide.</p>
 *
 * <h3>Inbound HTTP Basic</h3>
 * <p>Every application endpoint requires HTTP Basic, matching what {@code ms_pdp_search} and
 * {@code ms_plp_search} already enforce. Cloud Run runs this service with
 * {@code --allow-unauthenticated}, so without this the {@code --ingress} setting on the load balancer
 * was the only perimeter. The credential is a service credential, not a user identity: a valid Basic
 * credential says nothing about whether the request is logged in.</p>
 *
 * <p>Only the OpenAPI surfaces stay public, as in {@code ms_pdp_search}. They are already absent in
 * production, where {@code application-prod.yaml} sets {@code springdoc.swagger-ui.enabled=false}.</p>
 */
@Configuration
// The app context already registers this through @ConfigurationPropertiesScan; declaring it here too
// (same bean name, so no duplicate) is what lets a @WebMvcTest slice import this class alone and get
// a working chain, which is how all six controller tests are written.
@EnableConfigurationProperties(BasicAuthProperties.class)
public class SecurityConfig {

    private static final List<String> CORS_ALLOWED_METHODS = List.of("GET", "POST");

    /**
     * OpenAPI surfaces, left public so the spec and Swagger UI load without a credential — the same
     * allow-list {@code ms_pdp_search} carries. Paths are springdoc's defaults; this service overrides
     * neither {@code springdoc.api-docs.path} nor {@code springdoc.swagger-ui.path}.
     */
    private static final String[] PUBLIC_PATHS = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

    /** Matches exactly the {@link #PUBLIC_PATHS} surfaces, so one CSP decision can be made per request. */
    private static final RequestMatcher OPENAPI_MATCHER = new OrRequestMatcher(
            Arrays.stream(PUBLIC_PATHS).map(PathPatternRequestMatcher::pathPattern).toList());

    /**
     * Akamai's SureRoute probe object, permitted as its own rule rather than appended to
     * {@link #PUBLIC_PATHS}.
     *
     * <p>That list is not just an allow-list: {@link #OPENAPI_MATCHER} is derived from it, and that
     * matcher is what hands a request the permissive Swagger CSP. Adding this path there would have
     * opened it <em>and</em> silently granted an inert static file {@code script-src 'self'} and
     * {@code 'unsafe-inline'} styles. Kept apart, it is permitted here and still answers with
     * {@link #API_CSP} like every other non-OpenAPI response.</p>
     *
     * <p>Matched as an exact path, never {@code /static/**}: only this one object is public, and a
     * wildcard would expose anything a future change drops into the static resource tree.</p>
     */
    private static final String SUREROUTE_TEST_OBJECT = CdnConstants.SUREROUTE_TEST_OBJECT_PATH;

    /**
     * Content-Security-Policy for every application response: this is a JSON API that renders nothing,
     * so the browser needs no privilege at all.
     */
    private static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";

    /**
     * Content-Security-Policy for the OpenAPI surfaces only.
     *
     * <p>Swagger UI, unlike the API, <em>is</em> a page. Under {@link #API_CSP} the browser blocks its
     * own stylesheet, its three bundles and its data-URI icons, so {@code /swagger-ui/index.html} paints
     * a blank white screen with no server-side error. curl does not enforce CSP, which is why every
     * asset still answers 200 when probed from a shell.</p>
     *
     * <p>Everything is pinned to {@code 'self'}: the UI loads no third-party asset. {@code 'unsafe-inline'}
     * is granted to styles only — Swagger UI sets element styles from JavaScript as it renders — while
     * {@code script-src} stays free of both {@code 'unsafe-inline'} and {@code 'unsafe-eval'}, and
     * {@code frame-ancestors 'none'} is carried over unchanged so the page still cannot be framed.</p>
     */
    private static final String OPENAPI_CSP =
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; font-src 'self'; connect-src 'self'; "
                    + "object-src 'none'; base-uri 'self'; frame-ancestors 'none'";

    // Headers the storefront's browser fetch sends across GET /blp/**, GET /clp/** and
    // POST …/blocks/products-list (the same set ms-home allow-lists for its own routes). x-preview does
    // nothing since ADR-053 removed preview, but it stays allow-listed for exactly the same reason x-user-id does, below: allow-listing a
    // header the service ignores is free, while removing one the browser still sends fails the CORS
    // preflight for the whole request. x-user-id must stay allow-listed even though ms-clp-blp
    // no longer reads it for Salesforce identity (ADR-041 reads x-sf-user-id instead): the frontend
    // still sends it on every block-resolution call, and removing
    // it here would fail the browser's CORS preflight for the whole request, not just drop the header.
    // x-sf-user-id carries the ATG profile id that products_list sends to Salesforce as ID_ATG1
    // (ADR-041). It must be allow-listed here or the browser's CORS preflight fails the whole
    // block-resolution request, not just that one header.
    // authorization carries the inbound Basic credential. Same rule, and it bites harder: without it
    // the preflight fails before the browser ever sends the credentialed request, so every call reads
    // as a CORS error rather than as the 401 it would otherwise be.
    // x-auth-version and x-client-platform fed the SessionId exchange this service no longer makes.
    // Same rule again: the browser still sends them, and a missing entry fails the whole preflight.
    private static final List<String> CORS_ALLOWED_HEADERS = List.of(
            "authorization", "content-type", "x-brand-id", "x-locale", "x-channel", "x-preview",
            "x-user-id", "x-jml-user-id", "x-jml-device-id", "x-sf-user-id", "x-is-logged-in",
            "x-auth-version", "x-client-platform");

    /**
     * Defines the stateless, Basic-authenticated filter chain with secure response headers.
     *
     * @param http the security builder
     * @return the configured filter chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(SUREROUTE_TEST_OBJECT).permitAll()
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.authenticationEntryPoint(basicAuthEntryPoint()))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(opts -> {
                        })
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000))
                        .referrerPolicy(referrer -> referrer
                                .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.NO_REFERRER))
                        // Deliberately NOT headers.contentSecurityPolicy(...): that registers a writer
                        // which sets the header for every request, and Spring Security appends
                        // addHeaderWriter() writers *after* it while both skip a header that is already
                        // present. Combining the two would leave this one silently inert. Owning the
                        // header outright is what keeps a single CSP on the response.
                        .addHeaderWriter(SecurityConfig::writeContentSecurityPolicy));
        return http.build();
    }

    /**
     * Writes the one {@code Content-Security-Policy} the response carries, picking {@link #OPENAPI_CSP}
     * for the Swagger UI / spec surfaces and {@link #API_CSP} for everything else.
     *
     * <p>Kept inside this chain rather than split into a second {@link SecurityFilterChain} for the
     * OpenAPI paths: the only thing those paths need differently is this header, and they are already
     * carved out here by {@link #PUBLIC_PATHS}. A separate chain would duplicate that path list — and
     * the two copies would drift.</p>
     *
     * @param request  the request being answered, matched against {@link #OPENAPI_MATCHER}
     * @param response the response to write the policy onto
     */
    private static void writeContentSecurityPolicy(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Content-Security-Policy",
                OPENAPI_MATCHER.matches(request) ? OPENAPI_CSP : API_CSP);
    }

    /**
     * The single service account callers authenticate as.
     *
     * <p>Declaring this bean backs off Spring Boot's {@code UserDetailsServiceAutoConfiguration}, so
     * the framework's fallback user — {@code user} with a password generated afresh on every restart —
     * never exists here. That fallback is not a security hole (nobody can guess it), but it turns a
     * missing credential into "every call 401s for reasons nothing explains", which costs an
     * afternoon to diagnose.</p>
     *
     * <p>A blank username or password fails startup instead. This is deliberate and is the whole point
     * of having no committed default (Rule 10): a revision that refuses to come up is a visible,
     * one-line fix, whereas any lenient reading of "unset" — an empty credential that authenticates,
     * or auth quietly disabled — puts the service on the internet open. Do not "improve" this into a
     * warning.</p>
     *
     * @param properties the injected inbound credential
     * @return an in-memory manager holding exactly that one account
     * @throws IllegalStateException if either credential is missing or blank
     */
    @Bean
    public UserDetailsService basicAuthUserDetailsService(BasicAuthProperties properties) {
        requireConfigured(properties.username(), "MS_CLP_BLP_SECURITY_USERNAME", "security.basic.username");
        requireConfigured(properties.password(), "MS_CLP_BLP_SECURITY_PASSWORD", "security.basic.password");

        // {noop} selects the no-op encoder inside the default DelegatingPasswordEncoder. The stored
        // credential is a shared secret injected per environment, not a user-chosen password, so
        // there is no hash to verify against — the same arrangement ms_pdp_search runs.
        return new InMemoryUserDetailsManager(User.withUsername(properties.username())
                .password("{noop}" + properties.password())
                .roles("SERVICE")
                .build());
    }

    /**
     * @return the entry point that renders a 401 as RFC 7807 rather than the container's HTML page
     */
    @Bean
    public BasicAuthEntryPoint basicAuthEntryPoint() {
        return new BasicAuthEntryPoint();
    }

    private static void requireConfigured(String value, String envVar, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Inbound HTTP Basic credential is not configured: set the " + envVar
                            + " environment variable (property " + property
                            + "). ms-clp-blp refuses to start rather than serve traffic unauthenticated.");
        }
    }

    /**
     * Scoped to the surfaces the storefront's browser calls directly with
     * {@code credentials: 'include'} so the {@code SessionId} and {@code favoriteStore} cookies travel:
     * {@code GET /blp/**}, {@code GET /clp/**} and their {@code POST …/blocks/**} resolution
     * endpoints. A server-side call has no browser origin and is unaffected by this config.
     *
     * <p><b>Security note:</b> origins are wide open ({@code allowedOriginPatterns("*")}) while
     * {@code allowCredentials(true)} stays on, so any origin's browser JS can call these endpoints
     * with the caller's {@code SessionId} cookie attached. Plain {@code setAllowedOrigins("*")} is
     * rejected by Spring under credentialed CORS; {@code allowedOriginPatterns} is the mechanism that
     * makes an open wildcard reachable with credentials still enabled. Inbound Basic narrows what that
     * reaches — a foreign origin now needs the service credential too — but it does not close it:
     * anything that holds the credential can still be called from any origin.</p>
     */
    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(CORS_ALLOWED_METHODS);
        configuration.setAllowedHeaders(CORS_ALLOWED_HEADERS);
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/blp/**", configuration);
        source.registerCorsConfiguration("/clp/**", configuration);
        return source;
    }
}
