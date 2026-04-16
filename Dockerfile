FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# Cache Maven dependencies
COPY pom.xml .
RUN apk add --no-cache maven \
    && mvn dependency:go-offline -B -q 2>/dev/null || true

# Build the application (skip tests — they need MongoDB)
# Vendor frontend assets (Bootstrap, SockJS, STOMP) are committed under
# src/main/resources/static/vendor/ — no npm step needed in Docker build.
# To update vendor files locally: npm install && npm run copy-vendor
COPY src ./src
COPY dependency-check-suppressions.xml .
RUN mvn package -DskipTests -Ddependency-check.skip=true -q \
    && mv target/stellar-grooves-*.jar target/app.jar

# --- Runtime stage ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN apk add --no-cache curl \
    && addgroup -S grooves && adduser -S grooves -G grooves
COPY --from=build /app/target/app.jar app.jar
RUN chown grooves:grooves app.jar

USER grooves

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -sf http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
