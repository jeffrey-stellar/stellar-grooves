package com.stellarideas.grooves.config;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * One-shot backfill: stamps existing {@code music_files} with
 * {@code sourceType: "local"}.
 *
 * <p>The {@code sourceType} field was added when object-storage support landed;
 * documents scanned before then have no such field. Reads default to "local"
 * anyway, but backfilling makes the stored data explicit so future queries on
 * {@code sourceType} are reliable. Idempotent — the filter only matches
 * documents missing the field.</p>
 */
@Component
public class MongoSourceTypeMigration {

    private static final Logger log = LoggerFactory.getLogger(MongoSourceTypeMigration.class);

    private final MongoTemplate mongoTemplate;

    public MongoSourceTypeMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillSourceType() {
        MongoCollection<Document> collection = mongoTemplate.getCollection("music_files");
        Bson filter = Filters.exists("sourceType", false);
        long total = collection.countDocuments(filter);
        if (total == 0) {
            return;
        }
        log.info("Backfilling sourceType=local on {} music_files documents", total);
        collection.updateMany(filter, Updates.set("sourceType", "local"));
        log.info("sourceType backfill complete");
    }
}
