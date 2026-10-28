package com.liverpool.ms_clp_blp.shared.config;

import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

/**
 * Builds an {@link ApplicationContextRunner} that resolves {@code application*.yaml} with the real
 * OS environment stripped out.
 *
 * <p>The config tests in this package assert what happens when a variable is <em>unset</em>
 * ({@code LOG_FORMAT}, {@code LOG_LEVEL_APP}). A plain runner inherits the machine's environment, so
 * any developer who exports those — e.g. by sourcing {@code .env}, where both are set for local
 * debugging — sees the "unset" tests fail while CI stays green. Dropping the
 * {@code systemEnvironment} property source before config data is processed makes the assertions
 * mean what they say on every machine.</p>
 *
 * <p>Removal must happen <em>before</em> {@link ConfigDataApplicationContextInitializer}: profile
 * activation ({@code spring.profiles.include: ${LOG_FORMAT:}}) is resolved during config data
 * processing, not lazily at {@code getProperty()} time.</p>
 */
final class HermeticConfigRunner {

    private HermeticConfigRunner() {
    }

    /**
     * @return a runner that loads the real config files but sees no OS environment variables
     */
    static ApplicationContextRunner create() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }
}
