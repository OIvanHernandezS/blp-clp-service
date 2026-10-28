package com.liverpool.ms_clp_blp.landing.ports;

/**
 * Outbound port for evaluating numeric runtime settings ({@code LandingSettings}) without a redeploy —
 * the numeric counterpart of {@link FeatureFlagPort}, and per brand for the same reason: one instance
 * serves every brand at once.
 */
public interface RuntimeSettingsPort {

    /**
     * @param settingId the setting identifier (e.g. {@code products-list-max-images})
     * @param brand     the brand whose page is being composed (e.g. {@code LP}); may be {@code null}
     *                  when no session could be resolved, in which case {@code fallback} applies
     * @param fallback  the configured value, used when the brand declares no override. The caller
     *                  supplies it because each setting's default lives in its own slice's properties
     * @return the value in effect for that brand
     */
    int intValue(String settingId, String brand, int fallback);
}
