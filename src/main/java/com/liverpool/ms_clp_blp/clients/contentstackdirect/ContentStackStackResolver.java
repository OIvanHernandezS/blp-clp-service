package com.liverpool.ms_clp_blp.clients.contentstackdirect;

import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.liverpool.config.properties.ContentStackConfig;
import com.liverpool.config.properties.ContentStackConfigResolver;
import com.liverpool.enums.Sites;

/**
 * Maps an {@code x-brand-id} header value onto the Contentstack stack that hosts it.
 *
 * <p>Mirrors what the content-service does with its {@code PROVIDER_*} configuration, and has to keep
 * mirroring it: the two read the same stacks, and any disagreement shows up as the fallback serving a
 * different brand's content. Two rules are copied rather than invented —</p>
 * <ul>
 *   <li>a stack that hosts several brands needs entries filtered by {@code taxonomies.brand}, one that
 *       hosts a single brand does not (the proxy derives this as {@code brands.length > 1} in
 *       {@code src/app/config/content-service.config.ts});</li>
 *   <li>the filter value is the brand lower-cased with any suffix after {@code -} dropped
 *       ({@code brand.split('-')[0].toLowerCase()} in {@code src/app/shared/provider/contentstack.provider.ts}).</li>
 * </ul>
 *
 * <p>Everything this class cannot serve returns empty rather than throwing, because "this brand has no
 * stack" is an ordinary outcome, not an error: the caller turns it into the status code it deserves.</p>
 */
@Component
public class ContentStackStackResolver {

    private static final String BRAND_SUFFIX_SEPARATOR = "-";

    private final ContentStackConfigResolver configResolver;

    /**
     * @param configResolver commons' resolver from a {@link Sites} constant to that stack's credentials
     */
    public ContentStackStackResolver(ContentStackConfigResolver configResolver) {
        this.configResolver = configResolver;
    }

    /**
     * Resolves the stack serving a brand.
     *
     * @param brandHeader the {@code x-brand-id} value
     * @return the stack and its credentials, or empty when the brand cannot be served
     */
    public Optional<ResolvedStack> resolve(String brandHeader) {
        Optional<Sites> site = siteFor(brandHeader);
        if (site.isEmpty()) {
            return Optional.empty();
        }
        ContentStackConfig config = configResolver.resolve(site.get());
        if (isBlank(config.getApiKey()) || isBlank(config.getAccessToken())) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedStack(
                site.get(), config, brandFilter(brandHeader), isSharedStack(site.get())));
    }

    /**
     * Explains why a brand cannot be served, for the one log line that says so.
     *
     * @param brandHeader the {@code x-brand-id} value
     * @return the reason code, or empty when the brand can in fact be served
     */
    public Optional<FallbackUnavailableReason> unavailableReason(String brandHeader) {
        if (siteFor(brandHeader).isEmpty()) {
            return Optional.of(FallbackUnavailableReason.UNMAPPED_BRAND);
        }
        return resolve(brandHeader).isEmpty()
                ? Optional.of(FallbackUnavailableReason.MISSING_CREDENTIALS)
                : Optional.empty();
    }

    /**
     * Resolves the stack constant, excluding brands mapped to no stack at all.
     *
     * @param brandHeader the {@code x-brand-id} value
     * @return the stack constant, or empty
     */
    private Optional<Sites> siteFor(String brandHeader) {
        if (brandHeader == null || brandHeader.isBlank()) {
            return Optional.empty();
        }
        return Sites.findContentStackSite(brandHeader.trim());
    }

    /**
     * @param site the resolved stack
     * @return whether the stack hosts more than one brand
     */
    private static boolean isSharedStack(Sites site) {
        return site == Sites.MULTI1 || site == Sites.MULTI2;
    }

    /**
     * @param brandHeader the {@code x-brand-id} value
     * @return the value to match against {@code taxonomies.brand}
     */
    private static String brandFilter(String brandHeader) {
        String brand = brandHeader.trim();
        int separator = brand.indexOf(BRAND_SUFFIX_SEPARATOR);
        if (separator > 0) {
            brand = brand.substring(0, separator);
        }
        return brand.toLowerCase(Locale.ROOT);
    }

    /**
     * @param value credential value to check
     * @return whether the value is absent or empty
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
