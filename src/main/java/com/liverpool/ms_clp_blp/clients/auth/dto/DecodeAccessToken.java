package com.liverpool.ms_clp_blp.clients.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Decoded access-token claims used to derive login/guest state and profile id.
 *
 * @param isAnonymous guest discriminator ({@code false} = logged in)
 * @param prn         profile id for logged-in users
 * @param anonId      anonymous id used as the profile id for guests
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DecodeAccessToken(
		@JsonProperty("isAnonymous") Boolean isAnonymous,
		@JsonProperty("prn") String prn,
		@JsonProperty("anonId") String anonId) {
}
