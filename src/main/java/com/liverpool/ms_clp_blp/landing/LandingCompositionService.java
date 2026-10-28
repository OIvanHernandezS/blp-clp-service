package com.liverpool.ms_clp_blp.landing;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

import com.liverpool.ms_clp_blp.landing.models.AudienceFilter;
import com.liverpool.ms_clp_blp.landing.models.BlockDefinition;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.BlockKind;
import com.liverpool.ms_clp_blp.landing.models.BlockParamsRegistry;
import com.liverpool.ms_clp_blp.landing.models.BlockResolution;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionCatalog;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionContext;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;
import com.liverpool.ms_clp_blp.landing.models.BlockResolverRegistry;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.landing.models.DynamicBlockStatus;
import com.liverpool.ms_clp_blp.landing.models.DynamicPlaceholder;
import com.liverpool.ms_clp_blp.landing.models.LandingBlock;
import com.liverpool.ms_clp_blp.landing.models.LandingDefinition;
import com.liverpool.ms_clp_blp.landing.models.LandingFeatureFlags;
import com.liverpool.ms_clp_blp.landing.models.LandingPage;
import com.liverpool.ms_clp_blp.landing.models.LoadStrategy;
import com.liverpool.ms_clp_blp.landing.models.PageSection;
import com.liverpool.ms_clp_blp.landing.models.ResolvedBlock;
import com.liverpool.ms_clp_blp.landing.models.ResolvedContent;
import com.liverpool.ms_clp_blp.landing.models.SelectedStore;
import com.liverpool.ms_clp_blp.landing.models.StaticBlock;
import com.liverpool.ms_clp_blp.landing.ports.BlockResolver;
import com.liverpool.ms_clp_blp.landing.ports.FeatureFlagPort;
import com.liverpool.ms_clp_blp.shared.errors.Causes;
import com.liverpool.ms_clp_blp.shared.errors.ClpBlpException;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;
import com.liverpool.ms_clp_blp.shared.logging.MdcPropagation;
import com.liverpool.ms_clp_blp.shared.session.Channel;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

/**
 * Pure domain logic that composes a {@link LandingPage} from a raw {@link LandingDefinition} (Rule 18).
 *
 * <p>Responsibilities: preserve Contentstack order within every {@link PageSection}, apply
 * audience/channel visibility, classify each block as static or dynamic, and — for dynamic blocks —
 * decide between <em>eager</em> and <em>lazy</em> resolution by the block's position relative to the
 * configured offset:</p>
 * <ul>
 *   <li><strong>index &lt; offset (above the fold):</strong> resolved eagerly. The block's
 *       {@link BlockResolver} is invoked during the page request (on a virtual thread) and the detail
 *       is inlined, avoiding first-paint flickering.</li>
 *   <li><strong>index &ge; offset:</strong> emitted as a {@link DynamicPlaceholder} advertising the
 *       endpoint (url + http method + {@link LoadStrategy#LAZY}); the frontend resolves it.</li>
 * </ul>
 *
 * <p>The index is page-wide: sections are composed in {@link PageSection} order and the position
 * keeps counting across them, so the offset means "the first N visible blocks of the page" whatever
 * section they sit in. Every eager block of every section is resolved concurrently in one fan-out
 * while order is always preserved. Resilience stays per-block: a resolver failure or an open circuit
 * breaker degrades that block alone to a {@link DynamicBlockStatus#UNAVAILABLE} placeholder — the page
 * never fails as a whole. No Spring; the only I/O is delegated to injected resolvers, so the class
 * stays unit-testable with fakes (Rule 1).</p>
 */
public class LandingCompositionService {

    private static final Logger log = LoggerFactory.getLogger(LandingCompositionService.class);

    private static final String ATTR_FALLBACK = "fallback";
    private static final String ATTR_IS_ACTIVE = "is_active";
    private static final String ATTR_TIMER = "timer";
    private static final String KEY_FOOTER = "footer";
    private static final String KEY_MENU_ITEMS = "menu_items";
    private static final String KEY_AUDIENCE_FILTER = "audience_filter";
    private static final String FLAG_CONDITIONAL_SHOW_HIDE = LandingFeatureFlags.CONDITIONAL_SHOW_HIDE;

    private final FeatureFlagPort featureFlags;
    private final BlockResolutionCatalog resolutionCatalog;
    private final BlockResolverRegistry resolverRegistry;
    private final BlockParamsRegistry paramsRegistry;
    private final int loggedEagerOffset;
    private final int guestEagerOffset;
    /** Stateless and pure, so it is created here rather than injected. */
    private final BffEventDecorator eventDecorator = new BffEventDecorator();

    /**
     * Full constructor with eager-resolution collaborators, the {@code ms_params} projection, and one
     * default offset per audience.
     *
     * @param featureFlags      runtime flag evaluation for dynamic blocks
     * @param resolutionCatalog endpoint + flag metadata per dynamic block type
     * @param resolverRegistry  eager resolver strategies per dynamic block type
     * @param paramsRegistry    {@code ms_params} projections per dynamic block type
     * @param loggedEagerOffset leading blocks resolved eagerly for an authenticated session when no
     *                          per-request override is supplied
     * @param guestEagerOffset  leading blocks resolved eagerly for a guest session when no per-request
     *                          override is supplied
     */
    public LandingCompositionService(FeatureFlagPort featureFlags,
                                     BlockResolutionCatalog resolutionCatalog,
                                     BlockResolverRegistry resolverRegistry,
                                     BlockParamsRegistry paramsRegistry,
                                     int loggedEagerOffset,
                                     int guestEagerOffset) {
        this.featureFlags = featureFlags;
        this.resolutionCatalog = resolutionCatalog;
        this.resolverRegistry = resolverRegistry;
        this.paramsRegistry = paramsRegistry == null ? BlockParamsRegistry.empty() : paramsRegistry;
        this.loggedEagerOffset = Math.max(0, loggedEagerOffset);
        this.guestEagerOffset = Math.max(0, guestEagerOffset);
    }

    /**
     * Constructor applying one offset to <em>both</em> audiences, with a {@code ms_params} projection.
     *
     * @param featureFlags       runtime flag evaluation for dynamic blocks
     * @param resolutionCatalog  endpoint + flag metadata per dynamic block type
     * @param resolverRegistry   eager resolver strategies per dynamic block type
     * @param paramsRegistry     {@code ms_params} projections per dynamic block type
     * @param defaultEagerOffset number of leading blocks resolved eagerly, guest or logged alike
     */
    public LandingCompositionService(FeatureFlagPort featureFlags,
                                     BlockResolutionCatalog resolutionCatalog,
                                     BlockResolverRegistry resolverRegistry,
                                     BlockParamsRegistry paramsRegistry,
                                     int defaultEagerOffset) {
        this(featureFlags, resolutionCatalog, resolverRegistry, paramsRegistry, defaultEagerOffset,
                defaultEagerOffset);
    }

    /**
     * Constructor applying one offset to <em>both</em> audiences. Production wiring always
     * distinguishes them (see {@code LandingConfig}); this arity keeps the tests that only care about
     * "N blocks eager, the rest lazy" free of an audience they do not exercise.
     *
     * @param featureFlags       runtime flag evaluation for dynamic blocks
     * @param resolutionCatalog  endpoint + flag metadata per dynamic block type
     * @param resolverRegistry   eager resolver strategies per dynamic block type
     * @param defaultEagerOffset number of leading blocks resolved eagerly, guest or logged alike
     */
    public LandingCompositionService(FeatureFlagPort featureFlags,
                                     BlockResolutionCatalog resolutionCatalog,
                                     BlockResolverRegistry resolverRegistry,
                                     int defaultEagerOffset) {
        this(featureFlags, resolutionCatalog, resolverRegistry, BlockParamsRegistry.empty(),
                defaultEagerOffset, defaultEagerOffset);
    }

    /**
     * Convenience constructor for lazy-only composition (offset 0, no resolvers) — every dynamic
     * block becomes a placeholder.
     *
     * @param featureFlags      runtime flag evaluation for dynamic blocks
     * @param resolutionCatalog endpoint + flag metadata per dynamic block type
     */
    public LandingCompositionService(FeatureFlagPort featureFlags, BlockResolutionCatalog resolutionCatalog) {
        this(featureFlags, resolutionCatalog, new BlockResolverRegistry(Map.of()), 0);
    }

    /**
     * Composes the page with the offset configured for the session's audience, no gateway headers and
     * no favourite store.
     *
     * @param definition raw landing definition from the CMS
     * @param session    resolved session context
     * @return the composed page, without category data
     */
    public LandingPage compose(LandingDefinition definition, SessionContext session) {
        return compose(definition, session, Map.of(), null, null);
    }

    /**
     * Composes the page without a favourite store.
     *
     * @param definition          raw landing definition from the CMS
     * @param session             resolved session context
     * @param gatewayHeaders      propagated {@code x-*} request headers (identity for personalisation)
     * @param eagerOffsetOverride optional per-request offset; {@code null} uses the audience default
     * @return the composed page, without category data
     */
    public LandingPage compose(LandingDefinition definition,
                               SessionContext session,
                               Map<String, String> gatewayHeaders,
                               Integer eagerOffsetOverride) {
        return compose(definition, session, gatewayHeaders, eagerOffsetOverride, null);
    }

    /**
     * Composes the page, resolving above-the-fold dynamic blocks eagerly and the rest lazily, then
     * filling the backend-owned analytics values.
     *
     * @param definition          raw landing definition from the CMS
     * @param session             resolved session context
     * @param gatewayHeaders      propagated {@code x-*} request headers (identity for personalisation)
     * @param eagerOffsetOverride optional per-request offset; {@code null} uses the default configured
     *                            for the session's audience (guest or logged)
     * @param store               the shopper's favourite store for {@code selected_store.*} entries, or
     *                            {@code null}
     * @return the composed page, without category data (the use case attaches it)
     */
    public LandingPage compose(LandingDefinition definition,
                               SessionContext session,
                               Map<String, String> gatewayHeaders,
                               Integer eagerOffsetOverride,
                               SelectedStore store) {
        CompositionTrace trace = new CompositionTrace();
        Map<PageSection, List<BlockPlan>> plans =
                planSections(definition, session, effectiveOffset(eagerOffsetOverride, session), trace);
        Map<PageSection, List<LandingBlock>> sections = resolvePlans(plans, session, gatewayHeaders, trace);

        // Analytics values run last and page-wide: the index counter is positional, so it can only be
        // correct once drops and ordering are settled in every section.
        List<LandingBlock> allBlocks = new ArrayList<>();
        sections.values().forEach(allBlocks::addAll);
        BffEventDecorator.Decorated decorated = eventDecorator.decorate(
                allBlocks, filterFooterByAudience(definition.entryMetadata(), session), store);
        Map<PageSection, List<LandingBlock>> decoratedSections = split(sections, decorated.blocks());

        trace.logSummary(log, definition.pageType(), definition.blockCount(), allBlocks.size(), session);
        return new LandingPage(definition.pageType(), definition.locale(), decorated.entryMetadata(),
                decoratedSections, null);
    }

    // ── planning (pure, no I/O) ────────────────────────────────────────────────────────────────────

    /**
     * Classifies every section in {@link PageSection} order with one running position, so the eager
     * offset is measured against the page rather than restarted per section.
     */
    private Map<PageSection, List<BlockPlan>> planSections(LandingDefinition definition, SessionContext session,
                                                           int offset, CompositionTrace trace) {
        Map<PageSection, List<BlockPlan>> plans = new EnumMap<>(PageSection.class);
        int[] position = {0};
        definition.sections().forEach((section, blocks) ->
                plans.put(section, planBlocks(blocks, session, offset, position, trace)));
        return plans;
    }

    /**
     * Classifies one ordered list of blocks.
     *
     * @param position the page-wide visible position, advanced for every block that survives
     */
    private List<BlockPlan> planBlocks(List<BlockDefinition> blocks, SessionContext session, int offset,
                                       int[] position, CompositionTrace trace) {
        List<BlockPlan> plans = new ArrayList<>();
        for (BlockDefinition raw : blocks) {
            BlockDropReason reason = dropReason(raw, session);
            if (reason != null) {
                recordDrop(raw, reason, session, trace);
                continue;
            }
            plans.add(classify(raw, position[0] < offset, session));
            position[0]++;
        }
        return plans;
    }

    /**
     * Returns the first rule that removes this block from the page, or {@code null} when it survives.
     * The answer names the rule that fired, which is the difference between an operator reading a log
     * line and an operator reading this file.
     */
    private BlockDropReason dropReason(BlockDefinition raw, SessionContext session) {
        if (!matchesAudience(raw, session)) {
            return BlockDropReason.AUDIENCE_MISMATCH;
        }
        if (!enabledForChannel(raw, session)) {
            return BlockDropReason.CHANNEL_DISABLED;
        }
        if (isExpiredCountdown(raw)) {
            return BlockDropReason.COUNTDOWN_EXPIRED;
        }
        if (isGuestOnlyBlockedForAuthenticated(raw, session)) {
            return BlockDropReason.GUEST_ONLY_FOR_AUTHENTICATED;
        }
        return null;
    }

    private void recordDrop(BlockDefinition raw, BlockDropReason reason, SessionContext session,
                            CompositionTrace trace) {
        trace.dropped(reason);
        LogEvents.at(log, LogEvents.BLOCK_DROPPED)
                .addKeyValue(LogFields.BLOCK_ID, raw.uid())
                .addKeyValue(LogFields.BLOCK_TYPE, raw.contentTypeUid())
                .addKeyValue(LogFields.REASON, reason)
                .addKeyValue(LogFields.AUTHENTICATED, session.authenticated())
                .addKeyValue(LogFields.CHANNEL, session.channel())
                .log("Block dropped during composition");
    }

    /**
     * Resolves the offset this request composes with: an explicit {@code ?eagerOffset=N} always wins,
     * otherwise the default configured for the session's audience. A request is either guest or
     * logged, never both, so one override needs no per-audience form.
     */
    private int effectiveOffset(Integer override, SessionContext session) {
        if (override != null) {
            return Math.max(0, override);
        }
        return session.isGuest() ? guestEagerOffset : loggedEagerOffset;
    }

    /**
     * Classifies one visible block into a resolution plan. A dynamic type without resolution config
     * (or an unknown type) is treated as static pass-through so content is never dropped (Rule 18).
     *
     * @param withinOffset whether the block's page-wide position falls inside the eager offset
     */
    private BlockPlan classify(BlockDefinition raw, boolean withinOffset, SessionContext session) {
        BlockType type = BlockType.fromContentTypeUid(raw.contentTypeUid());
        if (type.kind() != BlockKind.DYNAMIC) {
            return BlockPlan.staticBlock(raw, type);
        }
        String brand = session.brand();
        BlockResolutionKey key = BlockResolutionKey.of(type, raw.sourceOfData());
        Optional<BlockResolution> resolution = resolutionCatalog.forKey(key);
        if (resolution.isEmpty()) {
            // No adapter owns this (type, source) pair — e.g. source_of_data=lob or recently_viewed, or a
            // source added in the CMS before it is implemented here. Fall back to static pass-through:
            // the content survives (Rule 18) and, critically, the block is never routed to whichever
            // adapter happens to share its content type.
            LogEvents.at(log, LogEvents.BLOCK_NO_RESOLVER)
                    .addKeyValue(LogFields.BLOCK_ID, raw.uid())
                    .addKeyValue(LogFields.BLOCK_TYPE, type.contentTypeUid())
                    .addKeyValue(LogFields.SOURCE, raw.sourceOfData())
                    .log("No adapter owns this block type and source; serving its CMS content as static");
            return BlockPlan.staticBlock(raw, type);
        }
        BlockResolution meta = resolution.get();
        // Computed before the flag check so a DISABLED block advertises its parameters too: the flag can
        // be turned back on without the frontend having re-fetched the page.
        Map<String, Object> msParams = paramsRegistry.paramsFor(key, raw.uid(), raw.attributes());
        if (!featureFlags.isEnabled(meta.featureFlagId(), brand)) {
            return BlockPlan.lazy(raw, type, meta, DynamicBlockStatus.DISABLED, msParams);
        }
        Optional<BlockResolver> resolver = eagerResolver(raw, key, withinOffset, brand);
        if (resolver.isPresent()) {
            return BlockPlan.eager(raw, type, meta, resolver.get(), msParams);
        }
        return BlockPlan.lazy(raw, type, meta, DynamicBlockStatus.AVAILABLE, msParams);
    }

    /**
     * Returns the resolver that inlines this block into the page, or empty when it must stay lazy.
     *
     * <p>A block inside the offset is still kept lazy when an A/B {@code conditional_show_hide} rule
     * splits its content by cohort. The page is cached at the edge in one guest slot per brand, so an
     * inlined carousel would be whichever arm the first caller happened to get, served to every cohort
     * until the entry expires. As a placeholder it resolves per request against the products-list
     * endpoint, which reads the caller's own marker.</p>
     *
     * @param withinOffset whether the block's position falls inside the eager offset
     */
    private Optional<BlockResolver> eagerResolver(BlockDefinition raw, BlockResolutionKey key,
                                                  boolean withinOffset, String brand) {
        if (!withinOffset) {
            return Optional.empty();
        }
        if (splitsByCohort(raw, brand)) {
            LogEvents.at(log, LogEvents.BLOCK_DEFERRED)
                    .addKeyValue(LogFields.BLOCK_ID, raw.uid())
                    .addKeyValue(LogFields.BLOCK_TYPE, raw.contentTypeUid())
                    .addKeyValue(LogFields.SOURCE, raw.sourceOfData())
                    .addKeyValue(LogFields.FLAG_ID, FLAG_CONDITIONAL_SHOW_HIDE)
                    .log("Block splits by A/B cohort; served lazy so the shared page carries no cohort");
            return Optional.empty();
        }
        return resolverRegistry.forKey(key);
    }

    /**
     * Whether an applicable {@code conditional_show_hide} rule makes this block's content depend on the
     * caller's cohort. Reads the flag only for a block that carries such a rule, so ordinary blocks cost
     * no extra lookup — and with the flag off the rule is ignored, so the block is inlined as before.
     */
    private boolean splitsByCohort(BlockDefinition raw, String brand) {
        return ConditionalShowHide.from(raw.attributes()).applies()
                && featureFlags.isEnabled(FLAG_CONDITIONAL_SHOW_HIDE, brand);
    }

    // ── resolution (eager blocks run concurrently on virtual threads) ──────────────────────────────

    /**
     * Runs every eager resolution of every section and assembles each section's ordered block list.
     *
     * <p><strong>One fan-out for the whole page.</strong> Every eager plan of every section is
     * submitted to a single per-request virtual-thread executor before anything is joined, so the
     * page's latency is the slowest call rather than the sum of the sections.</p>
     *
     * <p><strong>One call per distinct block.</strong> Submissions are memoised by
     * {@code (resolution key, block id)}: the same {@code products_list} entry referenced twice on a
     * page is resolved once. The memo is scoped to this one call and keyed on nothing session-derived,
     * so it is not a cache: it cannot outlive the request or be read by another shopper.</p>
     */
    private Map<PageSection, List<LandingBlock>> resolvePlans(Map<PageSection, List<BlockPlan>> plans,
                                                              SessionContext session,
                                                              Map<String, String> gatewayHeaders,
                                                              CompositionTrace trace) {
        Map<MemoKey, Future<ResolvedContent>> pending = new HashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            plans.values().forEach(sectionPlans ->
                    submitEager(sectionPlans, session, gatewayHeaders, executor, pending, trace));
            Map<PageSection, List<LandingBlock>> sections = new EnumMap<>(PageSection.class);
            plans.forEach((section, sectionPlans) ->
                    sections.put(section, materialize(sectionPlans, pending, trace)));
            return sections;
        }
    }

    /** Submits each distinct eager resolution of one section exactly once. */
    private void submitEager(List<BlockPlan> plans,
                             SessionContext session,
                             Map<String, String> gatewayHeaders,
                             ExecutorService executor,
                             Map<MemoKey, Future<ResolvedContent>> pending,
                             CompositionTrace trace) {
        for (BlockPlan plan : plans) {
            if (plan.mode() != Mode.EAGER) {
                continue;
            }
            MemoKey key = MemoKey.of(plan);
            if (pending.containsKey(key)) {
                trace.deduplicated();
                continue;
            }
            BlockResolutionContext ctx = toContext(plan, session, gatewayHeaders);
            // Wrapped so the block's own log lines still carry the request's correlation ids:
            // a virtual thread starts with an empty MDC (see MdcPropagation).
            pending.put(key, executor.submit(MdcPropagation.wrap(() -> plan.resolver().resolve(ctx))));
        }
    }

    /**
     * Turns one section's plans into its ordered block list, joining each eager resolution as it is
     * reached. A {@code null} means the block is dropped: a resolver that chose to.
     */
    private List<LandingBlock> materialize(List<BlockPlan> plans,
                                           Map<MemoKey, Future<ResolvedContent>> pending,
                                           CompositionTrace trace) {
        List<LandingBlock> out = new ArrayList<>(plans.size());
        for (BlockPlan plan : plans) {
            LandingBlock block = switch (plan.mode()) {
                case STATIC -> new StaticBlock(plan.raw().uid(), plan.type(), plan.raw().attributes());
                case LAZY -> toPlaceholder(plan, plan.status(), LoadStrategy.LAZY);
                case EAGER -> materializeEager(plan, pending, trace);
            };
            if (block != null) {
                out.add(block);
            }
        }
        return List.copyOf(out);
    }

    /** Joins one eager plan's shared resolution and turns it into this plan's own block. */
    private LandingBlock materializeEager(BlockPlan plan,
                                         Map<MemoKey, Future<ResolvedContent>> pending,
                                         CompositionTrace trace) {
        Future<ResolvedContent> future = pending.get(MemoKey.of(plan));
        if (future == null) {
            // Unreachable: submitEager puts a future for every eager plan before anything is joined.
            // Degrading rather than throwing keeps a defect here from failing the whole page.
            return degrade(plan);
        }
        return joinOrDegrade(plan, future, trace);
    }

    /**
     * Turns one shared {@link ResolvedContent} into this plan's own block, or records the drop.
     *
     * <p>The content may be shared with another plan for the same block uid (see the memo in
     * {@link #resolvePlans}), which is why the block is built here from the plan rather than inside the
     * resolver task: the payload is identical for the same CMS entry, but the plan is what knows where
     * this particular occurrence sits and what it advertises.</p>
     */
    private LandingBlock toResolvedBlock(BlockPlan plan, ResolvedContent content, CompositionTrace trace) {
        if (content == null) {
            // The strategy chose to drop this block (e.g. fewer products than min_of_products, or an
            // audience the provider does not serve). The adapter has already logged the specific
            // reason; this records that the drop happened, so the summary's count matches the response
            // even when DEBUG is off — digital_bff removed such a block from the page the same way.
            trace.dropped(BlockDropReason.RESOLVER_RETURNED_EMPTY);
            LogEvents.at(log, LogEvents.BLOCK_DROPPED)
                    .addKeyValue(LogFields.BLOCK_ID, plan.raw().uid())
                    .addKeyValue(LogFields.BLOCK_TYPE, plan.type().contentTypeUid())
                    .addKeyValue(LogFields.SOURCE, plan.raw().sourceOfData())
                    .addKeyValue(LogFields.REASON, BlockDropReason.RESOLVER_RETURNED_EMPTY)
                    .log("Resolver returned no content for this session; block dropped");
            return null;
        }
        trace.resolvedEagerly();
        return new ResolvedBlock(
                plan.raw().uid(),
                plan.type(),
                plan.raw().attributes(),
                content.payload(),
                plan.resolution().resolutionPath(),
                plan.resolution().httpMethod(),
                LoadStrategy.EAGER,
                DynamicBlockStatus.AVAILABLE,
                plan.resolution().blockIdInPath(),
                plan.msParams());
    }

    /**
     * Joins one off-thread result, or degrades the block on failure so the page still renders: a
     * dynamic block becomes a lazy {@link DynamicBlockStatus#UNAVAILABLE} placeholder. The failure is
     * logged and counted as degraded.
     */
    private LandingBlock joinOrDegrade(BlockPlan plan, Future<ResolvedContent> future,
                                       CompositionTrace trace) {
        try {
            return toResolvedBlock(plan, future.get(), trace);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logDegradation(plan, e, trace).log("Block resolution was interrupted; degrading the block");
            return degrade(plan);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            logDegradation(plan, cause, trace).log("Block resolution failed; degrading the block");
            return degrade(plan);
        }
    }

    /**
     * Builds the degradation line with everything known about the failure.
     *
     * <p>A typed {@link ClpBlpException} contributes its error code, category and its own context
     * (which upstream answered what), so the line says <em>why</em> the block failed rather than
     * only that it did. An untyped cause is a different animal: nothing in this service is supposed
     * to throw one, so it is a defect here rather than an upstream outage, and it is logged at ERROR
     * with the stack trace instead of being filed away as routine degradation.</p>
     */
    private LoggingEventBuilder logDegradation(BlockPlan plan, Throwable cause, CompositionTrace trace) {
        trace.degraded();
        boolean typed = cause instanceof ClpBlpException;

        // Merged before emitting: the exception's own context overlaps with the plan's (both name the
        // block), and emitting a key twice would render it twice in the console pattern.
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(LogFields.BLOCK_ID, plan.raw().uid());
        fields.put(LogFields.BLOCK_TYPE, plan.type().contentTypeUid());
        fields.put(LogFields.SOURCE, plan.raw().sourceOfData());
        fields.put(LogFields.ERROR_CLASS, cause.getClass().getSimpleName());
        fields.put(LogFields.CAUSE_CHAIN, Causes.chain(cause));
        fields.put(LogFields.DETAIL, Causes.rootMessage(cause));
        if (cause instanceof ClpBlpException domain) {
            fields.put(LogFields.ERROR_CODE, domain.getErrorCode());
            fields.put(LogFields.CATEGORY, domain.getCategory());
            fields.put(LogFields.RETRYABLE, domain.isRetryable());
            fields.putAll(domain.context());
        }

        LoggingEventBuilder builder =
                LogEvents.at(log, LogEvents.BLOCK_DEGRADED, typed ? Level.WARN : Level.ERROR);
        fields.forEach(builder::addKeyValue);
        return typed ? builder : builder.setCause(cause);
    }

    /**
     * Fallback for a block whose off-thread resolution did not complete. A static plan carries no
     * {@link BlockResolution}, so it cannot become a placeholder — it keeps its CMS content (Rule 18
     * — never drop content because a resolution failed).
     */
    private LandingBlock degrade(BlockPlan plan) {
        if (plan.mode() != Mode.STATIC) {
            return toPlaceholder(plan, DynamicBlockStatus.UNAVAILABLE, LoadStrategy.LAZY);
        }
        return new StaticBlock(plan.raw().uid(), plan.type(), plan.raw().attributes());
    }

    private DynamicPlaceholder toPlaceholder(BlockPlan plan, DynamicBlockStatus status, LoadStrategy loadStrategy) {
        Object fallback = plan.raw().attributes().get(ATTR_FALLBACK);
        return new DynamicPlaceholder(
                plan.raw().uid(),
                plan.type(),
                plan.raw().attributes(),
                plan.resolution().resolutionPath(),
                fallback,
                plan.resolution().featureFlagId(),
                status,
                plan.resolution().httpMethod(),
                loadStrategy,
                plan.resolution().blockIdInPath(),
                plan.msParams());
    }

    private BlockResolutionContext toContext(BlockPlan plan, SessionContext session, Map<String, String> headers) {
        return new BlockResolutionContext(
                plan.raw().uid(),
                plan.type(),
                plan.raw().attributes(),
                session,
                headers,
                plan.resolution().resolutionPath(),
                plan.resolution().httpMethod());
    }

    /**
     * Splits the decorated, page-wide block list back into its sections. The decorator rebuilds each
     * block one-for-one and in order, so each section takes back exactly as many blocks as it gave.
     */
    private static Map<PageSection, List<LandingBlock>> split(Map<PageSection, List<LandingBlock>> sections,
                                                              List<LandingBlock> decorated) {
        Map<PageSection, List<LandingBlock>> out = new EnumMap<>(PageSection.class);
        int from = 0;
        for (Map.Entry<PageSection, List<LandingBlock>> entry : sections.entrySet()) {
            int to = from + entry.getValue().size();
            out.put(entry.getKey(), List.copyOf(decorated.subList(from, to)));
            from = to;
        }
        return out;
    }

    // ── visibility guards ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns true when the block's {@code audience_filter} admits this session. An absent filter
     * admits everyone. Audience and channel are separate guards only so a drop can name which of the
     * two removed the block (Rule 19 reference: digital_bff {@code shouldPopulate}).
     */
    private boolean matchesAudience(BlockDefinition raw, SessionContext session) {
        return AudienceFilter.from(raw.audienceFilter()).visibleFor(session.authenticated());
    }

    /**
     * Returns true when the block is enabled for the requesting channel ({@code enable_on_web} /
     * {@code enable_on_apps}, both defaulting to enabled).
     */
    private boolean enabledForChannel(BlockDefinition raw, SessionContext session) {
        // KIOSCO and POS render the web experience, so they read the web toggle.
        return Channel.from(session.channel()).isWebLike() ? raw.enabledOnWeb() : raw.enabledOnApps();
    }

    /**
     * Returns true when the block is a {@code countdown} that must be dropped because it is inactive or
     * its timer has already elapsed (legacy {@code countdown.strategy.ts}). Composition-time visibility
     * only — no external call.
     *
     * <p>Fails open: a non-countdown block, a missing/unparseable {@code timer}, or an absent
     * {@code is_active} flag never drops the block, so content is preserved when the CMS shape is
     * unexpected (Rule 18 — never drop content unintentionally).</p>
     */
    private boolean isExpiredCountdown(BlockDefinition raw) {
        if (BlockType.fromContentTypeUid(raw.contentTypeUid()) != BlockType.COUNTDOWN) {
            return false;
        }
        Map<String, Object> attributes = raw.attributes();
        if (Boolean.FALSE.equals(attributes.get(ATTR_IS_ACTIVE))) {
            return true;
        }
        Object timer = attributes.get(ATTR_TIMER);
        if (!(timer instanceof String timerValue) || timerValue.isBlank()) {
            return false;
        }
        try {
            return Instant.parse(timerValue).isBefore(Instant.now());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * Returns true when the block is a {@code container_guest} that must be dropped because the
     * session is authenticated (Rule 19 reference: legacy {@code container_guest.strategy.ts}).
     */
    private boolean isGuestOnlyBlockedForAuthenticated(BlockDefinition raw, SessionContext session) {
        return BlockType.fromContentTypeUid(raw.contentTypeUid()) == BlockType.CONTAINER_GUEST
                && session.authenticated();
    }

    /**
     * Removes the page footer's {@code menu_items} the session's audience must not see — logged-only
     * items for a guest, guest-only items for a signed-in shopper (Rule 19 reference: digital_bff
     * {@code FooterStrategy.filterMenuItemsByAudience}). Every other entry field passes through by
     * reference.
     */
    private static Map<String, Object> filterFooterByAudience(Map<String, Object> entryMetadata,
                                                              SessionContext session) {
        if (!(entryMetadata.get(KEY_FOOTER) instanceof Map<?, ?> footer)
                || !(footer.get(KEY_MENU_ITEMS) instanceof List<?> items)) {
            return entryMetadata;
        }
        List<Object> visible = new ArrayList<>(items.size());
        for (Object item : items) {
            Object audience = item instanceof Map<?, ?> menuItem ? menuItem.get(KEY_AUDIENCE_FILTER) : null;
            if (AudienceFilter.from(audience instanceof String value ? value : null)
                    .visibleFor(session.authenticated())) {
                visible.add(item);
            }
        }
        if (visible.size() == items.size()) {
            return entryMetadata;
        }
        Map<String, Object> filteredFooter = new LinkedHashMap<>();
        footer.forEach((key, value) -> filteredFooter.put(String.valueOf(key), value));
        filteredFooter.put(KEY_MENU_ITEMS, visible);
        Map<String, Object> copy = new LinkedHashMap<>(entryMetadata);
        copy.put(KEY_FOOTER, filteredFooter);
        return copy;
    }

    // ── internal plan model ────────────────────────────────────────────────────────────────────────

    private enum Mode { STATIC, LAZY, EAGER }

    /**
     * The identity of one eager resolution, for the per-request memo in {@link #resolvePlans}.
     *
     * <p>{@code (resolution key, block id)} and nothing else. The resolution key carries the content
     * type and the {@code source_of_data}, so two blocks that share a uid but route to different
     * providers still resolve separately. Nothing session-derived is in here, and nothing needs to be:
     * the memo lives for one request, inside one session.</p>
     *
     * @param key     the block's routing key
     * @param blockId the block's Contentstack uid
     */
    private record MemoKey(BlockResolutionKey key, String blockId) {

        static MemoKey of(BlockPlan plan) {
            return new MemoKey(
                    BlockResolutionKey.of(plan.type(), plan.raw().sourceOfData()),
                    plan.raw().uid());
        }
    }

    /**
     * Immutable classification of one visible block: how it will be resolved plus the metadata each
     * mode needs. Pure data — computed before any I/O so eager tasks can be dispatched together.
     */
    private record BlockPlan(
            Mode mode,
            BlockDefinition raw,
            BlockType type,
            BlockResolution resolution,
            BlockResolver resolver,
            DynamicBlockStatus status,
            Map<String, Object> msParams) {

        static BlockPlan staticBlock(BlockDefinition raw, BlockType type) {
            return new BlockPlan(Mode.STATIC, raw, type, null, null, null, Map.of());
        }

        static BlockPlan lazy(BlockDefinition raw, BlockType type, BlockResolution resolution,
                              DynamicBlockStatus status, Map<String, Object> msParams) {
            return new BlockPlan(Mode.LAZY, raw, type, resolution, null, status, msParams);
        }

        static BlockPlan eager(BlockDefinition raw, BlockType type, BlockResolution resolution,
                               BlockResolver resolver, Map<String, Object> msParams) {
            return new BlockPlan(Mode.EAGER, raw, type, resolution, resolver,
                    DynamicBlockStatus.AVAILABLE, msParams);
        }
    }
}
