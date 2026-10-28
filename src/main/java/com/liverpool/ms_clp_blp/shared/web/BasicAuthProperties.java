package com.liverpool.ms_clp_blp.shared.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the <em>inbound</em> HTTP Basic auth that guards every application endpoint
 * (see {@link SecurityConfig}). Mirrors what {@code ms_pdp_search} and {@code ms_plp_search} already
 * run, so one platform credential can serve every service.
 *
 * <p>Not to be confused with {@code vertex.username}/{@code vertex.password}
 * ({@code clients.vertex.VertexProperties}), which is the <em>outbound</em> credential ms-clp-blp sends
 * <em>to</em> {@code ms_plp_search}. Same mechanism, opposite direction.</p>
 *
 * <p>Both values are secrets: env var only, never a committed default (Rule 10). Leaving either blank
 * fails startup in {@link SecurityConfig#basicAuthUserDetailsService(BasicAuthProperties)} rather than
 * silently accepting an empty credential — a deploy that does not come up is a far better outcome than
 * one that comes up open.</p>
 *
 * @param username HTTP Basic username callers must present ({@code MS_CLP_BLP_SECURITY_USERNAME})
 * @param password HTTP Basic password callers must present ({@code MS_CLP_BLP_SECURITY_PASSWORD})
 */
@ConfigurationProperties(prefix = "security.basic")
public record BasicAuthProperties(String username, String password) {
}
