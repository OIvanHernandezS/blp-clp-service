package com.liverpool.ms_clp_blp.clients.mongo;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed connection settings for the MongoDB instance holding per-brand feature-flag configuration.
 *
 * <p>The prefix is deliberately {@code mongo} and not {@code spring.data.mongodb}: this project binds
 * its own {@code MongoClient} (see {@link MongoConfig}) rather than inheriting Boot's defaults,
 * because the defaults are tuned for a service whose requests may wait on the database. Here the
 * read sits on the {@code GET /blp|clp/**} path and must fail fast instead — an unreachable Mongo degrades
 * the flags to their YAML values, it never holds up a page.</p>
 *
 * @param uri                    connection string (env var only — no committed default)
 * @param socketTimeout          per-socket connect and read timeout
 * @param serverSelectionTimeout how long a call waits for a reachable node before giving up; this is
 *                               the one that bounds the request-path cost when Mongo is down
 * @param maxConnectionIdleTime  how long a pooled connection may sit idle before being discarded
 */
@ConfigurationProperties(prefix = "mongo")
public record MongoProperties(
        String uri,
        Duration socketTimeout,
        Duration serverSelectionTimeout,
        Duration maxConnectionIdleTime) {
}
