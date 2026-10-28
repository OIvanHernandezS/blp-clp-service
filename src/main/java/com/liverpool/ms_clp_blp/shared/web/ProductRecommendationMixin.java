package com.liverpool.ms_clp_blp.shared.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Jackson mixin that makes {@code com.liverpool.dto.ProductRecommendation} serialize exactly like
 * digital_bff's {@code ProductDto}.
 *
 * <p>The shared platform-commons DTO carries no Jackson annotations of its own, which leaves three
 * mismatches against the legacy contract. Rather than fork the shared library, the wire shape is
 * corrected here:</p>
 *
 * <ul>
 *   <li><strong>{@code isMarketplace}</strong> — the field is {@code isMarketplace} with a Lombok
 *       getter {@code isMarketplace()}. Jackson strips the {@code is} prefix from boolean getters, so
 *       the property would serialize as {@code marketplace}. The explicit name restores it.</li>
 *   <li><strong>{@code mainCategory}</strong> — exists on the shared DTO but not on the legacy
 *       {@code ProductDto}, so it must not appear on the wire.</li>
 *   <li><strong>null handling</strong> — the legacy response is JavaScript, where absent fields are
 *       simply omitted (e.g. {@code discountLabel} on Salesforce products, {@code isCollectionProduct}
 *       everywhere on the storefront). {@code NON_NULL} reproduces that instead of emitting {@code null}.</li>
 * </ul>
 *
 * <p>{@code hasGift} (getter {@code isHasGift()}) and {@code isCollectionProduct} (getter
 * {@code getIsCollectionProduct()}) already resolve to the correct names — {@code ProductJsonTest}
 * pins that so a Lombok or library change cannot silently rename them.</p>
 *
 * <p>Annotations live in {@code com.fasterxml.jackson.annotation}, which both Jackson 2 and Jackson 3
 * read, so this one mixin serves both mappers (see {@link ProductJson}).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class ProductRecommendationMixin {

    /** @return whether the product is sold by a marketplace seller */
    @JsonProperty("isMarketplace")
    public abstract boolean isMarketplace();

    /** @return the shared DTO's extra category field, excluded from the legacy contract */
    @JsonIgnore
    public abstract String getMainCategory();
}
