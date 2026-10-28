package com.liverpool.ms_clp_blp.landing.blocks.productslist.models;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.models.SourceOfData;

import static org.assertj.core.api.Assertions.assertThat;

class SalesforceV2SettingsTest {

    @Test
    void from_readsTheNestedConfigAndTheTopLevelThresholds() {
        SalesforceV2Settings settings = SalesforceV2Settings.from(block(), "user-1", "dev-1");

        assertThat(settings.source()).isEqualTo(SourceOfData.SALESFORCE_V2);
        assertThat(settings.model()).isEqualTo("home-top-sellers");
        assertThat(settings.requiresUserId()).isFalse();
        assertThat(settings.requiresDeviceId()).isTrue();
        assertThat(settings.requiresItemId()).isFalse();
        assertThat(settings.minOfProducts()).isEqualTo(4);
        assertThat(settings.maxOfProducts()).isEqualTo(12);
        assertThat(settings.userId()).isEqualTo("user-1");
        assertThat(settings.deviceId()).isEqualTo("dev-1");
    }

    /** The CMS may publish the flags as strings; they must read the same as JSON booleans. */
    @Test
    void from_acceptsStringFlags() {
        Map<String, Object> block = new LinkedHashMap<>(Map.of(
                "salesforce_v2_config", Map.of("model", "m", "requires_user_id", "true")));

        assertThat(SalesforceV2Settings.from(block, null, null).requiresUserId()).isTrue();
    }

    /** Entries published before the feature existed carry no config: unresolvable, never an error. */
    @Test
    void from_blockWithoutConfig_hasNoModelAndNoRequirements() {
        SalesforceV2Settings settings = SalesforceV2Settings.from(Map.of("source_of_data", "salesforce_v2"), null, null);

        assertThat(settings.hasNoModel()).isTrue();
        assertThat(settings.requiresUserId()).isFalse();
        assertThat(settings.requiresDeviceId()).isFalse();
        assertThat(settings.requiresItemId()).isFalse();
    }

    @Test
    void from_nullAttributes_keepsTheIdentity() {
        SalesforceV2Settings settings = SalesforceV2Settings.from(null, "user-1", null);

        assertThat(settings.hasNoModel()).isTrue();
        assertThat(settings.userId()).isEqualTo("user-1");
    }

    @Test
    void individualId_prefersTheUserIdOverTheDeviceId() {
        assertThat(SalesforceV2Settings.from(block(), "user-1", "dev-1").individualId()).isEqualTo("user-1");
    }

    @Test
    void individualId_fallsBackToTheDeviceIdWhenTheUserIdIsBlank() {
        assertThat(SalesforceV2Settings.from(block(), " ", "dev-1").individualId()).isEqualTo("dev-1");
    }

    @Test
    void individualId_isNullWhenTheRequestCarriesNeither() {
        assertThat(SalesforceV2Settings.from(block(), null, "").individualId()).isNull();
    }

    @Test
    void paramsFrom_projectsOnlyWhatFromReads_andRoundTrips() {
        Map<String, Object> full = block();

        Map<String, Object> params = SalesforceV2Settings.paramsFrom(full);

        assertThat(params).containsOnlyKeys("salesforce_v2_config", "min_of_products", "max_of_products");
        assertThat(SalesforceV2Settings.from(params, "u", "d")).isEqualTo(SalesforceV2Settings.from(full, "u", "d"));
    }

    @Test
    void paramsFrom_blockWithoutConfig_projectsNoConfig() {
        assertThat(SalesforceV2Settings.paramsFrom(Map.of("min_of_products", "3")))
                .containsOnlyKeys("min_of_products");
    }

    private static Map<String, Object> block() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("source_of_data", "salesforce_v2");
        block.put("min_of_products", "4");
        block.put("max_of_products", "12");
        block.put("salesforce_carousel", "CMSOfertasIncreibles");
        block.put("salesforce_v2_config", new LinkedHashMap<>(Map.of(
                "model", "home-top-sellers",
                "requires_user_id", false,
                "requires_device_id", true,
                "requires_item_id", false)));
        return block;
    }
}
