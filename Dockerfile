# Build stage — official multi-arch Maven + Temurin 17 image (amd64 + arm64).
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache Maven dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B -q 2>/dev/null || true

# Build the application (skip tests — they need MongoDB)
# Vendor frontend assets (Bootstrap, SockJS, STOMP) are committed under
# src/main/resources/static/vendor/ — no npm step needed in Docker build.
# To update vendor files locally: npm install && npm run copy-vendor
COPY src ./src
COPY dependency-check-suppressions.xml .
RUN mvn package -DskipTests -Ddependency-check.skip=true -q \
    && mv target/stellar-grooves-*.jar target/app.jar

# --- Runtime stage ---
# Temurin's Alpine images are x86-only; jammy is multi-arch (amd64 + arm64),
# so the image builds and runs on Apple Silicon / ARM hosts too.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system grooves \
    && useradd --system --gid grooves --no-create-home grooves
COPY --from=build /app/target/app.jar app.jar
RUN chown grooves:grooves app.jar

USER grooves

EXPOSE 8089

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -sf http://localhost:8089/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
