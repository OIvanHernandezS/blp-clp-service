package com.liverpool.ms_clp_blp.landing.adapters.cache;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.models.ContentQuery;
import com.liverpool.ms_clp_blp.landing.models.LandingDefinition;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.ports.ContentPort;
import com.liverpool.ms_clp_blp.shared.web.CmsResponseMetadataContext;
import com.liverpool.ms_clp_blp.shared.web.ContentOrigin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachedLandingContentPortTest {

    private static final ContentQuery LP = new ContentQuery("LP", "es-mx", PageType.BLP, "catst26617625");
    private static final ContentQuery SB = new ContentQuery("SB", "es-mx", PageType.BLP, "catst26617625");
    private static final ContentQuery LP_CLP = new ContentQuery("LP", "es-mx", PageType.CLP, "catst26617625");
    private static final LandingDefinition DEFINITION =
            LandingDefinition.ofBlocks(PageType.BLP, "es-mx", List.of());
    private static final Executor SAME_THREAD = Runnable::run;

    @AfterEach
    void clearOriginContext() {
        CmsResponseMetadataContext.clear();
    }

    @Test
    void repeatedProductionReads_loadTheKeyOnce() {
        ContentPort delegate = mock(ContentPort.class);
        when(delegate.fetchDefinition(LP)).thenReturn(DEFINITION);
        CachedLandingContentPort cached = enabled(delegate);

        assertThat(cached.fetchDefinition(LP)).isSameAs(DEFINITION);
        assertThat(cached.fetchDefinition(LP)).isSameAs(DEFINITION);

        verify(delegate, times(1)).fetchDefinition(LP);
    }

    @Test
    void firstRead_isAMiss_doesNotOverrideWhateverOriginTheDelegateRecorded() {
        // A genuine miss calls the delegate synchronously on this thread: the router code downstream of
        // it already records the correct origin, so CachedLandingContentPort must not touch
        // the context on this branch. Simulated here since the mocked delegate has no router of its own.
        ContentPort delegate = mock(ContentPort.class);
        when(delegate.fetchDefinition(LP)).thenAnswer(invocation -> {
            CmsResponseMetadataContext.set(ContentOrigin.CONTENTSTACK);
            return DEFINITION;
        });
        CachedLandingContentPort cached = enabled(delegate);

        cached.fetchDefinition(LP);

        assertThat(CmsResponseMetadataContext.current()).isEqualTo(ContentOrigin.CONTENTSTACK);
    }

    @Test
    void secondRead_isACacheHit_overridesTheOriginToCaffeine() {
        ContentPort delegate = mock(ContentPort.class);
        when(delegate.fetchDefinition(LP)).thenAnswer(invocation -> {
            CmsResponseMetadataContext.set(ContentOrigin.CONTENTSTACK);
            return DEFINITION;
        });
        CachedLandingContentPort cached = enabled(delegate);
        cached.fetchDefinition(LP);

        CmsResponseMetadataContext.clear();
        cached.fetchDefinition(LP);

        assertThat(CmsResponseMetadataContext.current()).isEqualTo(ContentOrigin.CAFFEINE);
    }

    @Test
    void brandPageTypeAndCategoryRemainPartOfTheCacheKey() {
        ContentPort delegate = mock(ContentPort.class);
        when(delegate.fetchDefinition(LP)).thenReturn(DEFINITION);
        when(delegate.fetchDefinition(SB)).thenReturn(DEFINITION);
        when(delegate.fetchDefinition(LP_CLP)).thenReturn(DEFINITION);
        CachedLandingContentPort cached = enabled(delegate);

        cached.fetchDefinition(LP);
        cached.fetchDefinition(SB);
        cached.fetchDefinition(LP_CLP);

        verify(delegate).fetchDefinition(LP);
        verify(delegate).fetchDefinition(SB);
        verify(delegate).fetchDefinition(LP_CLP);
    }

    @Test
    void killSwitchBypassesTheCache() {
        ContentPort delegate = mock(ContentPort.class);
        when(delegate.fetchDefinition(LP)).thenReturn(DEFINITION);
        LandingContentCacheProperties disabled =
                new LandingContentCacheProperties(false, Duration.ofSeconds(30), Duration.ofMinutes(5), 100);
        CachedLandingContentPort cached = new CachedLandingContentPort(delegate, disabled, SAME_THREAD);

        cached.fetchDefinition(LP);
        cached.fetchDefinition(LP);

        verify(delegate, times(2)).fetchDefinition(LP);
    }

    @Test
    void failedInitialLoadIsNotCached() {
        ContentPort delegate = mock(ContentPort.class);
        when(delegate.fetchDefinition(LP))
                .thenThrow(new IllegalStateException("temporary"))
                .thenReturn(DEFINITION);
        CachedLandingContentPort cached = enabled(delegate);

        assertThatThrownBy(() -> cached.fetchDefinition(LP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary");
        assertThat(cached.fetchDefinition(LP)).isSameAs(DEFINITION);

        verify(delegate, times(2)).fetchDefinition(LP);
    }

    private static CachedLandingContentPort enabled(ContentPort delegate) {
        LandingContentCacheProperties properties =
                new LandingContentCacheProperties(true, Duration.ofSeconds(30), Duration.ofMinutes(5), 100);
        return new CachedLandingContentPort(delegate, properties, SAME_THREAD);
    }
}
