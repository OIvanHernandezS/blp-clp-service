package com.liverpool.ms_clp_blp.shared.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCategory;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCodes;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * Renders a missing or wrong HTTP Basic credential as the same RFC 7807 payload every other error in
 * this service uses, instead of the servlet container's default HTML error page.
 *
 * <p>{@code GlobalExceptionHandler} cannot do this job: an {@link AuthenticationException} is raised
 * inside the Spring Security filter chain, before {@code DispatcherServlet} ever runs, so no
 * {@code @RestControllerAdvice} sees it. This entry point is the only place the 401 body can be
 * shaped, and without it a caller that parses {@code application/problem+json} on every other status
 * would hit HTML on exactly the status it is most likely to encounter first.</p>
 *
 * <p>The body is assembled as a map rather than a {@code ProblemDetail}: Spring's flattening of the
 * custom {@code errorCode}/{@code category}/{@code retryable} properties comes from a Jackson mixin
 * registered on the context's message-converter mapper, which is not in play this early in the chain.
 * A map serialises to the identical wire shape with no such dependency — same reasoning as
 * {@code LandingPageMapper}, which also keeps its own mapper rather than injecting one.</p>
 */
public class BasicAuthEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(BasicAuthEntryPoint.class);

    /** Realm advertised in {@code WWW-Authenticate}; identifies which service is asking. */
    private static final String REALM = "ms-clp-blp";

    private static final String CHALLENGE = "Basic realm=\"" + REALM + "\", charset=\"UTF-8\"";

    private static final String DETAIL =
            "Valid HTTP Basic credentials are required to call this service.";

    /**
     * RFC 7807 default for "no further type information"; Spring emits the same value, so a 401 body
     * is indistinguishable in shape from the ones {@code GlobalExceptionHandler} produces.
     */
    private static final String TYPE_ABOUT_BLANK = "about:blank";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // WARN, not INFO: once callers are configured this should not happen, and a flood of these
        // is precisely the signal that a consumer shipped without its credential. The credential
        // itself is never logged — only whether one was presented at all.
        LogEvents.at(log, LogEvents.AUTHENTICATION_FAILED)
                .addKeyValue(LogFields.URI, request.getRequestURI())
                .addKeyValue(LogFields.HTTP_METHOD, request.getMethod())
                .addKeyValue(LogFields.ERROR_CLASS, authException.getClass().getSimpleName())
                .addKeyValue(LogFields.CREDENTIALS_PRESENTED,
                        request.getHeader(HttpHeaders.AUTHORIZATION) != null)
                .log("Rejected an unauthenticated request at the HTTP Basic boundary");

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        MAPPER.writeValue(response.getOutputStream(), problemBody(request));
    }

    /**
     * Builds the RFC 7807 body, field for field identical to what {@code GlobalExceptionHandler}
     * emits for a domain error, so consumers parse one shape for every failure.
     */
    private Map<String, Object> problemBody(HttpServletRequest request) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", TYPE_ABOUT_BLANK);
        problem.put("title", ErrorCodes.UNAUTHORIZED);
        problem.put("status", HttpStatus.UNAUTHORIZED.value());
        problem.put("detail", DETAIL);
        problem.put("instance", request.getRequestURI());
        problem.put("errorCode", ErrorCodes.UNAUTHORIZED);
        problem.put("category", ErrorCategory.AUTHENTICATION.name());
        problem.put("retryable", false);
        return problem;
    }
}
