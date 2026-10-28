package com.liverpool.ms_clp_blp.landing.adapters.cache;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.liverpool.ms_clp_blp.landing.models.ContentQuery;
import com.liverpool.ms_clp_blp.landing.models.LandingDefinition;
import com.liverpool.ms_clp_blp.landing.ports.ContentPort;
import com.liverpool.ms_clp_blp.shared.web.CmsResponseMetadataContext;
import com.liverpool.ms_clp_blp.shared.web.ContentOrigin;

@Component
@Primary
public class CachedLandingContentPort implements ContentPort {

    private final ContentPort delegate;
    private final LandingContentCacheProperties properties;
    private final LoadingCache<ContentQuery, LandingDefinition> cache;

    public CachedLandingContentPort(
            @Qualifier("landingContentClient") ContentPort delegate,
            LandingContentCacheProperties properties,
            @Qualifier("landingContentCacheExecutor") Executor executor) {
        this.delegate = delegate;
        this.properties = properties;
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.maximumSize())
                .refreshAfterWrite(properties.refreshAfterWrite())
                .expireAfterWrite(properties.expireAfterWrite())
                .executor(executor)
                .build(delegate::fetchDefinition);
    }

    @Override
    public LandingDefinition fetchDefinition(ContentQuery query) {
        if (!properties.enabled()) {
            return delegate.fetchDefinition(query);
        }
        // Checked before the load: a present age means the entry already existed, so this call will
        // return a cached value (a fresh hit, or a stale-but-not-expired one served while
        // refreshAfterWrite reloads in the background) rather than triggering delegate::fetchDefinition.
        // On a genuine miss, cache.get() calls the delegate synchronously on this thread, so the
        // router/backup-tier code below it already records the correct origin — nothing more to do here.
        boolean alreadyCached = cache.policy().expireAfterWrite()
                .map(policy -> policy.ageOf(query).isPresent())
                .orElse(false);
        LandingDefinition definition = cache.get(query);
        if (alreadyCached) {
            CmsResponseMetadataContext.set(ContentOrigin.CAFFEINE);
        }
        return definition;
    }
}
