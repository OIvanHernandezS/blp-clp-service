package com.liverpool.ms_clp_blp.shared.session;

import java.util.Locale;

/**
 * Classifies the raw {@code x-channel} request header into the small, closed set this service
 * reasons about. The single source of truth for "is this the app channel?" — before this class
 * existed, {@code EndpointUsageMetricsFilter}, {@code SessionContextAdapter},
 * {@code ShortcutsUseCases} and {@code LandingCompositionService} each re-declared their own local
 * {@code "APPS"} literal, and diverged: the real client contract is {@code APP} (singular), which
 * three of the four sites got wrong. Every future channel-branching call site must go through
 * {@link #from(String)} rather than adding a fifth local copy.
 *
 * <h3>The four defined channels</h3>
 * <p>{@code WEB}, {@code APP}, {@code KIOSCO} and {@code POS}. Kiosk and point-of-sale clients render
 * the web experience, so for now they behave as {@code WEB} everywhere a decision is taken
 * ({@link #isWebLike()}); they stay distinct values so usage metrics can tell them apart and so a
 * later divergence is a one-line change here rather than a hunt for call sites.</p>
 *
 * <p>{@link #from(String)} is lenient: a missing header reads as {@link #WEB}. It backs content
 * visibility, where every request needs an answer.</p>
 *
 * <p>Deliberately dependency-free (no Spring, no jakarta): {@code EndpointUsageMetricsFilter} runs
 * on every request, including ones that never reach a handler, and must not pay for a
 * request-scoped bean or a network call just to classify a header.</p>
 */
public enum Channel {

    WEB, APP, KIOSCO, POS, OTHER;

    /**
     * @param raw the {@code x-channel} header value, or {@code null}/blank when absent
     * @return {@link #WEB} when absent or blank; the matching defined channel (case-insensitive);
     *         {@link #OTHER} otherwise — including the legacy, incorrect {@code "APPS"} spelling
     */
    public static Channel from(String raw) {
        if (raw == null || raw.isBlank()) {
            return WEB;
        }
        return classify(raw);
    }

    /**
     * @return true when this is the native-app channel
     */
    public boolean isApp() {
        return this == APP;
    }

    /**
     * @return true for the channels that render the web experience: {@link #WEB}, {@link #KIOSCO}
     *         and {@link #POS}
     */
    public boolean isWebLike() {
        return this == WEB || this == KIOSCO || this == POS;
    }

    private static Channel classify(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "WEB" -> WEB;
            case "APP" -> APP;
            case "KIOSCO" -> KIOSCO;
            case "POS" -> POS;
            default -> OTHER;
        };
    }
}
