package com.liverpool.ms_clp_blp.shared.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive coverage of the classification rules, so call sites that delegate to {@link Channel}
 * only need to prove delegation rather than re-litigating every edge case.
 */
class ChannelTest {

    @Test
    void from_missingHeader_defaultsToWeb() {
        assertThat(Channel.from(null)).isEqualTo(Channel.WEB);
        assertThat(Channel.from("")).isEqualTo(Channel.WEB);
        assertThat(Channel.from("   ")).isEqualTo(Channel.WEB);
    }

    @Test
    void from_web_isCaseAndWhitespaceInsensitive() {
        assertThat(Channel.from("WEB")).isEqualTo(Channel.WEB);
        assertThat(Channel.from("web")).isEqualTo(Channel.WEB);
        assertThat(Channel.from(" Web ")).isEqualTo(Channel.WEB);
    }

    @Test
    void from_app_isCaseAndWhitespaceInsensitive() {
        assertThat(Channel.from("APP")).isEqualTo(Channel.APP);
        assertThat(Channel.from("app")).isEqualTo(Channel.APP);
        assertThat(Channel.from(" App ")).isEqualTo(Channel.APP);
    }

    /**
     * {@code "APPS"} (plural) is the legacy, incorrect spelling this classifier exists to stop
     * silently accepting — real clients always send the singular {@code "APP"}.
     */
    @Test
    void from_anythingElseIncludingLegacyPluralApps_isOther() {
        assertThat(Channel.from("APPS")).isEqualTo(Channel.OTHER);
        assertThat(Channel.from("junk")).isEqualTo(Channel.OTHER);
        assertThat(Channel.from("android")).isEqualTo(Channel.OTHER);
    }

    @Test
    void from_kioscoAndPos_areDefinedChannels() {
        assertThat(Channel.from("KIOSCO")).isEqualTo(Channel.KIOSCO);
        assertThat(Channel.from(" kiosco ")).isEqualTo(Channel.KIOSCO);
        assertThat(Channel.from("POS")).isEqualTo(Channel.POS);
        assertThat(Channel.from("pos")).isEqualTo(Channel.POS);
        // The BFF's sourceBFF spellings are not the x-channel contract.
        assertThat(Channel.from("KIOSK")).isEqualTo(Channel.OTHER);
        assertThat(Channel.from("MPOS")).isEqualTo(Channel.OTHER);
    }

    @Test
    void parse_isStrict_neverDefaultingAMissingOrUnknownChannel() {
        assertThat(Channel.parse(null)).isEmpty();
        assertThat(Channel.parse("")).isEmpty();
        assertThat(Channel.parse("   ")).isEmpty();
        assertThat(Channel.parse("APPS")).isEmpty();
        assertThat(Channel.parse("android")).isEmpty();
    }

    @Test
    void parse_definedChannels_matchCaseInsensitively() {
        assertThat(Channel.parse("web")).contains(Channel.WEB);
        assertThat(Channel.parse("APP")).contains(Channel.APP);
        assertThat(Channel.parse("Kiosco")).contains(Channel.KIOSCO);
        assertThat(Channel.parse(" POS ")).contains(Channel.POS);
    }

    @Test
    void isApp_trueOnlyForApp() {
        assertThat(Channel.APP.isApp()).isTrue();
        assertThat(Channel.WEB.isApp()).isFalse();
        assertThat(Channel.KIOSCO.isApp()).isFalse();
        assertThat(Channel.POS.isApp()).isFalse();
        assertThat(Channel.OTHER.isApp()).isFalse();
    }

    @Test
    void isWebLike_trueForWebKioscoAndPos() {
        assertThat(Channel.WEB.isWebLike()).isTrue();
        assertThat(Channel.KIOSCO.isWebLike()).isTrue();
        assertThat(Channel.POS.isWebLike()).isTrue();
        assertThat(Channel.APP.isWebLike()).isFalse();
        assertThat(Channel.OTHER.isWebLike()).isFalse();
    }
}
