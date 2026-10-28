package com.liverpool.ms_clp_blp.landing.brandflags.adapters.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.landing.brandflags.BrandFlagsStoreCondition;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsSyncService;

/**
 * The driving adapter for seeding: a boot-time trigger, structurally the same role as {@code
 * EntryBackupSchedulerAdapter} — it exists only to invoke the inbound port, keeping {@code
 * BrandFlagsUseCases} itself free of framework code.
 *
 * <p><strong>{@code ApplicationReadyEvent}, not {@code @Scheduled}.</strong> ADR-047 accepted {@code
 * @Scheduled} as a deliberate, singular exception for the daily entry backup, because Cloud Run
 * throttles CPU between requests and a timer can fire late or be skipped on an idle instance. Seeding
 * does not fit that exception's shape: it is a one-time action an instance must take once, at boot,
 * not a recurring tick — which is exactly what {@code ApplicationReadyEvent} gives directly, with no
 * timer to fire late.</p>
 *
 * <p>Unlike the daily backup, no Mongo lock or dedup field is needed here. Two instances racing to
 * seed the same brand both write an identical {@code landing.feature-flags} snapshot — a harmless
 * duplicate write, not a correctness problem, so there is nothing for a lock to protect.</p>
 *
 * <p>Conditional on the same expression as {@link
 * com.liverpool.ms_clp_blp.landing.brandflags.adapters.mongo.BrandFlagsMongoAdapter}: with no store wired,
 * there is nothing to seed.</p>
 */
@Component
@ConditionalOnExpression(BrandFlagsStoreCondition.AVAILABLE)
public class BrandFlagsSeedRunnerAdapter {

    private final BrandFlagsSyncService brandFlagsSyncService;

    /**
     * @param brandFlagsSyncService the inbound port this trigger invokes
     */
    public BrandFlagsSeedRunnerAdapter(BrandFlagsSyncService brandFlagsSyncService) {
        this.brandFlagsSyncService = brandFlagsSyncService;
    }

    /**
     * Fires once, when the application context is fully up. Every brand without a document is seeded
     * with the current {@code landing.feature-flags} snapshot; a brand that already has one — CMS-authored
     * or previously seeded — is left untouched.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedBrandFlagDefaults() {
        brandFlagsSyncService.seedDefaults();
    }
}
