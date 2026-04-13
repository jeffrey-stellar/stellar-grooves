package com.stellarideas.grooves.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests that require a real MongoDB instance.
 * Starts a MongoDB container via Testcontainers and wires the connection URI
 * into the Spring context automatically.
 *
 * <p>Extend this class for any test that needs to validate real queries,
 * indexes, or aggregation pipelines against MongoDB.</p>
 */
@SpringBootTest
@Testcontainers
public abstract class BaseIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        // Provide a dummy JWT secret for the app context to start
        registry.add("stellar.grooves.jwtSecret", () ->
                "dGVzdC1zZWNyZXQta2V5LWZvci1pbnRlZ3JhdGlvbi10ZXN0cy1vbmx5LXBsZWFzZS1jaGFuZ2U=");
    }
}
