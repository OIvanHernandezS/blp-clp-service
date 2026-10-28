package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.salesforcev2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.liverpool.constant.SalesforceV2Constants;
import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.salesforce.v2.SalesforceV2Product;

/**
 * Maps the products a Salesforce Personalization (V2) decision returned to the carousel response
 * contract.
 *
 * <p>A port of the mapping in commons' {@code ProductsListSalesforceV2Strategy} — whose helpers are
 * private — so a V2 carousel renders the same product card on a landing page as on PDP and PLP. Every
 * literal comes from {@link SalesforceV2Constants}. Two differences from the strategy, both because
 * ms-clp-blp owns them centrally: {@code max_of_products} truncation and the minimum-size drop happen in
 * {@code ProductsListUseCases}, and there is no restricted-product filtering, as for every other
 * provider here.</p>
 *
 * <p>{@code index} is assigned after unidentifiable products are skipped, so the sequence has no gaps —
 * the same numbering the strategy produces.</p>
 */
public class SalesforceV2ProductMapper {

    /**
     * Maps a decision's products to the product list for a carousel.
     *
     * @param products          the decided products, in Salesforce's order; may be {@code null}
     * @param personalizationId the id of the personalization they were decided in, published on every
     *                          product so the frontend can report impressions against it
     * @return the mapped products, with those carrying no id skipped; never {@code null}
     */
    public List<ProductRecommendation> toProducts(List<SalesforceV2Product> products, String personalizationId) {
        if (products == null || products.isEmpty()) {
            return List.of();
        }
        List<ProductRecommendation> mapped = new ArrayList<>(products.size());
        for (SalesforceV2Product product : products) {
            String productId = product == null ? null : productIdOf(product);
            if (productId != null) {
                mapped.add(toProduct(product, productId, personalizationId, mapped.size()));
            }
        }
        return List.copyOf(mapped);
    }

    private ProductRecommendation toProduct(SalesforceV2Product product, String productId,
                                            String personalizationId, int index) {
        ProductRecommendation dto = new ProductRecommendation();
        dto.setIndex(index);
        dto.setProductId(productId);
        dto.setName(product.getName());
        dto.setBrand(product.getBrandId());
        dto.setPriceInfo(priceInfo(product));
        dto.setDiscountLabel(discountLabel(product));
        dto.setMainCategory(orEmpty(product.getSectionDescription()));
        dto.setCategories(categories(product.getDigitalCategories()));
        dto.setImages(images(product.getImageUrl()));
        dto.setRating(defaultRating());
        dto.setSeller(SalesforceV2Constants.EMPTY_VALUE);
        dto.setSellerCode(SalesforceV2Constants.EMPTY_VALUE);
        dto.setHasGift(false);
        dto.setMarketplace(false);
        dto.setVariants(List.of());
        dto.setPersonalization(personalization(personalizationId, product.getPersonalizationContentId()));
        return dto;
    }

    /** @return the master product id, falling back to the SKU and then the record id; {@code null} when all are blank */
    private static String productIdOf(SalesforceV2Product product) {
        if (!isBlank(product.getMasterProductId())) {
            return product.getMasterProductId();
        }
        if (!isBlank(product.getProductSku())) {
            return product.getProductSku();
        }
        return isBlank(product.getId()) ? null : product.getId();
    }

    /** {@code price} is the promo price, as in the strategy; prices Salesforce omits stay {@code null}. */
    private static ProductRecommendation.PriceInfoDto priceInfo(SalesforceV2Product product) {
        ProductRecommendation.PriceInfoDto price = new ProductRecommendation.PriceInfoDto();
        price.setOriginalPrice(parseDouble(product.getVariantListPrice()));
        price.setPromoPrice(parseDouble(product.getVariantPromoPrice()));
        price.setPrice(parseDouble(product.getVariantPromoPrice()));
        price.setMinimumListPrice(parseDouble(product.getMinListPrice()));
        price.setMaximumListPrice(parseDouble(product.getMaxListPrice()));
        price.setMinimumPromoPrice(parseDouble(product.getMinPromoPrice()));
        price.setMaximumPromoPrice(parseDouble(product.getMaxPromoPrice()));
        return price;
    }

    /** @return a label such as {@code -20%}, or {@code null} when there is no effective discount */
    private static String discountLabel(SalesforceV2Product product) {
        Double listPrice = parseDouble(product.getVariantListPrice());
        Double promoPrice = parseDouble(product.getVariantPromoPrice());
        if (listPrice == null || promoPrice == null || listPrice <= 0 || promoPrice >= listPrice) {
            return null;
        }
        int discount = (int) Math.round((listPrice - promoPrice) * 100 / listPrice);
        return discount > 0 ? SalesforceV2Constants.DISCOUNT_LABEL_FORMAT.formatted(discount) : null;
    }

    /**
     * Splits the pipe-separated category ids into distinct categories, in order. Salesforce exposes no
     * category labels, so each id is its own label.
     */
    private static List<ProductRecommendation.Category> categories(String digitalCategories) {
        if (isBlank(digitalCategories)) {
            return new ArrayList<>();
        }
        Map<String, ProductRecommendation.Category> byId = new LinkedHashMap<>();
        for (String raw : digitalCategories.split(SalesforceV2Constants.CATEGORY_SEPARATOR)) {
            String id = raw.trim();
            if (!id.isEmpty()) {
                byId.putIfAbsent(id, category(id));
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static ProductRecommendation.Category category(String id) {
        ProductRecommendation.Category category = new ProductRecommendation.Category();
        category.setId(id);
        category.setLabel(id);
        return category;
    }

    /** Salesforce returns a single thumbnail per product; a missing one is published as an empty url. */
    private static List<ProductRecommendation.ProductImage> images(String url) {
        ProductRecommendation.ProductImage image = new ProductRecommendation.ProductImage();
        image.setType(SalesforceV2Constants.IMAGE_TYPE_THUMBNAIL);
        image.setUrl(url != null ? url : SalesforceV2Constants.EMPTY_VALUE);
        return List.of(image);
    }

    /** Salesforce V2 exposes no rating data, so every product reports a zeroed rating. */
    private static ProductRecommendation.Rating defaultRating() {
        ProductRecommendation.Rating rating = new ProductRecommendation.Rating();
        rating.setAverage(SalesforceV2Constants.DEFAULT_RATING);
        rating.setCount(SalesforceV2Constants.DEFAULT_RATING);
        return rating;
    }

    private static ProductRecommendation.Personalization personalization(String id, String contentId) {
        ProductRecommendation.Personalization personalization = new ProductRecommendation.Personalization();
        personalization.setId(id);
        personalization.setContentId(contentId);
        return personalization;
    }

    /** @return the parsed number, or {@code null} when it is missing or not a number */
    private static Double parseDouble(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String orEmpty(String value) {
        return value == null ? SalesforceV2Constants.EMPTY_VALUE : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
