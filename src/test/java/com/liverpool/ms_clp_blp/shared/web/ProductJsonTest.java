package com.liverpool.ms_clp_blp.shared.web;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.liverpool.dto.ProductRecommendation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire shape of a product object against digital_bff's {@code ProductDto}.
 *
 * <p>The shared platform-commons DTO has no Jackson annotations, so its default serialization drifts
 * from the legacy contract in ways that are invisible to every other test — a boolean renamed by
 * Jackson's {@code is}-prefix stripping still round-trips fine, it just breaks the frontend. These
 * assertions name the exact keys.</p>
 */
class ProductJsonTest {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final ObjectMapper mapper =
            ProductJson.withProductMixins(JsonMapper.builder().findAndAddModules().build());

    /** The exact key set digital_bff emits for a Jewel product. */
    private static final List<String> BFF_JEWEL_KEYS = List.of(
            "index", "productId", "discountLabel", "name", "priceInfo", "categories", "images",
            "brand", "seller", "sellerCode", "rating", "hasGift", "variants", "isMarketplace");

    @Test
    void serializedProduct_usesTheLegacyKeyNames() {
        Map<String, Object> json = mapper.convertValue(fullyPopulated(), MAP);

        assertThat(json).containsOnlyKeys(BFF_JEWEL_KEYS.toArray(String[]::new));
    }

    /**
     * The field is {@code isMarketplace} with a Lombok getter {@code isMarketplace()}; Jackson would
     * otherwise strip the prefix and emit {@code marketplace}.
     */
    @Test
    void isMarketplace_keepsItsIsPrefix() {
        Map<String, Object> json = mapper.convertValue(fullyPopulated(), MAP);

        assertThat(json).containsEntry("isMarketplace", true);
        assertThat(json).doesNotContainKey("marketplace");
    }

    /** {@code hasGift}'s getter is {@code isHasGift()} — the implicit name is already correct. */
    @Test
    void hasGift_isNotRenamedByThePrefixStripping() {
        Map<String, Object> json = mapper.convertValue(fullyPopulated(), MAP);

        assertThat(json).containsEntry("hasGift", false);
        assertThat(json).doesNotContainKey("gift");
    }

    /** Present on the shared DTO, absent from the legacy contract. */
    @Test
    void mainCategory_isNotSerialized() {
        ProductRecommendation product = fullyPopulated();
        product.setMainCategory("Mujer");

        assertThat(mapper.convertValue(product, MAP)).doesNotContainKey("mainCategory");
    }

    /**
     * Vertex is the exception: digital_bff's {@code fromVertex} publishes {@code mainCategory} and
     * {@code flags}, so {@code VertexProductRecommendation} lifts the mixin's ignoral for it alone. Both
     * mappers must agree — Jackson 2 writes the inlined (eager) carousels, Jackson 3 the lazy ones.
     */
    @Test
    void vertexProduct_publishesMainCategoryAndFlags_onBothJacksonMajors() {
        com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.vertex.VertexProductRecommendation product =
                new com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.vertex.VertexProductRecommendation();
        product.setProductId("1074619185");
        product.setMainCategory("Haus");
        product.setFlags(Map.of("isSponsoredRecord", false));
        tools.jackson.databind.json.JsonMapper jackson3 = tools.jackson.databind.json.JsonMapper.builder()
                .addMixIn(ProductRecommendation.class, ProductRecommendationMixin.class).build();

        Map<String, Object> json2 = mapper.convertValue(product, MAP);
        Map<String, Object> json3 = jackson3.convertValue(product, new tools.jackson.core.type.TypeReference<>() {});

        for (Map<String, Object> json : List.of(json2, json3)) {
            assertThat(json).containsEntry("mainCategory", "Haus")
                    .containsEntry("flags", Map.of("isSponsoredRecord", false))
                    .containsKey("isMarketplace");
        }
    }

    /**
     * The legacy response is JavaScript: absent fields are omitted, never null. Salesforce products
     * have no {@code discountLabel}, and nothing on Home sets {@code isCollectionProduct}.
     */
    @Test
    void nullFields_areOmittedRatherThanSerializedAsNull() {
        ProductRecommendation product = fullyPopulated();
        product.setDiscountLabel(null);

        Map<String, Object> json = mapper.convertValue(product, MAP);

        assertThat(json).doesNotContainKey("discountLabel");
        assertThat(json).doesNotContainKey("isCollectionProduct");
    }

    @Test
    void nestedShapes_matchTheLegacyContract() {
        Map<String, Object> json = mapper.convertValue(fullyPopulated(), MAP);

        assertThat(asMap(json.get("priceInfo"))).containsOnlyKeys(
                "minimumListPrice", "maximumListPrice", "minimumPromoPrice", "maximumPromoPrice",
                "originalPrice", "promoPrice", "price");
        assertThat(asMap(asList(json.get("images")).get(0))).containsOnlyKeys("type", "url");
        assertThat(asMap(asList(json.get("categories")).get(0))).containsOnlyKeys("id", "label");
        assertThat(asMap(json.get("rating"))).containsOnlyKeys("average", "count");
    }

    /** Prices are numbers on the wire, not strings — the old {@code (sku,title,price)} shape used a String. */
    @Test
    void prices_serializeAsNumbers() {
        Map<String, Object> priceInfo = asMap(mapper.convertValue(fullyPopulated(), MAP).get("priceInfo"));

        assertThat(priceInfo.get("price")).isInstanceOf(Number.class);
        assertThat(priceInfo.get("originalPrice")).isInstanceOf(Number.class);
    }

    /**
     * Documents why the mixin exists at all. Without it Jackson emits {@code "marketplace"} instead of
     * {@code "isMarketplace"}, leaks {@code mainCategory}, and writes nulls — so the mixin is load-bearing,
     * not decorative. If this ever starts failing, the shared DTO gained its own annotations and the
     * mixin should be re-evaluated rather than silently kept.
     */
    @Test
    void withoutTheMixin_theSharedDtoSerializesIncorrectly() {
        ObjectMapper unconfigured = JsonMapper.builder().findAndAddModules().build();

        Map<String, Object> json = unconfigured.convertValue(fullyPopulated(), MAP);

        assertThat(json).containsKey("marketplace").doesNotContainKey("isMarketplace");
        assertThat(json).containsKey("mainCategory");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private static ProductRecommendation fullyPopulated() {
        ProductRecommendation product = new ProductRecommendation();
        product.setIndex(0);
        product.setProductId("1190598240");
        product.setDiscountLabel("-60%");
        product.setName("Jeans wide leg para mujer");

        ProductRecommendation.PriceInfoDto priceInfo = new ProductRecommendation.PriceInfoDto();
        priceInfo.setMinimumListPrice(1699.0);
        priceInfo.setMaximumListPrice(1699.0);
        priceInfo.setMinimumPromoPrice(679.6);
        priceInfo.setMaximumPromoPrice(679.6);
        priceInfo.setOriginalPrice(1699.0);
        priceInfo.setPromoPrice(679.6);
        priceInfo.setPrice(679.6);
        product.setPriceInfo(priceInfo);

        ProductRecommendation.Category category = new ProductRecommendation.Category();
        category.setId("CATST4003072");
        category.setLabel("Mujer");
        product.setCategories(List.of(category));

        ProductRecommendation.ProductImage image = new ProductRecommendation.ProductImage();
        image.setType("thumbnailImage");
        image.setUrl("https://ss540.liverpool.com.mx/xl/1190598256.jpg");
        product.setImages(List.of(image));

        ProductRecommendation.Rating rating = new ProductRecommendation.Rating();
        rating.setAverage("5");
        rating.setCount("4");
        product.setRating(rating);

        product.setBrand("AMERICAN EAGLE");
        product.setSeller("liverpool");
        product.setSellerCode("");
        product.setHasGift(false);
        product.setMarketplace(true);
        product.setVariants(List.of());
        return product;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }
}
