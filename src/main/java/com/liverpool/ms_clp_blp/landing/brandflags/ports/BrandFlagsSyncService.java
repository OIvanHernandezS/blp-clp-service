package com.liverpool.ms_clp_blp.landing.brandflags.ports;

import java.util.Map;

import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlagsSyncResult;

/**
 * Inbound port for applying a Contentstack webhook delivery to the per-brand flag store.
 *
 * <p>The payload stays an untyped map on purpose: it is authored by Contentstack, not by us, and the
 * shape has already varied enough upstream that platform-commons' parser reads the content type from
 * three possible positions. Binding a schema here would turn a harmless new field into a 400.</p>
 */
public interface BrandFlagsSyncService {

    /**
     * Applies one delivery. Never throws for anything that is merely irrelevant or unusable.
     *
     * @param brand   brand identifier from the {@code x-brand-id} header
     * @param payload the webhook body as delivered
     * @return what was done, and why
     */
    BrandFlagsSyncResult apply(String brand, Map<String, Object> payload);

    /**
     * Ensures every brand in {@code brand-flags.seed-brands} has a document, writing a
     * {@code landing.feature-flags} snapshot for any brand found without one. Never throws: a store
     * failure for one brand is logged and does not stop the rest, and a brand that already has a
     * document — CMS-authored or previously seeded — is left untouched.
     */
    void seedDefaults();
}
