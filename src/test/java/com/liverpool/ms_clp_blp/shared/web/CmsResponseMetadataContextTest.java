package com.liverpool.ms_clp_blp.shared.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class CmsResponseMetadataContextTest {

    @AfterEach
    void clearContext() {
        CmsResponseMetadataContext.clear();
    }

    @Test
    void setThenCurrent_returnsTheRecordedOrigin() {
        CmsResponseMetadataContext.set(ContentOrigin.CAFFEINE);

        assertThat(CmsResponseMetadataContext.current()).isEqualTo(ContentOrigin.CAFFEINE);
    }

    @Test
    void set_calledTwice_theLaterValueWins() {
        // Whichever tier records last is the one that actually answered.
        CmsResponseMetadataContext.set(ContentOrigin.CONTENTSTACK);
        CmsResponseMetadataContext.set(ContentOrigin.CAFFEINE);

        assertThat(CmsResponseMetadataContext.current()).isEqualTo(ContentOrigin.CAFFEINE);
    }

    @Test
    void current_nothingSet_isNull() {
        assertThat(CmsResponseMetadataContext.current()).isNull();
    }

    @Test
    void clear_removesTheRecordedOrigin() {
        CmsResponseMetadataContext.set(ContentOrigin.CAFFEINE);

        CmsResponseMetadataContext.clear();

        assertThat(CmsResponseMetadataContext.current()).isNull();
    }

    @Test
    void attachTo_originSet_addsTheHeader() {
        CmsResponseMetadataContext.set(ContentOrigin.CONTENTSTACK);

        ResponseEntity<String> response =
                CmsResponseMetadataContext.attachTo(ResponseEntity.ok()).body("body");

        assertThat(response.getHeaders().getFirst("x-data-origin")).isEqualTo("ContentStack");
    }

    @Test
    void attachTo_nothingSet_leavesTheHeaderAbsent() {
        ResponseEntity<String> response =
                CmsResponseMetadataContext.attachTo(ResponseEntity.ok()).body("body");

        assertThat(response.getHeaders().containsHeader("x-data-origin")).isFalse();
    }
}
