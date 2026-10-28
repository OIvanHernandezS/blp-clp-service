/**
 * Per-brand feature-flag configuration: how a brand's overrides get from Contentstack into every
 * running instance.
 *
 * <p>A sub-slice of {@code landing} rather than a top-level slice, because {@code landing} is what
 * consumes flags. It carries its own hexagon: an inbound port for the webhook, outbound ports for the
 * CMS and the durable store, and a per-instance cache that is the only part of it on the landing page
 * path. Only the products-list provider flags ({@code LandingFeatureFlags.ALL}) are ever read.</p>
 *
 * <p>The flow: Contentstack publishes an entry and calls the webhook → the receiving instance reads
 * the entry from Contentstack → it writes the brand's flags to MongoDB and evicts its own
 * cached copy → every other instance picks the change up when its copy expires. Propagation is
 * therefore bounded by {@code brand-flags.cache-ttl}, not instant; see
 * {@code adapters.cache.BrandFlagsCache} for why that trade is the right one on Cloud Run.</p>
 *
 * <p><strong>Nothing here may fail a page.</strong> Both outbound adapters translate every failure
 * into an empty result, so an unreachable CMS or store means flags resolve from
 * {@code landing.feature-flags} in the YAML — the behaviour that shipped before this existed.</p>
 *
 * <p>May depend on {@code landing.ports}, {@code landing.models}, {@code shared} and {@code clients}.
 * Must never depend on {@code landing.blocks} (enforced by ArchitectureTest).</p>
 */
package com.liverpool.ms_clp_blp.landing.brandflags;
