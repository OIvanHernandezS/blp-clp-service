package com.liverpool.ms_clp_blp.landing.models;

import java.util.Arrays;

/**
 * Landing-page block types this service classifies, keyed by the Contentstack {@code _content_type_uid}.
 *
 * <p>The {@code page_blp} / {@code page_clp} schemas allow {@code heading}, {@code hero_banner_slider},
 * {@code container}, {@code card_slider}, {@code products_list} (wrapped as {@code product_list}),
 * {@code rich_text}, {@code button_list} and {@code banner_slider}. Only {@code products_list} needs
 * another system; everything else is CMS content served verbatim. Types that need a composition-time
 * rule ({@code countdown}, {@code container_guest}) are named so the rule can find them.</p>
 *
 * <p>Unknown types map to {@link #UNKNOWN} and are treated as static pass-through so unrecognised
 * content is never dropped — ordering and presence are always preserved (Rule 18). Adding a block
 * type here, plus its resolution config, is the only change needed for a new block (Open/Closed).</p>
 */
public enum BlockType {

    /** Full-width hero image/video carousel with optional CTA buttons; served as authored. */
    HERO_BANNER_SLIDER("hero_banner_slider", BlockKind.STATIC),

    /** Generic layout container that wraps child blocks (cards, etc.) in a responsive grid. */
    CONTAINER("container", BlockKind.STATIC),

    /** Strip shown only to guests; dropped for an authenticated session (legacy BR parity). */
    CONTAINER_GUEST("container_guest", BlockKind.STATIC),

    /** Horizontal card carousel. */
    CARD_SLIDER("card_slider", BlockKind.STATIC),

    /** Section heading. */
    HEADING("heading", BlockKind.STATIC),

    /** Authored rich text, optionally collapsable (SEO copy at the bottom of a landing page). */
    RICH_TEXT("rich_text", BlockKind.STATIC),

    /** Row of chips linking to sub-categories ({@code button_list}, "Landing Chips"). */
    BUTTON_LIST("button_list", BlockKind.STATIC),

    /** Banner carousel ({@code page_clp} top content). */
    BANNER_SLIDER("banner_slider", BlockKind.STATIC),

    /** Countdown timer; dropped once inactive or elapsed. */
    COUNTDOWN("countdown", BlockKind.STATIC),

    /** Product carousel whose products come from the provider named by {@code source_of_data}. */
    PRODUCTS_LIST("products_list", BlockKind.DYNAMIC),

    UNKNOWN("", BlockKind.STATIC);

    private final String contentTypeUid;
    private final BlockKind kind;

    BlockType(String contentTypeUid, BlockKind kind) {
        this.contentTypeUid = contentTypeUid;
        this.kind = kind;
    }

    /** @return the Contentstack {@code _content_type_uid} for this type ({@code ""} for unknown) */
    public String contentTypeUid() {
        return contentTypeUid;
    }

    /** @return whether the type is static or dynamic */
    public BlockKind kind() {
        return kind;
    }

    /**
     * @param contentTypeUid the raw {@code _content_type_uid}
     * @return the matching type, or {@link #UNKNOWN}
     */
    public static BlockType fromContentTypeUid(String contentTypeUid) {
        return Arrays.stream(values())
                .filter(type -> type != UNKNOWN && type.contentTypeUid.equals(contentTypeUid))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
