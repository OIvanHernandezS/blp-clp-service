package com.liverpool.ms_clp_blp.landing.models;

/**
 * Megamenu metadata of one category, field-for-field with digital_bff's {@code MetadataDto}
 * (Rule 19). Defaults for absent indexer fields follow the legacy mapper: {@code isBrand=false},
 * {@code imageUrl=""}, {@code groupType="Not Specified"}, {@code rootIndex=0}, {@code redirects=[]}
 * and {@code null} for the ancestor titles.
 *
 * @param level            depth in the category tree
 * @param parent           parent category id
 * @param title            category title
 * @param isBrand          whether the category is a brand (BLP) node
 * @param viewUrl          storefront URL
 * @param imageUrl         category image URL
 * @param showpath         page kind the storefront routes the category to ({@code BLP}, {@code CLP}, {@code PLP})
 * @param ancestors        the indexer's ancestor string
 * @param groupType        megamenu grouping
 * @param rootIndex        position among root categories
 * @param categoryId       upper-cased category id
 * @param parentTitle      parent title
 * @param parentShowpath   parent page kind
 * @param gp               grandparent id
 * @param gpTitle          grandparent title
 * @param gpShowpath       grandparent page kind
 * @param rootDisplay      whether shown at the megamenu root
 * @param categoryImage    category image
 * @param displayMegaMenu  whether shown in the megamenu
 * @param applyClpRedirect whether the storefront redirects this category to its CLP
 */
public record CategoryMetadata(
        Integer level,
        String parent,
        String title,
        boolean isBrand,
        String viewUrl,
        String imageUrl,
        String showpath,
        String ancestors,
        String groupType,
        int rootIndex,
        String categoryId,
        String parentTitle,
        String parentShowpath,
        String gp,
        String gpTitle,
        String gpShowpath,
        boolean rootDisplay,
        String categoryImage,
        boolean displayMegaMenu,
        boolean applyClpRedirect) {
}
