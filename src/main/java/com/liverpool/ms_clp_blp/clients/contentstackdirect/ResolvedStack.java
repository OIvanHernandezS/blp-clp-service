package com.liverpool.ms_clp_blp.clients.contentstackdirect;

import com.liverpool.config.properties.ContentStackConfig;
import com.liverpool.enums.Sites;

/**
 * One brand's resolved Contentstack stack: which stack, its credentials, and how entries belonging to
 * that brand are told apart once inside it.
 *
 * @param site        the stack hosting the brand
 * @param config      credentials, branch and environment for that stack
 * @param brandFilter value to match against {@code taxonomies.brand}; only meaningful when
 *                    {@code sharedStack}
 * @param sharedStack whether the stack hosts more than one brand, and therefore whether entries must
 *                    be filtered by brand
 */
public record ResolvedStack(Sites site, ContentStackConfig config, String brandFilter, boolean sharedStack) {
}
