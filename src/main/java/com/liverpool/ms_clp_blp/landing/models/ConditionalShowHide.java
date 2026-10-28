package com.liverpool.ms_clp_blp.landing.models;

import java.util.Map;

import com.liverpool.ms_clp_blp.shared.session.CohortMarkers;

/**
 * A block's {@code conditional_show_hide} rule, used to split shoppers into A/B cohorts by the value of
 * a request header or cookie.
 *
 * <p>A port of {@code ConditionalShowHideEvaluator} in {@code platform_commons}, so a carousel behaves
 * the same on a landing page as on PDP and PLP. The cohort marker is a tri-state: {@code true} means the
 * shopper is in the cohort, {@code false} means the experiment is off for them, and anything else —
 * not sent, or neither of those two words — leaves the cohort <em>unknown</em> (see
 * {@link CohortMarkers#parse(String)}).</p>
 *
 * <p>An active rule is two-sided: {@code show} keeps the block only for shoppers whose marker is
 * {@code true}, {@code hide} keeps it for everyone <em>except</em> them. Pairing the two on one marker
 * swaps one carousel for another. An unknown cohort drops the block whatever the action, because
 * serving the wrong arm of an experiment is worse than serving no carousel at all.</p>
 *
 * <p>Malformed rules fail open: an inactive rule, an unrecognised action, or a rule that names neither a
 * header nor a cookie is no rule at all, so a bad Contentstack save cannot blank a carousel for every
 * shopper. The legacy {@code header_value} field is intentionally not read — the cohort comes from the
 * value the request carries, never from a value configured here.</p>
 *
 * <p>The {@code conditional-show-hide} feature flag is not checked here: this record is framework-free
 * and has no access to flags, so every caller gates on the flag before consulting it.</p>
 *
 * @param active     whether the rule is switched on in Contentstack
 * @param action     {@code show} or {@code hide}, matched case-insensitively; anything else disables
 *                   the rule
 * @param headerName the request header carrying the marker; preferred over the cookie
 * @param cookieName the cookie carrying the marker, read when the header is absent or unreadable
 */
public record ConditionalShowHide(boolean active, String action, String headerName, String cookieName) {

    /** The Contentstack group holding the rule on a block. */
    public static final String ATTR_CONDITIONAL_SHOW_HIDE = "conditional_show_hide";

    /** The action that shows the block only to the cohort. */
    public static final String ACTION_SHOW = "show";

    /** The action that hides the block from the cohort only. */
    public static final String ACTION_HIDE = "hide";

    /** A block without a rule. */
    public static final ConditionalShowHide NONE = new ConditionalShowHide(false, null, null, null);

    static final String FIELD_ACTIVE = "active";
    static final String FIELD_ACTION = "action";
    static final String FIELD_HEADER_NAME = "header_name";
    static final String FIELD_COOKIE_NAME = "cookie_name";

    /**
     * Reads the rule from a block's raw attributes.
     *
     * <p>Lenient like {@link BlockAttributes}: the group is absent on older publications and may be only
     * partly filled, and {@code active} may arrive as a JSON boolean or its string form. A group that is
     * missing or is not an object yields {@link #NONE}.</p>
     *
     * @param attributes the block's raw Contentstack attributes, or its {@code ms_params}; may be null
     * @return the rule, never {@code null}
     */
    public static ConditionalShowHide from(Map<String, Object> attributes) {
        if (attributes == null
                || !(attributes.get(ATTR_CONDITIONAL_SHOW_HIDE) instanceof Map<?, ?> group)) {
            return NONE;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) group;
        return new ConditionalShowHide(
                BlockAttributes.bool(fields.get(FIELD_ACTIVE)),
                BlockAttributes.string(fields, FIELD_ACTION),
                BlockAttributes.string(fields, FIELD_HEADER_NAME),
                BlockAttributes.string(fields, FIELD_COOKIE_NAME));
    }

    /**
     * Whether this rule takes part in resolution at all.
     *
     * <p>False for an inactive rule and for the two ways an active one can be meaningless — an action
     * that is neither {@code show} nor {@code hide}, or nothing named to match on. A caller that sees
     * {@code false} treats the block as if it had no rule, and may inline it into a shared response.</p>
     *
     * @return true when the rule is active, has a recognised action, and names a header or a cookie
     */
    public boolean applies() {
        return active
                && (ACTION_SHOW.equalsIgnoreCase(action) || ACTION_HIDE.equalsIgnoreCase(action))
                && (isNamed(headerName) || isNamed(cookieName));
    }

    /**
     * Decides whether this rule removes the block for the shopper carrying these markers.
     *
     * @param markers the request's cohort markers; {@code null} counts as a request with none, which
     *                leaves the cohort unknown
     * @return {@code null} when the block is kept (including every rule that does not
     *         {@linkplain #applies() apply}); {@link BlockDropReason#CONDITIONAL_SHOW_HIDE_COHORT_UNKNOWN}
     *         when no readable marker arrived; {@link BlockDropReason#CONDITIONAL_SHOW_HIDE_EXCLUDED}
     *         when the marker places the shopper on the other side of the rule
     */
    public BlockDropReason dropReason(CohortMarkers markers) {
        if (!applies()) {
            return null;
        }
        Boolean inCohort = cohort(markers == null ? CohortMarkers.NONE : markers);
        if (inCohort == null) {
            return BlockDropReason.CONDITIONAL_SHOW_HIDE_COHORT_UNKNOWN;
        }
        boolean showForCohort = ACTION_SHOW.equalsIgnoreCase(action);
        return inCohort == showForCohort ? null : BlockDropReason.CONDITIONAL_SHOW_HIDE_EXCLUDED;
    }

    /**
     * Resolves the cohort, preferring the header: the cookie is consulted only when no header is named,
     * it was not sent, or its value was unreadable — so a stale cookie never overrides a header the
     * client did send.
     */
    private Boolean cohort(CohortMarkers markers) {
        Boolean fromHeader = markers.header(headerName);
        return fromHeader != null ? fromHeader : markers.cookie(cookieName);
    }

    private static boolean isNamed(String name) {
        return name != null && !name.isBlank();
    }
}
