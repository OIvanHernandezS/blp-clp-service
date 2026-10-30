package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.vertex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.groupby.ColorInfoDTO;
import com.liverpool.dto.groupby.FeatureFlags;
import com.liverpool.dto.groupby.GalleryContentDto;
import com.liverpool.dto.groupby.MinMaxPricesDTO;
import com.liverpool.dto.groupby.PriceInfoGBDto;
import com.liverpool.dto.groupby.RatingInfo;
import com.liverpool.dto.plp.RecordsResponse;
import com.liverpool.ms_clp_blp.shared.web.LegacyNumberFormat;

/**
 * Maps an {@code ms_plp_search} Vertex AI record to the response contract digital_bff publishes
 * (Rule 19 reference: {@code ProductDto.fromVertex} in
 * {@code libs/providers/src/dto/product/product.dto.ts}).
 *
 * <p>{@code RecordsResponse} (platform-commons) is the same wire shape digital_bff's legacy
 * {@code VertexRecord} described — verified 100% contract-compatible in {@code ms_plp_search}'s own
 * architecture docs — so this mapper reproduces the legacy field derivations directly rather than
 * introducing new ones:</p>
 * <ul>
 *   <li><strong>{@code priceInfo.price}/{@code promoPrice}</strong> are the resolved promo price
 *       ({@code promoPrice.price}, falling back to {@code promoPrice.maxPrice}, then {@code 0}) — a
 *       third convention distinct from both Jewel (promo price only) and Salesforce (list price
 *       only).</li>
 *   <li><strong>{@code categories}</strong> is the record's {@code carrouselCategories} ({@code id} +
 *       {@code label}, as digital_bff publishes since its "vertex cats" fix). Only when the record
 *       carries none does it fall back to the first breadcrumb in {@code categories[0]}, split on
 *       {@code >} then {@code #} (a crumb with no {@code #} publishes as label with a {@code null} id).</li>
 *   <li><strong>{@code mainCategory}</strong> is the record's {@code mainCategory}, {@code ""} when
 *       absent; {@code flags} is the record's {@code featureFlags}, {@code {}} when absent. Both are
 *       carried by {@link VertexProductRecommendation}, which publishes them for Vertex only.</li>
 *   <li><strong>{@code images}</strong> comes from the first color variant's gallery content, not a
 *       dedicated image list — Vertex has no top-level image field. The first one is
 *       {@code largeImage}, the rest {@code thumbnailImage}.</li>
 *   <li><strong>{@code variants}</strong> is always empty — legacy's {@code fromVertex} never
 *       populates it (a placeholder), unlike Jewel's {@code item_variants}.</li>
 * </ul>
 */
public class VertexProductMapper {

    private static final String FIRST_IMAGE_TYPE = "largeImage";
    private static final String IMAGE_TYPE = "thumbnailImage";
    private static final String BREADCRUMB_SEPARATOR = ">";
    private static final String BREADCRUMB_ID_LABEL_SEPARATOR = "#";
    private static final String ZERO = "0";

    /**
     * Maps a Vertex AI search response to the product list for a carousel.
     *
     * @param records the raw records, in response order
     * @return the mapped products, indexed contiguously from 0
     */
    public List<ProductRecommendation> toProducts(List<RecordsResponse> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<ProductRecommendation> mapped = new ArrayList<>(records.size());
        for (int index = 0; index < records.size(); index++) {
            RecordsResponse record = records.get(index);
            if (record != null) {
                mapped.add(toProduct(record, index));
            }
        }
        return List.copyOf(mapped);
    }

    private ProductRecommendation toProduct(RecordsResponse source, int index) {
        FeatureFlags flags = source.getFeatureFlags();

        VertexProductRecommendation product = new VertexProductRecommendation();
        product.setIndex(index);
        product.setProductId(source.getProductId() == null ? "" : source.getProductId());
        product.setName(source.getTitle() == null ? "" : source.getTitle());
        product.setPriceInfo(priceInfo(source.getPriceInfo()));
        product.setCategories(categories(source));
        product.setImages(images(source));
        product.setBrand(source.getBrand() == null ? "" : source.getBrand());
        product.setSeller(seller(source));
        product.setSellerCode("");
        product.setRating(rating(source.getRatingInfo()));
        product.setHasGift(false);
        product.setVariants(List.of());
        product.setMarketplace(flags != null && Boolean.TRUE.equals(flags.getIsMarketPlace()));
        product.setIsCollectionProduct(flags != null && Boolean.TRUE.equals(flags.getIsCollection()));
        product.setMainCategory(source.getMainCategory() == null ? "" : source.getMainCategory());
        product.setFlags(flags(flags));
        return product;
    }

    /**
     * {@code price}/{@code promoPrice} both resolve to the current selling price
     * ({@code promoPrice.price}, then {@code promoPrice.maxPrice}, then {@code 0}) — legacy's
     * {@code fromVertex} defaults every price field to {@code 0} rather than leaving it absent.
     */
    private ProductRecommendation.PriceInfoDto priceInfo(PriceInfoGBDto source) {
        MinMaxPricesDTO listPrice = source == null ? null : source.getListPrice();
        MinMaxPricesDTO promoPrice = source == null ? null : source.getPromoPrice();
        Double currentPrice = firstNonNull(price(promoPrice, MinMaxPricesDTO::getPrice),
                price(promoPrice, MinMaxPricesDTO::getMaxPrice), 0d);

        ProductRecommendation.PriceInfoDto priceInfo = new ProductRecommendation.PriceInfoDto();
        priceInfo.setMinimumListPrice(firstNonNull(
                price(listPrice, MinMaxPricesDTO::getMinPrice), price(listPrice, MinMaxPricesDTO::getPrice), 0d));
        priceInfo.setMaximumListPrice(firstNonNull(
                price(listPrice, MinMaxPricesDTO::getMaxPrice), price(listPrice, MinMaxPricesDTO::getPrice), 0d));
        priceInfo.setMinimumPromoPrice(firstNonNull(
                price(promoPrice, MinMaxPricesDTO::getMinPrice), price(promoPrice, MinMaxPricesDTO::getPrice), 0d));
        priceInfo.setMaximumPromoPrice(firstNonNull(
                price(promoPrice, MinMaxPricesDTO::getMaxPrice), price(promoPrice, MinMaxPricesDTO::getPrice), 0d));
        priceInfo.setOriginalPrice(firstNonNull(
                price(listPrice, MinMaxPricesDTO::getPrice), price(listPrice, MinMaxPricesDTO::getMaxPrice), 0d));
        priceInfo.setPromoPrice(currentPrice);
        priceInfo.setPrice(currentPrice);
        return priceInfo;
    }

    /**
     * {@code categories[0]} is {@code "id#label>id#label"}: split on {@code >}, then {@code #}. A crumb
     * with no {@code #} publishes as {@code {label: crumb, id: null}} (legacy parity).
     */
    private List<ProductRecommendation.Category> categories(RecordsResponse source) {
        List<ProductRecommendation.Category> carrousel = source.getCarrouselCategories();
        if (carrousel != null && !carrousel.isEmpty()) {
            return List.copyOf(carrousel.stream().filter(Objects::nonNull).toList());
        }
        List<String> categories = source.getCategories();
        if (categories == null || categories.isEmpty() || categories.get(0) == null
                || categories.get(0).isBlank()) {
            return List.of();
        }
        List<ProductRecommendation.Category> mapped = new ArrayList<>();
        for (String crumb : categories.get(0).split(BREADCRUMB_SEPARATOR)) {
            String[] parts = crumb.trim().split(BREADCRUMB_ID_LABEL_SEPARATOR);
            ProductRecommendation.Category category = new ProductRecommendation.Category();
            if (parts.length > 1) {
                category.setId(parts[0]);
                category.setLabel(parts[1]);
            } else {
                category.setLabel(parts[0]);
            }
            mapped.add(category);
        }
        return mapped;
    }

    /** The first color variant's gallery content; Vertex carries no dedicated image list. */
    private List<ProductRecommendation.ProductImage> images(RecordsResponse source) {
        List<ColorInfoDTO> variants = source.getVariantsColor();
        if (variants == null || variants.isEmpty() || variants.get(0).getGalleryContent() == null) {
            return List.of();
        }
        List<ProductRecommendation.ProductImage> images = new ArrayList<>();
        for (GalleryContentDto content : variants.get(0).getGalleryContent()) {
            ProductRecommendation.ProductImage image = new ProductRecommendation.ProductImage();
            image.setType(images.isEmpty() ? FIRST_IMAGE_TYPE : IMAGE_TYPE);
            image.setUrl(content.getUrl() == null ? "" : content.getUrl());
            images.add(image);
        }
        return images;
    }

    /**
     * The record's {@code featureFlags} as published by digital_bff: every flag the upstream sent,
     * in declaration order; absent ones are omitted, and no flags at all is an empty map.
     */
    private Map<String, Boolean> flags(FeatureFlags source) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        if (source == null) {
            return flags;
        }
        putIfPresent(flags, "isMarketPlace", source.getIsMarketPlace());
        putIfPresent(flags, "isSponsoredRecord", source.getIsSponsoredRecord());
        putIfPresent(flags, "isAr", source.getIsAr());
        putIfPresent(flags, "isCollection", source.getIsCollection());
        putIfPresent(flags, "exceedVariantsLimit", source.getExceedVariantsLimit());
        putIfPresent(flags, "isChanel", source.getIsChanel());
        putIfPresent(flags, "isHybrid", source.getIsHybrid());
        putIfPresent(flags, "isCrossBorder", source.getIsCrossBorder());
        return flags;
    }

    private static void putIfPresent(Map<String, Boolean> flags, String name, Boolean value) {
        if (value != null) {
            flags.put(name, value);
        }
    }

    /** {@code seller[0]}, falling back to {@code sellerName}. */
    private String seller(RecordsResponse source) {
        if (source.getSeller() != null && !source.getSeller().isEmpty()) {
            return source.getSeller().get(0);
        }
        return source.getSellerName();
    }

    private ProductRecommendation.Rating rating(RatingInfo source) {
        ProductRecommendation.Rating rating = new ProductRecommendation.Rating();
        Double average = source == null ? null : source.getProductAvgRating();
        Integer count = source == null ? null : source.getProductRatingCount();
        rating.setAverage(average == null ? ZERO : LegacyNumberFormat.toPlainString(average));
        rating.setCount(count == null ? ZERO : String.valueOf(count));
        return rating;
    }

    private Double price(MinMaxPricesDTO source, java.util.function.Function<MinMaxPricesDTO, Double> getter) {
        return source == null ? null : getter.apply(source);
    }

    private Double firstNonNull(Double first, Double second, Double fallback) {
        if (first != null) {
            return first;
        }
        return second != null ? second : fallback;
    }
}
