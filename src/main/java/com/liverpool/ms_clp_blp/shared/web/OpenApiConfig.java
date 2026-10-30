package com.liverpool.ms_clp_blp.shared.web;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.CookieParameter;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

/**
 * OpenAPI 3.1 document configuration for ms-clp-blp (Rule 8.1). Enables the auto-generated spec at
 * {@code /v3/api-docs} and Swagger UI at {@code /swagger-ui.html} (both gated by
 * {@code springdoc.swagger-ui.enabled}, which the {@code prod} profile turns off).
 *
 * <p>Per-endpoint documentation lives on the {@code *ApiV1} interfaces the controllers implement, so
 * the thin controllers stay free of annotation noise. This class owns only what is global: the API
 * {@link Info} banner and the request context that every endpoint reads through
 * {@code SessionContextPort} rather than as method parameters — the {@code x-brand-id} /
 * {@code x-channel} / {@code x-locale} headers and the {@code SessionId} cookie. Declaring those once
 * as an {@link OperationCustomizer} keeps them off every interface signature and guarantees they are
 * documented identically on all operations.</p>
 */
@Configuration
public class OpenApiConfig {

    private static final String HEADER_BRAND_ID = "x-brand-id";
    private static final String HEADER_CHANNEL = "x-channel";
    private static final String HEADER_LOCALE = "x-locale";
    private static final String COOKIE_SESSION_ID = "SessionId";
    private static final String HEADER_APP_VERSION = "x-app-version";

    /** Name of the inbound HTTP Basic scheme; referenced by the global security requirement. */
    private static final String SCHEME_BASIC_AUTH = "basicAuth";

    /**
     * The API banner shown at the top of Swagger UI and embedded in the generated spec, plus the
     * inbound HTTP Basic requirement that applies to every operation.
     *
     * <p>The security requirement is declared globally rather than per operation because
     * {@link SecurityConfig} authenticates every endpoint the same way; anything exempt there
     * ({@code /v3/api-docs}, Swagger UI itself) is not an operation in this document. Without it
     * Swagger UI has no <em>Authorize</em> button and every "Try it out" returns 401 — the gap
     * {@code ms_pdp_search} still has.</p>
     *
     * @return the OpenAPI document metadata (title, version, description, contact, server, security)
     */
    @Bean
    public OpenAPI msHomeOpenAPI() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(SCHEME_BASIC_AUTH,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("Service credential shared with ms_pdp_search and "
                                        + "ms_plp_search. Distinct from the SessionId cookie below, "
                                        + "which carries the end user's identity.")))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_BASIC_AUTH))
                .info(new Info()
                        .title("ms-clp-blp API")
                        .version("v1")
                        .description("""
                                Landing page composition service. Assembles the Contentstack-backed brand \
                                (`GET /blp/{locale}/**`) and category (`GET /clp/{locale}/**`) landing \
                                pages, and resolves product carousels through \
                                `POST /blp/blocks/products-list` / `POST /clp/blocks/products-list`. \
                                Session, brand and channel are resolved from \
                                upstream-validated headers; errors surface as RFC 7807 `ProblemDetail`.""")
                        .contact(new Contact().name("Search & Browse Experience Team")))
                .addServersItem(new Server().url("/").description("This service (through the API gateway)"));
    }

    /**
     * Appends the request-context headers and session cookie — resolved server-side via
     * {@code SessionContextPort}, never bound as method parameters — to every operation so callers see
     * them in Swagger UI without each interface having to redeclare them.
     *
     * @return a customizer that adds the shared context parameters to each operation
     */
    @Bean
    public OperationCustomizer sessionContextParameters() {
        return (operation, handlerMethod) -> {
            operation.addParametersItem(header(HEADER_BRAND_ID,
                    "Brand identifier. Falls back to the configured default brand when absent.", "LP"));
            operation.addParametersItem(header(HEADER_CHANNEL,
                    "Originating channel: WEB, APP, KIOSCO or POS (KIOSCO and POS behave as WEB). "
                            + "Defaults to WEB when absent.", "WEB"));
            operation.addParametersItem(header(HEADER_LOCALE,
                    "Content locale used to select the CMS entry.", "es-mx"));
            operation.addParametersItem(sessionCookie());
            return operation;
        };
    }

    /**
     * Appends {@code x-app-version} to every documented response of every operation — set by
     * {@link AppVersionHeaderFilter}, so it applies service-wide (including error responses), unlike
     * {@link #sessionContextParameters()} which documents request-side headers.
     *
     * @return a customizer that adds the header to every response entry on each operation
     */
    @Bean
    public OperationCustomizer appVersionHeader() {
        return (operation, handlerMethod) -> {
            if (operation.getResponses() != null) {
                operation.getResponses().values().forEach(response -> response.addHeaderObject(
                        HEADER_APP_VERSION, new Header()
                                .description("The deployed build's version tag (the git tag selected "
                                        + "when triggering the deploy), or \"unknown\" outside a real "
                                        + "deploy.")
                                .schema(new StringSchema())));
            }
            return operation;
        };
    }

    private HeaderParameter header(String name, String description, String example) {
        return (HeaderParameter) new HeaderParameter()
                .name(name)
                .description(description)
                .required(false)
                .schema(new StringSchema())
                .example(example);
    }

    private CookieParameter sessionCookie() {
        return (CookieParameter) new CookieParameter()
                .name(COOKIE_SESSION_ID)
                .description("Opaque session id. Only its presence and format are checked (never "
                        + "exchanged): it gates Salesforce V1 carousels and the signed-in edge-cache slot.")
                .required(false)
                .schema(new StringSchema())
                .example("a1b2c3d4-....");
    }
}
