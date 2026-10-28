package com.liverpool.ms_clp_blp.landing.models;

/**
 * The shopper's favourite store, as far as analytics needs it: the values written into
 * {@code customData} entries typed {@code selected_store.code} and {@code selected_store.name}
 * (Rule 19 reference: digital_bff {@code reqContext.cache.selectedStore}).
 *
 * @param code the store id, numeric as the legacy response published it ({@code 7})
 * @param name the store name ({@code Liverpool Santa Fe})
 */
public record SelectedStore(Object code, String name) {
}
