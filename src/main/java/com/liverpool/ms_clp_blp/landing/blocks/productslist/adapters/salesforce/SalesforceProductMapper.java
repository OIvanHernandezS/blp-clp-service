package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.salesforce;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.liverpool.constant.SalesforceCarrouselsConstants;
import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.salesforce.PriceDetail;
import com.liverpool.dto.salesforce.SalesforceAttributes;
import com.liverpool.dto.salesforce.SalesforceAttributesDetail;
import com.liverpool.dto.salesforce.SalesforceDimensions;
import com.liverpool.dto.salesforce.SalesforcePayload;
import com.liverpool.dto.salesforce.SalesforcePriceInfo;
import com.liverpool.dto.salesforce.SalesforceProductDataMapped;
import com.liverpool.dto.salesforce.SalesforceProductToShow;

/**
 * Maps Salesforce Evergage products to the response contract digital_bff publishes (Rule 19
 * reference: {@code ProductDto.fromSalesforceMapped} and {@code ProductDto.fromSalesfroce} — the
 * legacy typo is theirs).
 *
 * <h3>Three shapes, not two</h3>
 * <p>Salesforce returns carousel products in three shapes depending on how the campaign is
 * configured, and this class tries them in the order commons' {@code SalesforceCarrouselsApiCaller}
 * does:</p>
 * <ol>
 *   <li>{@code payload.productDataMapped} — already flattened, prices as formatted strings.</li>
 *   <li>{@code payload.productToShow} — raw catalog items, everything wrapped in {@code {value: …}}.</li>
 *   <li>{@code payload.products} — the same raw shape under a second key.</li>
 * </ol>
 * <p>Reading only the first and third is what left {@code productToShow}-configured campaigns
 * resolving to zero products, which drops the whole carousel. Selection is now per payload rather than
 * per record: with the typed DTOs the three shapes are distinct Java types, so the old
 * {@code containsKey("productId")} test per record — an artifact of untyped maps — has no meaning. A
 * payload never mixes shapes, so the observable result is unchanged.</p>
 *
 * <h3>Legacy behaviours reproduced deliberately</h3>
 * <p>The frontend consumes this contract as-is, so these stay exactly as digital_bff emits them —
 * commons maps several of them differently and that difference is intentional here:</p>
 * <ul>
 *   <li><strong>{@code priceInfo.price} is the LIST price</strong> on the mapped path, whereas the
 *       Jewel mapping sets it to the promo price. The field means different things per source.</li>
 *   <li><strong>{@code rating} is hardcoded</strong> to {@code {average:"0", count:"0"}} on the mapped
 *       path — Salesforce sends no rating there, and the legacy code invents zeros rather than
 *       omitting the object.</li>
 *   <li><strong>{@code sellerCode} duplicates {@code seller}</strong> on the mapped path (Jewel uses
 *       {@code ""}). Almost certainly a legacy bug, kept for byte compatibility.</li>
 *   <li><strong>{@code categories[].id} is the label text</strong> on the mapped path — Salesforce
 *       categories arrive as {@code {"lvl0": "Mujer"}} maps with no ids at all.</li>
 *   <li><strong>{@code images[].type} is {@code largeImage}</strong>, where Jewel uses
 *       {@code thumbnailImage}.</li>
 *   <li>{@code discountLabel}, {@code mainCategory} and populated {@code variants} are Jewel-only and
 *       never appear here.</li>
 *   <li>On the raw path {@code seller}/{@code sellerCode} stay unset and the minimum prices stay
 *       absent, even though Salesforce supplies {@code sellerNames}.</li>
 * </ul>
 *
 * <h3>The one raw-path correction</h3>
 * <p>Raw categories are read from the product's own {@code categories} — where Salesforce actually
 * puts them — instead of from {@code attributes.categories}, which does not exist in the payload and
 * therefore made this list unconditionally empty. This adds data to a field that was always {@code []}
 * rather than changing any value the frontend already receives; the legacy id-only shape (no label) is
 * preserved.</p>
 */
public class SalesforceProductMapper {

    private static final String IMAGE_TYPE = "largeImage";
    private static final String ZERO = "0";

    /**
     * Maps one campaign payload's products, choosing whichever of the three shapes it carries.
     *
     * @param payload the campaign payload; may be {@code null}
     * @return the mapped products indexed contiguously from 0, empty when the payload carries none
     */
    public List<ProductRecommendation> toProducts(SalesforcePayload payload) {
        if (payload == null) {
            return List.of();
        }
        List<ProductRecommendation> mapped = fromMappedProducts(payload.getProductDataMapped());
        if (!mapped.isEmpty()) {
            return mapped;
        }
        List<SalesforceProductToShow> raw = payload.getProductToShow() != null
                ? payload.getProductToShow()
                : payload.getProducts();
        return fromRawProducts(raw);
    }

    // ── productDataMapped ─────────────────────────────────────────────────────────────────────────

    private List<ProductRecommendation> fromMappedProducts(List<SalesforceProductDataMapped> products) {
        if (products == null || products.isEmpty()) {
            return List.of();
        }
        List<ProductRecommendation> mapped = new ArrayList<>(products.size());
        for (SalesforceProductDataMapped product : products) {
            if (product != null) {
                mapped.add(fromMapped(product, mapped.size()));
            }
        }
        return List.copyOf(mapped);
    }

    private ProductRecommendation fromMapped(SalesforceProductDataMapped raw, int index) {
        ProductRecommendation product = base(index);
        product.setProductId(raw.getProductId());
        product.setName(raw.getTitle());

        SalesforcePriceInfo priceInfo = raw.getPriceInfo();
        PriceDetail listPrice = listPriceOf(priceInfo);
        PriceDetail promoPrice = promoPriceOf(priceInfo);

        ProductRecommendation.PriceInfoDto prices = new ProductRecommendation.PriceInfoDto();
        prices.setMinimumListPrice(price(listPrice.getMinPrice()));
        prices.setMaximumListPrice(price(listPrice.getMaxPrice()));
        prices.setMinimumPromoPrice(price(promoPrice.getMinPrice()));
        prices.setMaximumPromoPrice(price(promoPrice.getMaxPrice()));
        prices.setOriginalPrice(price(listPrice.getPrice()));
        prices.setPromoPrice(price(promoPrice.getPrice()));
        // Legacy: the LIST price, unlike Jewel where `price` is the promo price.
        prices.setPrice(price(listPrice.getPrice()));
        product.setPriceInfo(prices);

        product.setCategories(mappedCategories(raw.getCategories()));
        product.setImages(images(raw.getImage()));
        product.setBrand(raw.getBrand());

        String sellers = raw.getSellernames();
        product.setSeller(sellers);
        // Legacy duplicates the seller name into sellerCode on this path only.
        product.setSellerCode(sellers == null ? SalesforceCarrouselsConstants.EMPTY_VALUE : sellers);

        product.setRating(rating(null));
        return product;
    }

    /** Categories arrive as {@code [{"lvl0": "Mujer"}]} — no ids exist, so the label doubles as one. */
    private List<ProductRecommendation.Category> mappedCategories(List<Map<String, String>> raw) {
        if (raw == null) {
            return List.of();
        }
        List<ProductRecommendation.Category> categories = new ArrayList<>();
        for (Map<String, String> entry : raw) {
            if (entry == null) {
                continue;
            }
            for (String value : entry.values()) {
                categories.add(category(value, value));
            }
        }
        return categories;
    }

    // ── productToShow / products ──────────────────────────────────────────────────────────────────

    private List<ProductRecommendation> fromRawProducts(List<SalesforceProductToShow> products) {
        if (products == null || products.isEmpty()) {
            return List.of();
        }
        List<ProductRecommendation> mapped = new ArrayList<>(products.size());
        for (SalesforceProductToShow product : products) {
            if (product != null) {
                mapped.add(fromRaw(product, mapped.size()));
            }
        }
        return List.copyOf(mapped);
    }

    /**
     * Maps one raw catalog item.
     *
     * <p>Salesforce inverts the price attribute names on this shape: {@code price} holds the list price
     * and {@code listPrice} holds the discounted one, as its own {@code maxListPrice} and
     * {@code maxPromoPrice} attributes confirm. Reading them at face value inverts every discount.</p>
     */
    private ProductRecommendation fromRaw(SalesforceProductToShow raw, int index) {
        ProductRecommendation product = base(index);
        product.setProductId(raw.getId());

        SalesforceAttributes attributes = raw.getAttributes();
        product.setName(attributeValue(attributes == null ? null : attributes.getName()));

        ProductRecommendation.PriceInfoDto prices = new ProductRecommendation.PriceInfoDto();
        if (attributes != null) {
            // Legacy sends no minimums on this path at all — they stay absent, not zero.
            prices.setMaximumListPrice(price(attributeValue(attributes.getMaxListPrice()), 0d));
            prices.setMaximumPromoPrice(price(attributeValue(attributes.getMaxPromoPrice()), 0d));
            prices.setOriginalPrice(price(attributeValue(attributes.getPrice())));
            prices.setPromoPrice(price(attributeValue(attributes.getListPrice())));
            prices.setPrice(price(attributeValue(attributes.getListPrice())));
        }
        product.setPriceInfo(prices);

        product.setCategories(rawCategories(raw.getCategoryNodes(), raw.getCategories()));
        product.setImages(images(attributeValue(attributes == null ? null : attributes.getImageUrl())));
        product.setBrand(firstBrand(raw.getDimensions()));
        // Legacy leaves seller/sellerCode unset on this path even though sellerNames is available.
        product.setRating(rating(attributeValue(attributes == null ? null : attributes.getRating())));
        return product;
    }

    /**
     * Prefers the {@code categoryNodes} Salesforce resolves (id and label); falls back to the bare id
     * strings in {@code categories}, for which the legacy mapping never resolves a label.
     */
    private List<ProductRecommendation.Category> rawCategories(
            List<ProductRecommendation.Category> nodes, List<String> ids) {
        if (nodes != null && !nodes.isEmpty()) {
            return nodes;
        }
        if (ids == null) {
            return List.of();
        }
        List<ProductRecommendation.Category> categories = new ArrayList<>();
        for (String id : ids) {
            categories.add(category(id, null));
        }
        return categories;
    }

    private String firstBrand(SalesforceDimensions dimensions) {
        if (dimensions == null || dimensions.getBrand() == null || dimensions.getBrand().isEmpty()) {
            return SalesforceCarrouselsConstants.EMPTY_VALUE;
        }
        return dimensions.getBrand().get(0);
    }

    // ── shared ────────────────────────────────────────────────────────────────────────────────────

    private ProductRecommendation base(int index) {
        ProductRecommendation product = new ProductRecommendation();
        product.setIndex(index);
        product.setHasGift(false);
        product.setMarketplace(false);
        product.setVariants(List.of());
        return product;
    }

    private ProductRecommendation.Category category(String id, String label) {
        ProductRecommendation.Category category = new ProductRecommendation.Category();
        category.setId(id);
        category.setLabel(label);
        return category;
    }

    private List<ProductRecommendation.ProductImage> images(String url) {
        ProductRecommendation.ProductImage image = new ProductRecommendation.ProductImage();
        image.setType(IMAGE_TYPE);
        image.setUrl(url == null ? SalesforceCarrouselsConstants.EMPTY_VALUE : url);
        return List.of(image);
    }

    /**
     * @param average the reported average, or {@code null} on the mapped path where Salesforce sends
     *                none; either way {@code count} is the legacy hardcoded zero
     */
    private ProductRecommendation.Rating rating(String average) {
        ProductRecommendation.Rating rating = new ProductRecommendation.Rating();
        rating.setAverage(isBlank(average) ? ZERO : average);
        rating.setCount(ZERO);
        return rating;
    }

    private PriceDetail listPriceOf(SalesforcePriceInfo priceInfo) {
        return priceInfo != null && priceInfo.getListPrice() != null
                ? priceInfo.getListPrice() : new PriceDetail();
    }

    private PriceDetail promoPriceOf(SalesforcePriceInfo priceInfo) {
        return priceInfo != null && priceInfo.getPromoPrice() != null
                ? priceInfo.getPromoPrice() : new PriceDetail();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String attributeValue(SalesforceAttributesDetail detail) {
        return detail == null ? null : detail.getValue();
    }

    private Double price(String raw) {
        return price(raw, null);
    }

    /**
     * Parses a Salesforce price.
     *
     * <p>The pre-mapped shape formats prices for display, e.g. {@code "1,879.00"}, while the raw shape
     * reports them unformatted, so the thousands separator goes before parsing. An unparseable value
     * yields {@code fallback} rather than zero, so the frontend can tell "no price" from "free".</p>
     */
    private Double price(String raw, Double fallback) {
        if (isBlank(raw)) {
            return fallback;
        }
        try {
            return Double.valueOf(raw.trim().replace(
                    SalesforceCarrouselsConstants.PRICE_THOUSANDS_SEPARATOR,
                    SalesforceCarrouselsConstants.EMPTY_VALUE));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
