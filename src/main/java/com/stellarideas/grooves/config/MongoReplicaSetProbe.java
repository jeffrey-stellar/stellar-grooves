package com.stellarideas.grooves.config;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Probes the connected MongoDB instance at startup to detect whether it is
 * running as a replica set (or mongos in front of one).
 *
 * <p>Without a replica set, Spring Data MongoDB will accept {@code @Transactional}
 * annotations without error, but the underlying driver does not start a session
 * — multi-document operations are <em>not</em> atomic. Operations annotated in
 * this codebase that depend on this for full atomicity:
 *
 * <ul>
 *   <li>{@code LibraryService.bulkDelete} and {@code clearLibrary} (track + cover-art + queue)</li>
 *   <li>{@code AdminController.deleteUser} (user record + files + playlists + cover art + play events)</li>
 * </ul>
 *
 * <p>On a single-instance deployment a partial failure mid-operation can leave
 * orphaned records. The operations themselves are idempotent (re-running the
 * delete cleans up leftovers), so this is best-effort rather than data-corrupting.
 *
 * <p>Disable the probe with
 * {@code stellar.grooves.mongo.replicaSetProbe.enabled=false}
 * (e.g. on local dev where you don't want the warning noise).
 */
@Component
@ConditionalOnProperty(
        name = "stellar.grooves.mongo.replicaSetProbe.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MongoReplicaSetProbe implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(MongoReplicaSetProbe.class);

    private final MongoTemplate mongoTemplate;

    public MongoReplicaSetProbe(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Document result = mongoTemplate.getDb().runCommand(new Document("hello", 1));
            String setName = result.getString("setName");
            String msg = result.getString("msg");
            boolean isMongos = "isdbgrid".equals(msg);
            if (setName != null) {
                logger.info("MongoDB replica set '{}' detected; multi-document transactions enabled.", setName);
            } else if (isMongos) {
                logger.info("MongoDB sharded cluster (mongos) detected; multi-document transactions enabled.");
            } else {
                logger.warn(
                        "MongoDB is not configured as a replica set. @Transactional methods "
                                + "(bulk delete, admin user delete cascade) execute without a session and are "
                                + "NOT atomic — a partial failure can leave orphaned records. Re-running the "
                                + "operation is idempotent. To enable transactions, run MongoDB as a replica set.");
            }
        } catch (Exception e) {
            // Probe failures are non-fatal: log and continue. The application still functions.
            logger.warn("Unable to probe MongoDB replica set status: {}", e.getMessage());
        }
    }
}
