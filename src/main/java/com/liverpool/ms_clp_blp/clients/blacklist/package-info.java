/**
 * Infrastructure for the per-brand product blacklist served by catalog-ingestion-service — the
 * declarative client, its isolated transport and typed connection properties (Rule 19 reference:
 * platform-commons {@code ComplementaryServicesClient#getRestrictedProducts} on branch
 * {@code feat/AHDV-401}, which ms_pdp_search uses for the same carousel filtering).
 *
 * <p>No ports, no domain types, no slice knowledge. Must never depend on {@code landing} (enforced by
 * ArchitectureTest).</p>
 */
package com.liverpool.ms_clp_blp.clients.blacklist;
