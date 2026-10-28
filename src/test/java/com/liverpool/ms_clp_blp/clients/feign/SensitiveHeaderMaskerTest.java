package com.liverpool.ms_clp_blp.clients.feign;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that turning the verbose outbound trace on no longer copies credentials into the log
 * pipeline. Each input below is a line the trace actually produces.
 */
class SensitiveHeaderMaskerTest {

    @Test
    void masksTheAuthorizationHeaderFeignEmits() {
        String masked = SensitiveHeaderMasker.mask(
                "[SalesforceClient#postAction] authorization: Basic dXNlcjpwYXNzd29yZA==");

        assertThat(masked)
                .doesNotContain("dXNlcjpwYXNzd29yZA==")
                .isEqualTo("[SalesforceClient#postAction] authorization: ***");
    }

    @Test
    void masksTheInterceptorsBracketedHeaderShape() {
        String masked = SensitiveHeaderMasker.mask("Header 'Authorization': [Bearer eyJhbGciOi]");

        assertThat(masked).isEqualTo("Header 'Authorization': [***]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"apikey", "api-key", "x-api-key", "Cookie", "SessionId",
            "x-sf-user-id", "x-jml-user-id", "x-jml-device-id"})
    void masksEveryCredentialBearingHeader(String header) {
        assertThat(SensitiveHeaderMasker.mask(header + ": secret-value"))
                .isEqualTo(header + ": ***");
    }

    @Test
    void masksSessionIdWhenItTravelsInTheQueryString() {
        String masked = SensitiveHeaderMasker.mask(
                "---> GET http://auth/v2/auth/exchange-token?SessionId=abc123&brand=LP HTTP/1.1");

        assertThat(masked)
                .doesNotContain("abc123")
                .contains("SessionId=***")
                .contains("brand=LP");
    }

    @Test
    void leavesDiagnosticallyUsefulHeadersAlone() {
        // The point is a usable trace, not a redacted one: everything that is not a credential stays.
        String line = "content-type: application/json";

        assertThat(SensitiveHeaderMasker.mask(line)).isEqualTo(line);
    }

    @Test
    void showsThatTheHeaderWasPresent_soAMissingCredentialStaysDiagnosable() {
        // "authorization: ***" and no authorization line at all mean very different things when a
        // call comes back 401.
        assertThat(SensitiveHeaderMasker.mask("authorization: ")).isEqualTo("authorization: ***");
    }

    @Test
    void maskValue_redactsByHeaderNameCaseInsensitively() {
        assertThat(SensitiveHeaderMasker.maskValue("Authorization", "[Bearer x]"))
                .isEqualTo(SensitiveHeaderMasker.REDACTED);
        assertThat(SensitiveHeaderMasker.maskValue("x-brand-id", "[LP]")).isEqualTo("[LP]");
    }

    @Test
    void mask_handlesNullAndEmpty() {
        assertThat(SensitiveHeaderMasker.mask(null)).isNull();
        assertThat(SensitiveHeaderMasker.mask("")).isEmpty();
    }
}
