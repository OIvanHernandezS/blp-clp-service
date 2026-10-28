FROM us-docker.pkg.dev/sit-cicd-images-base/liverpool-openjdk-images-base/openjdk:21-slim

# Set working dir
WORKDIR /app

# --- OpenTelemetry Java Agent ---
# Zero-code instrumentation exporting to Dynatrace over OTLP — the same mechanism ms_pdp_search uses,
# but pinned to a current agent: PDP's v2.11.0 predates Spring Boot 4 / Spring Framework 7.
# The agent is the service's ONLY tracer (there is no Micrometer Tracing bridge on the classpath),
# and it is what puts trace_id/span_id in the MDC for GcpStructuredLogFormatter.
# Endpoint, Api-Token and sampler are OTEL_* env vars on the Cloud Run service, never baked in here.
ARG OTEL_AGENT_VERSION=2.31.1
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar

# Copy jar (built by the pipeline via `mvn package` before `docker build`)
COPY target/ms-clp-blp-*.jar app.jar

# Set environment profile (dev | qa | staging | prod — see src/main/resources/application-*.yaml)
ARG ENVIRONMENT
ENV ENVIRONMENT=$ENVIRONMENT

# Deploy-time build/version identifier, surfaced on every response as x-app-version
# (AppVersionHeaderFilter). Set by the deploy workflows from the git tag the operator selected when
# triggering the deploy — deploys are tag-only and enforced, so a real deploy always has a real value.
# pom.xml's own <version> stays the static placeholder it always has been; this is a separate signal.
ARG APP_VERSION=unknown
ENV APP_VERSION=$APP_VERSION

# Rule 5/9 — 8080 is the gateway-exposed API port, 8081 is the actuator management port
# (K8s liveness/readiness probes only, never exposed via the gateway). See docs/deployment.md.
EXPOSE 8080
EXPOSE 8081

# Run application. The `sh -c` form is required: $ENVIRONMENT/$APP_VERSION must be expanded at runtime.
ENTRYPOINT ["sh", "-c", "java -javaagent:/otel/opentelemetry-javaagent.jar -Dspring.profiles.active=$ENVIRONMENT -DappVersion=$APP_VERSION -jar app.jar"]
