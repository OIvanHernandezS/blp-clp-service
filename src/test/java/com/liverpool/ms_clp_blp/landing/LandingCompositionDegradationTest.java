package com.liverpool.ms_clp_blp.landing;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import com.liverpool.ms_clp_blp.landing.models.BlockDefinition;
import com.liverpool.ms_clp_blp.landing.models.BlockResolution;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionCatalog;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionContext;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;
import com.liverpool.ms_clp_blp.landing.models.BlockResolverRegistry;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.LandingDefinition;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.models.ResolvedContent;
import com.liverpool.ms_clp_blp.landing.ports.BlockResolver;
import com.liverpool.ms_clp_blp.landing.ports.FeatureFlagPort;
import com.liverpool.ms_clp_blp.shared.errors.DynamicBlockServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCategory;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pins what a degraded block reports. The old line carried only {@code e.getCause().getMessage()} —
 * frequently {@code null} — so a Salesforce timeout, a 401 and a null pointer in our own mapper all
 * produced the same WARN.
 */
@ExtendWith(MockitoExtension.class)
class LandingCompositionDegradationTest {

    private static final String FLAG = "products-list-salesforce";
    private static final String ENDPOINT = "/blp/blocks/products-list";
    private static final SessionContext GUEST_WEB = new SessionContext(false, "LP", "WEB", "es-mx");

    @Mock
    private FeatureFlagPort featureFlagPort;

    private ListAppender<ILoggingEvent> appender;
    private Logger compositionLogger;

    @BeforeEach
    void setUp() {
        compositionLogger = (Logger) LoggerFactory.getLogger(LandingCompositionService.class);
        appender = new ListAppender<>();
        appender.start();
        compositionLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        compositionLogger.detachAppender(appender);
    }

    @Test
    void typedFailure_logsTheErrorCodeUpstreamAndStatusAtWarn() {
        DynamicBlockServiceUnavailableException failure = new DynamicBlockServiceUnavailableException(
                "pl-0", BlockType.PRODUCTS_LIST.contentTypeUid(), "salesforce", 504,
                "Salesforce call failed for carousel=CMSOfertas", new java.net.SocketTimeoutException("read timed out"));

        compose(ctx -> {
            throw failure;
        });

        ILoggingEvent event = degradation();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(keyValues(event))
                .containsEntry(LogFields.BLOCK_ID, "pl-0")
                .containsEntry(LogFields.BLOCK_TYPE, "products_list")
                .containsEntry(LogFields.ERROR_CODE, "BLOCK_SERVICE_UNAVAILABLE")
                .containsEntry(LogFields.CATEGORY, ErrorCategory.EXTERNAL_SERVICE)
                .containsEntry(LogFields.RETRYABLE, true)
                .containsEntry(LogFields.UPSTREAM, "salesforce")
                .containsEntry(LogFields.UPSTREAM_STATUS, 504)
                .containsEntry(LogFields.CAUSE_CHAIN,
                        "DynamicBlockServiceUnavailableException <- SocketTimeoutException")
                .containsEntry(LogFields.DETAIL, "read timed out");
        // No stack trace: an upstream outage is expected, and several blocks can degrade per request.
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void untypedFailure_isOurBugSoItLogsAtErrorWithAStackTrace() {
        compose(ctx -> {
            throw new NullPointerException("payload was null");
        });

        ILoggingEvent event = degradation();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(keyValues(event))
                .containsEntry(LogFields.ERROR_CLASS, "NullPointerException")
                .doesNotContainKey(LogFields.ERROR_CODE);
        assertThat(event.getThrowableProxy()).isNotNull();
    }

    @Test
    void upstreamStatusIsOmitted_whenTheCallNeverGotAResponse() {
        compose(ctx -> {
            throw new DynamicBlockServiceUnavailableException(
                    "pl-0", BlockType.PRODUCTS_LIST.contentTypeUid(), "jewel", 0, "connect timed out", null);
        });

        assertThat(keyValues(degradation()))
                .containsEntry(LogFields.UPSTREAM, "jewel")
                .doesNotContainKey(LogFields.UPSTREAM_STATUS);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    /** The failure a resolver raises; {@link BlockResolver} itself is not a functional interface. */
    @FunctionalInterface
    private interface FailingResolution {
        ResolvedContent resolve(BlockResolutionContext context);
    }

    private void compose(FailingResolution failing) {
        when(featureFlagPort.isEnabled(eq(FLAG), any())).thenReturn(true);
        BlockResolutionKey key = BlockResolutionKey.of(BlockType.PRODUCTS_LIST);
        BlockResolver resolver = new BlockResolver() {
            @Override
            public Set<BlockResolutionKey> resolutionKeys() {
                return Set.of(key);
            }

            @Override
            public ResolvedContent resolve(BlockResolutionContext context) {
                return failing.resolve(context);
            }
        };
        BlockResolutionCatalog catalog = new BlockResolutionCatalog(Map.of(
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST), BlockResolution.post(ENDPOINT, FLAG)));
        BlockResolverRegistry registry = new BlockResolverRegistry(Map.of(
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST), resolver));
        LandingCompositionService service =
                new LandingCompositionService(featureFlagPort, catalog, registry, 1);

        LandingDefinition definition = LandingDefinition.ofBlocks(PageType.BLP, "es-mx", List.of(
                new BlockDefinition("pl-0", "products_list", null, true, true, Map.of())));
        // The page still renders — degradation replaces the block, it never fails the request.
        assertThat(service.compose(definition, GUEST_WEB).blocks()).hasSize(1);
    }

    private ILoggingEvent degradation() {
        return appender.list.stream()
                .filter(e -> LogEvents.BLOCK_DEGRADED.name().equals(keyValues(e).get(LogFields.EVENT)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no BLOCK_DEGRADED event was logged"));
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        return pairs == null ? Map.of() : pairs.stream()
                .collect(java.util.stream.Collectors.toMap(p -> p.key, p -> p.value));
    }
}
