package com.liverpool.ms_clp_blp.landing.adapters.contentstack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.clients.contentstack.CmsProperties;
import com.liverpool.ms_clp_blp.clients.contentstack.ContentSourceRequest;
import com.liverpool.ms_clp_blp.clients.contentstack.ContentSourceRouter;
import com.liverpool.ms_clp_blp.landing.errors.LandingDefinitionNotFoundException;
import com.liverpool.ms_clp_blp.landing.models.BlockDefinition;
import com.liverpool.ms_clp_blp.landing.models.ContentQuery;
import com.liverpool.ms_clp_blp.landing.models.LandingDefinition;
import com.liverpool.ms_clp_blp.landing.models.PageSection;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.ports.ContentPort;
import com.liverpool.ms_clp_blp.shared.errors.BrandNotSupportedException;
import com.liverpool.ms_clp_blp.shared.errors.ContentServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.errors.ContentstackEntryNotFoundException;
import com.liverpool.ms_clp_blp.shared.web.CmsEventListNormalizer;

/**
 * Outbound adapter that reads a raw landing page ({@code page_blp} / {@code page_clp}) from
 * Contentstack and maps it to the domain model.
 *
 * <p>This is the only point where landing page content enters the domain — the Contentstack client
 * never leaks past this class (Rule 2). Transport, the per-brand circuit breaker and the failure
 * vocabulary all live behind {@link ContentSourceRouter}.</p>
 *
 * <h3>What the entry becomes (Rule 19 reference: digital_bff {@code ContentService.getContent})</h3>
 * <ul>
 *   <li>Each {@link PageSection} is read from the first source key the entry carries — {@code layout}
 *       (an object holding {@code blocks}), a flat {@code blocks} array, or {@code page_clp}'s
 *       {@code top_content}/{@code bottom_content} — and flattened by {@link BlockPayloadNormalizer},
 *       the same {@code normalizeDoubleBlocks} digital_bff ran.</li>
 *   <li>The source keys and {@code content} are removed from the passthrough metadata, since the
 *       sections are re-emitted under their response keys once composed.</li>
 *   <li>The page type's app-only keys are removed — for a BLP, {@code apps_top_content},
 *       {@code apps_products} and {@code apps_article}.</li>
 *   <li>Everything else — {@code uid}, {@code url}, {@code category_id}, {@code seo}, {@code events}, the
 *       per-page {@code header}/{@code footer} overrides — passes through in CMS order.</li>
 * </ul>
 *
 * <p>The global entry digital_bff attached as {@code globalData} is deliberately not read: the
 * storefront takes it from ms-home's {@code GET /global-data}.</p>
 */
@Component
public class LandingContentClient implements ContentPort {

    private static final String KEY_BLOCKS = "blocks";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_CATEGORY_ID = "category_id";

    private final ContentSourceRouter contentSourceRouter;
    private final CmsProperties properties;
    private final BlockPayloadNormalizer blockNormalizer;

    /**
     * @param contentSourceRouter reads the entry from Contentstack behind the per-brand breaker
     * @param properties          content-type uids and the default brand
     * @param blockNormalizer     flattens raw wrapped Contentstack blocks into the flat domain shape
     */
    public LandingContentClient(ContentSourceRouter contentSourceRouter,
                                CmsProperties properties,
                                BlockPayloadNormalizer blockNormalizer) {
        this.contentSourceRouter = contentSourceRouter;
        this.properties = properties;
        this.blockNormalizer = blockNormalizer;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Failures are split by what the caller can do about them: an unhosted brand is the caller's
     * mistake (400), an unpublished page is the CMS team's backlog (404), and anything else is a
     * genuine Contentstack failure (502).</p>
     *
     * @throws BrandNotSupportedException         when Contentstack does not host the brand
     * @throws LandingDefinitionNotFoundException when no entry is authored for the category
     * @throws ContentServiceUnavailableException when Contentstack could not be read
     */
    @Override
    public LandingDefinition fetchDefinition(ContentQuery query) {
        String brand = resolvedBrand(query);
        Map<String, Object> entry;
        try {
            entry = contentSourceRouter.fetch(new ContentSourceRequest(
                    contentTypeOf(query.pageType()), query.locale(), query.categoryId(), brand));
        } catch (ContentstackEntryNotFoundException e) {
            // Source-neutral "no entry" becomes this slice's own 404, so the message names the page
            // that is missing rather than the content type that happens to hold it.
            throw new LandingDefinitionNotFoundException(
                    query.pageType() + " landing page not found for category " + query.categoryId(),
                    detail(query, e.getDetail()));
        } catch (BrandNotSupportedException | ContentServiceUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ContentServiceUnavailableException(
                    "Unexpected error fetching the landing page.", detail(query, e.getMessage()), e);
        }
        return toDefinition(entry, query);
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Turns a raw CMS entry into the domain model.
     *
     * @param entry the raw entry, with {@code header}/{@code footer} already unwrapped
     * @param query what was asked for, for the page type and locale
     * @return the parsed definition
     */
    private LandingDefinition toDefinition(Map<String, Object> entry, ContentQuery query) {
        // Before anything reads the tree, so block flattening, composition, the event decorator and
        // the mapper all see event lists in one shape.
        CmsEventListNormalizer.normalize(entry);

        Map<String, Object> metadata = new LinkedHashMap<>(entry);
        metadata.remove(KEY_CONTENT);
        query.pageType().strippedKeys().forEach(metadata::remove);

        Map<PageSection, List<BlockDefinition>> sections = new EnumMap<>(PageSection.class);
        for (PageSection section : PageSection.values()) {
            List<Map<String, Object>> raw = null;
            for (String key : section.sourceKeys()) {
                if (raw == null && entry.containsKey(key)) {
                    raw = extractBlockList(entry.get(key));
                }
                metadata.remove(key);
            }
            if (raw != null) {
                sections.put(section, blockNormalizer.normalize(raw).stream()
                        .map(BlockDefinition::fromCms)
                        .toList());
            }
        }
        Object categoryId = entry.get(KEY_CATEGORY_ID);
        return new LandingDefinition(query.pageType(), query.locale(),
                categoryId instanceof String id && !id.isBlank() ? id : null, metadata, sections);
    }

    /**
     * Extracts a raw, still-wrapped block list. Accepts both the global-field shape, where the value is
     * an object holding a {@code blocks} array, and a plain modular-blocks array.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractBlockList(Object value) {
        Object candidate = value;
        if (candidate instanceof Map<?, ?> section) {
            candidate = section.get(KEY_BLOCKS);
        }
        if (!(candidate instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> blocks = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element instanceof Map<?, ?> block) {
                blocks.add((Map<String, Object>) block);
            }
        }
        return blocks;
    }

    private String contentTypeOf(PageType pageType) {
        String configured = pageType == PageType.BLP ? properties.blpContentType() : properties.clpContentType();
        return configured == null || configured.isBlank() ? pageType.contentTypeUid() : configured;
    }

    /** The brand a request resolves to, falling back to the configured default. */
    private String resolvedBrand(ContentQuery query) {
        return (query.brand() != null && !query.brand().isBlank())
                ? query.brand()
                : properties.defaultBrand();
    }

    private static String detail(ContentQuery query, String cause) {
        return "brand=" + query.brand() + " locale=" + query.locale() + " pageType=" + query.pageType()
                + " categoryId=" + query.categoryId() + " | cause: " + cause;
    }
}
