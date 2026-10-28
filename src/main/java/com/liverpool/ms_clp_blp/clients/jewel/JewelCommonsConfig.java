package com.liverpool.ms_clp_blp.clients.jewel;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.liverpool.config.properties.ApiJewelConfig;

/**
 * Registers {@link ApiJewelConfig} — the base URL, per-brand integration ids and timeouts reused from
 * {@code platform-commons} (ADR-013).
 *
 * <p>{@code ApiJewelConfig} lives outside this project's {@code @ConfigurationPropertiesScan} base
 * package, so it is bound explicitly here rather than by widening the scan to {@code com.liverpool} —
 * which would also drag in commons' {@code @ControllerAdvice} (see {@code MsClpBlpApplication}). Both
 * {@link JewelClient}'s transport config and {@code ProductsListJewelAdapter} depend on it.</p>
 */
@Configuration
@EnableConfigurationProperties(ApiJewelConfig.class)
public class JewelCommonsConfig {
}
