package com.liverpool.ms_clp_blp.landing.brandflags.adapters.mongo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.liverpool.ms_clp_blp.landing.brandflags.BrandFlagsProperties;
import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlags;
import com.liverpool.ms_clp_blp.shared.config.RuntimeProfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Two things matter here and nothing else does: the document id both sides agree on, and the promise
 * that this adapter never throws at a caller composing a page.
 */
class BrandFlagsMongoAdapterTest {

    private static final String COLLECTION = "ms_clp_blp_configuration";
    private static final String JEWEL = "jewel";

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final BrandFlagsMongoAdapter adapter = new BrandFlagsMongoAdapter(
            mongoTemplate, properties(), new RuntimeProfile("qa"));

    /**
     * The id is the contract between the webhook that writes and the request path that reads. It is
     * also what keeps QA documents out of production, so its exact shape is worth pinning.
     */
    @Test
    void readsByBrandAndProfile() {
        doReturn(document(Map.of(JEWEL, false)))
                .when(mongoTemplate).findOne(any(Query.class), eq(BrandFlagsDocument.class), eq(COLLECTION));

        Optional<BrandFlags> found = adapter.findByBrand("gap");

        assertThat(found).isPresent();
        assertThat(found.get().brand()).isEqualTo("GAP");
        assertThat(found.get().valueOf(JEWEL)).isFalse();

        org.mockito.ArgumentCaptor<Query> query = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findOne(query.capture(), eq(BrandFlagsDocument.class), eq(COLLECTION));
        assertThat(query.getValue().getQueryObject().toJson()).contains("GAP_QA");
    }

    /**
     * Settings are edited by hand in the Mongo shell, which stores {@code 3} as a double; only known
     * ids survive the read.
     */
    @Test
    void readsHandEditedSettings() {
        doReturn(document(Map.of(), Map.of("products-list-max-images", 3.0, "unknown", 7)))
                .when(mongoTemplate).findOne(any(Query.class), eq(BrandFlagsDocument.class), eq(COLLECTION));

        BrandFlags found = adapter.findByBrand("GAP").orElseThrow();

        assertThat(found.settings()).containsExactly(Map.entry("products-list-max-images", 3));
    }

    /** No write may name {@code settings}, or a publish or the seed would erase an operator's edit. */
    @Test
    void neverWritesSettings() {
        BrandFlags flags = new BrandFlags("gap", Map.of(JEWEL, true), Instant.EPOCH, "blt1",
                Map.of("products-list-max-images", 3));

        adapter.save(flags);
        adapter.saveDefaults(flags);

        org.mockito.ArgumentCaptor<Update> update = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, org.mockito.Mockito.times(2))
                .upsert(any(Query.class), update.capture(), eq(COLLECTION));
        assertThat(update.getAllValues()).allSatisfy(u -> assertThat(fields(u)).doesNotContainKey("settings"));
    }

    @Test
    void reportsAMissingDocumentAsNoOverrides() {
        doReturn(null)
                .when(mongoTemplate).findOne(any(Query.class), eq(BrandFlagsDocument.class), eq(COLLECTION));

        assertThat(adapter.findByBrand("GAP")).isEmpty();
    }

    /**
     * The load-bearing test. A store failure on the read path must look identical to "no overrides",
     * because the alternative is a 500 on {@code GET /blp|clp/**} caused by a feature whose whole purpose is
     * to be survivable.
     */
    @Test
    void aStoreFailureOnReadDegradesInsteadOfThrowing() {
        doThrow(new DataAccessResourceFailureException("no route to host"))
                .when(mongoTemplate).findOne(any(Query.class), eq(BrandFlagsDocument.class), eq(COLLECTION));

        assertThat(adapter.findByBrand("GAP")).isEmpty();
    }

    /**
     * Upsert, not update: a brand's very first publish has no document to update, and
     * {@code updateFirst} would silently do nothing.
     */
    @Test
    void writesWithAnUpsertSoTheFirstPublishLands() {
        assertThat(adapter.save(new BrandFlags("gap", Map.of(JEWEL, true), Instant.EPOCH, "blt1")))
                .isTrue();

        org.mockito.ArgumentCaptor<Update> update = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).upsert(any(Query.class), update.capture(), eq(COLLECTION));
        // Read the Document directly rather than rendering it: the update legitimately carries an
        // Instant, which only Spring Data's converter turns into something the driver can encode, so
        // toJson() here would assert against a codec registry this test does not use.
        Document set = fields(update.getValue());
        assertThat(set.get("brand")).isEqualTo("GAP");
        assertThat(set.get("profile")).isEqualTo("QA");
        assertThat(set.get("flags")).isEqualTo(Map.of(JEWEL, true));
        assertThat(set.get("entryUid")).isEqualTo("blt1");
        assertThat(set.get("source")).isEqualTo("contentstack");
    }

    /** A seeded default is stamped distinctly from a CMS-authored write, so the two are never confused. */
    @Test
    void writesDefaultsWithTheSeedSourceInsteadOfContentstack() {
        assertThat(adapter.saveDefaults(new BrandFlags("gap", Map.of(JEWEL, true), Instant.EPOCH, null)))
                .isTrue();

        org.mockito.ArgumentCaptor<Update> update = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).upsert(any(Query.class), update.capture(), eq(COLLECTION));
        Document set = fields(update.getValue());
        assertThat(set.get("brand")).isEqualTo("GAP");
        assertThat(set.get("flags")).isEqualTo(Map.of(JEWEL, true));
        assertThat(set.get("source")).isEqualTo("default-seed");
    }

    @Test
    void reportsAFailedDefaultsWrite() {
        doThrow(new DataAccessResourceFailureException("no route to host"))
                .when(mongoTemplate).upsert(any(Query.class), any(Update.class), eq(COLLECTION));

        assertThat(adapter.saveDefaults(new BrandFlags("GAP", Map.of(JEWEL, true), Instant.EPOCH, null)))
                .isFalse();
    }

    /** The _id must never appear in the update body; Mongo rejects an update that touches it. */
    @Test
    void neverPutsTheIdInTheUpdateBody() {
        adapter.save(new BrandFlags("GAP", Map.of(JEWEL, true), Instant.EPOCH, "blt1"));

        org.mockito.ArgumentCaptor<Update> update = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).upsert(any(Query.class), update.capture(), eq(COLLECTION));
        assertThat(fields(update.getValue())).doesNotContainKey("_id");
    }

    @Test
    void reportsAFailedWrite() {
        doThrow(new DataAccessResourceFailureException("no route to host"))
                .when(mongoTemplate).upsert(any(Query.class), any(Update.class), eq(COLLECTION));

        assertThat(adapter.save(new BrandFlags("GAP", Map.of(JEWEL, true), Instant.EPOCH, "blt1")))
                .isFalse();
    }

    @Test
    void removesByTheSameId() {
        assertThat(adapter.delete("gap")).isTrue();

        org.mockito.ArgumentCaptor<Query> query = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).remove(query.capture(), eq(COLLECTION));
        assertThat(query.getValue().getQueryObject().toJson()).contains("GAP_QA");
    }

    @Test
    void reportsAFailedRemoval() {
        doThrow(new DataAccessResourceFailureException("no route to host"))
                .when(mongoTemplate).remove(any(Query.class), eq(COLLECTION));

        assertThat(adapter.delete("GAP")).isFalse();
    }

    /** A blank brand cannot form an id, so it is rejected before it reaches the store. */
    @Test
    void rejectsABlankBrandWithoutTouchingTheStore() {
        assertThat(adapter.findByBrand(null)).isEmpty();
        assertThat(adapter.findByBrand("  ")).isEmpty();
        assertThat(adapter.delete(null)).isFalse();
        assertThat(adapter.save(null)).isFalse();
        assertThat(adapter.save(new BrandFlags("  ", Map.of(), Instant.EPOCH, "blt1"))).isFalse();
        assertThat(adapter.saveDefaults(null)).isFalse();

        org.mockito.Mockito.verifyNoInteractions(mongoTemplate);
    }

    /** The {@code $set} block of an update, which is where every written field lands. */
    private static Document fields(Update update) {
        return update.getUpdateObject().get("$set", Document.class);
    }

    private static BrandFlagsDocument document(Map<String, Boolean> flags) {
        return document(flags, null);
    }

    private static BrandFlagsDocument document(Map<String, Boolean> flags, Map<String, Object> settings) {
        return new BrandFlagsDocument(
                "GAP_QA", "GAP", "QA", flags, "blt1", Instant.EPOCH, "contentstack", settings);
    }

    private static BrandFlagsProperties properties() {
        return new BrandFlagsProperties(
                true, "ms_clp_blp_flags", "es-mx", COLLECTION, Duration.ofMinutes(3), List.of(), "");
    }
}
