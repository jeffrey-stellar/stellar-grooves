package com.stellarideas.grooves.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests that require a real MongoDB instance.
 *
 * <p>Uses the Testcontainers <strong>singleton container</strong> pattern: a
 * single MongoDB container is started once in a static initializer and shared by
 * every integration test class for the whole JVM run; Ryuk reaps it at JVM exit.
 *
 * <p>We deliberately do <em>not</em> annotate the container with {@code @Container}.
 * That would tie the container to the per-class start/stop lifecycle, starting a
 * fresh container (on a new port) for each test class. But Spring caches one
 * application context across all these classes (identical configuration), and
 * {@link DynamicPropertySource} binds the Mongo URI when that context is first
 * created — so every class after the first would reuse a context pointing at an
 * already-stopped container and fail with "connection refused". The singleton
 * keeps one container alive for the entire run, so the cached context stays valid.
 *
 * <p>{@code @Testcontainers(disabledWithoutDocker = true)} is kept only for its
 * skip-without-Docker behaviour (there are no {@code @Container} fields for it to
 * manage); the static start is guarded the same way so class initialization never
 * fails on a machine without Docker.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
public abstract class BaseIntegrationTest {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            MONGO.start();
        }
    }

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        // Provide a dummy JWT secret for the app context to start
        registry.add("stellar.grooves.jwtSecret", () ->
                "dGVzdC1zZWNyZXQta2V5LWZvci1pbnRlZ3JhdGlvbi10ZXN0cy1vbmx5LXBsZWFzZS1jaGFuZ2U=");
    }
}
