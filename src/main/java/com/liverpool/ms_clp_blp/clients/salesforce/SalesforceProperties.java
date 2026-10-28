package com.liverpool.ms_clp_blp.clients.salesforce;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ms-clp-blp's own settings for the Salesforce (Evergage) {@code products_list} carousels.
 *
 * <p>The transport — endpoint, timeouts and Basic credentials — is not here: it is bound by
 * platform-commons under {@code api.salesforce-carrousels} and {@code salesforce.auth}, the same keys
 * ms_plp_search and ms_pdp_search run with (see {@link SalesforceCarrouselsCommonsConfig}).</p>
 *
 * @param application  source application label sent in the request body ({@code Web} or {@code App})
 * @param userIdHeader request header carrying the ATG profile id that {@code products_list} sends to
 *                     Salesforce as {@code ID_ATG1} (default {@code x-sf-user-id}). Configurable for
 *                     the same reason Jewel's are: the gateway owns the name
 */
@ConfigurationProperties(prefix = "salesforce")
public record SalesforceProperties(String application, String userIdHeader) {
}
