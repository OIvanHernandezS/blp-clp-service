/**
 * Infrastructure for the shared auth microservice — the {@code authRestClient} builder and its typed
 * connection properties used to exchange the {@code SessionId} cookie for the request's login/guest
 * state and profile id.
 *
 * <p>No ports, no domain types, no slice knowledge: reusable plumbing injected by
 * {@code shared.session} (the token-exchange adapter). Must never depend on {@code landing}
 * (enforced by ArchitectureTest).</p>
 */
package com.liverpool.ms_clp_blp.clients.auth;
