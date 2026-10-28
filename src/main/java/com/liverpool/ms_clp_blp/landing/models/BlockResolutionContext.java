package com.liverpool.ms_clp_blp.landing.models;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.liverpool.ms_clp_blp.shared.session.SessionContext;

/**
 * Everything a {@link com.liverpool.ms_clp_blp.landing.ports.BlockResolver} needs to resolve one block
 * eagerly during {@code GET /blp|clp/**}, assembled by the composition service.
 *
 * <p>Framework-free (Rule 1): request identity travels as a plain header snapshot rather than a
 * servlet request. The composition service copies the propagatable {@code x-*} request headers into
 * {@link #gatewayHeaders}; each resolver reads the specific header(s) its backing service needs via
 * {@link #header(String)} — e.g. Jewel's tracking identity ({@code x-jml-user-id}/{@code
 * x-jml-device-id}), genuine client-forwarded headers by design. Salesforce-backed resolvers do
 * <strong>not</strong> read an identity header here: {@code x-user-id} is client-supplied and
 * unvalidated against the session, so ADR-033 moved that identity to {@code
 * session().authenticatedProfileId()} instead — server-derived from the {@code SessionId} exchange,
 * not trusted from the caller.</p>
 *
 * @param blockId        Contentstack block uid being resolved
 * @param blockType      classified block type
 * @param attributes     raw CMS attributes of the block (immutable, metadata included)
 * @param session        resolved session context (auth/brand/channel/locale)
 * @param gatewayHeaders lower-cased snapshot of propagatable request headers (never {@code null})
 * @param resolutionPath path of the block's dedicated resolution endpoint
 * @param httpMethod     http method the frontend would use against {@code resolutionPath}
 */
public record BlockResolutionContext(
        String blockId,
        BlockType blockType,
        Map<String, Object> attributes,
        SessionContext session,
        Map<String, String> gatewayHeaders,
        String resolutionPath,
        String httpMethod) {

    public BlockResolutionContext {
        // Null-tolerant copy of CMS attributes (they carry null-valued fields Map.copyOf would reject).
        attributes = attributes == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        gatewayHeaders = gatewayHeaders == null ? Map.of() : Map.copyOf(gatewayHeaders);
    }

    /**
     * Looks up a propagated gateway header value by name (case-insensitive).
     *
     * @param name the header name (e.g. {@code x-jml-user-id})
     * @return the header value, or {@code null} when absent
     */
    public String header(String name) {
        return name == null ? null : gatewayHeaders.get(name.toLowerCase());
    }
}
