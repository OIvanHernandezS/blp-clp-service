package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.salesforce;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.salesforce.SalesforcePayload;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Field-level parity for the Salesforce product mapping against digital_bff's
 * {@code ProductDto.fromSalesforceMapped} / {@code fromSalesfroce}.
 *
 * <p>These carousels only render for an authenticated session, so unlike the Jewel path they cannot
 * be diffed against the live BFF with a guest request. These tests are the parity evidence, which is
 * why they assert the legacy quirks explicitly rather than the shape alone.</p>
 *
 * <p>Payloads are built by deserializing the JSON Salesforce actually returns, so the commons DTO
 * bindings are exercised here rather than assumed.</p>
 */
class SalesforceProductMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SalesforceProductMapper mapper = new SalesforceProductMapper();

    // ── productDataMapped ─────────────────────────────────────────────────────────────────────────

    @Test
    void mappedRecord_populatesTheLegacyFields() {
        ProductRecommendation product = mapOne("""
                { "productDataMapped": [{
                    "productId": "1190598240",
                    "title": "Jeans wide leg para mujer",
                    "image": "https://cdn/img.jpg",
                    "brand": "AMERICAN EAGLE",
                    "sellernames": "liverpool"
                }] }
                """);

        assertThat(product.getIndex()).isZero();
        assertThat(product.getProductId()).isEqualTo("1190598240");
        assertThat(product.getName()).isEqualTo("Jeans wide leg para mujer");
        assertThat(product.getBrand()).isEqualTo("AMERICAN EAGLE");
        assertThat(product.getImages()).singleElement()
                .satisfies(image -> assertThat(image.getUrl()).isEqualTo("https://cdn/img.jpg"));
    }

    /** Salesforce sends display strings; the thousands separator must be stripped before parsing. */
    @Test
    void prices_areParsedFromStringsWithThousandsSeparators() {
        ProductRecommendation product = mapOne("""
                { "productDataMapped": [{
                    "productId": "SKU",
                    "priceInfo": {
                      "listPrice":  { "price": "1,699.00", "minPrice": "1,699.00", "maxPrice": "1,799.50" },
                      "promoPrice": { "price": "679.60" }
                    }
                }] }
                """);

        ProductRecommendation.PriceInfoDto prices = product.getPriceInfo();
        assertThat(prices.getOriginalPrice()).isEqualTo(1699.0);
        assertThat(prices.getMinimumListPrice()).isEqualTo(1699.0);
        assertThat(prices.getMaximumListPrice()).isEqualTo(1799.5);
        assertThat(prices.getPromoPrice()).isEqualTo(679.6);
    }

    /** Legacy: {@code price} is the LIST price here — the Jewel mapping uses the promo price. */
    @Test
    void price_isTheListPriceNotThePromoPrice() {
        ProductRecommendation product = mapOne("""
                { "productDataMapped": [{
                    "productId": "SKU",
                    "priceInfo": { "listPrice": { "price": "1699.00" }, "promoPrice": { "price": "679.60" } }
                }] }
                """);

        assertThat(product.getPriceInfo().getPrice()).isEqualTo(1699.0);
    }

    @Test
    void unparseablePrice_isOmittedRatherThanZeroed() {
        ProductRecommendation product = mapOne("""
                { "productDataMapped": [{ "productId": "SKU",
                    "priceInfo": { "listPrice": { "price": "n/a" } } }] }
                """);

        assertThat(product.getPriceInfo().getPrice()).isNull();
    }

    /** Salesforce categories are {@code {"lvl0": "Mujer"}} maps with no ids, so the label doubles as one. */
    @Test
    void categoryId_isTheLabelText() {
        ProductRecommendation product = mapOne("""
                { "productDataMapped": [{ "productId": "SKU",
                    "categories": [{ "lvl0": "Mujer" }] }] }
                """);

        assertThat(product.getCategories()).singleElement().satisfies(category -> {
            assertThat(category.getLabel()).isEqualTo("Mujer");
            assertThat(category.getId()).isEqualTo("Mujer");
        });
    }

    /** Legacy quirk: Salesforce sends no rating on this path and the legacy code invents zeros. */
    @Test
    void rating_isHardcodedToZero() {
        ProductRecommendation product = mapOne("""
                { "productDataMapped": [{ "productId": "SKU" }] }
                """);

        assertThat(product.getRating().getAverage()).isEqualTo("0");
        assertThat(product.getRating().getCount()).isEqualTo("0");
    }

    /** Legacy quirk: sellerCode duplicates seller on this path (Jewel publishes an empty string). */
    @Test
    void sellerCode_duplicatesTheSellerName() {
        ProductRecommendation product = mapOne("""
                { "productDataMapped": [{ "productId": "SKU", "sellernames": "liverpool" }] }
                """);

        assertThat(product.getSeller()).isEqualTo("liverpool");
        assertThat(product.getSellerCode()).isEqualTo("liverpool");
    }

    @Test
    void images_useTheLargeImageType_unlikeJewelsThumbnail() {
        ProductRecommendation product = mapOne("""
                { "productDataMapped": [{ "productId": "SKU", "image": "https://cdn/i.jpg" }] }
                """);

        assertThat(product.getImages()).singleElement()
                .satisfies(image -> assertThat(image.getType()).isEqualTo("largeImage"));
    }

    /** These two are Jewel-only in the legacy contract and must stay absent here. */
    @Test
    void discountLabelAndVariants_areNeverPopulated() {
        ProductRecommendation product = mapOne("""
                { "productDataMapped": [{ "productId": "SKU" }] }
                """);

        assertThat(product.getDiscountLabel()).isNull();
        assertThat((List<?>) product.getVariants()).isEmpty();
        assertThat(product.isMarketplace()).isFalse();
        assertThat(product.isHasGift()).isFalse();
    }

    @Test
    void index_isContiguous_unlikeTheJewelPath() {
        List<ProductRecommendation> products = map("""
                { "productDataMapped": [
                    { "productId": "a" }, { "productId": "b" }, { "productId": "c" }] }
                """);

        assertThat(products).extracting(ProductRecommendation::getIndex).containsExactly(0, 1, 2);
    }

    // ── raw products (productToShow / products) ──────────────────────────────────────────────────

    @Test
    void rawRecord_readsTheValueWrappedAttributes() {
        ProductRecommendation product = mapOne("""
                { "products": [{
                    "id": "sku-raw-001",
                    "dimensions": { "Brand": ["NIKE"] },
                    "attributes": {
                      "name":      { "value": "Raw Product" },
                      "listPrice": { "value": "49.99" },
                      "imageUrl":  { "value": "https://cdn/raw.jpg" },
                      "rating":    { "value": "4" }
                    }
                }] }
                """);

        assertThat(product.getProductId()).isEqualTo("sku-raw-001");
        assertThat(product.getName()).isEqualTo("Raw Product");
        assertThat(product.getPriceInfo().getPrice()).isEqualTo(49.99);
        assertThat(product.getBrand()).isEqualTo("NIKE");
        assertThat(product.getRating().getAverage()).isEqualTo("4");
    }

    /**
     * Salesforce inverts the price attribute names on this shape: {@code price} is the list price and
     * {@code listPrice} the discounted one. Reading them at face value inverts every discount.
     */
    @Test
    void rawRecord_pricesAreInverted() {
        ProductRecommendation product = mapOne("""
                { "products": [{ "id": "x", "attributes": {
                    "price":         { "value": "1299.00" },
                    "listPrice":     { "value": "999.00" },
                    "maxListPrice":  { "value": "1399.00" },
                    "maxPromoPrice": { "value": "1099.00" }
                } }] }
                """);

        ProductRecommendation.PriceInfoDto prices = product.getPriceInfo();
        assertThat(prices.getOriginalPrice()).isEqualTo(1299.00);
        assertThat(prices.getPromoPrice()).isEqualTo(999.00);
        assertThat(prices.getMaximumListPrice()).isEqualTo(1399.00);
        assertThat(prices.getMaximumPromoPrice()).isEqualTo(1099.00);
    }

    /** The raw path has no minimum prices upstream at all, so they stay absent rather than zero. */
    @Test
    void rawRecord_omitsMinimumPrices() {
        ProductRecommendation product = mapOne("""
                { "products": [{ "id": "x", "attributes": {} }] }
                """);

        assertThat(product.getPriceInfo().getMinimumListPrice()).isNull();
        assertThat(product.getPriceInfo().getMinimumPromoPrice()).isNull();
    }

    /** Legacy leaves seller unset on this path even though Salesforce supplies sellerNames. */
    @Test
    void rawRecord_leavesSellerUnset() {
        ProductRecommendation product = mapOne("""
                { "products": [{ "id": "x", "attributes": { "sellerNames": { "value": "liverpool" } } }] }
                """);

        assertThat(product.getSeller()).isNull();
        // sellerCode is never assigned on this path, so it keeps ProductRecommendation's own "" default
        // — unlike the mapped path, which deliberately duplicates the seller name into it.
        assertThat(product.getSellerCode()).isEmpty();
    }

    /**
     * Raw categories come from the product's own {@code categories}, which is where Salesforce puts
     * them — they were previously read from {@code attributes.categories}, which does not exist, so the
     * list was unconditionally empty. The legacy id-only shape (no label) is preserved.
     */
    @Test
    void rawRecord_categoriesAreIdsWithNoLabel() {
        ProductRecommendation product = mapOne("""
                { "products": [{ "id": "x", "categories": ["cat-1", "cat-2"], "attributes": {} }] }
                """);

        assertThat(product.getCategories()).hasSize(2);
        assertThat(product.getCategories()).extracting(ProductRecommendation.Category::getId)
                .containsExactly("cat-1", "cat-2");
        assertThat(product.getCategories()).extracting(ProductRecommendation.Category::getLabel)
                .containsOnlyNulls();
    }

    /** When Salesforce resolves {@code categoryNodes} they win over the bare id list, labels included. */
    @Test
    void rawRecord_categoryNodesWinOverCategoryIds() {
        ProductRecommendation product = mapOne("""
                { "products": [{ "id": "x", "categories": ["cat-1"],
                    "categoryNodes": [{ "id": "CAT1", "label": "Mujer" }, { "id": "CAT2", "label": "Vestidos" }],
                    "attributes": {} }] }
                """);

        assertThat(product.getCategories()).extracting(ProductRecommendation.Category::getId)
                .containsExactly("CAT1", "CAT2");
        assertThat(product.getCategories()).extracting(ProductRecommendation.Category::getLabel)
                .containsExactly("Mujer", "Vestidos");
    }

    // ── shape selection is per payload ───────────────────────────────────────────────────────────

    @Test
    void productDataMapped_winsOverBothRawShapes() {
        List<ProductRecommendation> products = map("""
                {
                  "productDataMapped": [{ "productId": "mapped-1", "title": "Mapped" }],
                  "productToShow":     [{ "id": "to-show", "attributes": {} }],
                  "products":          [{ "id": "plain",   "attributes": {} }]
                }
                """);

        assertThat(products).extracting(ProductRecommendation::getProductId).containsExactly("mapped-1");
    }

    /**
     * The shape that used to be unread entirely: a campaign serving only {@code productToShow} mapped to
     * zero products, which dropped the carousel.
     */
    @Test
    void productToShow_isReadAndWinsOverProducts() {
        List<ProductRecommendation> products = map("""
                {
                  "productToShow": [{ "id": "to-show", "attributes": {} }],
                  "products":      [{ "id": "plain",   "attributes": {} }]
                }
                """);

        assertThat(products).extracting(ProductRecommendation::getProductId).containsExactly("to-show");
    }

    @Test
    void emptyMappedList_fallsThroughToTheRawShape() {
        List<ProductRecommendation> products = map("""
                { "productDataMapped": [], "products": [{ "id": "raw-only", "attributes": {} }] }
                """);

        assertThat(products).extracting(ProductRecommendation::getProductId).containsExactly("raw-only");
    }

    @Test
    void nullOrEmptyPayload_yieldsNoProducts() {
        assertThat(mapper.toProducts(null)).isEmpty();
        assertThat(map("{}")).isEmpty();
        assertThat(map("{ \"productDataMapped\": [], \"products\": [] }")).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private List<ProductRecommendation> map(String payloadJson) {
        return mapper.toProducts(MAPPER.readValue(payloadJson, SalesforcePayload.class));
    }

    private ProductRecommendation mapOne(String payloadJson) {
        List<ProductRecommendation> products = map(payloadJson);
        assertThat(products).hasSize(1);
        return products.get(0);
    }
}
