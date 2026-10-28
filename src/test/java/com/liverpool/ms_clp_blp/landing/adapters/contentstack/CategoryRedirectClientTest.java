package com.liverpool.ms_clp_blp.landing.adapters.contentstack;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.clients.contentstack.CmsProperties;
import com.liverpool.ms_clp_blp.clients.contentstackdirect.ContentStackDirectGateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategoryRedirectClientTest {

    private ContentStackDirectGateway gateway;
    private CategoryRedirectClient client;

    @BeforeEach
    void setUp() {
        gateway = mock(ContentStackDirectGateway.class);
        when(gateway.isAvailableFor("LP")).thenReturn(true);
        client = new CategoryRedirectClient(gateway, new CmsProperties("LP", null, null, null));
    }

    /** content-service selected {source_category_id: id.toLowerCase(), is_active: true}. */
    @Test
    void selectsTheActiveRedirectOfTheLowerCasedCategory() {
        Map<String, Object> redirect = Map.of("uid", "blt-r", "is_active", true, "target_url", "/tienda/x");
        when(gateway.fetchByFields(eq("category_redirect"), eq("es-mx"), any(), eq("LP")))
                .thenReturn(Optional.of(redirect));

        assertThat(client.findActiveRedirect("LP", "es-mx", "CATST1")).contains(redirect);
        verify(gateway).fetchByFields("category_redirect", "es-mx",
                Map.of("source_category_id", "catst1", "is_active", true), "LP");
    }

    @Test
    void anInactiveEntry_isNoRedirect() {
        when(gateway.fetchByFields(any(), any(), any(), any()))
                .thenReturn(Optional.of(Map.of("uid", "blt-r", "is_active", false)));

        assertThat(client.findActiveRedirect("LP", "es-mx", "c1")).isEmpty();
    }

    @Test
    void aBrandContentstackCannotServe_costsNoCall() {
        assertThat(client.findActiveRedirect("LVS", "es-mx", "c1")).isEmpty();
        verify(gateway, never()).fetchByFields(any(), any(), any(), any());
    }

    @Test
    void aFailedLookup_isNoRedirect() {
        when(gateway.fetchByFields(any(), any(), any(), any())).thenThrow(new IllegalStateException("down"));

        assertThat(client.findActiveRedirect("LP", "es-mx", "c1")).isEmpty();
    }

    @Test
    void aConfiguredContentType_isUsed() {
        CategoryRedirectClient custom = new CategoryRedirectClient(gateway,
                new CmsProperties("LP", null, null, "category_redirect_v2"));
        when(gateway.fetchByFields(any(), any(), any(), any())).thenReturn(Optional.empty());

        custom.findActiveRedirect("LP", "es-mx", "c1");

        verify(gateway).fetchByFields(eq("category_redirect_v2"), any(), any(), any());
    }
}
