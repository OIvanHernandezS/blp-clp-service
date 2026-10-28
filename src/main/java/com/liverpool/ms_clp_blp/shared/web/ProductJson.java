package com.liverpool.ms_clp_blp.shared.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liverpool.dto.ProductRecommendation;

/**
 * Registers {@link ProductRecommendationMixin} on <strong>every</strong> mapper that can serialize a
 * product, so the eager and lazy paths cannot disagree about the wire shape.
 *
 * <p>Two mappers are in play, on two different Jackson majors:</p>
 * <ol>
 *   <li><strong>Jackson 3</strong> ({@code tools.jackson}) — the Spring MVC mapper Boot 4.1
 *       auto-configures, which writes the lazy {@code POST /blp|clp/blocks/*} responses. Customised via
 *       the {@link JsonMapperBuilderCustomizer} bean below.</li>
 *   <li><strong>Jackson 2</strong> ({@code com.fasterxml.jackson}) — the private mapper inside
 *       {@code LandingPageMapper}, which converts a resolved payload to a map for inlining into
 *       {@code GET /blp|clp/**}. Customised by passing it through {@link #withProductMixins(ObjectMapper)}.</li>
 * </ol>
 *
 * <p>Registering in only one place would produce a product object whose field names depend on whether
 * the block was resolved eagerly or lazily — the kind of divergence that survives every unit test.</p>
 */
@Configuration
public class ProductJson {

    /**
     * Applies the product mixins to a Jackson 2 mapper.
     *
     * @param mapper the mapper to configure
     * @param <T>    the mapper type, returned for chaining at a field initialiser
     * @return the same instance, configured
     */
    public static <T extends ObjectMapper> T withProductMixins(T mapper) {
        mapper.addMixIn(ProductRecommendation.class, ProductRecommendationMixin.class);
        return mapper;
    }

    /**
     * Applies the same mixins to the Jackson 3 mapper Spring MVC uses for responses.
     *
     * @return the customizer Boot applies to its auto-configured {@code JsonMapper}
     */
    @Bean
    public JsonMapperBuilderCustomizer productRecommendationMixins() {
        return builder -> builder.addMixIn(ProductRecommendation.class, ProductRecommendationMixin.class);
    }
}
