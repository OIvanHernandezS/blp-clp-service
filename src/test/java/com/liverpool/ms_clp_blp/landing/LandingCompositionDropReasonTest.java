package com.liverpool.ms_clp_blp.landing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import com.liverpool.ms_clp_blp.landing.models.BlockDefinition;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.BlockResolution;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionCatalog;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.LandingDefinition;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.landing.ports.FeatureFlagPort;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Pins the observability of a dropped block: every rule that removes a block from the page must say
 * which rule it was, and the request summary must agree with what was actually served.
 *
 * <p>Before this existed, composition dropped blocks with a bare {@code continue} — a block missing
 * in production could only be explained by reading the source and guessing among the rules.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LandingCompositionDropReasonTest {

    private static final String FLAG_VERTEX = "vertex";
    private static final SessionContext GUEST_WEB = new SessionContext(false, "LP", "WEB", "es-mx");
    private static final SessionContext AUTH_WEB = new SessionContext(true, "LP", "WEB", "es-mx");
    private static final SessionContext AUTH_APPS = new SessionContext(true, "LP", "APPS", "es-mx");

    @Mock
    private FeatureFlagPort featureFlagPort;

    private LandingCompositionService service;
    private ListAppender<ILoggingEvent> appender;
    private Logger compositionLogger;

    @BeforeEach
    void setUp() {
        BlockResolutionCatalog catalog = new BlockResolutionCatalog(Map.of(
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.VERTEX),
                BlockResolution.batch("/blp/blocks/products-list", FLAG_VERTEX)));
        service = new LandingCompositionService(featureFlagPort, catalog);
        when(featureFlagPort.isEnabled(anyString(), any())).thenReturn(true);

        compositionLogger = (Logger) LoggerFactory.getLogger(LandingCompositionService.class);
        compositionLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        compositionLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        compositionLogger.detachAppender(appender);
        compositionLogger.setLevel(null);
    }

    @Test
    void audienceMismatch_isReportedAsItsOwnReason() {
        compose(new BlockDefinition("b1", "container", "logged", true, true, Map.of()), GUEST_WEB);

        assertThat(dropReasons()).containsExactly(BlockDropReason.AUDIENCE_MISMATCH.name());
    }

    @Test
    void channelDisabled_isDistinguishedFromAudience() {
        // Both guards used to live in one isVisible() method, so a block hidden by channel and one
        // hidden by audience were indistinguishable after the fact.
        compose(new BlockDefinition("b1", "container", null, false, true, Map.of()), GUEST_WEB);

        assertThat(dropReasons()).containsExactly(BlockDropReason.CHANNEL_DISABLED.name());
    }

    @Test
    void expiredCountdown_isReported() {
        Map<String, Object> attributes = Map.of(
                "timer", Instant.now().minus(1, ChronoUnit.HOURS).toString());
        compose(new BlockDefinition("b1", "countdown", null, true, true, attributes), GUEST_WEB);

        assertThat(dropReasons()).containsExactly(BlockDropReason.COUNTDOWN_EXPIRED.name());
    }

    @Test
    void guestOnlyBlockOnAuthenticatedSession_isReported() {
        compose(new BlockDefinition("b1", "container_guest", null, true, true, Map.of()), AUTH_WEB);

        assertThat(dropReasons()).containsExactly(BlockDropReason.GUEST_ONLY_FOR_AUTHENTICATED.name());
    }

    @Test
    void dropLineCarriesTheBlockItIsAbout() {
        compose(new BlockDefinition("blt-42", "container_guest", null, true, true, Map.of()), AUTH_WEB);

        ILoggingEvent dropped = eventsFor(LogEvents.BLOCK_DROPPED).getFirst();
        assertThat(keyValues(dropped))
                .containsEntry(LogFields.BLOCK_ID, "blt-42")
                .containsEntry(LogFields.BLOCK_TYPE, "container_guest")
                .containsEntry(LogFields.AUTHENTICATED, true);
    }

    @Test
    void summaryReportsWhatWasServedAndWhyTheRestWasNot() {
        LandingDefinition definition = LandingDefinition.ofBlocks(PageType.BLP, "es-mx", List.of(
                new BlockDefinition("kept", "container", null, true, true, Map.of()),
                new BlockDefinition("guest-only", "container_guest", null, true, true, Map.of()),
                new BlockDefinition("guest-only-banner", "container", "guest", true, true, Map.of())));

        assertThat(service.compose(definition, AUTH_WEB).blocks()).hasSize(1);

        Map<String, Object> summary = keyValues(eventsFor(LogEvents.LANDING_COMPOSED).getFirst());
        assertThat(summary)
                .containsEntry(LogFields.PAGE_TYPE, PageType.BLP)
                .containsEntry(LogFields.BLOCKS_TOTAL, 3)
                .containsEntry(LogFields.BLOCKS_SERVED, 1)
                .containsEntry(LogFields.BLOCKS_DROPPED, 2)
                .containsEntry(LogFields.BRAND, "LP")
                .containsEntry(LogFields.AUTHENTICATED, true);
        assertThat(summary.get(LogFields.DROPS_BY_REASON)).isEqualTo(Map.of(
                BlockDropReason.GUEST_ONLY_FOR_AUTHENTICATED.name(), 1L,
                BlockDropReason.AUDIENCE_MISMATCH.name(), 1L));
    }

    @Test
    void summaryIsTheOnlyInfoLine_soTheHotPathStaysCheap() {
        compose(new BlockDefinition("b1", "container", null, true, true, Map.of()), GUEST_WEB);

        assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.INFO).toList())
                .singleElement()
                .satisfies(e -> assertThat(keyValues(e))
                        .containsEntry(LogFields.EVENT, LogEvents.LANDING_COMPOSED.name()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private void compose(BlockDefinition block, SessionContext session) {
        service.compose(LandingDefinition.ofBlocks(PageType.BLP, "es-mx", List.of(block)), session);
    }

    private List<String> dropReasons() {
        return eventsFor(LogEvents.BLOCK_DROPPED).stream()
                .map(event -> String.valueOf(keyValues(event).get(LogFields.REASON)))
                .toList();
    }

    private List<ILoggingEvent> eventsFor(LogEvents event) {
        return appender.list.stream()
                .filter(e -> event.name().equals(keyValues(e).get(LogFields.EVENT)))
                .toList();
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        return pairs == null ? Map.of() : pairs.stream()
                .collect(java.util.stream.Collectors.toMap(p -> p.key, p -> p.value));
    }
}
