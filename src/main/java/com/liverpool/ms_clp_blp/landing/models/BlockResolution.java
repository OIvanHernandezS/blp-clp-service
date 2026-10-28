package com.liverpool.ms_clp_blp.landing.models;

/**
 * Resolution metadata for a dynamic block type: where its dedicated endpoint lives, how it is addressed,
 * and which feature flag governs it. Supplied to the composition service so the domain stays free of
 * configuration mechanics (the values originate from externalised config, mapped in the adapter/config
 * layer).
 *
 * <p>The advertised {@code httpMethod} defaults to {@code POST}: the frontend re-posts the block's own
 * context (the CS content it already received from {@code GET /blp|clp/**}) to the resolution endpoint, so the
 * backend resolves the dynamic detail without an extra content-service round-trip to re-read the block's
 * config.</p>
 *
 * @param resolutionPath   path of the block's resolution endpoint
 * @param featureFlagId    id of the runtime feature flag controlling the block
 * @param httpMethod       http method the frontend uses against {@code resolutionPath} (e.g. {@code POST})
 * @param blockIdInPath    whether the endpoint addresses one block by appending its id to
 *                         {@code resolutionPath}. {@code false} for a collection endpoint that takes its
 *                         blocks in the request body — {@code products_list} resolves a batch that way
 *                         (ADR-029), and appending an id there would advertise a URL that is not mapped
 */
public record BlockResolution(
        String resolutionPath,
        String featureFlagId,
        String httpMethod,
        boolean blockIdInPath) {

    private static final String DEFAULT_HTTP_METHOD = "POST";

    public BlockResolution {
        httpMethod = (httpMethod == null || httpMethod.isBlank()) ? DEFAULT_HTTP_METHOD : httpMethod;
    }

    /**
     * Convenience factory for a per-block {@code POST} endpoint addressed as
     * {@code {resolutionPath}/{blockId}} (the frontend posts the block context back to avoid a backend
     * content-service re-fetch).
     *
     * @param resolutionPath path of the dedicated per-block resolution endpoint
     * @param featureFlagId  id of the runtime feature flag controlling the block
     * @return a resolution with {@code httpMethod = POST}, addressed by block id in the path
     */
    public static BlockResolution post(String resolutionPath, String featureFlagId) {
        return new BlockResolution(resolutionPath, featureFlagId, DEFAULT_HTTP_METHOD, true);
    }

    /**
     * Convenience factory for a single-block {@code POST} endpoint that resolves from the session alone.
     * The advertised URL is {@code resolutionPath} itself: the block id is not an input to the
     * resolution — {@code container_greeting} asks Salesforce about the <em>user</em>, never about the
     * block — so appending it would advertise a path segment the server neither maps nor reads.
     *
     * @param resolutionPath path of the block's resolution endpoint
     * @param featureFlagId  id of the runtime feature flag controlling the block
     * @return a resolution with {@code httpMethod = POST}, addressed without a path variable
     */
    public static BlockResolution sessionOnly(String resolutionPath, String featureFlagId) {
        return new BlockResolution(resolutionPath, featureFlagId, DEFAULT_HTTP_METHOD, false);
    }

    /**
     * Convenience factory for a collection {@code POST} endpoint that resolves several blocks in one
     * call, taking them in the request body. The advertised URL is {@code resolutionPath} itself, with no
     * block id appended.
     *
     * @param resolutionPath path of the batch resolution endpoint
     * @param featureFlagId  id of the runtime feature flag controlling the block
     * @return a resolution with {@code httpMethod = POST}, addressed without a path variable
     */
    public static BlockResolution batch(String resolutionPath, String featureFlagId) {
        return new BlockResolution(resolutionPath, featureFlagId, DEFAULT_HTTP_METHOD, false);
    }

    /**
     * Builds the URL a block advertises to the frontend — the single place this is derived, so a
     * placeholder and an eagerly-resolved block can never disagree about how the endpoint is addressed.
     *
     * @param resolutionPath the endpoint path
     * @param blockId        the block being addressed
     * @param blockIdInPath  whether the endpoint takes the block id as a path variable
     * @return {@code {path}/{blockId}} for a per-block endpoint, or {@code path} for a batch one
     */
    public static String url(String resolutionPath, String blockId, boolean blockIdInPath) {
        if (resolutionPath == null || resolutionPath.isBlank()) {
            return resolutionPath;
        }
        String base = resolutionPath.endsWith("/")
                ? resolutionPath.substring(0, resolutionPath.length() - 1)
                : resolutionPath;
        return blockIdInPath ? base + "/" + blockId : base;
    }

    /**
     * @param blockId the block being addressed
     * @return the URL a block with this resolution advertises
     */
    public String urlFor(String blockId) {
        return url(resolutionPath, blockId, blockIdInPath);
    }
}
