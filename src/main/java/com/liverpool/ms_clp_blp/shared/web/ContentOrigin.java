package com.liverpool.ms_clp_blp.shared.web;

/**
 * Which backend actually answered a CMS-backed landing page request: the per-instance Caffeine cache,
 * or Contentstack read directly. Surfaced to callers via {@code x-data-origin} so an operator can tell
 * which tier answered a given response — invisible otherwise, since both return the payload in the
 * same shape by design.
 */
public enum ContentOrigin {

    CONTENTSTACK("ContentStack"),
    CAFFEINE("caffeine");

    private final String headerValue;

    ContentOrigin(String headerValue) {
        this.headerValue = headerValue;
    }

    /**
     * @return the exact literal written to the {@code x-data-origin} response header
     */
    public String headerValue() {
        return headerValue;
    }
}
