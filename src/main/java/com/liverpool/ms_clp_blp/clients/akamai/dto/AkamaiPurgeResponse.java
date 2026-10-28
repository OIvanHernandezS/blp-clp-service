package com.liverpool.ms_clp_blp.clients.akamai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Akamai's answer to an accepted purge (HTTP 201).
 *
 * <p>Every field is boxed and unknown properties are tolerated: this is an upstream contract that may
 * grow fields, and a purge that was accepted must not fail here because Akamai added one.</p>
 *
 * <p>{@link #purgeId} and {@link #supportId} are the correlation ids to quote when an operator has to
 * ask Akamai what became of a purge, which is why both are logged.</p>
 *
 * @param httpStatus       status Akamai echoes in the body; 201 on success
 * @param detail           human-readable outcome, e.g. {@code "Request accepted"}
 * @param purgeId          unique id of this purge request
 * @param supportId        id to quote to Akamai Technical Support
 * @param estimatedSeconds Akamai's estimate of how long the purge takes to propagate
 * @param title            response type description; present mainly on errors
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AkamaiPurgeResponse(
		Integer httpStatus,
		String detail,
		String purgeId,
		String supportId,
		Integer estimatedSeconds,
		String title) {
}
