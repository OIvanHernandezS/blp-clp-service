package com.liverpool.ms_clp_blp.shared.errors;

import java.io.IOException;
import java.net.SocketTimeoutException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the field that answers "what actually blew up" on a WARN line that carries no stack trace.
 */
class CausesTest {

    @Test
    void chain_namesEveryLinkFromOutermostToRoot() {
        Throwable root = new SocketTimeoutException("Read timed out");
        Throwable middle = new IllegalStateException("call failed", root);
        Throwable outer = new ContentServiceUnavailableException("nope", "detail", middle);

        assertThat(Causes.chain(outer)).isEqualTo(
                "ContentServiceUnavailableException <- IllegalStateException <- SocketTimeoutException");
    }

    @Test
    void chain_handlesASingleThrowable() {
        assertThat(Causes.chain(new IOException("boom"))).isEqualTo("IOException");
    }

    @Test
    void chain_isNullForNothing_soTheFormatterOmitsTheField() {
        assertThat(Causes.chain(null)).isNull();
    }

    @Test
    void chain_terminatesOnASelfReferentialCause() {
        // A throwable that is its own cause would otherwise loop forever inside a log call.
        Throwable selfReferential = new IllegalStateException("loop");
        selfReferential.initCause(selfReferential.getCause());

        assertThat(Causes.chain(selfReferential)).isEqualTo("IllegalStateException");
    }

    @Test
    void rootMessage_prefersTheDeepestMessage_becauseThatIsWhereTheHostAndPortAre() {
        Throwable root = new SocketTimeoutException("connect timed out: salesforce:443");
        Throwable outer = new IllegalStateException("call failed", root);

        assertThat(Causes.rootMessage(outer)).isEqualTo("connect timed out: salesforce:443");
    }

    @Test
    void rootMessage_fallsBackToTheOutermostMessage_whenTheRootHasNone() {
        Throwable outer = new IllegalStateException("call failed", new IOException());

        assertThat(Causes.rootMessage(outer)).isEqualTo("call failed");
    }

    @Test
    void rootMessage_isNullForNothing() {
        assertThat(Causes.rootMessage(null)).isNull();
    }
}
