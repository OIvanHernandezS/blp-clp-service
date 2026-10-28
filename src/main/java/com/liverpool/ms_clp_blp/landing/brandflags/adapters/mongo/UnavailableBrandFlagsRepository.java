package com.liverpool.ms_clp_blp.landing.brandflags.adapters.mongo;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.landing.brandflags.BrandFlagsProperties;
import com.liverpool.ms_clp_blp.landing.brandflags.BrandFlagsStoreCondition;
import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlags;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsRepositoryPort;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;

/**
 * Stands in for the flag store when there is none: no connection string configured, or the feature
 * switched off. Answers "this brand overrides nothing" to every read and refuses every write.
 *
 * <p>Wired in place of {@link BrandFlagsMongoAdapter} rather than left absent, so that nothing
 * downstream needs a null check and every flag simply resolves from {@code landing.feature-flags}.</p>
 *
 * <p>The one case worth noticing is a misconfiguration: the feature switched on but no
 * {@code MONGO_URI}. That is not the same as switching it off, so it gets a WARN at startup — the
 * only signal an operator would otherwise have is per-brand overrides quietly never applying.</p>
 */
@Component
@ConditionalOnExpression(BrandFlagsStoreCondition.UNAVAILABLE)
public class UnavailableBrandFlagsRepository implements BrandFlagsRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(UnavailableBrandFlagsRepository.class);

    /** Log field: why the store is not wired, so the two reasons are distinguishable. */
    private static final String REASON = "reason";

    /**
     * @param properties used only to tell "switched off" from "switched on but unconfigured"
     */
    public UnavailableBrandFlagsRepository(BrandFlagsProperties properties) {
        if (properties.enabled()) {
            LogEvents.at(log, LogEvents.BRAND_FLAGS_STORE_UNAVAILABLE)
                    .addKeyValue(REASON, "no mongo.uri configured")
                    .log("Per-brand feature flags are enabled but have no store; "
                            + "every flag will resolve from landing.feature-flags");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<BrandFlags> findByBrand(String brand) {
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean save(BrandFlags brandFlags) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean saveDefaults(BrandFlags brandFlags) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean delete(String brand) {
        return false;
    }
}
