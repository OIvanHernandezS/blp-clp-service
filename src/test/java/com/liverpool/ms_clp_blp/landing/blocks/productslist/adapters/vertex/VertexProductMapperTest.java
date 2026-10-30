package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.vertex;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.groupby.ColorInfoDTO;
import com.liverpool.dto.groupby.FeatureFlags;
import com.liverpool.dto.groupby.GalleryContentDto;
import com.liverpool.dto.groupby.MinMaxPricesDTO;
import com.liverpool.dto.groupby.PriceInfoGBDto;
import com.liverpool.dto.groupby.RatingInfo;
import com.liverpool.dto.plp.RecordsResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link VertexProductMapper} (Rule 19 reference: {@code ProductDto.fromVertex} in
 * {@code libs/providers/src/dto/product/product.dto.ts}).
 */
class VertexProductMapperTest {

    private final VertexProductMapper mapper = new VertexProductMapper();

    @Test
    void toProducts_nullOrEmpty_returnsEmptyList() {
        assertThat(mapper.toProducts(null)).isEmpty();
        assertThat(mapper.toProducts(List.of())).isEmpty();
    }

    @Test
    void toProducts_nullRecordInList_isSkipped() {
        List<RecordsResponse> records = new java.util.ArrayList<>();
        records.add(null);
        records.add(record("sku-1", "Product One"));

        List<ProductRecommendation> products = mapper.toProducts(records);

        assertThat(products).hasSize(1);
        assertThat(products.get(0).getProductId()).isEqualTo("sku-1");
    }

    @Test
    void toProduct_indexAndBasicFields_areMapped() {
        RecordsResponse record = record("sku-1", "Product One");
        record.setBrand("Nike");

        ProductRecommendation product = mapper.toProducts(List.of(record)).get(0);

        assertThat(product.getIndex()).isZero();
        assertThat(product.getProductId()).isEqualTo("sku-1");
        assertThat(product.getName()).isEqualTo("Product One");
        assertThat(product.getBrand()).isEqualTo("Nike");
        assertThat(product.getSellerCode()).isEmpty();
        assertThat(product.isHasGift()).isFalse();
        assertThat(product.getVariants()).isEqualTo(List.of());
    }

    @Test
    void toProduct_nullProductIdOrTitle_defaultToEmptyString() {
        RecordsResponse record = new RecordsResponse();

        ProductRecommendation product = mapper.toProducts(List.of(record)).get(0);

        assertThat(product.getProductId()).isEmpty();
        assertThat(product.getName()).isEmpty();
        assertThat(product.getBrand()).isEmpty();
    }

    // ── price: current price = promoPrice.price, falling back to promoPrice.maxPrice, then 0 ────────

    @Test
    void priceInfo_fullPrices_mapEveryField() {
        PriceInfoGBDto prices = new PriceInfoGBDto();
        prices.setListPrice(new MinMaxPricesDTO(100d, 150d, null));
        prices.setPromoPrice(new MinMaxPricesDTO(80d, 120d, 90d));
        RecordsResponse record = record("sku-1", "P");
        record.setPriceInfo(prices);

        ProductRecommendation.PriceInfoDto priceInfo = mapper.toProducts(List.of(record)).get(0).getPriceInfo();

        assertThat(priceInfo.getMinimumListPrice()).isEqualTo(100d);
        assertThat(priceInfo.getMaximumListPrice()).isEqualTo(150d);
        assertThat(priceInfo.getMinimumPromoPrice()).isEqualTo(80d);
        assertThat(priceInfo.getMaximumPromoPrice()).isEqualTo(120d);
        // originalPrice: listPrice.price is null, falls back to listPrice.maxPrice.
        assertThat(priceInfo.getOriginalPrice()).isEqualTo(150d);
        // currentPrice: promoPrice.price (90) wins over promoPrice.maxPrice.
        assertThat(priceInfo.getPromoPrice()).isEqualTo(90d);
        assertThat(priceInfo.getPrice()).isEqualTo(90d);
    }

    @Test
    void priceInfo_noPromoPriceValue_fallsBackToMaxPrice() {
        PriceInfoGBDto prices = new PriceInfoGBDto();
        prices.setPromoPrice(new MinMaxPricesDTO(null, 200d, null));
        RecordsResponse record = record("sku-1", "P");
        record.setPriceInfo(prices);

        ProductRecommendation.PriceInfoDto priceInfo = mapper.toProducts(List.of(record)).get(0).getPriceInfo();

        assertThat(priceInfo.getPrice()).isEqualTo(200d);
        assertThat(priceInfo.getPromoPrice()).isEqualTo(200d);
    }

    @Test
    void priceInfo_missingEntirely_defaultsEveryFieldToZero() {
        RecordsResponse record = record("sku-1", "P");

        ProductRecommendation.PriceInfoDto priceInfo = mapper.toProducts(List.of(record)).get(0).getPriceInfo();

        assertThat(priceInfo.getMinimumListPrice()).isZero();
        assertThat(priceInfo.getMaximumListPrice()).isZero();
        assertThat(priceInfo.getMinimumPromoPrice()).isZero();
        assertThat(priceInfo.getMaximumPromoPrice()).isZero();
        assertThat(priceInfo.getOriginalPrice()).isZero();
        assertThat(priceInfo.getPromoPrice()).isZero();
        assertThat(priceInfo.getPrice()).isZero();
    }

    // ── categories: categories[0] is "id#label>id#label" ─────────────────────────────────────────

    @Test
    void categories_breadcrumbWithIdAndLabel_splitsBoth() {
        RecordsResponse record = record("sku-1", "P");
        record.setCategories(List.of("100#Mujer>200#Zapatos"));

        List<ProductRecommendation.Category> categories = mapper.toProducts(List.of(record)).get(0).getCategories();

        assertThat(categories).hasSize(2);
        assertThat(categories.get(0).getId()).isEqualTo("100");
        assertThat(categories.get(0).getLabel()).isEqualTo("Mujer");
        assertThat(categories.get(1).getId()).isEqualTo("200");
        assertThat(categories.get(1).getLabel()).isEqualTo("Zapatos");
    }

    @Test
    void categories_crumbWithNoHash_publishesRawTextAsLabelWithNullId() {
        RecordsResponse record = record("sku-1", "P");
        record.setCategories(List.of("JustALabel"));

        List<ProductRecommendation.Category> categories = mapper.toProducts(List.of(record)).get(0).getCategories();

        assertThat(categories).hasSize(1);
        assertThat(categories.get(0).getId()).isNull();
        assertThat(categories.get(0).getLabel()).isEqualTo("JustALabel");
    }

    @Test
    void categories_missingOrBlank_isEmpty() {
        RecordsResponse noCategories = record("sku-1", "P");
        RecordsResponse blankFirst = record("sku-2", "P");
        blankFirst.setCategories(List.of(""));
        RecordsResponse emptyList = record("sku-3", "P");
        emptyList.setCategories(List.of());

        assertThat(mapper.toProducts(List.of(noCategories)).get(0).getCategories()).isEmpty();
        assertThat(mapper.toProducts(List.of(blankFirst)).get(0).getCategories()).isEmpty();
        assertThat(mapper.toProducts(List.of(emptyList)).get(0).getCategories()).isEmpty();
    }

    /** digital_bff publishes Vertex's ids, which the breadcrumb strings do not always carry. */
    @Test
    void categories_carrouselCategoriesWinOverTheBreadcrumb() {
        RecordsResponse record = record("sku-1", "P");
        record.setCategories(List.of("Haus>Blancos"));
        record.setCarrouselCategories(List.of(category("CATST16930700", "Haus"), category("CATST7795771", "Blancos")));

        List<ProductRecommendation.Category> categories = mapper.toProducts(List.of(record)).get(0).getCategories();

        assertThat(categories).extracting(ProductRecommendation.Category::getId)
                .containsExactly("CATST16930700", "CATST7795771");
        assertThat(categories).extracting(ProductRecommendation.Category::getLabel)
                .containsExactly("Haus", "Blancos");
    }

    @Test
    void mainCategory_isTheRecordsMainCategory_orBlank() {
        RecordsResponse withMain = record("sku-1", "P");
        withMain.setMainCategory("Haus");
        RecordsResponse without = record("sku-2", "P");

        assertThat(mapper.toProducts(List.of(withMain)).get(0).getMainCategory()).isEqualTo("Haus");
        assertThat(mapper.toProducts(List.of(without)).get(0).getMainCategory()).isEmpty();
    }

    // ── flags: the record's featureFlags ─────────────────────────────────────────────────────────

    @Test
    void flags_carryEveryFlagTheRecordSent_andOmitAbsentOnes() {
        FeatureFlags flags = new FeatureFlags();
        flags.setIsMarketPlace(false);
        flags.setIsSponsoredRecord(true);
        flags.setIsCrossBorder(false);
        RecordsResponse record = record("sku-1", "P");
        record.setFeatureFlags(flags);

        VertexProductRecommendation product =
                (VertexProductRecommendation) mapper.toProducts(List.of(record)).get(0);

        assertThat(product.getFlags()).containsExactly(
                java.util.Map.entry("isMarketPlace", false),
                java.util.Map.entry("isSponsoredRecord", true),
                java.util.Map.entry("isCrossBorder", false));
    }

    @Test
    void flags_noFeatureFlags_isEmptyNotNull() {
        VertexProductRecommendation product =
                (VertexProductRecommendation) mapper.toProducts(List.of(record("sku-1", "P"))).get(0);

        assertThat(product.getFlags()).isEmpty();
    }

    // ── images: first color variant's gallery content ────────────────────────────────────────────

    @Test
    void images_fromFirstVariantColorGallery() {
        GalleryContentDto image1 = new GalleryContentDto("image", "http://img/1.jpg", "large");
        GalleryContentDto image2 = new GalleryContentDto("image", "http://img/2.jpg", "large");
        ColorInfoDTO variant = ColorInfoDTO.builder().galleryContent(List.of(image1, image2)).build();
        RecordsResponse record = record("sku-1", "P");
        record.setVariantsColor(List.of(variant));

        List<ProductRecommendation.ProductImage> images = mapper.toProducts(List.of(record)).get(0).getImages();

        assertThat(images).hasSize(2);
        // digital_bff: the first gallery image is the large one, the rest are thumbnails.
        assertThat(images.get(0).getType()).isEqualTo("largeImage");
        assertThat(images.get(1).getType()).isEqualTo("thumbnailImage");
        assertThat(images.get(0).getUrl()).isEqualTo("http://img/1.jpg");
        assertThat(images.get(1).getUrl()).isEqualTo("http://img/2.jpg");
    }

    @Test
    void images_noVariants_isEmpty() {
        RecordsResponse record = record("sku-1", "P");

        assertThat(mapper.toProducts(List.of(record)).get(0).getImages()).isEmpty();
    }

    // ── seller: seller[0], falling back to sellerName ────────────────────────────────────────────

    @Test
    void seller_usesFirstSellerWhenPresent() {
        RecordsResponse record = record("sku-1", "P");
        record.setSeller(List.of("Seller A", "Seller B"));
        record.setSellerName("Fallback Seller");

        assertThat(mapper.toProducts(List.of(record)).get(0).getSeller()).isEqualTo("Seller A");
    }

    @Test
    void seller_fallsBackToSellerName_whenSellerListEmpty() {
        RecordsResponse record = record("sku-1", "P");
        record.setSeller(List.of());
        record.setSellerName("Fallback Seller");

        assertThat(mapper.toProducts(List.of(record)).get(0).getSeller()).isEqualTo("Fallback Seller");
    }

    // ── rating ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void rating_populated_isFormattedAsPlainStrings() {
        RatingInfo ratingInfo = RatingInfo.builder()
                .productAvgRating(4.5d)
                .productRatingCount(120)
                .build();
        RecordsResponse record = record("sku-1", "P");
        record.setRatingInfo(ratingInfo);

        ProductRecommendation.Rating rating = mapper.toProducts(List.of(record)).get(0).getRating();

        assertThat(rating.getAverage()).isEqualTo("4.5");
        assertThat(rating.getCount()).isEqualTo("120");
    }

    @Test
    void rating_missing_defaultsToZeroStrings() {
        RecordsResponse record = record("sku-1", "P");

        ProductRecommendation.Rating rating = mapper.toProducts(List.of(record)).get(0).getRating();

        assertThat(rating.getAverage()).isEqualTo("0");
        assertThat(rating.getCount()).isEqualTo("0");
    }

    // ── feature flags ─────────────────────────────────────────────────────────────────────────────

    @Test
    void featureFlags_marketplaceAndCollection_areMapped() {
        FeatureFlags flags = new FeatureFlags();
        flags.setIsMarketPlace(true);
        flags.setIsCollection(true);
        RecordsResponse record = record("sku-1", "P");
        record.setFeatureFlags(flags);

        ProductRecommendation product = mapper.toProducts(List.of(record)).get(0);

        assertThat(product.isMarketplace()).isTrue();
        assertThat(product.getIsCollectionProduct()).isTrue();
    }

    @Test
    void featureFlags_missing_defaultToFalse() {
        RecordsResponse record = record("sku-1", "P");

        ProductRecommendation product = mapper.toProducts(List.of(record)).get(0);

        assertThat(product.isMarketplace()).isFalse();
        assertThat(product.getIsCollectionProduct()).isFalse();
    }

    private static RecordsResponse record(String productId, String title) {
        RecordsResponse record = new RecordsResponse();
        record.setProductId(productId);
        record.setTitle(title);
        return record;
    }

    private static ProductRecommendation.Category category(String id, String label) {
        ProductRecommendation.Category category = new ProductRecommendation.Category();
        category.setId(id);
        category.setLabel(label);
        return category;
    }
}
