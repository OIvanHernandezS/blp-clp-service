package com.liverpool.ms_clp_blp.clients.contentstackdirect;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.liverpool.clients.ContentStackClient;
import com.liverpool.ms_clp_blp.shared.errors.ContentstackEntryNotFoundException;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * Reads CMS entries straight from the Contentstack Delivery API, bypassing the content-service proxy.
 *
 * <p>The second source behind {@code ContentSourceRouter}. It returns payloads in the proxy's own
 * shape, so nothing downstream can tell which source answered — see
 * {@link ContentStackPayloadNormalizer} for why that equivalence is the design.</p>
 *
 * <p>The include parameters mirror the proxy's — embedded items and locale fallback — so a page
 * assembled here isn't subtly different from the one the proxy would have served, with one
 * deliberate exception: {@link #INCLUDE_ALL_DEPTH} is capped to the Delivery API's enforced maximum,
 * even for content types where the proxy still requests more and gets rejected. This gateway existing
 * to survive a proxy failure while requesting the exact parameter that fails is a bug, not a design
 * intent worth preserving.</p>
 */
@Component
public class ContentStackDirectGateway {

    /** Groups this class's log lines with the Feign transport events for the same system. */
    static final String UPSTREAM = "contentstack-direct";

    /**
     * Reference depth requested from Contentstack. Capped at the Delivery API's enforced maximum —
     * requesting more (this gateway used to send 5) is rejected outright with a 422
     * ({@code include_depth: should not be greater than 3}), which is what took the fallback down
     * alongside the proxy during the 2026-08-28 outage instead of covering for it.
     */
    static final Integer INCLUDE_ALL_DEPTH = 3;

    /** Embedded-items scope, matching the SDK's {@code includeEmbeddedItems()}. */
    static final String EMBEDDED_ITEMS_BASE = "BASE";

    private static final Integer SINGLE_ENTRY = 1;
    private static final String KEY_ENTRIES = "entries";

    private static final Logger log = LoggerFactory.getLogger(ContentStackDirectGateway.class);

    private final ContentStackClient client;
    private final ContentStackStackResolver stackResolver;
    private final ContentStackQueryBuilder queryBuilder;
    private final ContentStackPayloadNormalizer payloadNormalizer;

    /**
     * @param client            the Contentstack Delivery API client
     * @param stackResolver     maps a brand onto its stack and credentials
     * @param queryBuilder      builds the entry-selection query
     * @param payloadNormalizer reshapes an entry into the proxy's response shape
     */
    public ContentStackDirectGateway(ContentStackClient client,
            ContentStackStackResolver stackResolver,
            ContentStackQueryBuilder queryBuilder,
            ContentStackPayloadNormalizer payloadNormalizer) {
        this.client = client;
        this.stackResolver = stackResolver;
        this.queryBuilder = queryBuilder;
        this.payloadNormalizer = payloadNormalizer;
    }

    /**
     * Whether a brand can be served from Contentstack at all.
     *
     * @param brandHeader the {@code x-brand-id} value
     * @return {@code true} when the brand's stack is known and its credentials are configured
     */
    public boolean isAvailableFor(String brandHeader) {
        return stackResolver.resolve(brandHeader).isPresent();
    }

    /**
     * Explains a {@code false} from {@link #isAvailableFor}.
     *
     * @param brandHeader the {@code x-brand-id} value
     * @return the reason, or empty when the brand is in fact servable
     */
    public Optional<FallbackUnavailableReason> unavailableReason(String brandHeader) {
        return stackResolver.unavailableReason(brandHeader);
    }

    /**
     * Fetches a landing page entry by the category it is authored for.
     *
     * <p>Landing pages are selected by {@code category_id}, never by {@code url}: the storefront URL's
     * slug is free text ({@code esteelauder} vs the authored {@code estee-lauder}) and only its last
     * segment is reliable (Rule 19 reference: content-service {@code getCategoryIdFromSlug}). The id is
     * matched as typed and lower-cased, because the CMS field is authored lower-case while a URL may
     * not be.</p>
     *
     * @param contentType Contentstack content-type uid, {@code page_blp} or {@code page_clp}
     * @param locale      locale to resolve the entry in
     * @param categoryId  the category id from the storefront URL
     * @param brandHeader the {@code x-brand-id} value
     * @return the entry, with its single-reference {@code header}/{@code footer} unwrapped
     * @throws ContentstackEntryNotFoundException when the stack holds no matching entry
     */
    public Map<String, Object> fetchCategoryPage(String contentType, String locale, String categoryId,
            String brandHeader) {
        ResolvedStack stack = requireStack(brandHeader);
        Map<String, Object> entry = payloadNormalizer.asProxyPage(fetchSingle(stack, contentType, locale,
                queryBuilder.categoryPageQuery(stack, categoryId), categoryId));
        logServed(stack, contentType, locale, brandHeader);
        return entry;
    }

    /**
     * Fetches the first entry whose {@code field} holds {@code value}, or empty when none does.
     *
     * <p>Replaces the content-service's generic entries-query route for the one caller that used it:
     * brand-flags, which finds a brand's entry by an {@code identifier} field rather than by path.
     * Unlike {@link #fetchCategoryPage} this returns the raw Contentstack entry
     * with no proxy-shape normalization — its caller reads authored fields straight off the entry —
     * and reports "nothing matched" as an empty {@link Optional} rather than throwing, because for a
     * brand that has published no overrides that is the ordinary outcome, not a failure.</p>
     *
     * @param contentType Contentstack content-type uid, e.g. {@code ms_clp_blp_flags}
     * @param locale      locale to resolve the entry in
     * @param field       the entry field to match on
     * @param value       the exact value that field must hold
     * @param brandHeader the {@code x-brand-id} value
     * @return the first matching entry, or empty when the query matched nothing
     * @throws IllegalStateException when the brand maps to no configured stack
     */
    public Optional<Map<String, Object>> fetchByField(String contentType, String locale,
            String field, String value, String brandHeader) {
        ResolvedStack stack = requireStack(brandHeader);
        Map<String, Object> response = body(client.getEntriesByQueryWithIncludes(
                contentType,
                stack.config().getBranch(), stack.config().getApiKey(), stack.config().getAccessToken(),
                stack.config().getEnvironment(), locale,
                queryBuilder.fieldQuery(stack, field, value),
                true, INCLUDE_ALL_DEPTH, true, true, EMBEDDED_ITEMS_BASE, SINGLE_ENTRY));
        List<Map<String, Object>> entries = entriesOf(response);
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
    }

    /**
     * Fetches the first entry matching every field condition, or empty when none does — the
     * multi-field form of {@link #fetchByField}, for lookups such as the active
     * {@code category_redirect} of a category ({@code source_category_id} and {@code is_active}).
     *
     * @param contentType Contentstack content-type uid
     * @param locale      locale to resolve the entry in
     * @param conditions  field → exact value; every condition must hold
     * @param brandHeader the {@code x-brand-id} value
     * @return the first matching raw entry, or empty when the query matched nothing
     * @throws IllegalStateException when the brand maps to no configured stack
     */
    public Optional<Map<String, Object>> fetchByFields(String contentType, String locale,
            Map<String, Object> conditions, String brandHeader) {
        ResolvedStack stack = requireStack(brandHeader);
        Map<String, Object> response = body(client.getEntriesByQueryWithIncludes(
                contentType,
                stack.config().getBranch(), stack.config().getApiKey(), stack.config().getAccessToken(),
                stack.config().getEnvironment(), locale,
                queryBuilder.fieldsQuery(stack, conditions),
                true, INCLUDE_ALL_DEPTH, true, true, EMBEDDED_ITEMS_BASE, SINGLE_ENTRY));
        List<Map<String, Object>> entries = entriesOf(response);
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
    }

    /**
     * Issues the query and takes the single entry the caller asked for.
     *
     * @param stack       the resolved stack
     * @param contentType Contentstack content-type uid
     * @param locale      locale to resolve the entry in
     * @param query       the selection query, {@code null} when no filtering is needed
     * @param slug        the requested url, for the not-found detail only
     * @return the first matching entry
     * @throws ContentstackEntryNotFoundException when nothing matched
     */
    private Map<String, Object> fetchSingle(ResolvedStack stack, String contentType, String locale,
            String query, String slug) {
        Map<String, Object> response = body(client.getEntriesByQueryWithIncludes(
                contentType,
                stack.config().getBranch(), stack.config().getApiKey(), stack.config().getAccessToken(),
                stack.config().getEnvironment(), locale, query,
                true, INCLUDE_ALL_DEPTH, true, true, EMBEDDED_ITEMS_BASE, SINGLE_ENTRY));
        List<Map<String, Object>> entries = entriesOf(response);
        if (entries.isEmpty()) {
            throw new ContentstackEntryNotFoundException(
                    "No Contentstack entry for contentType=" + contentType + " locale=" + locale,
                    "stack=" + stack.site() + " branch=" + stack.config().getBranch()
                            + " environment=" + stack.config().getEnvironment()
                            + " slug=" + slug + " query=" + query);
        }
        return entries.get(0);
    }

    /**
     * @param brandHeader the {@code x-brand-id} value
     * @return the resolved stack
     * @throws IllegalStateException when called for a brand {@link #isAvailableFor} rejects
     */
    private ResolvedStack requireStack(String brandHeader) {
        return stackResolver.resolve(brandHeader)
                .orElseThrow(() -> new IllegalStateException(
                        "No Contentstack stack configured for brand " + brandHeader));
    }

    /**
     * Records that content was served from the fallback, and from which branch.
     *
     * @param stack       the stack that answered
     * @param contentType Contentstack content-type uid
     * @param locale      locale the entry was resolved in
     * @param brandHeader the {@code x-brand-id} value
     */
    private static void logServed(ResolvedStack stack, String contentType, String locale, String brandHeader) {
        LogEvents.at(log, LogEvents.CMS_SOURCE_SERVED)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(LogFields.BRAND, brandHeader)
                .addKeyValue(LogFields.LOCALE, locale)
                .addKeyValue(LogFields.CONTENT_TYPE, contentType)
                .addKeyValue(LogFields.SOURCE, stack.site())
                .addKeyValue(LogFields.CMS_BRANCH, stack.config().getBranch())
                .addKeyValue(LogFields.CMS_ENVIRONMENT, stack.config().getEnvironment())
                .log("Served CMS content from Contentstack");
    }

    /**
     * @param response the client's response entity
     * @return its body, or {@code null}
     */
    static Map<String, Object> body(ResponseEntity<Map<String, Object>> response) {
        return response == null ? null : response.getBody();
    }

    /**
     * @param response a Contentstack entries response
     * @return the {@code entries} array as maps, empty when absent or malformed
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entriesOf(Map<String, Object> response) {
        if (response == null || !(response.get(KEY_ENTRIES) instanceof List<?> entries)) {
            return List.of();
        }
        return (List<Map<String, Object>>) entries;
    }
}
