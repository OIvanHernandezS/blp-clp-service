package com.liverpool.ms_clp_blp.clients.salesforcev2;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.liverpool.config.properties.ApiSalesforceV2Config;

/**
 * Registers {@link ApiSalesforceV2Config} ({@code api.salesforce-v2.*} — url and timeouts), which
 * commons' {@code SalesforceV2FeignConfig} reads to build the Salesforce V2 transport.
 *
 * <p>It lives outside this project's {@code @ConfigurationPropertiesScan} base package, so it is bound
 * explicitly here rather than by widening the scan to {@code com.liverpool} — which would also drag in
 * commons' {@code @ControllerAdvice} (see {@code MsClpBlpApplication}). Same reasoning, and same shape,
 * as {@code JewelCommonsConfig}.</p>
 */
@Configuration
@EnableConfigurationProperties(ApiSalesforceV2Config.class)
public class SalesforceV2CommonsConfig {
}
