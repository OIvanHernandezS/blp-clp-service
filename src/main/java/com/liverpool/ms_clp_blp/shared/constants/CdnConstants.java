package com.liverpool.ms_clp_blp.shared.constants;

/**
 * Paths this service exposes for the CDN in front of it rather than for an API caller.
 *
 * <p>Separate from {@code ClpBlpConstants}, which holds domain values: nothing here is part of the
 * product. These paths exist because Akamai asks the origin for them, and they are declared once so
 * that the two places which must agree on them — the security chain that lets them through and the
 * usage-metrics filter that keeps them out of the business counters — cannot drift apart.</p>
 */
public final class CdnConstants {

    /**
     * The object Akamai SureRoute fetches to race edge&rarr;origin paths against each other.
     *
     * <p>SureRoute continuously downloads this object from many edge nodes and keeps the route that
     * wins. <strong>It must answer {@code 200}.</strong> Any other status — including the {@code 404}
     * that an open-but-missing path would produce — invalidates the race, and SureRoute silently falls
     * back to default routing while still probing forever. That is exactly the state this service was
     * in: the path required HTTP Basic, so every probe got a {@code 401} and roughly 35,500 dead
     * requests a day landed in the {@code UNKNOWN} usage bucket.</p>
     *
     * <p>The response body is inert HTML holding no data, which is why exposing it costs nothing. The
     * file backing it is {@code src/main/resources/META-INF/resources/static/sureroute-test-object.html}
     * — {@code classpath:/META-INF/resources/} is the first of Spring Boot's default static locations,
     * so the directory layout under it mirrors this URL one-for-one.</p>
     *
     * <p><strong>Pending confirmation from the CDN team:</strong> the path is reproduced here from the
     * probes observed in production, and the object is deliberately minimal. Akamai measures download
     * time to compare paths, so if the property expects a specific test-object size, the file is padded
     * to match and this note goes away. A wrong size degrades route selection; a wrong status breaks it
     * outright, and that is the half already fixed.</p>
     */
    public static final String SUREROUTE_TEST_OBJECT_PATH = "/static/sureroute-test-object.html";

    private CdnConstants() {
    }
}
