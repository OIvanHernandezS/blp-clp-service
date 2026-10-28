package com.liverpool.ms_clp_blp.landing.brandflags.adapters.mongo;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.landing.brandflags.AuthoredFlagsReader;
import com.liverpool.ms_clp_blp.landing.brandflags.BrandFlagsProperties;
import com.liverpool.ms_clp_blp.landing.brandflags.BrandFlagsStoreCondition;
import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlags;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsRepositoryPort;
import com.liverpool.ms_clp_blp.shared.config.RuntimeProfile;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * MongoDB-backed store of per-brand feature-flag overrides, keyed {@code <BRAND>_<PROFILE>}.
 *
 * <p><strong>This adapter never throws.</strong> Every operation is wrapped, because it sits on the
 * {@code GET /blp|clp/**} path: a page composed from the YAML defaults is a working page, while a page that
 * 500s because the flag store is unreachable is an outage this feature would have caused. Failures
 * become an empty {@link Optional} (reads) or {@code false} (writes) and one WARN line — the caller
 * cannot distinguish "no overrides for this brand" from "store is down", and deliberately so: it
 * would do the same thing either way.</p>
 *
 * <p>Numeric {@code settings} are read here but <strong>never written</strong>: they are edited by
 * hand in MongoDB, and every write below sets only the fields it owns, so neither a CMS publish nor
 * the startup seed can overwrite a value an operator set.</p>
 *
 * <p>Writes are upserts. {@code MongoTemplate#updateFirst} — which the equivalent service in
 * platform-commons uses — silently does nothing when the document does not exist yet, which would
 * mean a brand's very first publish never lands.</p>
 */
@Component
@ConditionalOnExpression(BrandFlagsStoreCondition.AVAILABLE)
public class BrandFlagsMongoAdapter implements BrandFlagsRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(BrandFlagsMongoAdapter.class);

    /** Log field: which store operation was attempted. */
    private static final String OPERATION = "storeOperation";

    /** Log field: the document id involved, which is the brand/profile pair. */
    private static final String DOCUMENT_ID = "documentId";

    private static final String UPSTREAM = "mongo";
    private static final String OP_FIND = "find";
    private static final String OP_SAVE = "save";
    private static final String OP_SAVE_DEFAULTS = "saveDefaults";
    private static final String OP_DELETE = "delete";

    private static final String FIELD_BRAND = "brand";
    private static final String FIELD_PROFILE = "profile";
    private static final String FIELD_FLAGS = "flags";
    private static final String FIELD_ENTRY_UID = "entryUid";
    private static final String FIELD_UPDATED_AT = "updatedAt";
    private static final String FIELD_SOURCE = "source";

    private final MongoTemplate mongoTemplate;
    private final BrandFlagsProperties properties;
    private final RuntimeProfile runtimeProfile;

    /**
     * @param mongoTemplate  template bound to the bounded client in {@code clients/mongo}
     * @param properties     supplies the collection name
     * @param runtimeProfile supplies the environment half of the document id
     */
    public BrandFlagsMongoAdapter(MongoTemplate mongoTemplate,
                                  BrandFlagsProperties properties,
                                  RuntimeProfile runtimeProfile) {
        this.mongoTemplate = mongoTemplate;
        this.properties = properties;
        this.runtimeProfile = runtimeProfile;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<BrandFlags> findByBrand(String brand) {
        if (brand == null || brand.isBlank()) {
            return Optional.empty();
        }
        String documentId = documentId(brand);
        try {
            BrandFlagsDocument document =
                    mongoTemplate.findOne(byId(documentId), BrandFlagsDocument.class, properties.collection());
            if (document == null) {
                return Optional.empty();
            }
            return Optional.of(new BrandFlags(
                    normalize(brand), document.flags(), document.updatedAt(), document.entryUid(),
                    AuthoredFlagsReader.readSettings(document.settings())));
        } catch (RuntimeException e) {
            logFailure(OP_FIND, documentId, e);
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean save(BrandFlags brandFlags) {
        return upsert(brandFlags, BrandFlagsDocument.SOURCE_CONTENTSTACK, OP_SAVE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean saveDefaults(BrandFlags brandFlags) {
        return upsert(brandFlags, BrandFlagsDocument.SOURCE_DEFAULT_SEED, OP_SAVE_DEFAULTS);
    }

    private boolean upsert(BrandFlags brandFlags, String source, String operation) {
        if (brandFlags == null || brandFlags.brand() == null || brandFlags.brand().isBlank()) {
            return false;
        }
        String documentId = documentId(brandFlags.brand());
        try {
            mongoTemplate.upsert(byId(documentId), updateFrom(brandFlags, source), properties.collection());
            return true;
        } catch (RuntimeException e) {
            logFailure(operation, documentId, e);
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean delete(String brand) {
        if (brand == null || brand.isBlank()) {
            return false;
        }
        String documentId = documentId(brand);
        try {
            mongoTemplate.remove(byId(documentId), properties.collection());
            return true;
        } catch (RuntimeException e) {
            logFailure(OP_DELETE, documentId, e);
            return false;
        }
    }

    private Update updateFrom(BrandFlags brandFlags, String source) {
        // Written field by field rather than as a whole document so the _id is never part of the
        // update — Mongo rejects an update that touches it, and the upsert supplies it from the query.
        // It is also why "settings" survives every write: it is simply never named here.
        Update update = new Update();
        update.set(FIELD_BRAND, normalize(brandFlags.brand()));
        update.set(FIELD_PROFILE, runtimeProfile.name());
        update.set(FIELD_FLAGS, new LinkedHashMap<>(brandFlags.flags()));
        update.set(FIELD_ENTRY_UID, brandFlags.entryUid());
        update.set(FIELD_UPDATED_AT,
                brandFlags.updatedAt() == null ? Instant.now() : brandFlags.updatedAt());
        update.set(FIELD_SOURCE, source);
        return update;
    }

    private Query byId(String documentId) {
        return new Query(Criteria.where(BrandFlagsDocument.FIELD_ID).is(documentId));
    }

    private String documentId(String brand) {
        return BrandFlagsDocument.idFor(normalize(brand), runtimeProfile.name());
    }

    private static String normalize(String brand) {
        return brand.trim().toUpperCase(Locale.ROOT);
    }

    private void logFailure(String operation, String documentId, RuntimeException e) {
        LogEvents.at(log, LogEvents.BRAND_FLAGS_STORE_UNAVAILABLE)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(OPERATION, operation)
                .addKeyValue(DOCUMENT_ID, documentId)
                .addKeyValue(LogFields.ERROR_CLASS, e.getClass().getSimpleName())
                .setCause(e)
                .log("Per-brand feature flag store is unreachable; falling back to configured defaults");
    }
}
