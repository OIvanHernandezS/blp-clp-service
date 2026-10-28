package com.liverpool.ms_clp_blp.landing;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;

import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

/**
 * Accumulates what happened while composing one page and emits the single summary line for the
 * request.
 *
 * <p>This is the piece that keeps observability cheap. Per-block detail sits at DEBUG, where it
 * costs nothing until an incident, while the one INFO line always carries the numbers that say
 * whether the page was healthy: how many blocks the CMS sent, how many were served, how many were
 * dropped and — grouped by reason — why. A page that quietly shrinks from 9 blocks to 7 is visible
 * without turning any log level up.</p>
 *
 * <p>Counters are concurrent because eager blocks resolve on virtual threads and record their own
 * drops from there.</p>
 */
final class CompositionTrace {

    private final long startNanos = System.nanoTime();
    private final Map<BlockDropReason, LongAdder> drops = new ConcurrentHashMap<>();
    private final AtomicInteger degraded = new AtomicInteger();
    private final AtomicInteger eagerResolved = new AtomicInteger();
    private final AtomicInteger deduplicated = new AtomicInteger();

    /**
     * Records one block that will not reach the response.
     *
     * @param reason why it was dropped
     */
    void dropped(BlockDropReason reason) {
        drops.computeIfAbsent(reason, key -> new LongAdder()).increment();
    }

    /** Records one block that failed to resolve and fell back to a placeholder or CMS content. */
    void degraded() {
        degraded.incrementAndGet();
    }

    /** Records one block resolved inline during the page request. */
    void resolvedEagerly() {
        eagerResolved.incrementAndGet();
    }

    /**
     * Records one eager resolution answered from the per-request memo rather than a second upstream
     * call — the same block uid authored more than once on the page.
     */
    void deduplicated() {
        deduplicated.incrementAndGet();
    }

    /**
     * Emits the request's summary line.
     *
     * @param log          the composition service's logger, so the line keeps its origin
     * @param pageType     BLP or CLP
     * @param blocksTotal  blocks the CMS definition carried, across every section
     * @param blocksServed blocks present in the response, across every section
     * @param session      the session the page was composed for
     */
    void logSummary(Logger log, PageType pageType, int blocksTotal, int blocksServed, SessionContext session) {
        LogEvents.at(log, LogEvents.LANDING_COMPOSED)
                .addKeyValue(LogFields.PAGE_TYPE, pageType)
                .addKeyValue(LogFields.BLOCKS_TOTAL, blocksTotal)
                .addKeyValue(LogFields.BLOCKS_SERVED, blocksServed)
                .addKeyValue(LogFields.BLOCKS_DROPPED, blocksTotal - blocksServed)
                .addKeyValue(LogFields.BLOCKS_DEGRADED, degraded.get())
                .addKeyValue(LogFields.EAGER_RESOLVED, eagerResolved.get())
                .addKeyValue(LogFields.EAGER_DEDUPLICATED, deduplicated.get() == 0 ? null : deduplicated.get())
                .addKeyValue(LogFields.DROPS_BY_REASON, dropsByReason())
                .addKeyValue(LogFields.BRAND, session.brand())
                .addKeyValue(LogFields.CHANNEL, session.channel())
                .addKeyValue(LogFields.AUTHENTICATED, session.authenticated())
                .addKeyValue(LogFields.ELAPSED_MS, elapsedMs())
                .log("Landing page composed");
    }

    /**
     * @return drop counts keyed by reason name, ordered by the enum so the field reads the same way
     *         every time; empty when nothing was dropped
     */
    Map<String, Long> dropsByReason() {
        Map<BlockDropReason, Long> ordered = new EnumMap<>(BlockDropReason.class);
        drops.forEach((reason, count) -> ordered.put(reason, count.sum()));
        Map<String, Long> byName = new LinkedHashMap<>();
        ordered.forEach((reason, count) -> byName.put(reason.name(), count));
        return byName;
    }

    long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
