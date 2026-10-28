package com.liverpool.ms_clp_blp.landing;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for landing-page behaviour: feature flags, dynamic block resolution paths, and
 * the eager-resolution offset.
 *
 * <p>Feature flags allow per-provider enable/disable without redeployment (Rule 18 feature toggle).
 * Block endpoint paths are advertised in dynamic placeholders so the frontend knows where to resolve
 * each block independently. The offsets govern how many leading (above-the-fold) dynamic blocks are
 * resolved eagerly during the page request to avoid first-paint flickering, one value per audience.
 * All are overridable via environment variables.</p>
 *
 * @param featureFlags    map of feature-flag id to enabled state (e.g. {@code vertex=true})
 * @param blocks          map of block configuration keys to values (resolution endpoint paths)
 * @param eagerResolution eager-resolution tuning (one offset per audience)
 */
@ConfigurationProperties(prefix = "landing")
public record LandingProperties(
        Map<String, Boolean> featureFlags,
        Map<String, String> blocks,
        EagerResolution eagerResolution) {

    public LandingProperties {
        featureFlags = featureFlags == null ? Map.of() : Map.copyOf(featureFlags);
        blocks = blocks == null ? Map.of() : Map.copyOf(blocks);
        eagerResolution = eagerResolution == null ? EagerResolution.defaults() : eagerResolution;
    }

    /**
     * @return the number of leading dynamic blocks resolved eagerly by default for a logged-in session
     */
    public int loggedEagerOffset() {
        return eagerResolution.loggedOffset();
    }

    /**
     * @return the number of leading dynamic blocks resolved eagerly by default for a guest session
     */
    public int guestEagerOffset() {
        return eagerResolution.guestOffset();
    }

    /**
     * Eager-resolution tuning bound from {@code landing.eager-resolution.*}, one offset per audience
     * ({@code LANDING_EAGER_OFFSET_LOGGED} / {@code LANDING_EAGER_OFFSET_GUEST}).
     *
     * <p>The two defaults differ on purpose. A logged-in session's dynamic blocks may be personalised
     * and each resolves through its own endpoint, so inlining them only delays the shell — hence
     * {@code 0}, everything lazy. A guest session has nothing to personalise, so a single fully
     * inlined response beats a page-load followed by N block calls — hence a ceiling high enough to
     * cover every block a landing page realistically carries.</p>
     *
     * @param loggedOffset leading blocks (by visible, page-wide index) resolved eagerly for an
     *                     authenticated session; clamped to {@code >= 0}
     * @param guestOffset  leading blocks (by visible, page-wide index) resolved eagerly for a guest
     *                     session; clamped to {@code >= 0}
     */
    public record EagerResolution(int loggedOffset, int guestOffset) {

        static final int DEFAULT_LOGGED_OFFSET = 0;
        static final int DEFAULT_GUEST_OFFSET = 100;

        public EagerResolution {
            loggedOffset = Math.max(0, loggedOffset);
            guestOffset = Math.max(0, guestOffset);
        }

        static EagerResolution defaults() {
            return new EagerResolution(DEFAULT_LOGGED_OFFSET, DEFAULT_GUEST_OFFSET);
        }
    }
}
