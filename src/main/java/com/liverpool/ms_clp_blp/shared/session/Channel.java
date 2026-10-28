package com.liverpool.ms_clp_blp.shared.session;

import java.util.Locale;
import java.util.Optional;

/**
 * Classifies the raw {@code x-channel} request header into the small, closed set this service
 * reasons about. The single source of truth for "is this the app channel?" — before this class
 * existed, {@code EndpointUsageMetricsFilter}, {@code SessionContextAdapter},
 * {@code ShortcutsUseCases} and {@code LandingCompositionService} each re-declared their own local
 * {@code "APPS"} literal, and diverged: the real client contract is {@code APP} (singular), which
 * three of the four sites got wrong. Every future channel-branching call site must go through
 * {@link #from(String)} or {@link #parse(String)} rather than adding a fifth local copy.
 *
 * <h3>The four defined channels</h3>
 * <p>{@code WEB}, {@code APP}, {@code KIOSCO} and {@code POS}. Kiosk and point-of-sale clients render
 * the web experience, so for now they behave as {@code WEB} everywhere a decision is taken
 * ({@link #isWebLike()}); they stay distinct values so usage metrics can tell them apart and so a
 * later divergence is a one-line change here rather than a hunt for call sites.</p>
 *
 * <h3>Two entry points, on purpose</h3>
 * <ul>
 *   <li>{@link #from(String)} is lenient: a missing header reads as {@link #WEB}. It backs content
 *       visibility and metrics, where every request needs an answer.</li>
 *   <li>{@link #parse(String)} is strict: a missing or unrecognised header has <em>no</em> channel.
 *       It backs the auth {@code tenant}, which must never be guessed — exchanging a session with the
 *       wrong tenant/client is worse than not exchanging it at all.</li>
 * </ul>
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
     * Strict counterpart of {@link #from(String)}: never infers a channel the caller did not send.
     *
     * @param raw the {@code x-channel} header value, or {@code null}/blank when absent
     * @return the matching defined channel (case-insensitive), or empty when the header is absent,
     *         blank, or not one of {@code WEB}/{@code APP}/{@code KIOSCO}/{@code POS}
     */
    public static Optional<Channel> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Channel channel = classify(raw);
        return channel == OTHER ? Optional.empty() : Optional.of(channel);
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
