package com.liverpool.ms_clp_blp.shared.web;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stamps {@code x-app-version} on every response, including error responses (useful — it identifies
 * which build produced a given 502).
 *
 * <p>{@code appVersion} is a build-wide constant, unrelated to any one request, so this is a plain
 * response header rather than something threaded through {@link CmsResponseMetadataContext}: it
 * applies to every endpoint, not just the three CMS-backed ones.</p>
 *
 * <p>The value is injected via the {@code appVersion} system property, set by the deploy pipeline from
 * the git tag the operator selected when triggering the deploy (see {@code Dockerfile} /
 * {@code deploy_DEV_QA.yml} / {@code deploy_PROD.yml}). {@code pom.xml}'s own version stays the static
 * placeholder it always has been; this is deliberately a separate, deploy-time-only signal. The
 * {@code unknown} default applies only to local/IDE runs where no such property is set — deploys are
 * tag-only and enforced, so a real deploy always has a real value.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 12)
public class AppVersionHeaderFilter extends OncePerRequestFilter {

    static final String HEADER_APP_VERSION = "x-app-version";

    private final String appVersion;

    public AppVersionHeaderFilter(@Value("${appVersion:unknown}") String appVersion) {
        this.appVersion = appVersion;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader(HEADER_APP_VERSION, appVersion);
        chain.doFilter(request, response);
    }
}
