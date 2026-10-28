package com.liverpool.ms_clp_blp.shared.session;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.clients.auth.AuthClient;
import com.liverpool.ms_clp_blp.clients.auth.dto.AuthExchangeResponse;
import com.liverpool.ms_clp_blp.clients.auth.dto.DecodeAccessToken;

import feign.FeignException;
import feign.Request;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthTokenExchangeAdapter}: response interpretation and the swallow-and-degrade
 * contract.
 *
 * <p>The percent-encoding of the session id is <strong>not</strong> asserted here — that is a property
 * of the bytes Feign puts on the wire, which a mocked client cannot observe. It is pinned by
 * {@code AuthClientWireTest} against a real socket.</p>
 */
class AuthTokenExchangeAdapterTest {

    private static final String PATH = "/v2/auth/exchange-token";
    private static final String SESSION_ID = "11111111-2222-3333-4444-555555555555.c2ln";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuthClient authClient;
    private AuthTokenExchangeAdapter adapter;

    @BeforeEach
    void setUp() {
        authClient = mock(AuthClient.class);
        adapter = new AuthTokenExchangeAdapter(authClient);
    }

    @Test
    void exchange_loggedInUser_passesSessionBrandAndTenant_andReturnsPrn() {
        stub("""
                { "decodeAccessToken": { "isAnonymous": false, "prn": "P123", "anonId": "A1" } }
                """);

        AuthExchangeResult result = adapter.exchange(v2(AuthTenant.WEB));

        assertThat(result.authenticated()).isTrue();
        assertThat(result.profileId()).isEqualTo("P123");
        // The raw session id is handed over untouched; encoding is the client's job.
        verify(authClient).exchangeToken(eq(2), eq(SESSION_ID), eq("LP"), eq("web"), isNull());
    }

    @Test
    void exchange_anonymousUser_returnsGuestWithAnonId() {
        stub("""
                { "decodeAccessToken": { "isAnonymous": true, "anonId": "A1" } }
                """);

        AuthExchangeResult result = adapter.exchange(v2(AuthTenant.APP));

        assertThat(result.authenticated()).isFalse();
        assertThat(result.profileId()).isEqualTo("A1");
        verify(authClient).exchangeToken(eq(2), eq(SESSION_ID), eq("LP"), eq("app"), isNull());
    }

    @Test
    void exchange_missingIsAnonymousFlag_defaultsToGuest() {
        stub("""
                { "decodeAccessToken": { "prn": "P123" } }
                """);

        AuthExchangeResult result = adapter.exchange(v2(AuthTenant.WEB));

        assertThat(result.authenticated()).isFalse();
        assertThat(result.profileId()).isNull();
    }

    @Test
    void exchange_missingDecodeAccessToken_returnsGuest() {
        stub("{}");

        assertThat(adapter.exchange(v2(AuthTenant.WEB))).isEqualTo(AuthExchangeResult.GUEST);
    }

    @Test
    void exchange_nullBody_returnsGuest() {
        when(authClient.exchangeToken(anyInt(), any(), any(), any(), any())).thenReturn(null);

        assertThat(adapter.exchange(v2(AuthTenant.WEB))).isEqualTo(AuthExchangeResult.GUEST);
    }

    @Test
    void exchange_serverError_swallowedAndReturnsGuest() {
        when(authClient.exchangeToken(anyInt(), any(), any(), any(), any()))
                .thenThrow(new FeignException.InternalServerError("boom", dummyRequest(), null, Map.of()));

        assertThat(adapter.exchange(v2(AuthTenant.WEB))).isEqualTo(AuthExchangeResult.GUEST);
    }

    /**
     * A 401 is the expected response to a malformed session id, and must still degrade rather than
     * propagate — composition tolerates guests.
     */
    @Test
    void exchange_unauthorized_swallowedAndReturnsGuest() {
        when(authClient.exchangeToken(anyInt(), any(), any(), any(), any()))
                .thenThrow(new FeignException.Unauthorized("invalid signature", dummyRequest(), null, Map.of()));

        assertThat(adapter.exchange(v2(AuthTenant.WEB))).isEqualTo(AuthExchangeResult.GUEST);
    }

    @Test
    void exchange_transportFailure_swallowedAndReturnsGuest() {
        when(authClient.exchangeToken(anyInt(), any(), any(), any(), any()))
                .thenThrow(new feign.RetryableException(
                        -1, "connect timed out", Request.HttpMethod.GET, (Long) null, dummyRequest()));

        assertThat(adapter.exchange(v2(AuthTenant.WEB))).isEqualTo(AuthExchangeResult.GUEST);
    }

    /** Unknown fields (tokens, scopes, roles, …) must not break deserialization. */
    @Test
    void exchange_unknownResponseFields_areIgnored() {
        stub("""
                {
                  "accessToken": "ey...",
                  "scopes": [ "a", "b" ],
                  "decodeAccessToken": { "isAnonymous": false, "prn": "P9", "iat": 1699999999 }
                }
                """);

        assertThat(adapter.exchange(v2(AuthTenant.WEB)).profileId()).isEqualTo("P9");
    }

    // ── versioned contract ──────────────────────────────────────────────────────────────────────

    @Test
    void exchange_v3_callsTheClientWithVersion3_andThePlatform() {
        stub("""
                { "accessToken": "AT", "decodeAccessToken": { "isAnonymous": false, "prn": "P3" } }
                """);

        AuthExchangeResult result = adapter.exchange(
                new AuthExchangeRequest(SESSION_ID, "SB", AuthTenant.APP, 3, "ios"));

        assertThat(result).isEqualTo(new AuthExchangeResult(true, "P3", "AT"));
        verify(authClient).exchangeToken(3, SESSION_ID, "SB", "app", "ios");
    }

    @Test
    void exchange_v2_forwardsThePlatformWhenPresent() {
        stub("""
                { "decodeAccessToken": { "isAnonymous": false, "prn": "P2" } }
                """);

        adapter.exchange(new AuthExchangeRequest(SESSION_ID, "LP", AuthTenant.WEB, 2, "web"));

        verify(authClient).exchangeToken(2, SESSION_ID, "LP", "web", "web");
    }

    /** The guest-degradation contract is the same on every version: an error never reaches composition. */
    @Test
    void exchange_v3_error_swallowedAndReturnsGuest() {
        when(authClient.exchangeToken(anyInt(), any(), any(), any(), any()))
                .thenThrow(new FeignException.BadRequest("invalid_grant", dummyRequest(), null, Map.of()));

        assertThat(adapter.exchange(new AuthExchangeRequest(SESSION_ID, "LP", AuthTenant.WEB, 3, "web")))
                .isEqualTo(AuthExchangeResult.GUEST);
    }

    @Test
    void request_toString_neverRendersTheSessionId() {
        assertThat(new AuthExchangeRequest(SESSION_ID, "LP", AuthTenant.WEB, 3, "web").toString())
                .doesNotContain(SESSION_ID);
    }

    @Test
    void request_authVersion_isTheVersionAsText() {
        assertThat(new AuthExchangeRequest(SESSION_ID, "LP", AuthTenant.WEB, 3, null).authVersion())
                .isEqualTo("3");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private void stub(String json) {
        AuthExchangeResponse response = MAPPER.readValue(json, AuthExchangeResponse.class);
        when(authClient.exchangeToken(anyInt(), any(), any(), any(), any())).thenReturn(response);
    }

    private static AuthExchangeRequest v2(AuthTenant tenant) {
        return new AuthExchangeRequest(SESSION_ID, "LP", tenant, 2, null);
    }

    private static Request dummyRequest() {
        return Request.create(Request.HttpMethod.GET, "http://auth-service" + PATH,
                Map.of(), null, StandardCharsets.UTF_8, null);
    }

    /** Guards against the projection silently losing a field if the record is edited. */
    @Test
    void responseProjection_readsAllThreeClaims() {
        DecodeAccessToken token = MAPPER.readValue("""
                { "isAnonymous": true, "prn": "P", "anonId": "A" }
                """, DecodeAccessToken.class);

        assertThat(token.isAnonymous()).isTrue();
        assertThat(token.prn()).isEqualTo("P");
        assertThat(token.anonId()).isEqualTo("A");
    }
}
