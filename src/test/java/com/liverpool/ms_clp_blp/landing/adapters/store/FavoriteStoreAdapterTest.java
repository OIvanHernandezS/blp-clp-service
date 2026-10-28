package com.liverpool.ms_clp_blp.landing.adapters.store;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.clients.store.StoreClient;
import com.liverpool.ms_clp_blp.clients.store.StoreProperties;
import com.liverpool.ms_clp_blp.clients.store.dto.StoreDetailsResponse;
import com.liverpool.ms_clp_blp.landing.models.SelectedStore;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FavoriteStoreAdapterTest {

    private StoreClient client;
    private FavoriteStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(StoreClient.class);
        adapter = new FavoriteStoreAdapter(client,
                new StoreProperties("http://store", Duration.ofSeconds(2), Duration.ofHours(24), 100),
                CircuitBreakerRegistry.ofDefaults());
    }

    /** digital_bff published the code as a number ({@code +storeId}). */
    @Test
    void aKnownStore_isItsNumericCodeAndName_andIsCached() {
        when(client.findStore("LP", "7")).thenReturn(new StoreDetailsResponse(7, "Liverpool Santa Fe"));

        assertThat(adapter.findStore("LP", "7")).contains(new SelectedStore(7, "Liverpool Santa Fe"));
        assertThat(adapter.findStore("LP", "7")).isPresent();

        verify(client, times(1)).findStore(any(), any());
    }

    @Test
    void aStringId_isParsedToANumberWhenItIsOne_andKeptOtherwise() {
        when(client.findStore("LP", "12")).thenReturn(new StoreDetailsResponse("12", "Insurgentes"));
        when(client.findStore("LP", "X1")).thenReturn(new StoreDetailsResponse(null, "Outlet"));

        assertThat(adapter.findStore("LP", "12")).hasValueSatisfying(s -> assertThat(s.code()).isEqualTo(12L));
        assertThat(adapter.findStore("LP", "X1")).hasValueSatisfying(s -> assertThat(s.code()).isEqualTo("X1"));
    }

    @Test
    void anUnknownOrUnreachableStore_isEmpty() {
        when(client.findStore("LP", "404")).thenReturn(new StoreDetailsResponse(404, null));
        when(client.findStore("LP", "500")).thenThrow(new IllegalStateException("down"));

        assertThat(adapter.findStore("LP", "404")).isEmpty();
        assertThat(adapter.findStore("LP", "500")).isEmpty();
    }
}
