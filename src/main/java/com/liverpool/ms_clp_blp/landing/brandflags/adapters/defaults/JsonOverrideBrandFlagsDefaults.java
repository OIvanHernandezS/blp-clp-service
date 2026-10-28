package com.liverpool.ms_clp_blp.landing.brandflags.adapters.defaults;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.landing.brandflags.AuthoredFlagsReader;
import com.liverpool.ms_clp_blp.landing.brandflags.BrandFlagsProperties;
import com.liverpool.ms_clp_blp.landing.ports.BrandFlagsDefaultsPort;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-brand overrides declared in one environment variable holding a JSON object.
 *
 * <p>This is the layer that keeps an outage from being brand-blind. {@code landing.feature-flags} is
 * service-wide, so with MongoDB unreachable every brand would fall onto the same values and a brand
 * that must never call Jewel would start calling it the moment the database blinked. These values
 * come from configuration, so they are reachable whenever the process is.</p>
 *
 * <p><strong>Shape.</strong> One flat object whose keys are {@code <flag>_<BRAND>}:</p>
 *
 * <pre>{@code
 * FF_OVERRIDE_JSON={"container-greeting_LP": false, "jewel_GAP": false}
 * }</pre>
 *
 * <p><strong>It is the exception, not the rule.</strong> Only overrides are listed — a brand that
 * should behave like the rest appears nowhere, and any flag not named here falls through to
 * {@code landing.feature-flags}. That is what keeps the variable short enough to read in a Cloud Run
 * revision, instead of a matrix of nine flags times thirteen brands.</p>
 *
 * <p>The brand is the segment after the <em>last</em> underscore, so flag ids containing their own
 * separator still parse: {@code container_shortcuts_GAP} is {@code container-shortcuts} for
 * {@code GAP}. Both {@code kebab-case} and {@code snake_case} spellings of the flag work, and only
 * ids {@link AuthoredFlagsReader} recognises are read — a typo cannot invent a flag.</p>
 *
 * <p>Parsed once at startup into an immutable map, so evaluating a flag costs a lookup and never
 * touches JSON. Malformed input is logged and ignored rather than failing startup: a bad character in
 * one variable must not stop the service from serving every brand from its YAML defaults.</p>
 */
@Component
public class JsonOverrideBrandFlagsDefaults implements BrandFlagsDefaultsPort {

    private static final Logger log = LoggerFactory.getLogger(JsonOverrideBrandFlagsDefaults.class);

    private static final char BRAND_SEPARATOR = '_';

    /** Log field: how many brands declare an override. */
    private static final String BRAND_COUNT = "brandCount";

    /** Log field: how many individual overrides were accepted. */
    private static final String OVERRIDE_COUNT = "overrideCount";

    /** Log field: a key that named no flag, so an operator can see the typo. */
    private static final String IGNORED_KEYS = "ignoredKeys";

    private final Map<String, Map<String, Boolean>> byBrand;

    /**
     * @param properties   supplies the raw JSON string, empty when nothing is overridden
     * @param objectMapper the application's Jackson mapper
     */
    public JsonOverrideBrandFlagsDefaults(BrandFlagsProperties properties, ObjectMapper objectMapper) {
        this.byBrand = parse(properties.overrideJson(), objectMapper);
        LogEvents.at(log, LogEvents.BRAND_FLAGS_DEFAULTS_LOADED)
                .addKeyValue(BRAND_COUNT, byBrand.size())
                .addKeyValue(OVERRIDE_COUNT, byBrand.values().stream().mapToInt(Map::size).sum())
                .log("Per-brand feature flag overrides loaded from configuration");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Boolean> lookup(String flagId, String brand) {
        if (brand == null || brand.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byBrand.get(normalizeBrand(brand))).map(flags -> flags.get(flagId));
    }

    private static Map<String, Map<String, Boolean>> parse(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> raw;
        try {
            raw = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException e) {
            LogEvents.at(log, LogEvents.BRAND_FLAGS_DEFAULTS_UNREADABLE)
                    .addKeyValue(LogFields.ERROR_CLASS, e.getClass().getSimpleName())
                    .setCause(e)
                    .log("Per-brand feature flag overrides are not valid JSON and were ignored; "
                            + "every brand falls back to landing.feature-flags");
            return Map.of();
        }
        Map<String, Map<String, Boolean>> parsed = new HashMap<>();
        StringBuilder ignored = new StringBuilder();
        raw.forEach((key, value) -> {
            if (!accept(parsed, key, value)) {
                ignored.append(ignored.isEmpty() ? "" : ",").append(key);
            }
        });
        if (!ignored.isEmpty()) {
            // Named rather than counted: the whole point of reporting this is that someone can find
            // the typo, and "3 keys ignored" sends them reading the variable character by character.
            LogEvents.at(log, LogEvents.BRAND_FLAGS_DEFAULTS_UNREADABLE)
                    .addKeyValue(IGNORED_KEYS, ignored.toString())
                    .log("Per-brand feature flag override keys named no known flag and were ignored");
        }
        parsed.replaceAll((brand, flags) -> Map.copyOf(flags));
        return Map.copyOf(parsed);
    }

    /**
     * @return true when the entry named a known flag for a brand and carried a real boolean
     */
    private static boolean accept(Map<String, Map<String, Boolean>> target, String key, Object value) {
        if (key == null) {
            return false;
        }
        int separator = key.lastIndexOf(BRAND_SEPARATOR);
        if (separator <= 0 || separator == key.length() - 1) {
            return false;
        }
        String flagId = AuthoredFlagsReader.canonicalFlagId(key.substring(0, separator));
        Boolean parsed = AuthoredFlagsReader.booleanValue(value);
        if (flagId == null || parsed == null) {
            return false;
        }
        target.computeIfAbsent(normalizeBrand(key.substring(separator + 1)), b -> new LinkedHashMap<>())
                .put(flagId, parsed);
        return true;
    }

    private static String normalizeBrand(String brand) {
        return brand.trim().toUpperCase(Locale.ROOT);
    }
}
