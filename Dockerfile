# ============================================================
# Multi-stage Dockerfile for mom-starter-api (Java 21 / Spring Boot)
# Stage 1 — Build with Maven
# Stage 2 — Lean JRE runtime, non-root user
# ============================================================

# ── Stage 1: Build ──────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Copy Maven wrapper first so dependency download layer is cached independently
COPY mvnw mvnw.cmd mvnwDebug mvnwDebug.cmd ./
COPY .mvn .mvn
COPY pom.xml ./

# Pre-fetch all dependencies (cached unless pom.xml changes)
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B -q

# Copy source and build (skip tests — tests run in CI, not in Docker build)
COPY src ./src
RUN ./mvnw package -DskipTests -B -q

# ── Stage 2: Runtime ────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime

# Install curl for HEALTHCHECK (not in base JRE image)
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create a dedicated non-root user (UID/GID 1001)
RUN addgroup --system --gid 1001 appgroup \
    && adduser --system --uid 1001 --gid 1001 --no-create-home --ingroup appgroup appuser

WORKDIR /app

COPY --from=builder /workspace/target/*.jar app.jar
RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

# Probe /v1/actuator/health (context-path /v1 + management base-path /actuator)
# PROD NOTE: switch to management port (e.g. 8081) when management.server.port is separated.
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -sf http://localhost:8080/v1/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]
