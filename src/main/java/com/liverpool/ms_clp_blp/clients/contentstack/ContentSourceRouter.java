package com.liverpool.ms_clp_blp.clients.contentstack;

import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.clients.contentstackdirect.ContentStackDirectGateway;
import com.liverpool.ms_clp_blp.clients.contentstackdirect.FallbackUnavailableReason;
import com.liverpool.ms_clp_blp.shared.errors.BrandNotSupportedException;
import com.liverpool.ms_clp_blp.shared.errors.ClpBlpException;
import com.liverpool.ms_clp_blp.shared.errors.ContentServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.errors.ContentstackEntryNotFoundException;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;
import com.liverpool.ms_clp_blp.shared.web.CmsResponseMetadataContext;
import com.liverpool.ms_clp_blp.shared.web.ContentOrigin;

import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * The single entry point for reading CMS content, with Contentstack as the only live source.
 *
 * <p>The {@code content-service} proxy that used to front every read is gone (ADR-053): its cache, its
 * multi-tenant brand routing and its failure modes are no longer in the path, and this class talks to
 * the Contentstack Delivery API through {@link ContentStackDirectGateway} instead. The three slices
 * that consume CMS content share this one instance, which is what makes the breaker below measure the
 * health of Contentstack rather than the health of one endpoint.</p>
 *
 * <h3>One breaker per brand</h3>
 * <p>Every brand gets its own breaker (named {@code contentstack-<BRAND>}, see {@link #breakerName}),
 * created lazily on first use and cached by {@link CircuitBreakerRegistry} itself. This is what stops
 * one brand's outage from taking healthy brands down with it — the 2026-09-01 incident that motivated
 * it: a content type failing on every brand but one tripped a single shared breaker, and every other
 * brand's otherwise-healthy calls were then rejected as collateral damage without ever reaching
 * Contentstack. Splitting by brand does not go further than that: the content kinds (
 * GLOBAL_DATA, LOYALTY) for one brand still share that brand's breaker, so a kind failing permanently
 * for one brand can still open that brand's own breaker — a much smaller blast radius (one brand
 * instead of every brand), and the next line of defence for it.</p>
 *
 * <h3>Why the breaker records only some failures</h3>
 * <p>The predicate is inherited from the proxy's breaker (ADR-034/ADR-046) because the reasoning
 * survives the source change intact. One client serves every content kind for a brand, so an
 * unfiltered breaker aggregates the health of all of them for that brand. A kind with nothing
 * published fails permanently, and at a 5% failure-rate threshold that permanent failure would drive
 * the breaker open and take the brand's other, healthy kinds down with it.</p>
 *
 * <p>{@link #isPermanentFailure} draws the line: an unhosted brand (400), an unpublished entry (404),
 * and a content type this brand's stack was never given (422 — see {@link #callContentstack}) are all
 * facts about the caller's request, not about Contentstack's health, so the breaker ignores them. What
 * it still records — connection refused, timeouts, 5xx, 401/403 — is service-wide by nature.
 * {@code ClpBlpException.isRetryable()} already draws that exact line, so one predicate decides both
 * what the breaker records and what the caller sees.</p>
 *
 * <h3>What the caller sees</h3>
 * <p>Unchanged from when the proxy existed, deliberately: a permanent 400/404 propagates untouched,
 * and everything else is a 502 {@code CONTENT_SERVICE_UNAVAILABLE}. Removing a source may only change
 * where content comes from — it must never change the failure contract of the landing endpoints.</p>
 */
@Component
public class ContentSourceRouter {

    /** Prefix for a brand's Contentstack breaker name; suffixed with the normalized brand. */
    static final String CB_CONTENTSTACK_PREFIX = "contentstack-";

    private static final Logger log = LoggerFactory.getLogger(ContentSourceRouter.class);

    /** Matches the name the Feign transport events derive from the Contentstack client. */
    private static final String UPSTREAM = "contentstack";

    /** Contentstack's status for "the request is well-formed but this stack rejects it outright". */
    private static final int HTTP_UNPROCESSABLE_ENTITY = 422;

    private final ContentStackDirectGateway gateway;
    private final CircuitBreakerRegistry registry;
    private final CircuitBreakerConfig breakerConfig;

    /**
     * @param gateway  the Contentstack Delivery API gateway — the only live source
     * @param registry shared breaker registry supplying the tuned defaults
     */
    public ContentSourceRouter(ContentStackDirectGateway gateway, CircuitBreakerRegistry registry) {
        this.gateway = gateway;
        this.registry = registry;
        // Built once, reused by every brand's breaker: every CB_* tuning knob (threshold, window,
        // wait duration...) still comes from the registry default, identically for every brand; only
        // the record/ignore predicate is this breaker family's own.
        this.breakerConfig = CircuitBreakerConfig.from(registry.getDefaultConfig())
                .ignoreException(ContentSourceRouter::isPermanentFailure)
                .build();
    }

    /**
     * Reads one CMS entry from Contentstack.
     *
     * @param request what to read and for which brand
     * @return the raw entry, in the shape every slice's mapper expects
     * @throws BrandNotSupportedException         when no Contentstack stack hosts the brand (permanent)
     * @throws ContentstackEntryNotFoundException when the stack holds no matching entry, including
     *                                             when it was never given this content type (permanent)
     * @throws ContentServiceUnavailableException when Contentstack could not be read
     */
    public Map<String, Object> fetch(ContentSourceRequest request) {
        rejectUnservableBrand(request);
        CircuitBreaker breaker = breakerFor(request.brandHeader());
        try {
            Map<String, Object> body = breaker.executeSupplier(() -> callContentstack(request));
            CmsResponseMetadataContext.set(ContentOrigin.CONTENTSTACK);
            return body;
        } catch (CallNotPermittedException e) {
            LogEvents.at(log, LogEvents.CIRCUIT_BREAKER_OPEN)
                    .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                    .addKeyValue(LogFields.BREAKER, breaker.getName())
                    .addKeyValue(LogFields.BRAND, request.brandHeader())
                    .addKeyValue(LogFields.CONTENT_TYPE, request.contentType())
                    .log("Circuit breaker is open; Contentstack was not called");
            throw new ContentServiceUnavailableException(
                    "Failed to reach Contentstack.",
                    detail(request, "circuit breaker " + breaker.getName() + " is open"), e);
        }
    }

    /**
     * Resolves this brand's breaker, creating it on first use.
     *
     * <p>No separate cache is kept here: {@link CircuitBreakerRegistry#circuitBreaker(String,
     * CircuitBreakerConfig)} is itself a thread-safe, per-name cache, so asking it every time is both
     * simpler and keeps this class from ever disagreeing with the registry about what a brand's
     * breaker is.</p>
     *
     * @param brandHeader the {@code x-brand-id} value
     * @return that brand's breaker
     */
    private CircuitBreaker breakerFor(String brandHeader) {
        return registry.circuitBreaker(breakerName(brandHeader), breakerConfig);
    }

    /**
     * @param brandHeader the {@code x-brand-id} value
     * @return this brand's breaker name, e.g. {@code "contentstack-LP"}
     */
    static String breakerName(String brandHeader) {
        return CB_CONTENTSTACK_PREFIX + brandHeader.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Whether a failure says something about the caller's request rather than Contentstack's health.
     *
     * <p>ADR-034's incident is in this predicate: these are the failures that must not count towards
     * opening a breaker shared by every content kind for one brand.</p>
     *
     * @param throwable the failure to classify
     * @return {@code true} for a permanent, caller-scoped failure
     */
    static boolean isPermanentFailure(Throwable throwable) {
        return throwable instanceof ClpBlpException e && !e.isRetryable();
    }

    // ── the source ───────────────────────────────────────────────────────────────────────────────

    /**
     * Fails fast for a brand Contentstack cannot serve, before the breaker is involved.
     *
     * <p>An unmapped brand is the caller's mistake and keeps the 400 the proxy used to return for it.
     * Missing credentials are a deployment fault, not a caller error, so that one is a 502 — and it
     * stays outside the breaker either way, because neither is evidence about Contentstack's health.</p>
     *
     * @param request what is being read
     * @throws BrandNotSupportedException         when no stack hosts the brand
     * @throws ContentServiceUnavailableException when the brand's stack has no credentials configured
     */
    private void rejectUnservableBrand(ContentSourceRequest request) {
        FallbackUnavailableReason reason =
                gateway.unavailableReason(request.brandHeader()).orElse(null);
        if (reason == null) {
            return;
        }
        LogEvents.at(log, LogEvents.CMS_SOURCE_UNAVAILABLE)
                .addKeyValue(LogFields.BRAND, request.brandHeader())
                .addKeyValue(LogFields.CONTENT_TYPE, request.contentType())
                .addKeyValue(LogFields.REASON, reason)
                .log("Contentstack cannot serve this brand; no CMS read was attempted");
        if (reason == FallbackUnavailableReason.UNMAPPED_BRAND) {
            throw new BrandNotSupportedException(
                    "Brand '" + request.brandHeader() + "' is not hosted on Contentstack.",
                    detail(request, "no Contentstack stack maps to this brand"), null);
        }
        throw new ContentServiceUnavailableException(
                "Contentstack is not configured for this brand.",
                detail(request, "cannot read Contentstack (" + reason + ")"), null);
    }

    /**
     * Reads the entry, translating the gateway's failures into the domain's own vocabulary.
     *
     * @param request what to read
     * @return the raw entry
     */
    private Map<String, Object> callContentstack(ContentSourceRequest request) {
        try {
            Map<String, Object> body = gateway.fetchCategoryPage(
                    request.contentType(), request.locale(), request.categoryId(), request.brandHeader());
            if (body == null) {
                throw new ContentServiceUnavailableException(
                        "Contentstack returned an empty response.", detail(request, "null body"), null);
            }
            return body;
        } catch (ClpBlpException e) {
            // Already in the domain's vocabulary — including the authoritative 404 from the gateway,
            // which must reach the caller as a 404 rather than being reclassified as an outage.
            throw e;
        } catch (FeignException e) {
            if (e.status() == HTTP_UNPROCESSABLE_ENTITY) {
                // Contentstack rejected the request outright — most commonly a content type this
                // brand's stack was never given (e.g. `page_clp` on a stack that only hosts BLPs), but
                // the same status also covers e.g. an include_all_depth the API enforces a hard cap
                // on. Either way, retrying the identical request only ever gets the identical 422
                // back, which is exactly ContentstackEntryNotFoundException's contract — "no entry
                // answers this request" — so it reuses that exception rather than a new one, and every
                // slice's existing 404 translation and isPermanentFailure handle it for free.
                throw new ContentstackEntryNotFoundException(
                        "No Contentstack entry for contentType=" + request.contentType()
                                + " locale=" + request.locale(),
                        detail(request, "HTTP 422 | " + e.contentUTF8()));
            }
            throw new ContentServiceUnavailableException(
                    "Failed to reach Contentstack.",
                    detail(request, e.getClass().getSimpleName() + " | " + e.getMessage()), e);
        } catch (Exception e) {
            throw new ContentServiceUnavailableException(
                    "Failed to reach Contentstack.",
                    detail(request, e.getClass().getSimpleName() + " | " + e.getMessage()), e);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /**
     * @param request what was being read
     * @param cause   technical description of the failure
     * @return the developer-facing detail line
     */
    private static String detail(ContentSourceRequest request, String cause) {
        return "brand=" + request.brandHeader() + " locale=" + request.locale()
                + " contentType=" + request.contentType() + " categoryId=" + request.categoryId()
                + " | cause: " + cause;
    }
}
