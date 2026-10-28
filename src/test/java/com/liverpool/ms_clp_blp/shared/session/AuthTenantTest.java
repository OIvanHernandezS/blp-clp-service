package com.liverpool.ms_clp_blp.shared.session;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The channel → auth tenant table. Exhaustive on purpose: this mapping decides which Auth0 client a
 * session is exchanged with. A missing channel resolves to the documented default (ADR-070).
 */
class AuthTenantTest {

    @Test
    void forChannel_webLikeChannels_mapToWeb() {
        assertThat(AuthTenant.forChannel(Channel.WEB)).contains(AuthTenant.WEB);
        assertThat(AuthTenant.forChannel(Channel.KIOSCO)).contains(AuthTenant.WEB);
        assertThat(AuthTenant.forChannel(Channel.POS)).contains(AuthTenant.WEB);
    }

    @Test
    void forChannel_app_mapsToApp() {
        assertThat(AuthTenant.forChannel(Channel.APP)).contains(AuthTenant.APP);
    }

    @Test
    void forChannel_otherOrNull_hasNoTenant() {
        assertThat(AuthTenant.forChannel(Channel.OTHER)).isEmpty();
        assertThat(AuthTenant.forChannel(null)).isEmpty();
    }

    /** ADR-070: no channel, or an unrecognised one, exchanges as web — it never means "no exchange". */
    @Test
    void resolve_definedChannel_usesItsTenant_otherwiseTheDefaultWeb() {
        assertThat(AuthTenant.resolve(Optional.of(Channel.APP))).isEqualTo(AuthTenant.APP);
        assertThat(AuthTenant.resolve(Optional.of(Channel.KIOSCO))).isEqualTo(AuthTenant.WEB);
        assertThat(AuthTenant.resolve(Optional.empty())).isEqualTo(AuthTenant.DEFAULT);
        assertThat(AuthTenant.resolve(Optional.of(Channel.OTHER))).isEqualTo(AuthTenant.DEFAULT);
        assertThat(AuthTenant.DEFAULT).isEqualTo(AuthTenant.WEB);
    }

    @Test
    void wireValue_isWhatTheAuthServiceExpects() {
        assertThat(AuthTenant.WEB.wireValue()).isEqualTo("web");
        assertThat(AuthTenant.APP.wireValue()).isEqualTo("app");
    }

    @Test
    void expectsPlatform_webTenant_acceptsOnlyWeb() {
        assertThat(AuthTenant.WEB.expectsPlatform("web")).isTrue();
        assertThat(AuthTenant.WEB.expectsPlatform(" WEB ")).isTrue();
        assertThat(AuthTenant.WEB.expectsPlatform("android")).isFalse();
        assertThat(AuthTenant.WEB.expectsPlatform(null)).isFalse();
    }

    @Test
    void expectsPlatform_appTenant_acceptsAndroidAndIos() {
        assertThat(AuthTenant.APP.expectsPlatform("android")).isTrue();
        assertThat(AuthTenant.APP.expectsPlatform("iOS")).isTrue();
        assertThat(AuthTenant.APP.expectsPlatform("web")).isFalse();
    }
}
