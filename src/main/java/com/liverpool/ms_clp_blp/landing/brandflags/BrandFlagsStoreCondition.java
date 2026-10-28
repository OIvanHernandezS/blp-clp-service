package com.liverpool.ms_clp_blp.landing.brandflags;

/**
 * The single condition deciding whether the per-brand flag store is wired at all.
 *
 * <p>It exists because the first version of this feature made the whole application refuse to start
 * when {@code MONGO_URI} was unset: the driver rejects a blank connection string, that failed the
 * {@code MongoClient} bean, and the failure cascaded all the way to {@code LandingUseCases}. A feature
 * whose entire design is "an unreachable flag store degrades to the YAML defaults" must not take the
 * service down because the store was never configured in the first place.</p>
 *
 * <p>So the repository is conditional and exactly one branch always wins: with a connection string and
 * the feature switched on, the Mongo-backed adapter; otherwise
 * {@code UnavailableBrandFlagsRepository}, which answers "no overrides" to everything. Nothing
 * downstream can tell the two apart, which is the point — the behaviour is identical either way.</p>
 */
public final class BrandFlagsStoreCondition {

    /** Wire the real store: a connection string is present and the feature is switched on. */
    public static final String AVAILABLE =
            "'${mongo.uri:}'.trim() != '' and ${brand-flags.enabled:true}";

    /** Wire the inert stand-in. Deliberately the exact negation, so neither branch can be skipped. */
    public static final String UNAVAILABLE =
            "!('${mongo.uri:}'.trim() != '' and ${brand-flags.enabled:true})";

    private BrandFlagsStoreCondition() {
    }
}
