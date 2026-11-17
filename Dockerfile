# ==============================================================================
# DeltaFlow Backend Service - Multi-Stage Production Dockerfile
# ==============================================================================

# --- Stage 1: Build Stage ---
FROM maven:3.9.9-eclipse-temurin-21-jammy AS builder
WORKDIR /build

# Cache dependency layer
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Copy source and build executable JAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# --- Stage 2: Minimal Runtime Stage ---
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Install minimal operational utilities for health checks
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Run as non-root user for security compliance
RUN groupadd -r deltaflow && useradd -r -g deltaflow deltaflow
USER deltaflow:deltaflow

# Copy repackaged executable jar
COPY --from=builder --chown=deltaflow:deltaflow /build/target/delta-flow-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

# Java 21 preview features required for Virtual Threads & StructuredTaskScope
ENV JAVA_TOOL_OPTIONS="--enable-preview -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
