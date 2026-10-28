package com.liverpool.ms_clp_blp.clients.salesforce;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.liverpool.config.properties.ApiSalesforceCarrouselsConfig;
import com.liverpool.config.properties.SalesForceConfig;

/**
 * Registers the two {@code platform-commons} property classes the Salesforce carousels transport needs:
 * {@link ApiSalesforceCarrouselsConfig} ({@code api.salesforce-carrousels.*} — url and timeouts) and
 * {@link SalesForceConfig} ({@code salesforce.auth.*} — the basic credentials).
 *
 * <p>Both live outside this project's {@code @ConfigurationPropertiesScan} base package, so they are
 * bound explicitly here rather than by widening the scan to {@code com.liverpool} — which would also
 * drag in commons' {@code @ControllerAdvice} (see {@code MsClpBlpApplication}). Same reasoning, and same
 * shape, as {@code JewelCommonsConfig}.</p>
 *
 * <p>Both are consumed by {@code SalesforceCarrouselsFeignConfig}, which
 * {@link SalesforceCarrouselsClient} names as its per-client configuration: that is where the
 * {@code Authorization: Basic base64(user:pass)} header and the isolated connection pool come from, so
 * no ms-clp-blp call site handles Salesforce credentials.</p>
 *
 * <p><strong>Two records bind the {@code salesforce} prefix.</strong> {@link SalesForceConfig} reads
 * {@code salesforce.auth.*} while ms-clp-blp's own {@link SalesforceProperties} reads
 * {@code salesforce.base-url}, {@code .actions-path} and the action names. That is deliberate, not
 * duplication: they are different consumers of one vendor's configuration block, and
 * {@code SalesforceProperties} is a record whose constructor binding simply ignores the {@code auth}
 * sub-tree. Do not merge them — {@link SalesForceConfig} is commons-owned and shared with
 * {@code ms_plp_search} / {@code ms_pdp_search}, which is exactly what keeps one credential serving
 * every service.</p>
 */
@Configuration
@EnableConfigurationProperties({ApiSalesforceCarrouselsConfig.class, SalesForceConfig.class})
public class SalesforceCarrouselsCommonsConfig {
}
