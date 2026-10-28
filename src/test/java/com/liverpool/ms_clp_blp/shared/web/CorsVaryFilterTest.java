package com.liverpool.ms_clp_blp.shared.web;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what {@link CorsVaryFilter} removes and — more importantly — what it leaves alone.
 *
 * <p>The whole point is a single surviving {@code Vary: Accept-Encoding}: anything else and Akamai
 * refuses to store the object, while dropping {@code Accept-Encoding} too would let the edge serve a
 * gzipped body to a client that never asked for one.</p>
 */
class CorsVaryFilterTest {

    private final CorsVaryFilter filter = new CorsVaryFilter();

    /**
     * Runs the filter over a request and lets the downstream chain write {@code Vary} exactly the way
     * Spring's CORS processor and Tomcat's compression do — one {@code addHeader} call each.
     *
     * @param method the request method
     * @param uri    the request URI
     * @param varyValues the values the downstream chain adds to {@code Vary}, in order
     * @return the {@code Vary} values that survived
     */
    private List<String> varyAfterFilter(String method, String uri, String... varyValues)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                for (String value : varyValues) {
                    ((HttpServletResponse) res).addHeader("Vary", value);
                }
            }
        };

        filter.doFilter(request, response, chain);
        return response.getHeaders("Vary");
    }

    private static final String BLP = "/blp/es-mx/tienda/esteelauder/catst26617625";
    private static final String CLP = "/clp/es-mx/tienda/belleza/cat5020010";

    @Test
    void stripsTheCorsValuesAndKeepsAcceptEncoding() throws Exception {
        List<String> vary = varyAfterFilter("GET", BLP,
                "Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers",
                "Accept-Encoding");

        assertThat(vary).containsExactly("Accept-Encoding");
    }

    @Test
    void leavesNoVaryAtAllWhenOnlyCorsValuesWereWritten() throws Exception {
        // An uncompressed response below the 2 KB threshold: nothing legitimate remains, and an empty
        // header would read as a Vary the edge must honour.
        List<String> vary = varyAfterFilter("GET", BLP,
                "Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers");

        assertThat(vary).isEmpty();
    }

    @Test
    void handlesACommaJoinedVaryValue() throws Exception {
        List<String> vary = varyAfterFilter("GET", CLP,
                "origin,access-control-request-method,access-control-request-headers,accept-encoding");

        assertThat(vary).containsExactly("accept-encoding");
    }

    @Test
    void appliesToBothLandingRoutesAndToTheirTrailingSlashForm() throws Exception {
        for (String uri : List.of(BLP, CLP, BLP + "/")) {
            assertThat(varyAfterFilter("GET", uri, "Origin", "Accept-Encoding"))
                    .as("Vary on GET %s", uri)
                    .containsExactly("Accept-Encoding");
        }
    }

    @Test
    void leavesEveryOtherRequestUntouched() throws Exception {
        // Block resolution is a personalised POST that nothing should cache: a truthful Vary is the
        // cheapest way to say so to any intermediary.
        assertThat(varyAfterFilter("POST", "/blp/blocks/products-list", "Origin", "Accept-Encoding"))
                .containsExactly("Origin", "Accept-Encoding");

        assertThat(varyAfterFilter("GET", "/ops/cache-purge", "Origin"))
                .containsExactly("Origin");
    }

    @Test
    void doesNotDisturbOtherResponseHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", BLP);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                HttpServletResponse http = (HttpServletResponse) res;
                http.addHeader("Vary", "Origin");
                http.setHeader("Access-Control-Allow-Origin", "https://www.liverpool.com.mx");
                http.setHeader("Cache-Control", "public");
            }
        };

        filter.doFilter(request, response, chain);

        // CORS itself is untouched; only the Vary value is dropped.
        assertThat(response.getHeader("Access-Control-Allow-Origin"))
                .isEqualTo("https://www.liverpool.com.mx");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("public");
        assertThat(response.getHeaders("Vary")).isEmpty();
    }
}
