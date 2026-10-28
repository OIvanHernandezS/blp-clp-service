package com.liverpool.ms_clp_blp.clients.categoryindexer.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One node of the indexer's category tree — only the fields the landing response is built from
 * (Rule 19 reference: digital_bff {@code CategoryTree}). Unknown fields are ignored so the indexer can
 * grow without breaking this service.
 *
 * @param id               category id, as the indexer stores it (lower-case)
 * @param title            category title
 * @param displayMegaMenu  whether shown in the megamenu
 * @param parent           parent category id
 * @param imageUrl         category image URL
 * @param viewUrl          storefront URL
 * @param showpath         page kind the storefront routes to ({@code BLP}, {@code CLP}, {@code PLP})
 * @param level            depth in the tree
 * @param ancestors        ancestor string
 * @param isBrand          whether the node is a brand
 * @param rootDisplay      whether shown at the megamenu root
 * @param groupType        megamenu grouping
 * @param rootIndex        position among root categories
 * @param parentTitle      parent title
 * @param parentShowpath   parent page kind
 * @param gp               grandparent id
 * @param gpTitle          grandparent title
 * @param gpShowpath       grandparent page kind
 * @param ggp              great-grandparent id
 * @param gggp             great-great-grandparent id
 * @param categoryImage    category image
 * @param breadCrumb       breadcrumb text ({@code "home > Estee Lauder"})
 * @param applyClpRedirect whether the storefront redirects the category to its CLP
 * @param children         child categories
 * @param visible          whether the node is visible
 * @param valid            whether the node is valid
 * @param status           HTTP-like status the indexer embeds for a missing node ({@code 404})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CategoryTreeResponse(
        String id,
        String title,
        Boolean displayMegaMenu,
        String parent,
        String imageUrl,
        String viewUrl,
        String showpath,
        Integer level,
        String ancestors,
        @JsonProperty("isBrand") Boolean isBrand,
        Boolean rootDisplay,
        String groupType,
        Integer rootIndex,
        String parentTitle,
        String parentShowpath,
        String gp,
        String gpTitle,
        String gpShowpath,
        String ggp,
        String gggp,
        String categoryImage,
        String breadCrumb,
        @JsonProperty("applyCLPRedirect") Boolean applyClpRedirect,
        List<CategoryTreeResponse> children,
        Boolean visible,
        Boolean valid,
        Integer status) {
}
