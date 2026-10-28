package com.liverpool.ms_clp_blp.clients.contentstackdirect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one difference between a raw Contentstack entry and the proxy's page response.
 *
 * <p>Getting this wrong is not a crash: the page maps cleanly and comes out empty, because
 * {@code template} is a one-element list where the mapper expects an object. That is the whole reason
 * the transformation is isolated here and pinned.</p>
 */
class ContentStackPayloadNormalizerTest {

    private final ContentStackPayloadNormalizer normalizer = new ContentStackPayloadNormalizer();

    @Test
    void asProxyPage_replacesTheReferenceArraysWithTheirFirstElement() {
        Map<String, Object> template = Map.of("blocks", List.of());
        Map<String, Object> header = Map.of("nav", "x");
        Map<String, Object> footer = Map.of("links", "y");

        Map<String, Object> result = normalizer.asProxyPage(entry(Map.of(
                "template", List.of(template),
                "header", List.of(header),
                "footer", List.of(footer))));

        assertThat(result).containsEntry("template", template)
                .containsEntry("header", header)
                .containsEntry("footer", footer);
    }

    /** The proxy emits {@code undefined} for an empty reference, which serialises to an absent key. */
    @Test
    void asProxyPage_dropsTheKeyEntirelyWhenTheReferenceIsEmpty() {
        Map<String, Object> result = normalizer.asProxyPage(entry(Map.of(
                "template", List.of(),
                "page_title", "Home")));

        assertThat(result).doesNotContainKey("template").containsEntry("page_title", "Home");
    }

    @Test
    void asProxyPage_leavesAnAlreadyUnwrappedObjectAlone() {
        Map<String, Object> template = Map.of("blocks", List.of());

        Map<String, Object> result = normalizer.asProxyPage(entry(Map.of("template", template)));

        assertThat(result).containsEntry("template", template);
    }

    @Test
    void asProxyPage_carriesEveryOtherFieldThrough() {
        Map<String, Object> result = normalizer.asProxyPage(entry(Map.of(
                "uid", "blt1", "url", "/home", "seo", Map.of("no_index", false))));

        assertThat(result).containsEntry("uid", "blt1")
                .containsEntry("url", "/home")
                .containsKey("seo");
    }

    @Test
    void asProxyPage_doesNotMutateTheSourceEntry() {
        Map<String, Object> source = entry(Map.of("template", List.of(Map.of("blocks", List.of()))));

        normalizer.asProxyPage(source);

        assertThat(source.get("template")).isInstanceOf(List.class);
    }

    /**
     * @param values the entry's fields
     * @return a mutable copy, as a decoded JSON body would be
     */
    private static Map<String, Object> entry(Map<String, Object> values) {
        return new LinkedHashMap<>(values);
    }
}
