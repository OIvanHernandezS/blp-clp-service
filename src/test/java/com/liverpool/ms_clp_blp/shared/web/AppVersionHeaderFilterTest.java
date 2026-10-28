package com.liverpool.ms_clp_blp.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AppVersionHeaderFilterTest {

    @Test
    void doFilter_versionConfigured_setsTheHeader() throws Exception {
        AppVersionHeaderFilter filter = new AppVersionHeaderFilter("v1.4.2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertThat(response.getHeader("x-app-version")).isEqualTo("v1.4.2");
    }

    @Test
    void doFilter_noVersionInjected_defaultsToUnknown() throws Exception {
        AppVersionHeaderFilter filter = new AppVersionHeaderFilter("unknown");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertThat(response.getHeader("x-app-version")).isEqualTo("unknown");
    }

    @Test
    void doFilter_setsTheHeaderEvenWhenTheChainProducesAnErrorStatus() throws Exception {
        // The whole point of a filter-based stamp: appVersion must be present on error responses too,
        // so an operator can tell which build produced a given 502.
        AppVersionHeaderFilter filter = new AppVersionHeaderFilter("v1.4.2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                ((MockHttpServletResponse) response).setStatus(502);
            }
        };

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getHeader("x-app-version")).isEqualTo("v1.4.2");
    }
}
