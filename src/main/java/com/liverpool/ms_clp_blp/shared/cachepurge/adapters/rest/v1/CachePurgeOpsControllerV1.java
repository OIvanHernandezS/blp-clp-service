package com.liverpool.ms_clp_blp.shared.cachepurge.adapters.rest.v1;

import java.util.Arrays;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.liverpool.ms_clp_blp.shared.cachepurge.CachePurgeProperties;
import com.liverpool.ms_clp_blp.shared.cachepurge.errors.CachePurgeDisabledException;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeAction;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeNetwork;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeReport;
import com.liverpool.ms_clp_blp.shared.cachepurge.ports.CachePurgeService;
import com.liverpool.ms_clp_blp.shared.errors.ValidationException;

/**
 * Inbound adapter letting an operator purge this service's content from the Akamai edge on demand.
 *
 * <p><strong>Contract:</strong> {@code POST /ops/cache-purge} (paths and tags),
 * {@code POST /ops/cache-purge/paths} and {@code POST /ops/cache-purge/tags}. All three accept
 * {@code ?action=invalidate|delete} and {@code ?network=staging|production}; both are optional and
 * fall back to {@code cache-purge.action} / {@code cache-purge.network}.
 * {@code POST /ops/cache-purge/objects} purges what its body names instead, taking the action from the
 * body's {@code type} and the network from the same {@code ?network=}.</p>
 *
 * <p>Both parameters are parsed here rather than bound as enums so that a typo is a 400 naming the
 * accepted values. Letting Spring coerce them would produce a generic 400 that does not say what was
 * wrong, and defaulting a typo silently is worse still: {@code ?network=prod} would quietly purge
 * staging and look like a purge that did nothing.</p>
 *
 * <p>Authentication is the platform's, not this endpoint's — the same inbound HTTP Basic credential
 * {@code SecurityConfig} requires of every endpoint. Like {@code EntryBackupOpsControllerV1} it is
 * deliberately absent from the CORS registration: no browser is a legitimate caller here.</p>
 */
@RestController
@RequestMapping("/ops")
@Validated
public class CachePurgeOpsControllerV1 implements CachePurgeOpsApiV1 {

    static final String HEADER_REQUEST_ID = "x-request-id";

    private static final String ACTION_PARAM = "action";
    private static final String TYPE_FIELD = "type";

    private final CachePurgeService cachePurgeService;
    private final CachePurgeProperties properties;

    /**
     * @param cachePurgeService the inbound port this trigger invokes
     * @param properties        supplies the kill switch and the action/network defaults
     */
    public CachePurgeOpsControllerV1(CachePurgeService cachePurgeService,
                                     CachePurgeProperties properties) {
        this.cachePurgeService = cachePurgeService;
        this.properties = properties;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/cache-purge")
    public ResponseEntity<CachePurgeResponse> purgeAll(
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "network", required = false) String network) {
        return run(cachePurgeService::purgeAll, ACTION_PARAM, action, network);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/cache-purge/paths")
    public ResponseEntity<CachePurgeResponse> purgePaths(
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "network", required = false) String network) {
        return run(cachePurgeService::purgePaths, ACTION_PARAM, action, network);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/cache-purge/tags")
    public ResponseEntity<CachePurgeResponse> purgeTags(
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "network", required = false) String network) {
        return run(cachePurgeService::purgeTags, ACTION_PARAM, action, network);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/cache-purge/objects")
    public ResponseEntity<CachePurgeResponse> purgeObjects(
            @RequestBody CachePurgeObjectsRequest request,
            @RequestParam(name = "network", required = false) String network) {
        return run((action, resolvedNetwork) -> cachePurgeService.purgeObjects(
                        action, resolvedNetwork, request.urls(), request.tags()),
                TYPE_FIELD, request.type(), network);
    }

    /**
     * Applies the kill switch, resolves the operator's options, and runs one of the port's methods.
     *
     * @param purge       the port method this endpoint drives
     * @param actionField name of the input carrying the action, quoted back in a 400
     * @param action      raw action value, possibly absent
     * @param network     raw {@code network} parameter, possibly absent
     * @return the run's outcome, echoing the request id
     */
    private ResponseEntity<CachePurgeResponse> run(
            BiFunction<PurgeAction, PurgeNetwork, PurgeReport> purge, String actionField, String action,
            String network) {
        if (!properties.enabled()) {
            throw new CachePurgeDisabledException();
        }
        PurgeReport report = purge.apply(
                parseAction(actionField, action, properties.defaultAction()),
                parseNetwork(network, properties.defaultNetwork()));
        return ResponseEntity.ok()
                .header(HEADER_REQUEST_ID, MDC.get("requestId"))
                .body(CachePurgeResponse.from(report));
    }

    private static PurgeAction parseAction(String field, String value, PurgeAction fallback) {
        try {
            return PurgeAction.parse(value, fallback);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown purge action.",
                    field + " must be one of " + allowed(PurgeAction.values()) + "; got '" + value + "'");
        }
    }

    private static PurgeNetwork parseNetwork(String value, PurgeNetwork fallback) {
        try {
            return PurgeNetwork.parse(value, fallback);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown purge network.",
                    "network must be one of " + allowed(PurgeNetwork.values()) + "; got '" + value + "'");
        }
    }

    private static <E extends Enum<E>> String allowed(E[] values) {
        return Arrays.stream(values)
                .map(value -> value.name().toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.joining(", "));
    }
}
