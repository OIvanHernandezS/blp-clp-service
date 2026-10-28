package com.liverpool.ms_clp_blp.clients.mongo;

import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

/**
 * Builds the MongoDB client explicitly instead of letting Boot auto-configure it, so the timeouts are
 * ours (Rule 6 — every external call is bounded).
 *
 * <p>This matters more than usual here: the per-brand flag read happens on the {@code GET /blp|clp/**} path
 * whenever an instance's cache entry expires. Boot's defaults would let a partitioned cluster hold a
 * request for ~30s of server selection; the values in {@link MongoProperties} cap it at a couple of
 * seconds, after which the flags fall back to their YAML values and the page is served anyway.</p>
 *
 * <p>Mirrors the shape already running in {@code platform-commons}' {@code MongoConfig}, which cannot
 * be reused directly: it lives under {@code com.liverpool.config}, and {@code MsClpBlpApplication}
 * deliberately restricts component scanning to {@code com.liverpool.ms_clp_blp}.</p>
 *
 * <p>Conditional on a connection string being present. Without one the driver rejects the blank
 * string and the failed bean cascades into every consumer, taking the whole service down — which is
 * the opposite of the degradation this feature is built around. No URI simply means no client, and
 * the flag store falls back to its inert stand-in.</p>
 */
@Configuration
@ConditionalOnExpression("'${mongo.uri:}'.trim() != ''")
public class MongoConfig {

    /** Used when the connection string names no database, so the factory always has a target. */
    static final String FALLBACK_DATABASE = "ms_home";

    private final MongoProperties properties;

    /**
     * @param properties connection string and timeouts bound from {@code mongo.*}
     */
    public MongoConfig(MongoProperties properties) {
        this.properties = properties;
    }

    /**
     * @return a client whose every wait is bounded by {@link MongoProperties}
     */
    @Bean
    public MongoClient mongoClient() {
        ConnectionString connectionString = new ConnectionString(properties.uri());
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToSocketSettings(builder -> builder
                        .connectTimeout(properties.socketTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .readTimeout(properties.socketTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .applyToClusterSettings(builder -> builder
                        .serverSelectionTimeout(
                                properties.serverSelectionTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .applyToConnectionPoolSettings(builder -> builder
                        .maxConnectionIdleTime(
                                properties.maxConnectionIdleTime().toMillis(), TimeUnit.MILLISECONDS))
                .build();
        return MongoClients.create(settings);
    }

    /**
     * @param mongoClient the bounded client above
     * @return factory targeting the database named in the connection string
     */
    @Bean
    public MongoDatabaseFactory mongoDatabaseFactory(MongoClient mongoClient) {
        return new SimpleMongoClientDatabaseFactory(mongoClient, databaseName());
    }

    /**
     * @param mongoDatabaseFactory the factory above
     * @return the template used by the per-brand flag repository
     */
    @Bean
    public MongoTemplate mongoTemplate(MongoDatabaseFactory mongoDatabaseFactory) {
        return new MongoTemplate(mongoDatabaseFactory);
    }

    private String databaseName() {
        String database = new ConnectionString(properties.uri()).getDatabase();
        return database == null || database.isBlank() ? FALLBACK_DATABASE : database;
    }
}
