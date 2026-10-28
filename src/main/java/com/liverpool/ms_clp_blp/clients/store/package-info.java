/**
 * Infrastructure for store-service — the declarative client, its isolated transport and typed
 * connection properties used to resolve the store id carried by the {@code favoriteStore} cookie.
 *
 * <p>No ports, no domain types, no slice knowledge. Must never depend on {@code landing} (enforced by
 * ArchitectureTest).</p>
 */
package com.liverpool.ms_clp_blp.clients.store;
