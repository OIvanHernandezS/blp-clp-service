package com.liverpool.ms_clp_blp.landing.brandflags.adapters.contentstack;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.clients.contentstackdirect.ContentStackDirectGateway;
import com.liverpool.ms_clp_blp.landing.brandflags.AuthoredFlagsReader;
import com.liverpool.ms_clp_blp.landing.brandflags.BrandFlagsProperties;
import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlags;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsContentPort;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * Reads a brand's authored feature flags straight from Contentstack.
 *
 * <p>Finds the entry the same way every other Liverpool service does: by an {@code identifier} field
 * shaped {@code <contentType>_<BRAND>} (see {@code ContentStackServiceImpl#getEntryByIdentifier} in
 * platform-commons). A stack may hold one entry per brand, so the brand is a query, not a path.</p>
 *
 * <p>Two shapes of leniency are deliberate, because the alternative is a configuration change that
 * silently does nothing:</p>
 * <ul>
 *   <li><strong>Key spelling.</strong> Flag ids are kebab-case ({@code container-shortcuts}) but
 *       Contentstack field uids cannot contain hyphens, so the CMS spells them
 *       {@code container_shortcuts}. Both are accepted.</li>
 *   <li><strong>Entry layout.</strong> The flags may sit in a {@code flags} group or directly on the
 *       entry. Both are read.</li>
 * </ul>
 *
 * <p>Anything not in {@link LandingFeatureFlags#ALL} is ignored, so an editor adding a field can never
 * invent a flag. Unlike the three request-path adapters, this one calls the gateway directly rather
 * than going through {@code ContentSourceRouter}: it carries no circuit breaker and no backup tier,
 * because it is off the request path and already degrades safely — a failure means "no overrides
 * captured" and the previously stored configuration stands, so there is nothing a second source would
 * rescue. Feeding its failures to the shared breaker would let a brand-flags outage trip the breaker
 * that {@code GET /blp|clp/**} depends on.</p>
 */
@Component
public class BrandFlagsContentClient implements BrandFlagsContentPort {

    private static final Logger log = LoggerFactory.getLogger(BrandFlagsContentClient.class);

    private static final String UPSTREAM = "contentstack";

    /** Field the entries are matched on, mirroring platform-commons' convention. */
    static final String QUERY_FIELD_IDENTIFIER = "identifier";

    private static final String KEY_UID = "uid";

    /** Log field: the identifier the query matched on. */
    private static final String IDENTIFIER = "identifier";

    /** Log field: how many recognised flags the entry yielded. */
    private static final String FLAG_COUNT = "flagCount";

    private final ContentStackDirectGateway gateway;
    private final BrandFlagsProperties properties;

    /**
     * @param gateway    the Contentstack Delivery API gateway
     * @param properties supplies the content type and the locale segment
     */
    public BrandFlagsContentClient(ContentStackDirectGateway gateway,
                                   BrandFlagsProperties properties) {
        this.gateway = gateway;
        this.properties = properties;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<BrandFlags> fetchForBrand(String brand) {
        if (brand == null || brand.isBlank()) {
            return Optional.empty();
        }
        String normalized = brand.trim().toUpperCase(Locale.ROOT);
        String identifier = properties.contentType() + "_" + normalized;
        try {
            Map<String, Object> entry = gateway.fetchByField(
                    properties.contentType(), properties.locale(),
                    QUERY_FIELD_IDENTIFIER, identifier, normalized).orElse(null);
            if (entry == null) {
                return logAndEmpty(normalized, identifier, "No entry matched the brand identifier");
            }
            Map<String, Boolean> flags = AuthoredFlagsReader.read(entry);
            if (flags.isEmpty()) {
                // An entry that yields nothing recognisable is treated as "nothing to say", not as
                // "turn everything off". Writing an empty document here would wipe a brand's
                // configuration on a mis-authored publish.
                return logAndEmpty(normalized, identifier, "Entry declared no recognised feature flag");
            }
            LogEvents.at(log, LogEvents.CONTENT_SERVED)
                    .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                    .addKeyValue(LogFields.BRAND, normalized)
                    .addKeyValue(IDENTIFIER, identifier)
                    .addKeyValue(FLAG_COUNT, flags.size())
                    .log("Per-brand feature flags fetched from Contentstack");
            return Optional.of(new BrandFlags(normalized, flags, Instant.now(), stringValue(entry, KEY_UID)));
        } catch (RuntimeException e) {
            LogEvents.at(log, LogEvents.UPSTREAM_CALL_FAILED)
                    .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                    .addKeyValue(LogFields.BRAND, normalized)
                    .addKeyValue(IDENTIFIER, identifier)
                    .addKeyValue(LogFields.ERROR_CLASS, e.getClass().getSimpleName())
                    .setCause(e)
                    .log("Could not read per-brand feature flags from Contentstack");
            return Optional.empty();
        }
    }

    private static String stringValue(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        return value == null ? null : value.toString();
    }

    private Optional<BrandFlags> logAndEmpty(String brand, String identifier, String message) {
        LogEvents.at(log, LogEvents.BRAND_FLAGS_SYNC_IGNORED)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(LogFields.BRAND, brand)
                .addKeyValue(IDENTIFIER, identifier)
                .log(message);
        return Optional.empty();
    }
}
