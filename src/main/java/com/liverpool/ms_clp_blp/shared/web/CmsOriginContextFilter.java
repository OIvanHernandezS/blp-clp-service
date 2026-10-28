package com.liverpool.ms_clp_blp.shared.web;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guarantees {@link CmsResponseMetadataContext} is cleared after every request, mirroring
 * {@link MdcRequestContextFilter}'s hygiene for its own ThreadLocal-backed context.
 *
 * <p>Not strictly load-bearing on the request-serving happy path — each HTTP request runs on a fresh
 * virtual thread (Rule 3.1/4), so there is no cross-request thread reuse to leak through — but cheap,
 * defensive against any future change to that setting, and keeps thread-reusing test harnesses
 * (MockMvc dispatches synchronously on the test thread) from leaking a stale origin between tests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class CmsOriginContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            CmsResponseMetadataContext.clear();
        }
    }
}
