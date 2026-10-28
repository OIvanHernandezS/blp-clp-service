package com.liverpool.ms_clp_blp.clients.blacklist.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The blacklist answer: product ids that must not be shown.
 *
 * @param products the restricted product ids; may be {@code null} when the site has none
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlacklistResponse(List<String> products) {
}
