package com.liverpool.ms_clp_blp.landing.adapters.rest.v1;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.liverpool.ms_clp_blp.landing.LandingProperties;
import com.liverpool.ms_clp_blp.landing.models.LandingPage;
import com.liverpool.ms_clp_blp.landing.models.LandingPageQuery;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.ports.LandingService;
import com.liverpool.ms_clp_blp.shared.constants.ClpBlpConstants;
import com.liverpool.ms_clp_blp.shared.errors.GlobalExceptionHandler;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;
import com.liverpool.ms_clp_blp.shared.session.SessionContextPort;
import com.liverpool.ms_clp_blp.shared.web.CmsResponseMetadataContext;
import com.liverpool.ms_clp_blp.shared.web.EdgeCacheControl;

import lombok.RequiredArgsConstructor;

/**
 * Inbound adapter for the landing page endpoints. Orchestrates the request lifecycle from HTTP inputs
 * to the domain use case and back to a JSON response (Rule 1 — dependencies point inward).
 *
 * <p><strong>Contract:</strong> {@code GET /blp/{locale}/**} and {@code GET /clp/{locale}/**} — the
 * same path digital_bff took after {@code /content/page-blp/} and {@code /content/page-clp/}, e.g.
 * {@code /blp/es-mx/tienda/esteelauder/catst26617625}.</p>
 *
 * <p>This controller is intentionally thin: it extracts inputs, delegates to the use case, and maps
 * the result. No business logic lives here (Rule 18). All domain exceptions are translated by
 * {@link GlobalExceptionHandler} to RFC 7807 {@code ProblemDetail} responses (Rule 12).</p>
 *
 * <h3>Request signals:</h3>
 * <ul>
 *   <li>Session (brand/channel/headers) — from upstream-validated headers via
 *       {@link SessionContextPort}, with no outbound call. Brand is the {@code x-brand-id} header,
 *       falling back to {@code cms.default-brand}.</li>
 *   <li>{@code x-is-logged-in} — the caller's login declaration; it decides the audience a block is
 *       filtered for and selects the edge cache slot.</li>
 *   <li>{@code favoriteStore} cookie — the store written into {@code selected_store.*} analytics
 *       values.</li>
 *   <li>Eager offset — optional query param overriding how many leading dynamic blocks resolve
 *       inline.</li>
 * </ul>
 *
 * <h3>Response signals:</h3>
 * <ul>
 *   <li>{@code x-request-id} — echoed from the inbound header or generated if absent.</li>
 *   <li>{@code x-data-origin} — which tier answered: {@code ContentStack} or {@code caffeine}.</li>
 *   <li>{@code Cache-Control} — {@code public} for the shareable variants, {@code no-store, private}
 *       otherwise; see {@link #edgeCacheable}.</li>
 * </ul>
 */
@RestController
@Validated
@RequiredArgsConstructor
public class LandingControllerV1 implements LandingApiV1 {

    private static final Logger log = LoggerFactory.getLogger(LandingControllerV1.class);

    /** Endpoint-local log field: the request input the composition summary cannot see. */
    private static final String EAGER_OFFSET = "eagerOffset";

    static final String HEADER_REQUEST_ID = "x-request-id";

    private final LandingService landingService;
    private final SessionContextPort sessionContextPort;
    private final LandingPageMapper landingPageMapper;
    /** Supplies the logged-in default eager offset, which decides whether a page is shareable. */
    private final LandingProperties landingProperties;

    /**
     * {@inheritDoc}
     */
    @GetMapping("/blp/{locale}/{*path}")
    @Override
    public ResponseEntity<LandingPageResponse> getBrandLandingPage(
            @PathVariable String locale,
            @PathVariable String path,
            @RequestParam(required = false) Integer eagerOffset,
            @CookieValue(name = ClpBlpConstants.COOKIE_FAVORITE_STORE, required = false) String favoriteStore,
            HttpServletRequest request) {
        return compose(PageType.BLP, locale, path, eagerOffset, favoriteStore);
    }

    /**
     * {@inheritDoc}
     */
    @GetMapping("/clp/{locale}/{*path}")
    @Override
    public ResponseEntity<LandingPageResponse> getCategoryLandingPage(
            @PathVariable String locale,
            @PathVariable String path,
            @RequestParam(required = false) Integer eagerOffset,
            @CookieValue(name = ClpBlpConstants.COOKIE_FAVORITE_STORE, required = false) String favoriteStore,
            HttpServletRequest request) {
        return compose(PageType.CLP, locale, path, eagerOffset, favoriteStore);
    }

    /**
     * Composes one landing page for the requesting session — the one code path both routes share.
     */
    private ResponseEntity<LandingPageResponse> compose(PageType pageType, String locale, String path,
                                                        Integer eagerOffset, String favoriteStore) {
        SessionContext session = resolveSession();

        // The request's outcome is reported once by LandingCompositionService (LANDING_COMPOSED), which
        // carries brand/channel/authenticated already. Only the request-shaping input it cannot see is
        // logged here, and at DEBUG.
        LogEvents.at(log, LogEvents.LANDING_COMPOSED, Level.DEBUG)
                .addKeyValue(LogFields.PAGE_TYPE, pageType)
                .addKeyValue(EAGER_OFFSET, eagerOffset)
                .log("Composing the landing page");

        LandingPageQuery query = new LandingPageQuery(pageType, locale, path, session, session.headers(),
                eagerOffset, favoriteStore);
        LandingPage page = landingService.getLandingPage(query);
        LandingPageResponse response = landingPageMapper.toResponse(page);

        return CmsResponseMetadataContext.attachTo(ResponseEntity.ok())
                .header(HEADER_REQUEST_ID, MDC.get("requestId"))
                .cacheControl(edgeCacheable(session, eagerOffset, favoriteStore)
                        ? EdgeCacheControl.SHARED
                        : EdgeCacheControl.PERSONAL)
                .body(response);
    }

    /**
     * Resolves the session for this request.
     *
     * <p>{@link SessionContextPort#currentContext()} resolves brand, channel, locale and headers with
     * no outbound call, and the caller's own {@link SessionContext#declaresLoggedIn()} declaration is
     * folded in as {@code authenticated}: a landing page only needs guest-vs-logged-in for block
     * visibility (ADR-067).</p>
     *
     * @return the session to compose the page with
     */
    private SessionContext resolveSession() {
        SessionContext session = sessionContextPort.currentContext();
        return session.declaresLoggedIn() ? session.withAuthenticated(true) : session;
    }

    /**
     * Whether the edge may share this page across callers of the same brand.
     *
     * <p>The rules are ms-home's, plus one. Shareable are the guest page and the signed-in
     * <em>skeleton</em> (eager offset {@code 0} with a {@code SessionId} cookie, so no resolver runs and
     * nothing personal is inlined). The extra rule: a request carrying a {@code favoriteStore} cookie is
     * never shareable, because its {@code selected_store.*} analytics values are that shopper's store —
     * an edge copy would report one shopper's store for every other.</p>
     *
     * @param session       the resolved session
     * @param eagerOffset   the caller's offset override, or {@code null} for the configured default
     * @param favoriteStore the {@code favoriteStore} cookie, or {@code null}
     * @return true when the response may be held by a shared cache
     */
    private boolean edgeCacheable(SessionContext session, Integer eagerOffset, String favoriteStore) {
        if (favoriteStore != null && !favoriteStore.isBlank()) {
            return false;
        }
        if (session.declaresGuest()) {
            return true;
        }
        if (!session.declaresLoggedIn()) {
            // No declaration: the edge has no slot for this request.
            return false;
        }
        int effective = eagerOffset != null
                ? Math.max(0, eagerOffset)
                : landingProperties.loggedEagerOffset();
        return session.sessionId() != null && effective == 0;
    }
}
