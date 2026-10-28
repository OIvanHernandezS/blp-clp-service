package com.liverpool.ms_clp_blp.shared.errors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * Single translation point from domain exceptions to RFC 7807 {@link ProblemDetail} HTTP responses
 * (Rule 12 — never throw unchecked exceptions for recoverable business errors; no per-type logic in
 * HTTP adapters).
 *
 * <p>All domain exception semantics (status code, error code, category, retryability) are carried
 * on the exception itself so this class adds no business logic — it only translates. Logging is
 * centralised here (log-once at the highest layer, Rule 11).</p>
 *
 * <h3>Handler order:</h3>
 * <ol>
 *   <li>{@link DynamicBlockServiceUnavailableException} — most specific; includes block context.</li>
 *   <li>{@link ClpBlpException} — covers all other domain errors.</li>
 *   <li>{@link HandlerMethodValidationException} — Spring 6+ validation failures on path/query params.</li>
 *   <li>{@link Exception} — catch-all; always 500, never leaks internal detail.</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROP_ERROR_CODE = "errorCode";
    private static final String PROP_CATEGORY = "category";
    private static final String PROP_RETRYABLE = "retryable";
    private static final String PROP_BLOCK_ID = "blockId";
    private static final String PROP_BLOCK_TYPE = "blockType";

    /**
     * Handles dynamic-block service failures, adding block context so the frontend can target the
     * exact placeholder that failed without affecting the rest of the page render.
     *
     * @param ex      the block-level service failure
     * @param request the inbound request (for logging context)
     * @return 502 ProblemDetail with blockId and blockType properties
     */
    @ExceptionHandler(DynamicBlockServiceUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleDynamicBlockUnavailable(
            DynamicBlockServiceUnavailableException ex, HttpServletRequest request) {
        logDomainError(ex, request);

        ProblemDetail problem = buildBaseProblem(ex);
        // Omitted rather than null for a block addressed by session alone (container_greeting): the
        // frontend targets that placeholder by type, and a null property reads as a lost id.
        if (ex.getBlockId() != null) {
            problem.setProperty(PROP_BLOCK_ID, ex.getBlockId());
        }
        problem.setProperty(PROP_BLOCK_TYPE, ex.getBlockType());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    /**
     * Handles all other domain exceptions (validation, not-found, content-service unavailable,
     * circuit-breaker open). The HTTP status, error code, category, and retryability are taken
     * directly from the exception — no switch/if chain needed here (Rule 12).
     *
     * @param ex      any {@link ClpBlpException} not handled by a more specific method
     * @param request the inbound request (for logging context)
     * @return ProblemDetail with the exception's status and properties
     */
    @ExceptionHandler(ClpBlpException.class)
    public ResponseEntity<ProblemDetail> handleHomeException(ClpBlpException ex, HttpServletRequest request) {
        logDomainError(ex, request);
        return ResponseEntity.status(ex.getStatus()).body(buildBaseProblem(ex));
    }

    /**
     * Handles Spring MVC method-level validation failures ({@code @Validated} on controller +
     * {@code @NotBlank}/{@code @Size} on path/query params). Always returns HTTP 400.
     *
     * @param ex      the validation failure
     * @param request the inbound request (for logging context)
     * @return 400 ProblemDetail with a stable validation error code
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        logValidationFailure(ex, request);
        return ResponseEntity.badRequest().body(buildValidationProblem());
    }

    /**
     * Handles bean-validation constraint violations raised by the AOP {@code MethodValidationInterceptor}
     * ({@code @Validated} on Spring beans). Always returns HTTP 400.
     *
     * @param ex      the constraint violation
     * @param request the inbound request (for logging context)
     * @return 400 ProblemDetail with a stable validation error code
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        logValidationFailure(ex, request);
        return ResponseEntity.badRequest().body(buildValidationProblem());
    }

    /**
     * Catch-all for any unhandled exception. Logs at ERROR level and returns a generic 500 without
     * leaking internal implementation details to the client (security — Rule 12).
     *
     * @param ex      the unhandled throwable
     * @param request the inbound request (for logging context)
     * @return 500 ProblemDetail with a stable, generic error code
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        LogEvents.at(log, LogEvents.UNEXPECTED_ERROR)
                .addKeyValue(LogFields.URI, request.getRequestURI())
                .addKeyValue(LogFields.ERROR_CLASS, ex.getClass().getSimpleName())
                .addKeyValue(LogFields.CAUSE_CHAIN, Causes.chain(ex))
                .setCause(ex)
                .log("Unhandled exception reached the HTTP boundary; this is a defect in ms-clp-blp");

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle(ErrorCodes.UNEXPECTED_ERROR);
        problem.setDetail("An unexpected error occurred. Please try again later.");
        problem.setProperty(PROP_ERROR_CODE, ErrorCodes.UNEXPECTED_ERROR);
        problem.setProperty(PROP_RETRYABLE, false);
        return ResponseEntity.internalServerError().body(problem);
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Emits the one log line for a domain failure, with every field the exception knows about.
     *
     * <p>The subtype's own fields arrive through {@link ClpBlpException#context()}, so adding a new
     * exception type never requires editing this method — the {@code instanceof} chain this class
     * exists to avoid does not creep back in through logging.</p>
     *
     * <p>A 5xx logs at ERROR, a 4xx at WARN: the caller sent a bad request in one case and we
     * failed in the other. The technical detail goes on both — on a 4xx it is the only thing that
     * names the brand/locale and quotes the upstream body, which is what makes a
     * {@code BRAND_NOT_SUPPORTED} or a {@code *_NOT_FOUND} diagnosable without attaching a debugger
     * to production.</p>
     */
    private void logDomainError(ClpBlpException ex, HttpServletRequest request) {
        Level level = ex.getStatus() >= HttpStatus.INTERNAL_SERVER_ERROR.value() ? Level.ERROR : Level.WARN;
        LoggingEventBuilder builder = LogEvents.at(log, LogEvents.DOMAIN_ERROR, level)
                .addKeyValue(LogFields.URI, request.getRequestURI())
                .addKeyValue(LogFields.ERROR_CODE, ex.getErrorCode())
                .addKeyValue(LogFields.CATEGORY, ex.getCategory())
                .addKeyValue(LogFields.HTTP_STATUS, ex.getStatus())
                .addKeyValue(LogFields.RETRYABLE, ex.isRetryable())
                .addKeyValue(LogFields.DETAIL, ex.getDetail())
                .addKeyValue(LogFields.CAUSE_CHAIN, Causes.chain(ex.getCause()));
        ex.context().forEach(builder::addKeyValue);
        builder.log(ex.getMessage());
    }

    private void logValidationFailure(Exception ex, HttpServletRequest request) {
        LogEvents.at(log, LogEvents.VALIDATION_FAILED)
                .addKeyValue(LogFields.URI, request.getRequestURI())
                .addKeyValue(LogFields.ERROR_CLASS, ex.getClass().getSimpleName())
                .addKeyValue(LogFields.DETAIL, ex.getMessage())
                .log("Request rejected by validation");
    }

    private ProblemDetail buildValidationProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle(ErrorCodes.VALIDATION_ERROR);
        problem.setDetail("Request validation failed. Check the supplied parameters.");
        problem.setProperty(PROP_ERROR_CODE, ErrorCodes.VALIDATION_ERROR);
        problem.setProperty(PROP_RETRYABLE, false);
        return problem;
    }

    /**
     * Builds a {@link ProblemDetail} populated with the standard fields common to all
     * {@link ClpBlpException} types. The consumer-facing message (not the technical detail) is
     * used as the RFC 7807 {@code detail} field.
     */
    private ProblemDetail buildBaseProblem(ClpBlpException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(ex.getStatus());
        problem.setTitle(ex.getErrorCode());
        problem.setDetail(ex.getMessage());
        problem.setProperty(PROP_ERROR_CODE, ex.getErrorCode());
        problem.setProperty(PROP_CATEGORY, ex.getCategory().name());
        problem.setProperty(PROP_RETRYABLE, ex.isRetryable());
        return problem;
    }
}
