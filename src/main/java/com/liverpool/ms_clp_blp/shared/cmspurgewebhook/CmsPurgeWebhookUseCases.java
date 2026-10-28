package com.liverpool.ms_clp_blp.shared.cmspurgewebhook;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.liverpool.ms_clp_blp.shared.cachepurge.CachePurgeProperties;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeAction;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeNetwork;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeReport;
import com.liverpool.ms_clp_blp.shared.cachepurge.ports.CachePurgeService;
import com.liverpool.ms_clp_blp.shared.cmspurgewebhook.models.CmsPurgeWebhookResult;
import com.liverpool.ms_clp_blp.shared.cmspurgewebhook.ports.CmsPurgeWebhookService;
import com.liverpool.ms_clp_blp.shared.errors.Causes;
import com.liverpool.ms_clp_blp.shared.errors.ClpBlpException;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * Decides whether a Contentstack delivery warrants purging the edge, and purges it through the same
 * port {@code POST /ops/cache-purge} drives.
 *
 * <p>The purge itself is not reimplemented: {@link CachePurgeService#purgeAll} already knows which
 * URLs and cache tags ms-clp-blp owns and how to submit them. Landing pages live at one URL per
 * category, so no fixed URL list can cover them: the {@code Edge-Cache-Tag} ({@code ms-clp-blp}) is
 * what invalidates every cached landing page in one call, and any configured paths go with it. What this class adds is the policy an operator supplies by
 * hand at the ops endpoint — which deliveries matter, what happens when one fails, and how a bulk
 * publish is stopped from becoming a burst of identical purges.</p>
 *
 * <p><strong>Nothing escapes.</strong> Every exit is a {@link CmsPurgeWebhookResult}, because the only
 * status this webhook may answer is 200: Contentstack retries every non-2xx, and a purge that Akamai
 * refuses would otherwise be retried until the delivery expires — which neither fixes the credential
 * nor stops at a sensible number of attempts.</p>
 *
 * <p><strong>One line per delivery.</strong> The failure line here is not a duplicate of the
 * {@code CACHE_PURGE_FAILED} that {@code AkamaiCachePurgeAdapter} emits: that one carries the HTTP
 * status, the elapsed time and Akamai's ids, and knows nothing about why the purge was attempted; this
 * one carries the content type, the event and the entry, and is the only line at all when the failure
 * never reached Akamai (an unconfigured credential).</p>
 */
@Service
public class CmsPurgeWebhookUseCases implements CmsPurgeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(CmsPurgeWebhookUseCases.class);

    private final CachePurgeService cachePurgeService;
    private final CmsPurgeWebhookProperties properties;
    private final CachePurgeProperties cachePurgeProperties;
    private final Clock clock;

    /**
     * When the last purge was submitted, or {@code null} if none has been. Per-instance: on a fleet of
     * N instances a burst can still produce up to N purges, which is accepted — purging more than
     * necessary is correct, it only spends Fast Purge quota.
     */
    private final AtomicReference<Instant> lastPurgeAt = new AtomicReference<>();

    /**
     * Marked {@code @Autowired} because the class has two constructors: without it Spring cannot pick
     * one, falls back to a default constructor that does not exist, and every context fails to start.
     *
     * @param cachePurgeService    the same inbound port the operator endpoint drives
     * @param properties           which content types trigger a purge, and with what options
     * @param cachePurgeProperties read only for the service-wide {@code cache-purge.enabled} switch
     */
    @Autowired
    public CmsPurgeWebhookUseCases(CachePurgeService cachePurgeService,
                                   CmsPurgeWebhookProperties properties,
                                   CachePurgeProperties cachePurgeProperties) {
        this(cachePurgeService, properties, cachePurgeProperties, Clock.systemUTC());
    }

    /**
     * Test seam for the cooldown, which is otherwise only observable by waiting.
     *
     * @param cachePurgeService    the purge port
     * @param properties           trigger configuration
     * @param cachePurgeProperties service-wide purge configuration
     * @param clock                the clock the cooldown is measured against
     */
    CmsPurgeWebhookUseCases(CachePurgeService cachePurgeService,
                            CmsPurgeWebhookProperties properties,
                            CachePurgeProperties cachePurgeProperties,
                            Clock clock) {
        this.cachePurgeService = cachePurgeService;
        this.properties = properties;
        this.cachePurgeProperties = cachePurgeProperties;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CmsPurgeWebhookResult apply(Map<String, Object> payload) {
        String contentType = ContentstackPayload.contentTypeUid(payload);
        String event = ContentstackPayload.event(payload);

        String rejection = reasonToSkip(contentType);
        if (rejection != null) {
            return ignored(payload, contentType, event, rejection);
        }
        if (!reserveCooldownSlot()) {
            return ignored(payload, contentType, event, "A purge was submitted less than "
                    + properties.purgeCooldown().toSeconds() + "s ago and already covers this change");
        }
        return purge(payload, contentType, event);
    }

    /**
     * Why this delivery should not purge anything, if it should not.
     *
     * <p>Every event purges — a publish, an unpublish and a delete all leave the edge holding content
     * that no longer reflects the CMS, and the second two are the more visible of the three.</p>
     *
     * @param contentType the delivery's content type, possibly {@code null}
     * @return the reason to skip, or {@code null} when the delivery is actionable
     */
    private String reasonToSkip(String contentType) {
        if (!properties.enabled()) {
            return "CMS-triggered cache purge is switched off (cms-purge-webhook.enabled)";
        }
        if (!cachePurgeProperties.enabled()) {
            return "Cache purge is switched off on this service (cache-purge.enabled)";
        }
        if (contentType == null) {
            return "Delivery carried no content type";
        }
        if (!properties.handles(contentType)) {
            return "Content type '" + contentType + "' does not front any cached ms-clp-blp endpoint; "
                    + "the ones that do are " + properties.purgeableContentTypes();
        }
        return null;
    }

    /**
     * Submits the purge and reports it, translating any failure into an outcome rather than an error.
     *
     * @param payload     the delivery, for the log line's correlation fields
     * @param contentType the content type that triggered the purge
     * @param event       the Contentstack event
     * @return what happened
     */
    private CmsPurgeWebhookResult purge(Map<String, Object> payload, String contentType, String event) {
        PurgeAction action = properties.purgeAction();
        PurgeNetwork network = properties.purgeNetwork();
        try {
            PurgeReport report = cachePurgeService.purgeAll(action, network);
            int objectCount = report.totalObjects();
            correlated(LogEvents.at(log, LogEvents.CMS_PURGE_TRIGGERED), payload, contentType, event)
                    .addKeyValue(LogFields.PURGE_ACTION, action.name())
                    .addKeyValue(LogFields.PURGE_NETWORK, network.name())
                    .addKeyValue(LogFields.PURGE_OBJECT_COUNT, objectCount)
                    .log("A Contentstack publish triggered an edge-cache purge");
            return CmsPurgeWebhookResult.purged(contentType, event, action, network, objectCount);
        } catch (ClpBlpException e) {
            return failed(payload, contentType, event, action, network, e, LogEvents.CMS_PURGE_FAILED.level());
        } catch (RuntimeException e) {
            // Not one of our typed failures, so this is a defect here rather than an upstream outage —
            // the one case the level policy reserves a stack trace for.
            return failed(payload, contentType, event, action, network, e, Level.ERROR);
        }
    }

    private CmsPurgeWebhookResult failed(Map<String, Object> payload, String contentType, String event,
                                         PurgeAction action, PurgeNetwork network, RuntimeException e,
                                         Level level) {
        String detail = e instanceof ClpBlpException domain ? domain.getDetail() : Causes.rootMessage(e);
        LoggingEventBuilder builder = correlated(
                LogEvents.at(log, LogEvents.CMS_PURGE_FAILED, level), payload, contentType, event)
                .addKeyValue(LogFields.PURGE_ACTION, action.name())
                .addKeyValue(LogFields.PURGE_NETWORK, network.name())
                .addKeyValue(LogFields.ERROR_CLASS, e.getClass().getSimpleName())
                .addKeyValue(LogFields.CAUSE_CHAIN, Causes.chain(e))
                .addKeyValue(LogFields.DETAIL, detail);
        if (level == Level.ERROR) {
            builder = builder.setCause(e);
        }
        builder.log("A Contentstack publish could not purge the edge; it still serves the old content");
        return CmsPurgeWebhookResult.failed(contentType, event, action, network, e.getMessage());
    }

    private CmsPurgeWebhookResult ignored(Map<String, Object> payload, String contentType, String event,
                                          String reason) {
        correlated(LogEvents.at(log, LogEvents.CMS_PURGE_IGNORED), payload, contentType, event)
                .addKeyValue(LogFields.REASON, reason)
                .log("Contentstack delivery did not trigger an edge-cache purge");
        return CmsPurgeWebhookResult.ignored(contentType, event, reason);
    }

    /**
     * Attaches the fields that tie a line back to the entry an editor saved.
     *
     * @param builder     the line being built
     * @param payload     the delivery
     * @param contentType its content type
     * @param event       its event
     * @return the same builder, for chaining
     */
    private static LoggingEventBuilder correlated(
            LoggingEventBuilder builder, Map<String, Object> payload, String contentType, String event) {
        return builder
                .addKeyValue(LogFields.CONTENT_TYPE, contentType)
                .addKeyValue(LogFields.CMS_EVENT, event)
                .addKeyValue(LogFields.ENTRY_UID, ContentstackPayload.entryUid(payload))
                .addKeyValue(LogFields.LOCALE, ContentstackPayload.locale(payload));
    }

    /**
     * Claims the right to purge, if the cooldown has elapsed.
     *
     * <p>Publishing a template with many blocks makes Contentstack deliver one webhook per entry, and
     * each would otherwise submit an identical purge. The slot is claimed with a compare-and-set so
     * that concurrent deliveries cannot both win it.</p>
     *
     * <p>A failed purge still consumes the slot. That is the better of the two mistakes: a CDN that is
     * rate limiting or rejecting the credential should not then be hit once per entry.</p>
     *
     * @return {@code true} when this delivery may purge, {@code false} when a recent purge covers it
     */
    private boolean reserveCooldownSlot() {
        Duration cooldown = properties.purgeCooldown();
        Instant now = clock.instant();
        if (cooldown.isZero()) {
            lastPurgeAt.set(now);
            return true;
        }
        while (true) {
            Instant last = lastPurgeAt.get();
            if (last != null && Duration.between(last, now).compareTo(cooldown) < 0) {
                return false;
            }
            if (lastPurgeAt.compareAndSet(last, now)) {
                return true;
            }
        }
    }
}
