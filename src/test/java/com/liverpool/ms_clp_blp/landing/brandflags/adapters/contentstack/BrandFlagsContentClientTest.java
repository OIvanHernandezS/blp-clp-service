package com.liverpool.ms_clp_blp.landing.brandflags.adapters.contentstack;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.clients.contentstackdirect.ContentStackDirectGateway;
import com.liverpool.ms_clp_blp.landing.brandflags.BrandFlagsProperties;
import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlags;
import com.liverpool.ms_clp_blp.landing.models.LandingFeatureFlags;

import feign.FeignException;
import feign.Request;
import feign.Response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The leniency in this adapter is not politeness — every case below is a way a published change would
 * otherwise silently do nothing, which is the worst failure mode a configuration system can have.
 */
class BrandFlagsContentClientTest {

    private static final String CONTENT_TYPE = "ms_clp_blp_flags";
    private static final String LOCALE = "es-mx";

    private final ContentStackDirectGateway gateway = mock(ContentStackDirectGateway.class);
    private final BrandFlagsContentClient client =
            new BrandFlagsContentClient(gateway, properties());

    /**
     * The identifier convention is shared with every other Liverpool service; getting it wrong means
     * the query silently matches nothing.
     */
    @Test
    void queriesByTheBrandIdentifier() {
        doReturn(Optional.of(entry(Map.of("jewel", true))))
                .when(gateway).fetchByField(anyString(), anyString(), anyString(), anyString(), anyString());

        client.fetchForBrand("gap");

        verify(gateway).fetchByField(
                eq(CONTENT_TYPE), eq(LOCALE), eq("identifier"), eq("ms_clp_blp_flags_GAP"), eq("GAP"));
    }


    /**
     * The spelling trap. Flag ids are kebab-case, but a Contentstack field uid cannot contain a
     * hyphen — so the CMS can only ever spell them with underscores. Rejecting those would mean the
     * shortcut flags could never be configured at all.
     */
    @Test
    void acceptsSnakeCaseFieldNamesAsWellAsKebabCase() {
        doReturn(Optional.of(entry(Map.of(
                "products_list_salesforce_v2", false,
                "jewel", false))))
                .when(gateway).fetchByField(anyString(), anyString(), anyString(), anyString(), anyString());

        assertThat(flagsFor("GAP")).containsOnly(
                Map.entry(LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE_V2, false),
                Map.entry(LandingFeatureFlags.JEWEL, false));
    }

    /** The flags may sit in their own group instead of on the entry root. */
    @Test
    void readsFlagsFromANestedGroup() {
        doReturn(Optional.of(Map.of("uid", "blt1", "flags", Map.of("vertex", false))))
                .when(gateway).fetchByField(anyString(), anyString(), anyString(), anyString(), anyString());

        assertThat(flagsFor("GAP")).containsExactly(Map.entry(LandingFeatureFlags.VERTEX, false));
    }

    /** A hand-authored JSON or text field arrives as a string rather than a boolean. */
    @Test
    void acceptsBooleansSpelledAsStrings() {
        doReturn(Optional.of(entry(Map.of("jewel", "false", "vertex", "TRUE"))))
                .when(gateway).fetchByField(anyString(), anyString(), anyString(), anyString(), anyString());

        assertThat(flagsFor("GAP")).containsOnly(
                Map.entry(LandingFeatureFlags.JEWEL, false),
                Map.entry(LandingFeatureFlags.VERTEX, true));
    }

    /**
     * An entry may carry all sorts of editorial fields. None of them may become a flag, or an editor
     * adding a field could disable a block by accident.
     */
    @Test
    void ignoresEverythingThatIsNotAKnownFlag() {
        doReturn(Optional.of(entry(Map.of(
                "jewel", true,
                "title", "GAP flags",
                "some_other_toggle", true,
                "notes", 42))))
                .when(gateway).fetchByField(anyString(), anyString(), anyString(), anyString(), anyString());

        assertThat(flagsFor("GAP")).containsExactly(Map.entry(LandingFeatureFlags.JEWEL, true));
    }

    /**
     * An entry with nothing recognisable must read as "no overrides captured", never as an empty set
     * of overrides — the caller uses that difference to decide not to overwrite what is stored.
     */
    @Test
    void reportsAnEntryWithNoRecognisedFlagAsEmpty() {
        doReturn(Optional.of(entry(Map.of("title", "GAP flags"))))
                .when(gateway).fetchByField(anyString(), anyString(), anyString(), anyString(), anyString());

        assertThat(client.fetchForBrand("GAP")).isEmpty();
    }

    @Test
    void reportsNoMatchingEntryAsEmpty() {
        doReturn(Optional.empty())
                .when(gateway).fetchByField(anyString(), anyString(), anyString(), anyString(), anyString());

        assertThat(client.fetchForBrand("GAP")).isEmpty();
    }

    /** A CMS outage must not throw at the webhook; it means "nothing captured", nothing more. */
    @Test
    void reportsAnUpstreamFailureAsEmpty() {
        doThrow(new IllegalStateException("contentstack unavailable"))
                .when(gateway).fetchByField(anyString(), anyString(), anyString(), anyString(), anyString());

        assertThat(client.fetchForBrand("GAP")).isEmpty();
    }

    @Test
    void rejectsABlankBrandWithoutCallingContentstack() {
        assertThat(client.fetchForBrand(null)).isEmpty();
        assertThat(client.fetchForBrand("  ")).isEmpty();

        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    /** The entry uid is carried through so a stored value can be traced back to what published it. */
    @Test
    void carriesTheEntryUidThrough() {
        doReturn(Optional.of(entry(Map.of("jewel", true))))
                .when(gateway).fetchByField(anyString(), anyString(), anyString(), anyString(), anyString());

        Optional<BrandFlags> flags = client.fetchForBrand("GAP");

        assertThat(flags).isPresent();
        assertThat(flags.get().entryUid()).isEqualTo("blt1");
        assertThat(flags.get().brand()).isEqualTo("GAP");
    }

    private Map<String, Boolean> flagsFor(String brand) {
        return client.fetchForBrand(brand).map(BrandFlags::flags).orElseThrow();
    }

    private static Map<String, Object> entry(Map<String, Object> fields) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>(fields);
        entry.put("uid", "blt1");
        return entry;
    }

    private static FeignException feignException(int status) {
        Request request = Request.create(Request.HttpMethod.POST, "/content/entries/es-mx/ms_clp_blp_flags",
                Map.of(), new byte[0], StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("getEntriesByQuery",
                Response.builder().status(status).request(request).build());
    }

    private static BrandFlagsProperties properties() {
        return new BrandFlagsProperties(true, CONTENT_TYPE, LOCALE, "ms_clp_blp_configuration",
                Duration.ofMinutes(3), List.of(), "");
    }
}
