package com.liverpool.ms_clp_blp.shared.cachepurge.models;

import java.util.List;

/**
 * Every purge submitted by one operator request.
 *
 * <p>A full purge is necessarily more than one call — Akamai separates URL and tag purges into
 * different endpoints — so the unit an operator gets back is a list, not a single outcome.</p>
 *
 * <p>There is no partial-success state to model: the use case submits the groups in order and lets
 * the first failure propagate, so a report that exists at all describes purges that were all
 * accepted. That is deliberate — a half-purged edge reported as a 200 is the outcome most likely to
 * be mistaken for a completed purge.</p>
 *
 * @param outcomes one entry per submitted purge, in submission order
 */
public record PurgeReport(List<PurgeOutcome> outcomes) {

    /**
     * @param outcomes the submitted purges, defensively copied
     */
    public PurgeReport {
        outcomes = List.copyOf(outcomes);
    }

    /**
     * @return total objects purged across every call in this run
     */
    public int totalObjects() {
        return outcomes.stream().mapToInt(PurgeOutcome::objectCount).sum();
    }
}
