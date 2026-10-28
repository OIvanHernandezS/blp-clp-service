package com.liverpool.ms_clp_blp.shared.cmspurgewebhook;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the tolerance of the payload reader.
 *
 * <p>Every case here is a delivery shape Contentstack has actually produced, or one a misconfigured
 * stack could produce. None of them may throw: this parser runs before anything else in the webhook,
 * and an exception would become a 500 that Contentstack retries.</p>
 */
class ContentstackPayloadTest {

    @Test
    void readsTheContentTypeFromTheTopLevel() {
        assertThat(ContentstackPayload.contentTypeUid(Map.of("content_type_uid", "page")))
                .isEqualTo("page");
    }

    @Test
    void readsTheContentTypeFromInsideData() {
        Map<String, Object> payload = Map.of("data", Map.of("content_type_uid", "flex"));

        assertThat(ContentstackPayload.contentTypeUid(payload)).isEqualTo("flex");
    }

    @Test
    void readsTheContentTypeFromTheNestedContentTypeObject() {
        Map<String, Object> payload = Map.of("data", Map.of("content_type", Map.of("uid", "flex")));

        assertThat(ContentstackPayload.contentTypeUid(payload)).isEqualTo("flex");
    }

    /** The top-level position wins, so a stack that sends both cannot be read two ways. */
    @Test
    void prefersTheTopLevelContentTypeOverTheNestedOne() {
        Map<String, Object> payload = Map.of(
                "content_type_uid", "page",
                "data", Map.of("content_type", Map.of("uid", "flex")));

        assertThat(ContentstackPayload.contentTypeUid(payload)).isEqualTo("page");
    }

    @Test
    void readsTheEventEntryUidAndLocale() {
        Map<String, Object> payload = Map.of(
                "event", "publish",
                "data", Map.of("entry", Map.of(
                        "uid", "blt2f716c7fdc5e71be",
                        "locale", "es-mx")));

        assertThat(ContentstackPayload.event(payload)).isEqualTo("publish");
        assertThat(ContentstackPayload.entryUid(payload)).isEqualTo("blt2f716c7fdc5e71be");
        assertThat(ContentstackPayload.locale(payload)).isEqualTo("es-mx");
    }

    @Test
    void answersNullForAnEmptyDelivery() {
        Map<String, Object> empty = Map.of();

        assertThat(ContentstackPayload.contentTypeUid(empty)).isNull();
        assertThat(ContentstackPayload.event(empty)).isNull();
        assertThat(ContentstackPayload.entryUid(empty)).isNull();
        assertThat(ContentstackPayload.locale(empty)).isNull();
    }

    /**
     * The shape-confusion case: {@code data} present but not an object, {@code entry} a string. A cast
     * here is what would turn a malformed delivery into a retried 500.
     */
    @Test
    void answersNullWhenTheNestingIsNotWhatItClaims() {
        Map<String, Object> payload = Map.of(
                "data", "not-an-object",
                "content_type_uid", "   ");

        assertThat(ContentstackPayload.contentTypeUid(payload)).isNull();
        assertThat(ContentstackPayload.entryUid(payload)).isNull();

        Map<String, Object> entryIsAString = Map.of("data", Map.of("entry", "blt123"));
        assertThat(ContentstackPayload.entryUid(entryIsAString)).isNull();
        assertThat(ContentstackPayload.locale(entryIsAString)).isNull();
    }

    /** A null value in a HashMap is a different path from an absent key; both must answer null. */
    @Test
    void answersNullForExplicitNullValues() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", null);
        payload.put("content_type_uid", null);
        payload.put("data", null);

        assertThat(ContentstackPayload.event(payload)).isNull();
        assertThat(ContentstackPayload.contentTypeUid(payload)).isNull();
        assertThat(ContentstackPayload.entryUid(payload)).isNull();
    }
}
