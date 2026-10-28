package com.liverpool.ms_clp_blp.landing.adapters.categoryindexer;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.clients.categoryindexer.CategoryIndexerClient;
import com.liverpool.ms_clp_blp.clients.categoryindexer.CategoryIndexerProperties;
import com.liverpool.ms_clp_blp.clients.categoryindexer.dto.CategoryTreeResponse;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategoryIndexerAdapterTest {

    private CategoryIndexerClient client;
    private CategoryIndexerAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(CategoryIndexerClient.class);
        adapter = new CategoryIndexerAdapter(client,
                new CategoryIndexerProperties("http://indexer", "/search-category", Duration.ofSeconds(2),
                        Duration.ofHours(24), 100),
                CircuitBreakerRegistry.ofDefaults());
    }

    @Test
    void aFoundCategory_isMapped_andCachedPerBrandIgnoringCase() {
        when(client.findCategory("LP", "catst1")).thenReturn(tree("catst1"));

        assertThat(adapter.findCategory("LP", "catst1")).hasValueSatisfying(c -> assertThat(c.id()).isEqualTo("CATST1"));
        assertThat(adapter.findCategory("LP", "CATST1")).isPresent();

        verify(client, times(1)).findCategory(any(), any());
    }

    @Test
    void anIndexerFailure_isEmpty_andNotCached() {
        when(client.findCategory("LP", "c1")).thenThrow(new IllegalStateException("down")).thenReturn(tree("c1"));

        assertThat(adapter.findCategory("LP", "c1")).isEmpty();
        assertThat(adapter.findCategory("LP", "c1")).isPresent();
    }

    @Test
    void anAnswerWithNoCategory_isEmpty() {
        when(client.findCategory("LP", "c1")).thenReturn(null);
        assertThat(adapter.findCategory("LP", "c1")).isEmpty();

        when(client.findCategory("SB", "c1")).thenReturn(new CategoryTreeResponse(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null));
        assertThat(adapter.findCategory("SB", "c1")).isEmpty();
    }

    private static CategoryTreeResponse tree(String id) {
        return new CategoryTreeResponse(id, "Title", false, null, "", "/tienda/" + id, "BLP", 1, id, true, true,
                "NOT_SPECIFIED", 1, null, null, null, null, null, null, null, "", "home > Title", false,
                List.of(), true, true, null);
    }
}
