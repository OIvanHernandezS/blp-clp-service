package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.salesforcev2;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.salesforce.v2.SalesforceV2Product;

import static org.assertj.core.api.Assertions.assertThat;

class SalesforceV2ProductMapperTest {

    private final SalesforceV2ProductMapper mapper = new SalesforceV2ProductMapper();

    @Test
    void toProducts_mapsTheProductCard() {
        SalesforceV2Product product = product("MP-1");
        product.setName("Pantalla 55\"");
        product.setBrandId("SONY");
        product.setSectionDescription("Electrónica");
        product.setImageUrl("https://img/1.jpg");
        product.setPersonalizationContentId("content-1");

        ProductRecommendation mapped = mapper.toProducts(List.of(product), "pers-1").get(0);

        assertThat(mapped.getIndex()).isZero();
        assertThat(mapped.getProductId()).isEqualTo("MP-1");
        assertThat(mapped.getName()).isEqualTo("Pantalla 55\"");
        assertThat(mapped.getBrand()).isEqualTo("SONY");
        assertThat(mapped.getMainCategory()).isEqualTo("Electrónica");
        assertThat(mapped.getImages()).singleElement().satisfies(image -> {
            assertThat(image.getType()).isEqualTo("thumbnailImage");
            assertThat(image.getUrl()).isEqualTo("https://img/1.jpg");
        });
        assertThat(mapped.getRating().getAverage()).isEqualTo("0");
        assertThat(mapped.getRating().getCount()).isEqualTo("0");
        assertThat(mapped.getSeller()).isEmpty();
        assertThat(mapped.getVariants()).isEqualTo(List.of());
        assertThat(mapped.getPersonalization().getId()).isEqualTo("pers-1");
        assertThat(mapped.getPersonalization().getContentId()).isEqualTo("content-1");
    }

    @Test
    void toProducts_productIdFallsBackFromMasterIdToSkuToRecordId() {
        SalesforceV2Product sku = product(null);
        sku.setProductSku("SKU-2");
        SalesforceV2Product record = product(null);
        record.setId("REC-3");

        List<ProductRecommendation> mapped = mapper.toProducts(List.of(sku, record), "p");

        assertThat(mapped).extracting(ProductRecommendation::getProductId).containsExactly("SKU-2", "REC-3");
    }

    /** Products without any id are skipped, and the index stays contiguous — the strategy's numbering. */
    @Test
    void toProducts_skipsUnidentifiableProductsWithoutIndexGaps() {
        List<ProductRecommendation> mapped =
                mapper.toProducts(Arrays.asList(product("A"), product(null), null, product("B")), "p");

        assertThat(mapped).extracting(ProductRecommendation::getProductId).containsExactly("A", "B");
        assertThat(mapped).extracting(ProductRecommendation::getIndex).containsExactly(0, 1);
    }

    @Test
    void toProducts_mapsPricesWithPromoAsThePrice_andComputesTheDiscountLabel() {
        SalesforceV2Product product = product("A");
        product.setVariantListPrice("1000.0");
        product.setVariantPromoPrice(" 800 ");
        product.setMinListPrice("900");
        product.setMaxPromoPrice("not-a-number");

        ProductRecommendation mapped = mapper.toProducts(List.of(product), "p").get(0);

        assertThat(mapped.getPriceInfo().getOriginalPrice()).isEqualTo(1000.0);
        assertThat(mapped.getPriceInfo().getPromoPrice()).isEqualTo(800.0);
        assertThat(mapped.getPriceInfo().getPrice()).isEqualTo(800.0);
        assertThat(mapped.getPriceInfo().getMinimumListPrice()).isEqualTo(900.0);
        assertThat(mapped.getPriceInfo().getMaximumPromoPrice()).isNull();
        assertThat(mapped.getDiscountLabel()).isEqualTo("-20%");
    }

    @Test
    void toProducts_noEffectiveDiscount_hasNoLabel() {
        SalesforceV2Product product = product("A");
        product.setVariantListPrice("500");
        product.setVariantPromoPrice("500");

        assertThat(mapper.toProducts(List.of(product), "p").get(0).getDiscountLabel()).isNull();
    }

    @Test
    void toProducts_splitsCategoriesDistinctAndInOrder_withTheIdAsLabel() {
        SalesforceV2Product product = product("A");
        product.setDigitalCategories("cat-1| cat-2 |cat-1||");

        List<ProductRecommendation.Category> categories =
                mapper.toProducts(List.of(product), "p").get(0).getCategories();

        assertThat(categories).extracting(ProductRecommendation.Category::getId).containsExactly("cat-1", "cat-2");
        assertThat(categories).extracting(ProductRecommendation.Category::getLabel).containsExactly("cat-1", "cat-2");
    }

    @Test
    void toProducts_missingImageAndSection_publishEmptyValues() {
        ProductRecommendation mapped = mapper.toProducts(List.of(product("A")), "p").get(0);

        assertThat(mapped.getImages().get(0).getUrl()).isEmpty();
        assertThat(mapped.getMainCategory()).isEmpty();
        assertThat(mapped.getCategories()).isEmpty();
    }

    @Test
    void toProducts_nullOrEmpty_isEmpty() {
        assertThat(mapper.toProducts(null, "p")).isEmpty();
        assertThat(mapper.toProducts(List.of(), "p")).isEmpty();
    }

    private static SalesforceV2Product product(String masterProductId) {
        SalesforceV2Product product = new SalesforceV2Product();
        product.setMasterProductId(masterProductId);
        return product;
    }
}
