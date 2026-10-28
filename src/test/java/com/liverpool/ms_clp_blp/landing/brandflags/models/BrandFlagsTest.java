package com.liverpool.ms_clp_blp.landing.brandflags.models;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The flags arrive from CMS fields an editor may leave empty, so the defensive copy has to survive
 * nulls. {@code Map.copyOf} does not — that is the failure that already produced a 502 in
 * {@code GlobalDataClient}, and repeating it here would break the webhook instead.
 */
class BrandFlagsTest {

    private static final String BRAND = "GAP";

    @Test
    void dropsNullValuesInsteadOfThrowing() {
        Map<String, Boolean> authored = new HashMap<>();
        authored.put("jewel", Boolean.TRUE);
        authored.put("vertex", null);

        BrandFlags flags = new BrandFlags(BRAND, authored, Instant.EPOCH, "blt1");

        assertThat(flags.flags()).containsExactly(Map.entry("jewel", Boolean.TRUE));
    }

    /**
     * A dropped flag must read as "not declared", not as "false" — the difference decides whether the
     * caller falls back to the configured default or disables the block.
     */
    @Test
    void aDroppedFlagIsAbsentRatherThanFalse() {
        Map<String, Boolean> authored = new HashMap<>();
        authored.put("vertex", null);

        BrandFlags flags = new BrandFlags(BRAND, authored, Instant.EPOCH, "blt1");

        assertThat(flags.valueOf("vertex")).isNull();
    }

    @Test
    void treatsAMissingMapAsNoOverrides() {
        assertThat(new BrandFlags(BRAND, null, Instant.EPOCH, "blt1").flags()).isEmpty();
    }

    @Test
    void exposesDeclaredValues() {
        BrandFlags flags = new BrandFlags(BRAND, Map.of("jewel", false), Instant.EPOCH, "blt1");

        assertThat(flags.valueOf("jewel")).isFalse();
        assertThat(flags.valueOf("products-list-salesforce-v2")).isNull();
    }

    /** The stored copy must not track later edits to the map the caller handed over. */
    @Test
    void copiesDefensivelyAndStaysImmutable() {
        Map<String, Boolean> authored = new HashMap<>();
        authored.put("jewel", Boolean.TRUE);
        BrandFlags flags = new BrandFlags(BRAND, authored, Instant.EPOCH, "blt1");

        authored.put("vertex", Boolean.TRUE);

        assertThat(flags.flags()).hasSize(1);
        assertThatThrownBy(() -> flags.flags().put("vertex", Boolean.TRUE))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
