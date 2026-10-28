package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.salesforce;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.salesforce.SalesforceResponse;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test against a <strong>real</strong> Salesforce response, captured live from the
 * {@code CMSOfertasIncreibles} campaign for an authenticated shopper and stripped of every user
 * identifier.
 *
 * <p>It exists because the synthetic fixtures got two things wrong about the live payload, and both
 * are exactly the kind of detail that fails silently:</p>
 * <ul>
 *   <li><strong>The Home carousels serve {@code productToShow}, not {@code productDataMapped}.</strong>
 *       The pre-mapped shape is what the hand-written tests all assumed; the live campaigns behind
 *       {@code CMSOfertasIncreibles} and {@code CMSTePuedeGustar} return the raw one.</li>
 *   <li><strong>{@code attributes.*.value} arrives as a JSON number for some fields and a string for
 *       others</strong> — {@code price} is {@code 29998}, {@code maxListPrice} is {@code "40525"} —
 *       while commons declares {@code value} as a {@code String}. This pins the coercion: if a Jackson
 *       upgrade ever tightens it, every price on the carousel turns null here rather than in
 *       production.</li>
 * </ul>
 */
class SalesforceCarrouselRealPayloadTest {

    private static final Path FIXTURE =
            Path.of("src/test/resources/fixtures/salesforce-carrousel-producttoshow.json");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SalesforceProductMapper mapper = new SalesforceProductMapper();

    private SalesforceResponse realResponse() throws Exception {
        return MAPPER.readValue(Files.readString(FIXTURE, StandardCharsets.UTF_8),
                SalesforceResponse.class);
    }

    @Test
    void theLiveCampaignDeserializesAndCarriesTheRawShape() throws Exception {
        SalesforceResponse response = realResponse();

        assertThat(response.getCampaignResponses()).hasSize(1);
        var payload = response.getCampaignResponses().get(0).getPayload();
        assertThat(response.getCampaignResponses().get(0).getCampaignName())
                .isEqualTo("LIV_ECM_SXP_PORQUE_VISTE");
        assertThat(payload.getTitle()).isEqualTo("Descubre más de lo que viste");
        // The shape the adapter used to skip entirely.
        assertThat(payload.getProductToShow()).hasSize(3);
        assertThat(payload.getProductDataMapped()).isNullOrEmpty();
    }

    @Test
    void everyProductMapsWithItsPricesAndIdentity() throws Exception {
        List<ProductRecommendation> products = mapper.toProducts(
                realResponse().getCampaignResponses().get(0).getPayload());

        assertThat(products).hasSize(3);
        assertThat(products).allSatisfy(product -> {
            assertThat(product.getProductId()).isNotBlank();
            assertThat(product.getName()).isNotBlank();
            assertThat(product.getPriceInfo().getOriginalPrice()).isNotNull().isPositive();
        });

        ProductRecommendation first = products.get(0);
        assertThat(first.getProductId()).isEqualTo("1186186914");
        assertThat(first.getName()).isEqualTo("iPhone 17 Pro 6.3 pulgadas Super Retina XDR");
        assertThat(first.getBrand()).isEqualTo("APPLE");

        // Numeric JSON values coerced into the String DTO field, then parsed back.
        assertThat(first.getPriceInfo().getOriginalPrice()).isEqualTo(29998.0);   // attributes.price
        assertThat(first.getPriceInfo().getPromoPrice()).isEqualTo(28498.1);      // attributes.listPrice
        // String-valued on the wire, and the max pair is read straight through.
        assertThat(first.getPriceInfo().getMaximumListPrice()).isEqualTo(40525.0);
        assertThat(first.getPriceInfo().getMaximumPromoPrice()).isEqualTo(38498.75);

        // The promo price is genuinely below the list price — proof the inverted attribute names are
        // being read the right way round. Taking them at face value would report a price *increase*.
        assertThat(first.getPriceInfo().getPromoPrice())
                .isLessThan(first.getPriceInfo().getOriginalPrice());

        // Categories come from the product's own `categories`, which used to be read from a path that
        // does not exist in the payload — leaving this list permanently empty.
        assertThat(first.getCategories()).extracting(ProductRecommendation.Category::getId)
                .containsExactly("CAT5150041", "CAT5150023", "CAT5150024");

        assertThat(first.getImages()).singleElement().satisfies(image ->
                assertThat(image.getUrl()).startsWith("https://sp632.liverpool.com.mx/"));
        // rating.value arrives as the number 0 and still lands as the legacy "0" string.
        assertThat(first.getRating().getAverage()).isEqualTo("0");
    }

    /** Both keys carry the same items live, so the preferred one must win without changing anything. */
    @Test
    void productToShowAndProductsAgree() throws Exception {
        var payload = realResponse().getCampaignResponses().get(0).getPayload();

        assertThat(mapper.toProducts(payload)).extracting(ProductRecommendation::getProductId)
                .isEqualTo(payload.getProducts().stream().map(p -> p.getId()).toList());
    }
}
