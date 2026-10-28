package com.liverpool.ms_clp_blp.shared.web;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Drops the CORS-contributed {@code Vary} values from the three edge-cacheable GET responses, so
 * Akamai will store them at all.
 *
 * <p>Akamai does not cache a response whose {@code Vary} is anything other than a lone
 * {@code Accept-Encoding}. Spring's CORS support emits three values ({@code Origin},
 * {@code Access-Control-Request-Method}, {@code Access-Control-Request-Headers}) on every request to a
 * path with a registered {@code CorsConfiguration} — which is exactly the set of paths we want cached,
 * and it emits them even when the request carries no {@code Origin} at all. Without this filter the
 * origin's {@code Cache-Control} is irrelevant: the edge reports {@code x-check-cacheable: NO} and
 * every request is a {@code TCP_MISS}.</p>
 *
 * <h3>Why this wraps the response instead of just setting the header</h3>
 *
 * <p>{@code DefaultCorsProcessor} adds those values during the <em>request</em> phase, from inside
 * Spring Security's chain (order {@code -100}). Every filter this application registers sits at
 * {@code HIGHEST_PRECEDENCE + 10…20} — i.e. <em>before</em> that — so pre-setting {@code Vary} here
 * accomplishes nothing: the processor re-reads the response and appends its values anyway. Writing the
 * header <em>after</em> {@code chain.doFilter} is no better, because a response this size is long
 * committed by then. So the filter installs a wrapper on the way in and lets it veto the writes as
 * they happen, whatever the ordering.</p>
 *
 * <h3>Why {@code Accept-Encoding} must survive</h3>
 *
 * <p>{@code server.compression.enabled} is on with a 2 KB threshold and these payloads are two orders
 * of magnitude past it, so Tomcat compresses them and adds {@code Vary: Accept-Encoding} itself. That
 * value is both legitimate and the one form of {@code Vary} the edge accepts, so it passes through
 * untouched — this filter removes values, it never adds one. (Akamai's own {@code removeVary} behavior
 * would solve the same problem at the edge, but it drops the header wholesale, this included.)</p>
 *
 * <p>Scope is deliberately narrow: only {@code GET} on the landing page routes ({@code /blp/**},
 * {@code /clp/**}). Everything else, the {@code …/blocks/**} POSTs included, keeps a fully correct
 * {@code Vary} — those responses are POSTs and
 * personalised, nothing should ever cache them, and a truthful {@code Vary} is the cheapest way to say
 * so to any intermediary.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 13)
public class CorsVaryFilter extends OncePerRequestFilter {

    /** Path prefixes whose GET responses the edge is expected to cache. */
    private static final List<String> CACHEABLE_PREFIXES = List.of("/blp/", "/clp/");

    /**
     * The values Spring's CORS support contributes to {@code Vary}. Compared case-insensitively:
     * these are header names, and nothing guarantees the casing a given Spring version emits.
     */
    private static final Set<String> CORS_VARY_VALUES = Set.of(
            HttpHeaders.ORIGIN.toLowerCase(),
            HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD.toLowerCase(),
            HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS.toLowerCase());

    /**
     * Applies only to GETs on the landing page routes; every other request keeps its {@code Vary}
     * exactly as Spring wrote it.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.GET.matches(request.getMethod())
                || CACHEABLE_PREFIXES.stream().noneMatch(normalizedPath(request)::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(request, new VaryFilteringResponse(response));
    }

    /**
     * The request path with any single trailing slash removed, so {@code /blp/es-mx/x/} is recognised as the
     * same endpoint as {@code /blp/es-mx/x}.
     */
    private static String normalizedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return "";
        }
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /**
     * Response wrapper that drops CORS values from {@code Vary} while leaving every other header — and
     * every other {@code Vary} value — alone.
     */
    private static final class VaryFilteringResponse extends HttpServletResponseWrapper {

        private VaryFilteringResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setHeader(String name, String value) {
            if (!HttpHeaders.VARY.equalsIgnoreCase(name)) {
                super.setHeader(name, value);
                return;
            }
            String retained = retainedVary(value);
            // A value of only CORS names would otherwise clear the header to an empty string; leaving
            // it absent is what we want, and reads as "no Vary" to the edge.
            if (!retained.isEmpty()) {
                super.setHeader(name, retained);
            }
        }

        @Override
        public void addHeader(String name, String value) {
            if (!HttpHeaders.VARY.equalsIgnoreCase(name)) {
                super.addHeader(name, value);
                return;
            }
            String retained = retainedVary(value);
            if (!retained.isEmpty()) {
                super.addHeader(name, retained);
            }
        }

        /**
         * Splits a {@code Vary} value on commas and keeps everything that is not a CORS request
         * header. Spring adds its three values one call at a time, but a comma-joined list is legal
         * HTTP and Tomcat may well produce one, so this handles both rather than assuming.
         *
         * @param value the {@code Vary} value being written
         * @return the surviving values, comma-joined; empty when nothing survives
         */
        private static String retainedVary(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(token -> !token.isEmpty())
                    .filter(token -> !CORS_VARY_VALUES.contains(token.toLowerCase()))
                    .collect(Collectors.joining(", "));
        }
    }
}
