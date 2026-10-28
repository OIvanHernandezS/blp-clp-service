package com.liverpool.ms_clp_blp.clients.contentstackdirect;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.liverpool.config.properties.ContentStackConfig;
import com.liverpool.enums.Sites;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every case here mirrors a branch of the content-service's own query construction. The queries have
 * to agree, or this service selects a different entry than digital_bff received.
 */
class ContentStackQueryBuilderTest {

    private static final ResolvedStack SINGLE_BRAND_STACK =
            new ResolvedStack(Sites.LP, ContentStackConfig.builder().build(), "lp", false);
    private static final ResolvedStack SHARED_STACK =
            new ResolvedStack(Sites.MULTI2, ContentStackConfig.builder().build(), "gap", true);

    private final ContentStackQueryBuilder builder = new ContentStackQueryBuilder();

    // ── landing pages ─────────────────────────────────────────────────────────────────────────────

    /** content-service selects page_blp/page_clp by category_id, never by url. */
    @Test
    void categoryPageQuery_selectsByCategoryId() {
        assertThat(builder.categoryPageQuery(SINGLE_BRAND_STACK, "catst26617625"))
                .isEqualTo("{\"category_id\":{\"$in\":[\"catst26617625\"]}}");
    }

    /** The field is authored lower-case; a URL may not be, so both spellings are matched. */
    @Test
    void categoryPageQuery_upperCaseIdAlsoMatchesItsLowerCaseSpelling() {
        assertThat(builder.categoryPageQuery(SINGLE_BRAND_STACK, "CATST26617625"))
                .isEqualTo("{\"category_id\":{\"$in\":[\"CATST26617625\",\"catst26617625\"]}}");
    }

    @Test
    void categoryPageQuery_sharedStackFiltersByBrandTaxonomy() {
        assertThat(builder.categoryPageQuery(SHARED_STACK, "cat1"))
                .isEqualTo("{\"taxonomies.brand\":\"gap\",\"category_id\":{\"$in\":[\"cat1\"]}}");
    }

    @Test
    void categoryPageQuery_nullIdMatchesNothingRatherThanEverything() {
        assertThat(builder.categoryPageQuery(SINGLE_BRAND_STACK, null))
                .isEqualTo("{\"category_id\":{\"$in\":[\"\"]}}");
    }

    // ── field queries ─────────────────────────────────────────────────────────────────────────────

    @Test
    void fieldQuery_matchesOneExactValue() {
        assertThat(builder.fieldQuery(SINGLE_BRAND_STACK, "identifier", "ms_clp_blp_flags_LP"))
                .isEqualTo("{\"identifier\":\"ms_clp_blp_flags_LP\"}");
    }

    @Test
    void fieldsQuery_keepsEveryConditionInOrderAfterTheBrandFilter() {
        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put("source_category_id", "cat1");
        conditions.put("is_active", true);

        assertThat(builder.fieldsQuery(SHARED_STACK, conditions))
                .isEqualTo("{\"taxonomies.brand\":\"gap\",\"source_category_id\":\"cat1\",\"is_active\":true}");
    }

    /** No condition on a single-brand stack means "send no query at all", as the SDK does. */
    @Test
    void fieldsQuery_nothingToFilterOn_sendsNoQuery() {
        assertThat(builder.fieldsQuery(SINGLE_BRAND_STACK, null)).isNull();
    }
}
