package com.liverpool.ms_clp_blp.clients.feign;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import com.liverpool.ms_clp_blp.clients.OutboundLoggingProperties;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pins the volume and the shape of the outbound trace.
 *
 * <p>The previous logger emitted Feign's {@code Level.FULL} dump at INFO — one line per request
 * header, one per response header, around thirty per call — which buried every line that said what
 * the page actually did. What replaces it is: nothing at INFO, one WARN per failure, and one
 * {@code curl} per request at DEBUG.
 */
class FeignOutboundLoggerTest {

    private static final String CONFIG_KEY = "AuthClient#exchangeToken(String,String,String)";
    /** {@code upstreamOf} derives "store" from this, which is what a rule names. */
    private static final String STORE_KEY = "StoreClient#findStore(String,String)";

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(FeignOutboundLogger.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(null);
    }

    @Test
    void aSuccessfulCallEmitsNothingAtInfo() {
        FeignOutboundLogger outboundLogger = loggerWith(false);

        outboundLogger.logRequest(CONFIG_KEY, feign.Logger.Level.BASIC, request());
        rebuffer(outboundLogger, response(200, "{\"ok\":true}"));

        assertThat(appender.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.INFO));
    }

    @Test
    void theRequestIsRenderedAsOneRunnableCurl() {
        loggerWith(false).logRequest(CONFIG_KEY, feign.Logger.Level.BASIC, request());

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage())
                    .startsWith("curl -i -X GET 'https://auth.liverpool.com.mx/v2/auth/exchange-token?SessionId=***'")
                    .contains("-H 'tenant: web'")
                    .contains("-H 'x-brand-id: LP'")
                    // The credential is named but never printed.
                    .contains("-H 'authorization: ***'")
                    .doesNotContain("Bearer super-secret");
            assertThat(keyValues(event)).containsEntry(LogFields.UPSTREAM, "auth");
        });
    }

    @Test
    void theCurlCarriesTheRequestBodyOnlyWhenBodiesAreEnabled() {
        loggerWith(false).logRequest(CONFIG_KEY, feign.Logger.Level.BASIC, request());
        assertThat(appender.list.getFirst().getFormattedMessage()).doesNotContain("--data");

        appender.list.clear();
        loggerWith(true).logRequest(CONFIG_KEY, feign.Logger.Level.BASIC, request());
        assertThat(appender.list.getFirst().getFormattedMessage()).contains("--data '{\"probe\":1}'");
    }

    @Test
    void aFailedCallWarnsOnceAndCarriesTheUpstreamsOwnErrorBody() throws IOException {
        // The status alone does not say why: {"code":"TOKEN_NOT_FOUND"} is the diagnosis.
        rebuffer(loggerWith(false), response(404, "{\"message\":\"Token not found\",\"code\":\"TOKEN_NOT_FOUND\"}"));

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(keyValues(event))
                    .containsEntry(LogFields.EVENT, LogEvents.UPSTREAM_CALL_FAILED.name())
                    .containsEntry(LogFields.UPSTREAM, "auth")
                    .containsEntry(LogFields.HTTP_STATUS, 404)
                    .containsEntry(LogFields.RESPONSE_BODY,
                            "{\"message\":\"Token not found\",\"code\":\"TOKEN_NOT_FOUND\"}");
        });
    }

    @Test
    void theFailedResponseIsRebufferedSoTheDecoderStillSeesIt() throws IOException {
        Response rebuffered = loggerWith(false).logAndRebufferResponse(
                CONFIG_KEY, feign.Logger.Level.BASIC, response(500, "boom"), 12L);

        assertThat(new String(feign.Util.toByteArray(rebuffered.body().asInputStream()),
                StandardCharsets.UTF_8)).isEqualTo("boom");
    }

    @Test
    void aSuccessfulResponseBodyIsNotReadUnlessBodiesAreEnabled() throws IOException {
        rebuffer(loggerWith(false), response(200, "{\"secret\":\"value\"}"));

        assertThat(keyValues(appender.list.getFirst())).doesNotContainKey(LogFields.RESPONSE_BODY);
    }

    @Test
    void aTransportFailureReportsNoStatusAndNamesTheException() {
        loggerWith(false).logIOException(CONFIG_KEY, feign.Logger.Level.BASIC,
                new SocketTimeoutException("connect timed out"), 3000L);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(keyValues(event))
                    .containsEntry(LogFields.HTTP_STATUS, 0)
                    .containsEntry(LogFields.ELAPSED_MS, 3000L)
                    .containsEntry(LogFields.ERROR_CLASS, "SocketTimeoutException")
                    .containsEntry(LogFields.DETAIL, "connect timed out");
        });
    }

    @Test
    void nullValuedFieldsAreOmittedRatherThanRenderedAsTheStringNull() {
        // errorClass="null" detail="null" is noise that reads like data.
        rebuffer(loggerWith(false), response(200, "ok"));

        assertThat(keyValues(appender.list.getFirst()))
                .doesNotContainKey(LogFields.ERROR_CLASS)
                .doesNotContainKey(LogFields.DETAIL);
    }

    @Test
    void nothingIsEmittedAtAllWhenDebugIsOff_exceptFailures() throws IOException {
        logger.setLevel(Level.INFO);
        FeignOutboundLogger outboundLogger = loggerWith(true);

        outboundLogger.logRequest(CONFIG_KEY, feign.Logger.Level.BASIC, request());
        rebuffer(outboundLogger, response(200, "ok"));
        assertThat(appender.list).isEmpty();

        rebuffer(outboundLogger, response(503, "unavailable"));
        assertThat(appender.list).singleElement()
                .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
    }

    // ── outcomes that are not failures ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an expected outcome is not logged as a failure")
    void anExpectedOutcomeIsSilent() {
        // auth's 401 means "this session expired" and 404 "this session is unknown". The adapter that
        // asked already reports both; this class saying "failed" on top reported the case twice at WARN.
        FeignOutboundLogger outboundLogger = loggerWith(false, List.of("store:404", "auth:401"));

        outboundLogger.logRequest(STORE_KEY, feign.Logger.Level.BASIC, request());
        rebuffer(outboundLogger, STORE_KEY, response(404, "{\"message\":\"store not found\"}"));
        outboundLogger.logRequest(CONFIG_KEY, feign.Logger.Level.BASIC, request());
        rebuffer(outboundLogger, CONFIG_KEY, response(401, "{\"message\":\"unauthorized\"}"));

        assertThat(appender.list).noneMatch(event ->
                LogEvents.UPSTREAM_CALL_FAILED.name().equals(keyValues(event).get(LogFields.EVENT)));
    }

    @Test
    @DisplayName("the same provider failing for real still warns")
    void theRuleIsAPairNotAProvider() {
        // The failure this guards against: suppressing store:404 must not suppress store:500, which is
        // a real outage.
        FeignOutboundLogger outboundLogger = loggerWith(false, List.of("store:404"));

        outboundLogger.logRequest(STORE_KEY, feign.Logger.Level.BASIC, request());
        rebuffer(outboundLogger, STORE_KEY, response(500, "boom"));

        assertThat(appender.list).anyMatch(event ->
                LogEvents.UPSTREAM_CALL_FAILED.name().equals(keyValues(event).get(LogFields.EVENT)));
    }

    @Test
    @DisplayName("a provider that is not listed is untouched")
    void anotherProviderIsUnaffected() {
        FeignOutboundLogger outboundLogger = loggerWith(false, List.of("store:404"));

        outboundLogger.logRequest(CONFIG_KEY, feign.Logger.Level.BASIC, request());
        rebuffer(outboundLogger, CONFIG_KEY, response(404, "not found"));

        assertThat(appender.list).anyMatch(event ->
                LogEvents.UPSTREAM_CALL_FAILED.name().equals(keyValues(event).get(LogFields.EVENT)));
    }

    @Test
    @DisplayName("an expected outcome does not read the response body it will not report")
    void anExpectedOutcomeLeavesTheBodyAlone() {
        // A failure's body is read because it is the diagnosis. An expected outcome's body is just
        // the ordinary response, so reading it would allocate a copy for nothing — and the decoder must
        // still receive a usable stream either way.
        FeignOutboundLogger outboundLogger = loggerWith(false, List.of("store:404"));
        outboundLogger.logRequest(STORE_KEY, feign.Logger.Level.BASIC, request());

        Response rebuffered = rebuffer(outboundLogger, STORE_KEY, response(404, "{\"store\":null}"));

        assertThatCode(() -> feign.Util.toByteArray(rebuffered.body().asInputStream()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with no rules configured, nothing changes from before")
    void anEmptyRuleListRestoresThePreviousBehaviour() {
        FeignOutboundLogger outboundLogger = loggerWith(false, List.of());

        outboundLogger.logRequest(STORE_KEY, feign.Logger.Level.BASIC, request());
        rebuffer(outboundLogger, STORE_KEY, response(404, "no store"));

        assertThat(appender.list).anyMatch(event ->
                LogEvents.UPSTREAM_CALL_FAILED.name().equals(keyValues(event).get(LogFields.EVENT)));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private FeignOutboundLogger loggerWith(boolean bodies) {
        return loggerWith(bodies, List.of());
    }

    /**
     * @param bodies  whether request/response bodies are logged
     * @param ignored the {@code provider:status} pairs to treat as normal business answers
     */
    private FeignOutboundLogger loggerWith(boolean bodies, List<String> ignored) {
        return new FeignOutboundLogger(new OutboundLoggingProperties(bodies, ignored));
    }

    /**
     * Reports one outcome under a given Feign key, which is what decides the provider a rule matches.
     *
     * @param outboundLogger the logger under test
     * @param configKey      Feign's {@code Interface#method(..)} key
     * @param response       the upstream response
     * @return the response as the decoder would receive it
     */
    private static Response rebuffer(FeignOutboundLogger outboundLogger, String configKey,
            Response response) {
        try {
            return outboundLogger.logAndRebufferResponse(configKey, feign.Logger.Level.BASIC,
                    response, 42L);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void rebuffer(FeignOutboundLogger outboundLogger, Response response) {
        try {
            outboundLogger.logAndRebufferResponse(CONFIG_KEY, feign.Logger.Level.BASIC, response, 42L);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Request request() {
        return Request.create(Request.HttpMethod.GET,
                "https://auth.liverpool.com.mx/v2/auth/exchange-token?SessionId=abc123",
                Map.of("tenant", List.of("web"),
                        "x-brand-id", List.of("LP"),
                        "authorization", List.of("Bearer super-secret")),
                "{\"probe\":1}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8,
                new RequestTemplate());
    }

    private static Response response(int status, String body) {
        return Response.builder()
                .status(status)
                .reason("reason")
                .request(request())
                .headers(Map.of("content-type", List.of("application/json")))
                .body(body, StandardCharsets.UTF_8)
                .build();
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        return pairs == null ? Map.of() : pairs.stream()
                .collect(java.util.stream.Collectors.toMap(p -> p.key, p -> p.value));
    }
}
