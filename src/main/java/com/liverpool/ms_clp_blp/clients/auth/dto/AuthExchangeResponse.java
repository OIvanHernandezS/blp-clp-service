package com.liverpool.ms_clp_blp.clients.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Minimal projection of the auth-service exchange response — only the fields ms-clp-blp needs. Unknown
 * fields (id token, scopes, timestamps, roles, …) are ignored.
 *
 * @param accessToken       the bearer token the decommission stack (wishlist, orders, cart) expects;
 *                          a <strong>session secret</strong> — never log it
 * @param decodeAccessToken decoded claims, {@code null} when the service returns an empty body
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthExchangeResponse(String accessToken, DecodeAccessToken decodeAccessToken) {
}
