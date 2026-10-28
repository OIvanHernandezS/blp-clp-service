package com.liverpool.ms_clp_blp.clients.contentstackdirect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.liverpool.clients.ContentStackClient;
import com.liverpool.config.properties.ContentStackConfig;
import com.liverpool.enums.Sites;
import com.liverpool.ms_clp_blp.shared.errors.ContentstackEntryNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gateway's own job: send the proxy's parameters, take the one entry, and hand back a payload
 * indistinguishable from the proxy's.
 */
class ContentStackDirectGatewayTest {

    private static final ResolvedStack STACK = new ResolvedStack(
            Sites.LP,
            ContentStackConfig.builder()
                    .apiKey("api-key-lp").accessToken("delivery-token-lp")
                    .branch("qa").environment("qa").build(),
            "lp", false);

    private ContentStackClient client;
    private ContentStackStackResolver stackResolver;
    private ContentStackDirectGateway gateway;

    @BeforeEach
    void setUp() {
        client = mock(ContentStackClient.class);
        stackResolver = mock(ContentStackStackResolver.class);
        when(stackResolver.resolve("LP")).thenReturn(Optional.of(STACK));
        when(stackResolver.resolve("LVS")).thenReturn(Optional.empty());
        gateway = new ContentStackDirectGateway(client, stackResolver, new ContentStackQueryBuilder(),
                new ContentStackPayloadNormalizer());
    }

    // ── availability ─────────────────────────────────────────────────────────────────────────────

    @Test
    void isAvailableFor_followsTheStackResolver() {
        assertThat(gateway.isAvailableFor("LP")).isTrue();
        assertThat(gateway.isAvailableFor("LVS")).isFalse();
    }

    // ── landing page fetches ─────────────────────────────────────────────────────────────────────

    @Test
    void fetchCategoryPage_sendsTheProxysParametersAndSelectsByCategoryId() {
        stubEntries(List.of(pageEntry()));

        gateway.fetchCategoryPage("page_blp", "es-mx", "catst26617625", "LP");

        verify(client).getEntriesByQueryWithIncludes(
                eq("page_blp"), eq("qa"), eq("api-key-lp"), eq("delivery-token-lp"), eq("qa"), eq("es-mx"),
                eq("{\"category_id\":{\"$in\":[\"catst26617625\"]}}"),
                eq(true), eq(3), eq(true), eq(true), eq("BASE"), eq(1));
    }

    /** The header/footer overrides must arrive unwrapped, exactly as digital_bff received them. */
    @Test
    void fetchCategoryPage_unwrapsTheHeaderAndFooterReferences() {
        stubEntries(List.of(pageEntry()));

        Map<String, Object> page = gateway.fetchCategoryPage("page_blp", "es-mx", "catst26617625", "LP");

        assertThat(page.get("header")).isInstanceOf(Map.class);
        assertThat(page).doesNotContainKey("footer");
        assertThat(page).containsEntry("category_id", "catst26617625");
    }

    @Test
    void fetchCategoryPage_noMatchingEntry_throwsEntryNotFound() {
        stubEntries(List.of());

        assertThatThrownBy(() -> gateway.fetchCategoryPage("page_clp", "es-mx", "cat5020010", "LP"))
                .isInstanceOf(ContentstackEntryNotFoundException.class)
                .hasMessageContaining("contentType=page_clp");
    }

    @Test
    void fetchCategoryPage_responseWithoutAnEntriesArray_throwsEntryNotFound() {
        doReturn(ResponseEntity.ok(Map.<String, Object>of())).when(client)
                .getEntriesByQueryWithIncludes(any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> gateway.fetchCategoryPage("page_blp", "es-mx", "catst1", "LP"))
                .isInstanceOf(ContentstackEntryNotFoundException.class);
    }

    // ── field queries ────────────────────────────────────────────────────────────────────────────

    @Test
    void fetchByFields_combinesEveryConditionAndReturnsTheFirstEntry() {
        stubEntries(List.of(new LinkedHashMap<>(Map.of("uid", "blt-redirect", "is_active", true))));

        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put("source_category_id", "catst1");
        conditions.put("is_active", true);
        Optional<Map<String, Object>> entry = gateway.fetchByFields("category_redirect", "es-mx", conditions, "LP");

        assertThat(entry).isPresent().get().satisfies(e -> assertThat(e).containsEntry("uid", "blt-redirect"));
        verify(client).getEntriesByQueryWithIncludes(
                eq("category_redirect"), any(), any(), any(), any(), eq("es-mx"),
                eq("{\"source_category_id\":\"catst1\",\"is_active\":true}"),
                eq(true), eq(3), eq(true), eq(true), eq("BASE"), eq(1));
    }

    @Test
    void fetchByFields_noMatch_isEmptyRatherThanAnError() {
        stubEntries(List.of());

        assertThat(gateway.fetchByFields("category_redirect", "es-mx", Map.of("is_active", true), "LP")).isEmpty();
    }

    @Test
    void fetchByField_returnsTheFirstEntryMatchingOneField() {
        stubEntries(List.of(new LinkedHashMap<>(Map.of("identifier", "ms_clp_blp_flags_LP"))));

        assertThat(gateway.fetchByField("ms_clp_blp_flags", "es-mx", "identifier", "ms_clp_blp_flags_LP", "LP"))
                .isPresent();
    }

    @Test
    void unavailableReason_followsTheStackResolver() {
        when(stackResolver.unavailableReason("LVS")).thenReturn(Optional.of(FallbackUnavailableReason.UNMAPPED_BRAND));

        assertThat(gateway.unavailableReason("LVS")).contains(FallbackUnavailableReason.UNMAPPED_BRAND);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /**
     * @return a raw Contentstack {@code page_blp} entry, with its header still a reference array and an
     *         empty footer reference
     */
    private static Map<String, Object> pageEntry() {
        return new LinkedHashMap<>(Map.of(
                "uid", "blt1",
                "url", "/tienda/estee-lauder/catst26617625",
                "category_id", "catst26617625",
                "header", List.of(Map.of("_content_type_uid", "header")),
                "footer", List.of(),
                "layout", Map.of("blocks", List.of())));
    }

    /**
     * @param entries the entries the Delivery API should return
     */
    private void stubEntries(List<Map<String, Object>> entries) {
        doReturn(ResponseEntity.ok(Map.<String, Object>of("entries", entries))).when(client)
                .getEntriesByQueryWithIncludes(any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any(), any());
    }
}
