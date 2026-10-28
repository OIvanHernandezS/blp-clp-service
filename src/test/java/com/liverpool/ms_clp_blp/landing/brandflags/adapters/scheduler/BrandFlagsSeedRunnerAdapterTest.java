package com.liverpool.ms_clp_blp.landing.brandflags.adapters.scheduler;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsSyncService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The thinnest possible test for the thinnest possible adapter: firing the boot event invokes the
 * inbound port exactly once and does nothing else. The event wiring itself is Spring's to guarantee.
 */
class BrandFlagsSeedRunnerAdapterTest {

    @Test
    void firingTheTriggerInvokesTheServiceExactlyOnce() {
        BrandFlagsSyncService service = mock(BrandFlagsSyncService.class);
        BrandFlagsSeedRunnerAdapter adapter = new BrandFlagsSeedRunnerAdapter(service);

        adapter.seedBrandFlagDefaults();

        verify(service, times(1)).seedDefaults();
    }
}
