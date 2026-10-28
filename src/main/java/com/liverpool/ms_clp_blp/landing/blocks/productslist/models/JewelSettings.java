package com.liverpool.ms_clp_blp.landing.blocks.productslist.models;

import com.liverpool.ms_clp_blp.landing.models.SourceOfData;

/**
 * Per-block Jewel settings plus the Jewel tracking identity for this request.
 *
 * <p>{@code userId}/{@code deviceId} are Jewel-specific tracking identifiers carried by the
 * {@code x-jml-user-id}/{@code x-jml-device-id} request headers (configurable — see
 * {@code JewelProperties}) — distinct from the {@code SessionId}-exchanged {@code profileId} used for
 * Salesforce (ADR-033). Jewel's identity is genuine header passthrough by design, unchanged by
 * ADR-033. Either may be absent depending on the session and client; whether an absent value blocks
 * resolution is decided by the block's own {@link JewelModelConfig}, not by service configuration
 * (ADR-022).</p>
 *
 * @param userId      Jewel tracking user id; {@code null} when absent
 * @param deviceId    Jewel tracking device id; {@code null} when absent
 * @param modelConfig the block's own Jewel settings from Contentstack; {@code null} when the block
 *                    carries no {@code jewel_model_config}, which makes it unresolvable
 */
public record JewelSettings(
        String userId,
        String deviceId,
        JewelModelConfig modelConfig) implements ProviderSettings {

    @Override
    public SourceOfData source() {
        return SourceOfData.JEWEL;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Jewel also receives this as {@code minimum_items}, but the API is not obliged to honour it, so
     * the use case enforces it on the response as well (ADR-029).</p>
     */
    @Override
    public Integer minOfProducts() {
        return modelConfig == null ? null : modelConfig.minItems();
    }

    /** {@inheritDoc} Sent to Jewel as {@code number_of_placements} and enforced on the response. */
    @Override
    public Integer maxOfProducts() {
        return modelConfig == null ? null : modelConfig.maxItems();
    }
}
