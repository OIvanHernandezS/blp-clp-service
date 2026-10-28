package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.jewel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.jewel.JewelProduct;
import com.liverpool.dto.jewel.JewelStandardFeatures;
import com.liverpool.ms_clp_blp.shared.web.LegacyNumberFormat;

/**
 * Maps a JewelML product to the response contract digital_bff publishes (Rule 19 reference:
 * {@code ProductDto.fromJewel} in {@code libs/providers/src/dto/product/product.dto.ts}).
 *
 * <p>Everything here already exists on {@link JewelStandardFeatures}; it was simply discarded before,
 * leaving the frontend with {@code {sku, title, price}} and no way to render a product card.</p>
 *
 * <p>Two legacy behaviours are reproduced deliberately rather than corrected, because the frontend
 * consumes this contract as-is:</p>
 * <ul>
 *   <li><strong>{@code index} can have gaps.</strong> The legacy code assigns the index while mapping
 *       and only afterwards discards products that yielded no id, so a dropped product leaves a hole
 *       in the sequence. Numbering after the filter would be tidier and would not match.</li>
 *   <li><strong>{@code priceInfo.price} is the promo price</strong> here, whereas the Salesforce
 *       mapping sets it to the list price. The field means different things per source.</li>
 * </ul>
 */
public class JewelProductMapper {

    private static final String IMAGE_TYPE = "thumbnailImage";
    private static final String OFFER_ID_SEPARATOR = "_";
    private static final String SELLER_SEPARATOR = "\\|";
    private static final String BREADCRUMB_SEPARATOR = ">";
    private static final String BREADCRUMB_ID_LABEL_SEPARATOR = "#";
    private static final String BREADCRUMB_LABEL_SUFFIX_SEPARATOR = "\\|";
    private static final String ZERO = "0";

    /**
     * Maps a Jewel response to the product list for a carousel.
     *
     * @param products             the raw Jewel products, in response order
     * @param includeItemVariants  whether the {@code jewel_carousel_item_variants} flag is enabled;
     *                             when false every product reports an empty {@code variants} list
     * @return the mapped products, with unidentifiable ones dropped (leaving index gaps, as above)
     */
    public List<ProductRecommendation> toProducts(List<JewelProduct> products, boolean includeItemVariants) {
        if (products == null || products.isEmpty()) {
            return List.of();
        }
        List<ProductRecommendation> mapped = new ArrayList<>(products.size());
        for (int index = 0; index < products.size(); index++) {
            // The index is bound before the drop below — that is what produces the legacy gaps.
            toProduct(products.get(index), index, includeItemVariants).ifPresent(mapped::add);
        }
        return List.copyOf(mapped);
    }

    private Optional<ProductRecommendation> toProduct(JewelProduct source, int index, boolean includeItemVariants) {
        if (source == null) {
            return Optional.empty();
        }
        JewelStandardFeatures features = source.getStandardFeatures();
        String productId = productId(features);
        if (productId == null || productId.isBlank()) {
            return Optional.empty();
        }

        ProductRecommendation product = new ProductRecommendation();
        product.setIndex(index);
        product.setProductId(productId);
        product.setDiscountLabel(discountLabel(features));
        product.setName(source.getTitle() == null ? "" : source.getTitle());
        product.setPriceInfo(priceInfo(features));
        product.setMainCategory(mainCategory(features));
        product.setCategories(categories(features));
        product.setImages(images(features));
        product.setBrand(features == null || features.getBrand() == null ? "" : features.getBrand());
        product.setSeller(firstSeller(features));
        product.setSellerCode("");
        product.setRating(rating(features));
        product.setHasGift(false);
        product.setVariants(variants(features, includeItemVariants));
        product.setMarketplace(features != null && Boolean.TRUE.equals(features.getIsMarketPlace()));
        return Optional.of(product);
    }

    /** {@code offer_id} up to the first underscore, else {@code event_id}; null drops the product. */
    private String productId(JewelStandardFeatures features) {
        if (features == null) {
            return null;
        }
        String offerId = features.getOfferId();
        if (offerId != null && !offerId.isBlank()) {
            return offerId.split(OFFER_ID_SEPARATOR)[0];
        }
        return features.getEventId();
    }

    /** {@code "-60%"}; omitted entirely when there is no discount. */
    private String discountLabel(JewelStandardFeatures features) {
        if (features == null || features.getDiscountAmount() <= 0) {
            return null;
        }
        return "-" + LegacyNumberFormat.toPlainString(features.getDiscountAmount()) + "%";
    }

    private ProductRecommendation.PriceInfoDto priceInfo(JewelStandardFeatures features) {
        ProductRecommendation.PriceInfoDto priceInfo = new ProductRecommendation.PriceInfoDto();
        if (features == null) {
            return priceInfo;
        }
        priceInfo.setMinimumListPrice(features.getMinListPrice());
        priceInfo.setMaximumListPrice(features.getMaxListPrice());
        priceInfo.setMinimumPromoPrice(features.getMinPromoPrice());
        priceInfo.setMaximumPromoPrice(features.getMaxPromoPrice());
        priceInfo.setOriginalPrice(features.getPrice());
        priceInfo.setPromoPrice(features.getSalePrice());
        // Legacy: `price` is the PROMO price for Jewel (the Salesforce mapping uses the list price).
        priceInfo.setPrice(features.getSalePrice());
        return priceInfo;
    }

    /**
     * The product's leaf category label, derived by locating the last id from {@code itemType} inside
     * {@code categoryBreadCrumbs} and taking the label that follows it (Rule 19 reference:
     * {@code ProductsListJewelStrategy.parseMainCategory} in {@code platform-commons}).
     *
     * <p>Empty when either field is missing or the id cannot be located — the breadcrumb format is not
     * guaranteed, and a blank main category is the neutral result rather than a failure.</p>
     */
    private String mainCategory(JewelStandardFeatures features) {
        if (features == null || isBlank(features.getItemType())
                || isBlank(features.getCategoryBreadCrumbs())) {
            return "";
        }
        String[] categoryIds = features.getItemType().split(BREADCRUMB_SEPARATOR);
        String leafId = categoryIds[categoryIds.length - 1].trim();
        String[] afterLeaf = features.getCategoryBreadCrumbs()
                .split(leafId.concat(BREADCRUMB_ID_LABEL_SEPARATOR));
        if (afterLeaf.length < 2) {
            return "";
        }
        String[] labelAndBrand = afterLeaf[1].split(BREADCRUMB_LABEL_SUFFIX_SEPARATOR);
        return labelAndBrand.length < 2 ? "" : labelAndBrand[0];
    }

    /**
     * {@code categoryBreadCrumbs} is {@code "id#label|extra>id#label|extra"}: split on {@code >}, then
     * {@code #}, then keep the label up to the first {@code |}.
     */
    private List<ProductRecommendation.Category> categories(JewelStandardFeatures features) {
        if (features == null || features.getCategoryBreadCrumbs() == null
                || features.getCategoryBreadCrumbs().isBlank()) {
            return List.of();
        }
        List<ProductRecommendation.Category> categories = new ArrayList<>();
        for (String crumb : features.getCategoryBreadCrumbs().split(BREADCRUMB_SEPARATOR)) {
            String[] parts = crumb.split(BREADCRUMB_ID_LABEL_SEPARATOR);
            ProductRecommendation.Category category = new ProductRecommendation.Category();
            category.setId(parts[0]);
            category.setLabel(parts.length > 1 ? parts[1].split(BREADCRUMB_LABEL_SUFFIX_SEPARATOR)[0] : null);
            categories.add(category);
        }
        return categories;
    }

    /** Always exactly one image; Jewel's multi-image {@code item_images} is unused by the legacy shape. */
    private List<ProductRecommendation.ProductImage> images(JewelStandardFeatures features) {
        ProductRecommendation.ProductImage image = new ProductRecommendation.ProductImage();
        image.setType(IMAGE_TYPE);
        image.setUrl(features == null || features.getImageUrlSrc() == null ? "" : features.getImageUrlSrc());
        return List.of(image);
    }

    /** {@code seller_names} is pipe-separated; only the first is published. */
    private String firstSeller(JewelStandardFeatures features) {
        if (features == null || features.getSellerNames() == null || features.getSellerNames().isBlank()) {
            return "";
        }
        return features.getSellerNames().split(SELLER_SEPARATOR)[0];
    }

    /** Both values are strings on the wire, so they need JS-style number rendering. */
    private ProductRecommendation.Rating rating(JewelStandardFeatures features) {
        ProductRecommendation.Rating rating = new ProductRecommendation.Rating();
        rating.setAverage(features == null ? ZERO : LegacyNumberFormat.toPlainString(features.getRating()));
        rating.setCount(features == null ? ZERO : String.valueOf(features.getRatingCount()));
        return rating;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Raw passthrough of Jewel's nested variant objects — the legacy shape does not reshape them. */
    private Object variants(JewelStandardFeatures features, boolean includeItemVariants) {
        if (!includeItemVariants || features == null || features.getItemVariants() == null) {
            return List.of();
        }
        return features.getItemVariants();
    }
}
