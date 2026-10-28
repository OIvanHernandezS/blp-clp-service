/**
 * Infrastructure for the category-tree indexer — the declarative client, its isolated transport and
 * typed connection properties used to read the category a landing page belongs to.
 *
 * <p>No ports, no domain types, no slice knowledge: reusable plumbing injected by the landing slice's
 * category adapter. Must never depend on {@code landing} (enforced by ArchitectureTest).</p>
 */
package com.liverpool.ms_clp_blp.clients.categoryindexer;
