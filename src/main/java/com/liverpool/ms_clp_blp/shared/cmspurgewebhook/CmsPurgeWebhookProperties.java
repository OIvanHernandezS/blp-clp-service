package com.liverpool.ms_clp_blp.shared.cmspurgewebhook;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeAction;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeNetwork;

/**
 * Which Contentstack publishes purge the edge, and how.
 *
 * <p>{@link #action} and {@link #network} are this trigger's own keys rather than a reuse of
 * {@code cache-purge.action} / {@code cache-purge.network}. Those two are the defaults an <em>operator</em>
 * gets when they type neither parameter, and an operator rehearsing on staging should not also be
 * redirecting every editor's publish there. The webhook's target is a deployment decision of its own.</p>
 *
 * <p>{@code enabled} switches off only this trigger. The service-wide kill switch remains
 * {@code cache-purge.enabled}, which the use case checks separately — off, no purge happens by any
 * route.</p>
 *
 * @param contentTypes content type uids whose publish invalidates the edge, e.g. {@code page},
 *                     {@code flex}; a delivery about anything else is acknowledged and dropped
 * @param action       what to ask the CDN for; {@code invalidate} is the safe half of the pair
 * @param network      which Akamai network to purge; {@code production} is the point of the feature
 * @param cooldown     how long after a submitted purge further deliveries are collapsed into it;
 *                     {@code 0s} disables the collapsing
 * @param enabled      kill switch for CMS-triggered purges alone
 */
@ConfigurationProperties(prefix = "cms-purge-webhook")
public record CmsPurgeWebhookProperties(
        List<String> contentTypes,
        PurgeAction action,
        PurgeNetwork network,
        Duration cooldown,
        boolean enabled) {

    /**
     * Whether a publish of this content type should purge the edge.
     *
     * @param contentTypeUid the delivery's content type, possibly {@code null}
     * @return {@code true} when it is one of the configured types, ignoring case and surrounding blanks
     */
    public boolean handles(String contentTypeUid) {
        if (contentTypeUid == null || contentTypeUid.isBlank() || contentTypes == null) {
            return false;
        }
        String candidate = contentTypeUid.strip();
        return contentTypes.stream()
                .filter(configured -> configured != null && !configured.isBlank())
                .anyMatch(configured -> configured.strip().equalsIgnoreCase(candidate));
    }

    /**
     * @return the configured content types with blanks dropped, lowercased for display; empty when
     *         none is configured, which switches the trigger off in practice
     */
    public List<String> purgeableContentTypes() {
        if (contentTypes == null) {
            return List.of();
        }
        return contentTypes.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .toList();
    }

    /**
     * @return the configured action, or {@link PurgeAction#INVALIDATE} when unset — a missing value
     *         must never escalate to an eviction, the same reasoning {@code CachePurgeProperties} uses
     */
    public PurgeAction purgeAction() {
        return action == null ? PurgeAction.INVALIDATE : action;
    }

    /**
     * @return the configured network, or {@link PurgeNetwork#PRODUCTION} when unset; a publish that
     *         quietly purged staging would look exactly like a purge that worked
     */
    public PurgeNetwork purgeNetwork() {
        return network == null ? PurgeNetwork.PRODUCTION : network;
    }

    /**
     * @return the configured cooldown, or {@link Duration#ZERO} when unset or negative
     */
    public Duration purgeCooldown() {
        return cooldown == null || cooldown.isNegative() ? Duration.ZERO : cooldown;
    }
}
