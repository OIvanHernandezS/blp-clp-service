package com.liverpool.ms_clp_blp.clients.contentstackdirect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the {@code query} parameter for a Contentstack Delivery API call.
 *
 * <p>Reproduces the queries the content-service builds through the Delivery SDK
 * ({@code src/app/shared/provider/contentstack.provider.ts}), because this service has to select the
 * same entry digital_bff received. Two details are load-bearing and neither is guessable from the
 * Contentstack docs:</p>
 * <ul>
 *   <li><b>Landing pages are selected by {@code category_id}.</b> The storefront slug is free text;
 *       only its last segment identifies the page. The id is matched as typed and lower-cased, since
 *       the field is authored lower-case and a URL may not be.</li>
 *   <li><b>Brand filtering only on shared stacks.</b> Adding {@code taxonomies.brand} on a
 *       single-brand stack would match nothing, since those entries carry no brand taxonomy.</li>
 * </ul>
 *
 * <p>A {@code null} return means "send no query at all" — Feign omits a null request parameter, and
 * the SDK likewise appends no {@code &query=} when it has no conditions.</p>
 */
@Component
public class ContentStackQueryBuilder {

    private static final String FIELD_CATEGORY_ID = "category_id";
    private static final String FIELD_BRAND_TAXONOMY = "taxonomies.brand";
    private static final String OPERATOR_IN = "$in";

    // Self-contained: this app exposes no ObjectMapper bean, and the query shape is fixed anyway.
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * Builds the query selecting a landing page entry by the category it is authored for.
     *
     * @param stack      the resolved stack, deciding whether a brand filter applies
     * @param categoryId the category id from the storefront URL
     * @return the query JSON
     */
    public String categoryPageQuery(ResolvedStack stack, String categoryId) {
        Map<String, Object> conditions = baseConditions(stack);
        conditions.put(FIELD_CATEGORY_ID, Map.of(OPERATOR_IN, categoryVariants(categoryId)));
        return serialize(conditions);
    }

    /**
     * Builds the query selecting entries whose {@code field} holds exactly {@code value}.
     *
     * @param stack the resolved stack, deciding whether a brand filter applies
     * @param field the entry field to match on
     * @param value the exact value that field must hold
     * @return the query JSON
     */
    public String fieldQuery(ResolvedStack stack, String field, String value) {
        Map<String, Object> conditions = baseConditions(stack);
        conditions.put(field, value);
        return serialize(conditions);
    }

    /**
     * Builds the query selecting entries matching every condition — exact values, in the given order.
     *
     * @param stack      the resolved stack, deciding whether a brand filter applies
     * @param conditions field → exact value
     * @return the query JSON, or {@code null} when there is nothing to filter on
     */
    public String fieldsQuery(ResolvedStack stack, Map<String, Object> conditions) {
        Map<String, Object> query = baseConditions(stack);
        if (conditions != null) {
            query.putAll(conditions);
        }
        return serialize(query);
    }

    /**
     * Starts the condition map with the brand filter when the stack hosts more than one brand.
     *
     * @param stack the resolved stack
     * @return a mutable condition map, empty for a single-brand stack
     */
    private Map<String, Object> baseConditions(ResolvedStack stack) {
        Map<String, Object> conditions = new LinkedHashMap<>();
        if (stack.sharedStack()) {
            conditions.put(FIELD_BRAND_TAXONOMY, stack.brandFilter());
        }
        return conditions;
    }

    /**
     * Renders the spellings a category id may have been authored with.
     *
     * @param categoryId the id from the URL, as typed
     * @return the id as typed and lower-cased, without duplicates
     */
    private static List<String> categoryVariants(String categoryId) {
        String typed = categoryId == null ? "" : categoryId.trim();
        String lower = typed.toLowerCase(Locale.ROOT);
        return typed.equals(lower) ? List.of(typed) : List.of(typed, lower);
    }

    /**
     * @param conditions the accumulated query conditions
     * @return the conditions as JSON, or {@code null} when there are none
     */
    private static String serialize(Map<String, Object> conditions) {
        return conditions.isEmpty() ? null : MAPPER.writeValueAsString(conditions);
    }
}
