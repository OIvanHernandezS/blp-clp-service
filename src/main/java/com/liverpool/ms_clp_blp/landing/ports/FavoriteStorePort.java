package com.liverpool.ms_clp_blp.landing.ports;

import java.util.Optional;

import com.liverpool.ms_clp_blp.landing.models.SelectedStore;

/**
 * Outbound port for the store the shopper picked as favourite, resolved from the store id the
 * {@code favoriteStore} cookie carries.
 *
 * <p>Best effort by contract: a store the service does not know, or an unreachable store service,
 * answers {@link Optional#empty()} — the analytics values are then left unset, as digital_bff left
 * them.</p>
 */
public interface FavoriteStorePort {

    /**
     * @param brand   brand identifier
     * @param storeId the store id from the {@code favoriteStore} cookie
     * @return the store's code and name, or empty when it cannot be resolved
     */
    Optional<SelectedStore> findStore(String brand, String storeId);
}
