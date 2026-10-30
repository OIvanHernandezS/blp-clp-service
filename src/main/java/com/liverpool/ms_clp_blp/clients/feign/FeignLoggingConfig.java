package com.liverpool.ms_clp_blp.clients.feign;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.liverpool.ms_clp_blp.clients.OutboundLoggingProperties;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * Global Feign logging.
 *
 * <p>Unlike the per-client {@code *FeignConfig} classes — which are deliberately left un-annotated so
 * their beans stay inside Feign's per-client child context — this one <em>is</em> a
 * {@code @Configuration}. That is intentional: a request trace you have to enable per client is
 * useless during an incident, so these beans are global and every {@code @FeignClient} inherits them.
 *
 * <h3>Reading the trace</h3>
 * <p>Visibility is the log level, not a flag. Failed calls are WARN and always visible; the
 * {@code curl} for each request and the line for each response are DEBUG, reachable with
 * {@code LOG_LEVEL_APP=DEBUG}. {@code FEIGN_LOG_ENABLED} adds <em>bodies</em> on top of that — see
 * {@link FeignOutboundLogger} for exactly what lands at each level, and
 * {@link OutboundLoggingProperties} for what a body may expose.
 *
 * <p>There is no {@code RequestInterceptor} here any more. One used to log the request line and a
 * line per header at INFO, duplicating what Feign's own logger already emitted and, between them,
 * producing about thirty lines per call. {@link FeignOutboundLogger#logRequest} renders the same
 * information as one {@code curl}, and it is the only place that can also see the response.
 */
@Configuration
public class FeignLoggingConfig {

	private static final Logger log = LoggerFactory.getLogger(FeignLoggingConfig.class);

	/**
	 * Selects how much Feign's built-in logger emits.
	 *
	 * <p>Always {@code BASIC}, and deliberately never {@code NONE}: Feign skips the
	 * {@code logRequest}/{@code logAndRebufferResponse} hooks entirely at {@code NONE}, and those
	 * hooks are where {@link FeignOutboundLogger} gets the elapsed time — the only measurement of
	 * upstream latency this service has. It is not {@code FULL} because that level only changes what
	 * the <em>base class</em> would print, and every one of its printing hooks is overridden.
	 *
	 * @return {@code BASIC}
	 */
	@Bean
	public feign.Logger.Level feignLoggerLevel() {
		return feign.Logger.Level.BASIC;
	}

	/**
	 * Replaces Feign's default {@code Slf4jLogger} for every client.
	 *
	 * <p>Spring Cloud's {@code DefaultFeignLoggerFactory} uses this bean when one is present and falls
	 * back to {@code Slf4jLogger} otherwise. See {@link FeignOutboundLogger} for why the default does
	 * not work here.
	 *
	 * @param properties outbound logging settings, read per call so bodies can be switched on without
	 *                   rebuilding the bean; also supplies the outcomes that must not be treated as
	 *                   failures
	 * @return the shared Feign logger
	 */
	@Bean
	public feign.Logger feignLogger(OutboundLoggingProperties properties) {
		announceExpectedFailures(properties);
		return new FeignOutboundLogger(properties);
	}

	/**
	 * Says once, at boot, which outcomes this instance will not call failures.
	 *
	 * <p>A suppression list nobody can see is how errors get lost quietly: an environment with a
	 * mistyped or over-broad {@code OUTBOUND_EXPECTED_FAILURES} would look exactly like an
	 * environment where nothing is going wrong. One INFO line at startup makes the configuration a
	 * fact an incident can read instead of something it has to infer.</p>
	 *
	 * <p>Logged from this configuration rather than from the logger itself so it happens once per
	 * instance, never once per call.</p>
	 *
	 * @param properties the configuration to announce
	 */
	private void announceExpectedFailures(OutboundLoggingProperties properties) {
		LogEvents.at(log, LogEvents.OUTBOUND_EXPECTED_FAILURES_LOADED)
				.addKeyValue(LogFields.EXPECTED_FAILURES, properties.expectedFailures())
				.log("Outbound outcomes that will not be logged as failures");
	}
}
