/**
 * Landing page composition slice — answers {@code GET /blp/{locale}/**} and {@code GET /clp/{locale}/**}.
 *
 * <p>Inbound port: {@link com.liverpool.ms_clp_blp.landing.ports.LandingService}, implemented by
 * {@link com.liverpool.ms_clp_blp.landing.LandingUseCases}. The page entry ({@code page_blp} /
 * {@code page_clp}) is read from Contentstack by {@code category_id} and cached in-process; static
 * blocks are passed through, and {@code products_list} carousels are either inlined (eager) or
 * returned as placeholders — carrying their raw CMS content plus a resolution {@code url} — that the
 * frontend resolves against the sub-slice under {@code landing.blocks}. Category data, the category
 * redirect, the favourite store and the product blacklist are best-effort outbound ports. May depend
 * on {@code shared} and {@code clients} — never on anything inside {@code landing.blocks.*} (enforced
 * by ArchitectureTest).</p>
 *
 * <p>The response is a <strong>superset of digital_bff's {@code page-blp}/{@code page-clp}
 * endpoint</strong> (Rule 19): the raw Contentstack entry passed through, each composed section
 * ({@code blocks}, {@code top_content}, {@code bottom_content}) under its own key, flat blocks keyed by
 * {@code _content_type_uid}, and {@code categoryData}. It deliberately omits {@code globalData}, which
 * the storefront reads from ms-home. Expand-only — never rename, re-nest, or strip an existing
 * field.</p>
 */
package com.liverpool.ms_clp_blp.landing;
