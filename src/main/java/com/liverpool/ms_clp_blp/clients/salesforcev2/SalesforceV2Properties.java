package com.liverpool.ms_clp_blp.clients.salesforcev2;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ms-clp-blp-local Salesforce V2 settings not covered by the reused
 * {@code com.liverpool.config.properties.ApiSalesforceV2Config} (url and timeouts). All values are
 * externalised — no header name is ever hardcoded in application logic (Rule 6).
 *
 * <p>Identity is genuine header passthrough, in parity with commons'
 * {@code ProductsListSalesforceV2Strategy}: the logged-in id is preferred and the device id is the
 * fallback, and whether either is <em>required</em> is decided per block by its
 * {@code salesforce_v2_config}. Unlike Salesforce V1 (ADR-041) there is no {@code SessionId} gate —
 * V2 serves guests through their device id.</p>
 *
 * @param userIdHeader   request header carrying the Salesforce logged-in id (default {@code x-sf-user-id},
 *                       the same header Salesforce V1 reads)
 * @param deviceIdHeader request header carrying the Salesforce device id (default {@code x-sf-device-id})
 */
@ConfigurationProperties(prefix = "salesforce-v2")
public record SalesforceV2Properties(
        String userIdHeader,
        String deviceIdHeader) {
}
