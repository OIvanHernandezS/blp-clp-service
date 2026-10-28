package com.liverpool.ms_clp_blp.shared.web;

import java.math.BigDecimal;

/**
 * Formats a number into a string the way JavaScript would, for the response fields digital_bff builds
 * by interpolating a number into a string.
 *
 * <p>Java and JavaScript disagree on how a whole number prints: {@code String.valueOf(5.0d)} yields
 * {@code "5.0"} while JS template interpolation yields {@code "5"}. That difference is invisible in
 * numeric JSON fields — {@code 1699} and {@code 1699.0} parse to the same value — but these are
 * <strong>string</strong> fields on the wire ({@code rating.average}, {@code rating.count},
 * {@code discountLabel}), so a frontend rendering them directly would show {@code "5.0"} where the
 * legacy service showed {@code "5"}.</p>
 *
 * <p>Only applies to values that reach the wire as text; numeric fields such as {@code priceInfo.*}
 * are left as native JSON numbers.</p>
 */
public final class LegacyNumberFormat {

    private LegacyNumberFormat() {
    }

    /**
     * @param value the number to render
     * @return {@code "5"} for {@code 5.0}, {@code "679.6"} for {@code 679.6}
     */
    public static String toPlainString(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
