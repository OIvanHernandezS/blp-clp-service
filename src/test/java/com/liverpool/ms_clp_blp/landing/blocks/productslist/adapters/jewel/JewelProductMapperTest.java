package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.jewel;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.jewel.JewelItemVariant;
import com.liverpool.dto.jewel.JewelProduct;
import com.liverpool.dto.jewel.JewelStandardFeatures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Field-level parity for the Jewel product mapping against digital_bff's {@code ProductDto.fromJewel}.
 *
 * <p>ms-clp-blp previously published {@code {sku, title, price}} — three fields out of ~fourteen — so
 * these tests exist to keep the full contract honest, including the legacy behaviours that look like
 * bugs but which the frontend consumes.</p>
 */
class JewelProductMapperTest {

    private final JewelProductMapper mapper = new JewelProductMapper();

    // ── identity ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void productId_isTheOfferIdUpToTheFirstUnderscore() {
        ProductRecommendation product = mapOne(features(f -> f.setOfferId("1190598240_azul_medio")));

        assertThat(product.getProductId()).isEqualTo("1190598240");
    }

    @Test
    void productId_fallsBackToEventId() {
        ProductRecommendation product = mapOne(features(f -> f.setEventId("EVT-001")));

        assertThat(product.getProductId()).isEqualTo("EVT-001");
    }

    @Test
    void productWithNoUsableId_isDropped() {
        assertThat(mapper.toProducts(List.of(product(features(f -> { }))), true)).isEmpty();
    }

    /**
     * The legacy code numbers products while mapping and only afterwards discards the ones with no id,
     * so a dropped product leaves a hole. Reproduced deliberately — see {@link JewelProductMapper}.
     */
    @Test
    void index_keepsTheGapLeftByADroppedProduct() {
        List<ProductRecommendation> products = mapper.toProducts(List.of(
                product(features(f -> f.setOfferId("A"))),
                product(features(f -> { })),                 // dropped
                product(features(f -> f.setOfferId("C")))), true);

        assertThat(products).extracting(ProductRecommendation::getIndex).containsExactly(0, 2);
    }

    // ── prices ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void priceInfo_mapsEveryLegacyField() {
        ProductRecommendation product = mapOne(features(f -> {
            f.setOfferId("SKU");
            f.setMinListPrice(1699);
            f.setMaxListPrice(1799);
            f.setMinPromoPrice(679.6);
            f.setMaxPromoPrice(699.9);
            f.setPrice(1699);
            f.setSalePrice(679.6);
        }));

        ProductRecommendation.PriceInfoDto price = product.getPriceInfo();
        assertThat(price.getMinimumListPrice()).isEqualTo(1699.0);
        assertThat(price.getMaximumListPrice()).isEqualTo(1799.0);
        assertThat(price.getMinimumPromoPrice()).isEqualTo(679.6);
        assertThat(price.getMaximumPromoPrice()).isEqualTo(699.9);
        assertThat(price.getOriginalPrice()).isEqualTo(1699.0);
        assertThat(price.getPromoPrice()).isEqualTo(679.6);
    }

    /** Jewel's {@code price} is the PROMO price — the Salesforce mapping uses the list price instead. */
    @Test
    void priceInfoPrice_isThePromoPriceNotTheListPrice() {
        ProductRecommendation product = mapOne(features(f -> {
            f.setOfferId("SKU");
            f.setPrice(1699);
            f.setSalePrice(679.6);
        }));

        assertThat(product.getPriceInfo().getPrice()).isEqualTo(679.6);
    }

    // ── discount label ────────────────────────────────────────────────────────────────────────────

    @Test
    void discountLabel_isRenderedWithoutATrailingDecimal() {
        ProductRecommendation product = mapOne(features(f -> {
            f.setOfferId("SKU");
            f.setDiscountAmount(60);
        }));

        // "-60%", never "-60.0%" — Java would print the double, JavaScript does not.
        assertThat(product.getDiscountLabel()).isEqualTo("-60%");
    }

    @Test
    void discountLabel_isOmittedWhenThereIsNoDiscount() {
        ProductRecommendation product = mapOne(features(f -> {
            f.setOfferId("SKU");
            f.setDiscountAmount(0);
        }));

        assertThat(product.getDiscountLabel()).isNull();
    }

    // ── categories ────────────────────────────────────────────────────────────────────────────────

    @Test
    void categories_areParsedFromTheBreadcrumbString() {
        ProductRecommendation product = mapOne(features(f -> {
            f.setOfferId("SKU");
            f.setCategoryBreadCrumbs("CATST4003072#Mujer|extra>CATST4003074#Ropa");
        }));

        assertThat(product.getCategories())
                .extracting(ProductRecommendation.Category::getId, ProductRecommendation.Category::getLabel)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("CATST4003072", "Mujer"),
                        org.assertj.core.groups.Tuple.tuple("CATST4003074", "Ropa"));
    }

    @Test
    void categories_areEmptyWhenTheBreadcrumbIsAbsent() {
        assertThat(mapOne(features(f -> f.setOfferId("SKU"))).getCategories()).isEmpty();
    }

    // ── mainCategory (ADR-029: previously never populated for Jewel) ──────────────────────────────

    @Test
    void mainCategory_isTheLabelFollowingTheLeafItemTypeId() {
        ProductRecommendation product = mapOne(features(f -> {
            f.setOfferId("SKU");
            f.setItemType("CATST4003072>CATST4003074");
            f.setCategoryBreadCrumbs("CATST4003072#Mujer|marca>CATST4003074#Ropa|marca");
        }));

        assertThat(product.getMainCategory()).isEqualTo("Ropa");
    }

    @Test
    void mainCategory_isBlankWhenItemTypeIsAbsent() {
        ProductRecommendation product = mapOne(features(f -> {
            f.setOfferId("SKU");
            f.setCategoryBreadCrumbs("CATST4003072#Mujer|marca");
        }));

        assertThat(product.getMainCategory()).isEmpty();
    }

    @Test
    void mainCategory_isBlankWhenTheLeafIdIsNotInTheBreadcrumb() {
        ProductRecommendation product = mapOne(features(f -> {
            f.setOfferId("SKU");
            f.setItemType("CATST9999999");
            f.setCategoryBreadCrumbs("CATST4003072#Mujer|marca");
        }));

        assertThat(product.getMainCategory()).isEmpty();
    }

    @Test
    void mainCategory_isBlankWhenTheLabelCarriesNoBrandSuffix() {
        // The legacy parse requires the "label|brand" shape; without the pipe there is no main category.
        ProductRecommendation product = mapOne(features(f -> {
            f.setOfferId("SKU");
            f.setItemType("CATST4003072");
            f.setCategoryBreadCrumbs("CATST4003072#Mujer");
        }));

        assertThat(product.getMainCategory()).isEmpty();
    }

    // ── images, seller, rating ────────────────────────────────────────────────────────────────────

    @Test
    void images_areASingleThumbnail() {
        ProductRecommendation product = mapOne(features(f -> {
            f.setOfferId("SKU");
            f.setImageUrlSrc("https://cdn/img.jpg");
        }));

        assertThat(product.getImages()).singleElement().satisfies(image -> {
            assertThat(image.getType()).isEqualTo("thumbnailImage");
            assertThat(image.getUrl()).isEqualTo("https://cdn/img.jpg");
        });
    }

    @Test
    void seller_isTheFirstPipeSeparatedName_andSellerCodeIsBlank() {
        ProductRecommendation product = mapOne(features(f -> {
            f.setOfferId("SKU");
            f.setSellerNames("liverpool|otro");
        }));

        assertThat(product.getSeller()).isEqualTo("liverpool");
        assertThat(product.getSellerCode()).isEmpty();
    }

    /** Rating is a string on the wire, so it must not pick up Java's trailing {@code .0}. */
    @Test
    void rating_isRenderedAsLegacyStrings() {
        ProductRecommendation product = mapOne(features(f -> {
            f.setOfferId("SKU");
            f.setRating(5);
            f.setRatingCount(4);
        }));

        assertThat(product.getRating().getAverage()).isEqualTo("5");
        assertThat(product.getRating().getCount()).isEqualTo("4");
    }

    // ── variants ──────────────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void variants_arePassedThroughUnreshapedWhenEnabled() {
        JewelItemVariant variant = new JewelItemVariant();
        variant.setTitle("Jeans wide leg");
        ProductRecommendation product = mapOne(features(f -> {
            f.setOfferId("SKU");
            f.setItemVariants(List.of(variant));
        }));

        assertThat((List<Object>) product.getVariants()).containsExactly(variant);
    }

    @Test
    void variants_areEmptyWhenTheFlagIsOff() {
        JewelItemVariant variant = new JewelItemVariant();
        List<ProductRecommendation> products = mapper.toProducts(
                List.of(product(features(f -> {
                    f.setOfferId("SKU");
                    f.setItemVariants(List.of(variant));
                }))), false);

        assertThat((List<?>) products.get(0).getVariants()).isEmpty();
    }

    // ── constants and edge cases ──────────────────────────────────────────────────────────────────

    @Test
    void hasGiftIsAlwaysFalse_andMarketplaceComesFromTheFeed() {
        ProductRecommendation product = mapOne(features(f -> {
            f.setOfferId("SKU");
            f.setIsMarketPlace(true);
        }));

        assertThat(product.isHasGift()).isFalse();
        assertThat(product.isMarketplace()).isTrue();
    }

    @Test
    void absentOptionalStrings_becomeEmptyRatherThanNull() {
        ProductRecommendation product = mapOne(features(f -> f.setOfferId("SKU")));

        assertThat(product.getName()).isEmpty();
        assertThat(product.getBrand()).isEmpty();
        assertThat(product.getSeller()).isEmpty();
        assertThat(product.getImages().get(0).getUrl()).isEmpty();
    }

    @Test
    void nullOrEmptyInput_yieldsNoProducts() {
        assertThat(mapper.toProducts(null, true)).isEmpty();
        assertThat(mapper.toProducts(List.of(), true)).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private ProductRecommendation mapOne(JewelStandardFeatures features) {
        List<ProductRecommendation> products = mapper.toProducts(List.of(product(features)), true);
        assertThat(products).hasSize(1);
        return products.get(0);
    }

    private static JewelProduct product(JewelStandardFeatures features) {
        JewelProduct product = new JewelProduct();
        product.setStandardFeatures(features);
        return product;
    }

    private static JewelStandardFeatures features(java.util.function.Consumer<JewelStandardFeatures> customise) {
        JewelStandardFeatures features = new JewelStandardFeatures();
        customise.accept(features);
        return features;
    }
}
