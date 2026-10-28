package com.liverpool.ms_clp_blp.landing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.stereotype.Service;

import com.liverpool.ms_clp_blp.landing.errors.LandingDefinitionNotFoundException;
import com.liverpool.ms_clp_blp.landing.models.CategoryData;
import com.liverpool.ms_clp_blp.landing.models.ContentQuery;
import com.liverpool.ms_clp_blp.landing.models.LandingDefinition;
import com.liverpool.ms_clp_blp.landing.models.LandingPage;
import com.liverpool.ms_clp_blp.landing.models.LandingPageQuery;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.models.SelectedStore;
import com.liverpool.ms_clp_blp.landing.ports.CategoryPort;
import com.liverpool.ms_clp_blp.landing.ports.CategoryRedirectPort;
import com.liverpool.ms_clp_blp.landing.ports.ContentPort;
import com.liverpool.ms_clp_blp.landing.ports.FavoriteStorePort;
import com.liverpool.ms_clp_blp.landing.ports.LandingService;
import com.liverpool.ms_clp_blp.shared.errors.ValidationException;
import com.liverpool.ms_clp_blp.shared.logging.MdcPropagation;

/**
 * Application use case composing a BLP or CLP landing page (Rule 19 reference: digital_bff
 * {@code ContentService.getContent} for {@code page-blp}/{@code page-clp}).
 *
 * <p>Four reads, three of them best effort:</p>
 * <ul>
 *   <li>the page entry ({@link ContentPort}) — the only one that can fail the request: 404 when no page
 *       is authored for the category, 502 when Contentstack is down;</li>
 *   <li>the category tree ({@link CategoryPort}) and its CMS redirect ({@link CategoryRedirectPort}) —
 *       {@code categoryData} is omitted when either is unavailable;</li>
 *   <li>the favourite store ({@link FavoriteStorePort}), only when the {@code favoriteStore} cookie is
 *       present — the {@code selected_store.*} analytics values stay unset without it.</li>
 * </ul>
 *
 * <p>The best-effort reads run on virtual threads while the page entry is read on the request thread,
 * so the page's latency is the slowest of them rather than their sum. The page entry stays on the
 * request thread on purpose: the CMS layer records which tier answered ({@code x-data-origin}) in a
 * request-scoped holder the controller reads afterwards.</p>
 *
 * <p>Deliberate difference from digital_bff: a CLP whose category lookup fails is still served,
 * without {@code categoryData}, exactly like a BLP. digital_bff answered that CLP case with a 404,
 * which turned an indexer outage into missing pages.</p>
 */
@Service
public class LandingUseCases implements LandingService {

    private static final String KEY_HAS_REDIRECT = "hasRedirect";

    private final ContentPort contentPort;
    private final CategoryPort categoryPort;
    private final CategoryRedirectPort categoryRedirectPort;
    private final FavoriteStorePort favoriteStorePort;
    private final LandingCompositions compositions;

    /**
     * @param contentPort          the page entry, cached (the {@code @Primary} Caffeine port)
     * @param categoryPort         the category tree
     * @param categoryRedirectPort the category's CMS redirect
     * @param favoriteStorePort    the store named by the {@code favoriteStore} cookie
     * @param compositions         one composition service per page type
     */
    public LandingUseCases(ContentPort contentPort,
                           CategoryPort categoryPort,
                           CategoryRedirectPort categoryRedirectPort,
                           FavoriteStorePort favoriteStorePort,
                           LandingCompositions compositions) {
        this.contentPort = contentPort;
        this.categoryPort = categoryPort;
        this.categoryRedirectPort = categoryRedirectPort;
        this.favoriteStorePort = favoriteStorePort;
        this.compositions = compositions;
    }

    /**
     * {@inheritDoc}
     *
     * @throws ValidationException                when the path carries no category id
     * @throws LandingDefinitionNotFoundException when no page is authored for the category
     */
    @Override
    public LandingPage getLandingPage(LandingPageQuery query) {
        String categoryId = query.categoryId();
        if (categoryId == null) {
            throw new ValidationException("The landing page path must end with a category id.",
                    "pageType=" + query.pageType() + " path=" + query.path());
        }
        String brand = query.session().brand();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Optional<CategoryData>> category = executor.submit(MdcPropagation.wrap(
                    () -> findCategory(brand, query.locale(), categoryId)));
            Future<Optional<SelectedStore>> store = executor.submit(MdcPropagation.wrap(
                    () -> findStore(brand, query.favoriteStoreId())));

            LandingDefinition definition = contentPort.fetchDefinition(
                    new ContentQuery(brand, query.locale(), query.pageType(), categoryId));
            requireCategoryId(definition, query);

            LandingPage page = compositions.forPage(query.pageType()).compose(definition, query.session(),
                    query.gatewayHeaders(), query.eagerOffsetOverride(), join(store).orElse(null));
            // digital_bff attached categoryData only when the entry itself names a category.
            return definition.categoryId() == null ? page : page.withCategoryData(join(category).orElse(null));
        }
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A BLP must name its category: digital_bff rejected an entry without {@code category_id} with a
     * 404 ({@code CategoryResponseNoCategoryIdError}), since a brand page without its brand cannot
     * render. Entries are selected by that very field, so this only fires for a malformed entry.
     */
    private static void requireCategoryId(LandingDefinition definition, LandingPageQuery query) {
        if (definition.pageType() == PageType.BLP && definition.categoryId() == null) {
            throw new LandingDefinitionNotFoundException(
                    "The brand landing page does not name its category.",
                    "pageType=BLP categoryId=" + query.categoryId() + " | entry carries no category_id");
        }
    }

    /** The category with its active redirect attached, read concurrently. */
    private Optional<CategoryData> findCategory(String brand, String locale, String categoryId) {
        Optional<CategoryData> category = categoryPort.findCategory(brand, categoryId);
        if (category.isEmpty()) {
            return category;
        }
        return Optional.of(categoryRedirectPort.findActiveRedirect(brand, locale, categoryId)
                .map(redirect -> category.get().withRedirect(withHasRedirect(redirect)))
                .orElse(category.get()));
    }

    /** {@code { hasRedirect: true, ...redirect }}, as digital_bff spread it. */
    private static Map<String, Object> withHasRedirect(Map<String, Object> redirect) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put(KEY_HAS_REDIRECT, true);
        content.putAll(redirect);
        return content;
    }

    private Optional<SelectedStore> findStore(String brand, String storeId) {
        if (storeId == null || storeId.isBlank()) {
            return Optional.empty();
        }
        return favoriteStorePort.findStore(brand, storeId.trim());
    }

    /**
     * Joins a best-effort read. The ports never throw by contract, so a failure here is a defect or an
     * interrupt — either way it degrades to "unavailable" rather than failing the page.
     */
    private static <T> Optional<T> join(Future<Optional<T>> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            return Optional.empty();
        }
    }
}
