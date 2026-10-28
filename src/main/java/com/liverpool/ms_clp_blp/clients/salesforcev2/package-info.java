/**
 * Transport for the Salesforce Personalization (V2) decisions API, which backs {@code products_list}
 * carousels whose {@code source_of_data} is {@code salesforce_v2}.
 *
 * <p>Holds the {@code @FeignClient} interface, the registration of platform-commons'
 * {@code ApiSalesforceV2Config} and the header names the provider reads its identity from. The wire
 * DTOs and the isolated OkHttp transport are commons-owned ({@code com.liverpool.dto.salesforce.v2},
 * {@code SalesforceV2FeignConfig}) — the same contract {@code ms_pdp_search} and {@code ms_plp_search}
 * speak — so the shape is shared rather than duplicated.</p>
 *
 * <p>No ports, no domain types, no slice knowledge. Must never depend on {@code landing} (enforced by ArchitectureTest).</p>
 */
package com.liverpool.ms_clp_blp.clients.salesforcev2;
