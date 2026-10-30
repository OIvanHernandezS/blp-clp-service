package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.vertex;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.liverpool.dto.ProductRecommendation;

/**
 * A Vertex product with the two fields digital_bff's {@code ProductDto.fromVertex} publishes and
 * platform-commons' {@link ProductRecommendation} cannot carry on the wire.
 *
 * <ul>
 *   <li><strong>{@code flags}</strong> — the record's {@code featureFlags} as-is; the shared DTO has
 *       no such field.</li>
 *   <li><strong>{@code mainCategory}</strong> — exists on the shared DTO, but {@code ProductRecommendationMixin}
 *       hides it for every provider because Jewel and Salesforce V2 do not publish it in
 *       digital_bff. {@code @JsonIgnore(false)} on this override lifts that ignoral for Vertex only.</li>
 * </ul>
 *
 * <p>Annotations come from {@code com.fasterxml.jackson.annotation}, which both the Jackson 2 page
 * mapper and the Jackson 3 MVC mapper read, so the eager and lazy paths agree.</p>
 */
public class VertexProductRecommendation extends ProductRecommendation {

    private Map<String, Boolean> flags = new LinkedHashMap<>();

    /** @return the record's feature flags, empty (never {@code null}) when it carried none */
    @JsonProperty("flags")
    public Map<String, Boolean> getFlags() {
        return flags;
    }

    /** @param flags the record's feature flags; {@code null} is stored as empty */
    public void setFlags(Map<String, Boolean> flags) {
        this.flags = flags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(flags);
    }

    /** @return the record's main category label, published for Vertex unlike other providers */
    @Override
    @JsonIgnore(false)
    @JsonProperty("mainCategory")
    public String getMainCategory() {
        return super.getMainCategory();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VertexProductRecommendation that && super.equals(other)
                && flags.equals(that.flags);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + flags.hashCode();
    }
}
