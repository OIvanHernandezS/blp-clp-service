package com.liverpool.ms_clp_blp.landing.adapters.blacklist;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.clients.blacklist.BlacklistClient;
import com.liverpool.ms_clp_blp.clients.blacklist.BlacklistProperties;
import com.liverpool.ms_clp_blp.clients.blacklist.dto.BlacklistResponse;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestrictedProductsAdapterTest {

    private BlacklistClient client;
    private RestrictedProductsAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(BlacklistClient.class);
        adapter = new RestrictedProductsAdapter(client,
                new BlacklistProperties("http://decomm", "/catalog-ingestion-service/v1/blacklist", -1,
                        Duration.ofSeconds(2), Duration.ofMinutes(10), Map.of("LP", "LIVERPOOL", "SB", "SUBURBIA")),
                CircuitBreakerRegistry.ofDefaults());
    }

    @Test
    void readsTheWholeListOfTheBrandsSite_once() {
        when(client.findRestrictedProducts("LIVERPOOL", -1))
                .thenReturn(new BlacklistResponse(Arrays.asList("1001", null, "1002")));

        assertThat(adapter.restrictedProductIds("lp")).containsExactlyInAnyOrder("1001", "1002");
        assertThat(adapter.restrictedProductIds("LP")).hasSize(2);

        verify(client, times(1)).findRestrictedProducts(anyString(), anyInt());
    }

    @Test
    void aBrandWithNoSite_hasNoBlacklist_andCostsNoCall() {
        assertThat(adapter.restrictedProductIds("GAP")).isEmpty();
        assertThat(adapter.restrictedProductIds(null)).isEmpty();
        verify(client, never()).findRestrictedProducts(anyString(), anyInt());
    }

    @Test
    void failsOpen_andRetriesOnTheNextCall() {
        when(client.findRestrictedProducts("SUBURBIA", -1))
                .thenThrow(new IllegalStateException("down"))
                .thenReturn(new BlacklistResponse(null));

        assertThat(adapter.restrictedProductIds("SB")).isEmpty();
        assertThat(adapter.restrictedProductIds("SB")).isEmpty();
        verify(client, times(2)).findRestrictedProducts("SUBURBIA", -1);
    }
}
