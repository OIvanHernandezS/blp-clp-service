package com.liverpool.ms_clp_blp.shared.web;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CmsOriginContextFilterTest {

    private final CmsOriginContextFilter filter = new CmsOriginContextFilter();

    @AfterEach
    void clearContext() {
        CmsResponseMetadataContext.clear();
    }

    @Test
    void doFilter_afterTheChainSetsAnOrigin_clearsItOnceTheRequestCompletes() throws Exception {
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                CmsResponseMetadataContext.set(ContentOrigin.CONTENTSTACK);
            }
        };

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(CmsResponseMetadataContext.current()).isNull();
    }

    @Test
    void doFilter_chainThrows_stillClearsTheContext() {
        CmsResponseMetadataContext.set(ContentOrigin.CAFFEINE);
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws ServletException {
                throw new ServletException("downstream failure");
            }
        };

        assertThatThrownBy(() ->
                filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain))
                .isInstanceOf(ServletException.class);
        assertThat(CmsResponseMetadataContext.current()).isNull();
    }
}
