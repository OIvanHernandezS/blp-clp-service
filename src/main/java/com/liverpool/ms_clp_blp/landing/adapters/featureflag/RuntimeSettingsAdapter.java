package com.liverpool.ms_clp_blp.landing.adapters.featureflag;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.landing.ports.BrandFlagsLookupPort;
import com.liverpool.ms_clp_blp.landing.ports.RuntimeSettingsPort;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * Resolves a numeric setting for one brand: the brand's override from the per-brand store when it
 * declares one, otherwise the configured value the caller passes in.
 *
 * <p>Two layers, not the four of {@link FeatureFlagAdapter}: settings are edited by hand in MongoDB
 * only — neither the CMS nor the packaged per-brand baseline carries them — so the store and the
 * environment variable are the whole chain. A store that is unreachable or switched off reads as "no
 * override", which lands on the environment variable.</p>
 */
@Component
public class RuntimeSettingsAdapter implements RuntimeSettingsPort {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSettingsAdapter.class);

    /** Log field: the setting evaluated. */
    private static final String SETTING_ID = "settingId";

    /** Log field: the setting's evaluated value. */
    private static final String VALUE = "value";

    /** Log field: which layer of the chain produced the value. */
    private static final String SOURCE = "settingSource";

    private static final String SOURCE_BRAND = "brand";
    private static final String SOURCE_DEFAULTS = "defaults";

    private final BrandFlagsLookupPort brandFlags;

    /**
     * @param brandFlags per-brand published overrides, cached per instance
     */
    public RuntimeSettingsAdapter(BrandFlagsLookupPort brandFlags) {
        this.brandFlags = brandFlags;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int intValue(String settingId, String brand, int fallback) {
        Optional<Integer> published = brandFlags.lookupSetting(settingId, brand);
        int value = published.orElse(fallback);
        LogEvents.at(log, LogEvents.RUNTIME_SETTING_EVALUATED)
                .addKeyValue(SETTING_ID, settingId)
                .addKeyValue(LogFields.BRAND, brand)
                .addKeyValue(SOURCE, published.isPresent() ? SOURCE_BRAND : SOURCE_DEFAULTS)
                .addKeyValue(VALUE, value)
                .log("Runtime setting evaluated");
        return value;
    }
}
