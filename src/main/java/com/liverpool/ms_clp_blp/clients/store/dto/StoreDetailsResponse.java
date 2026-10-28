package com.liverpool.ms_clp_blp.clients.store.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The two store-service fields the landing page uses (Rule 19 reference: digital_bff
 * {@code StoreEntity}); everything else is ignored.
 *
 * @param storeId the store id — numeric for every Liverpool store, but typed loosely because the
 *                legacy entity allowed a string too
 * @param name    the store name
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoreDetailsResponse(Object storeId, String name) {
}
