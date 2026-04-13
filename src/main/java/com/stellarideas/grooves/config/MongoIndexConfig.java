package com.stellarideas.grooves.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.TextIndexDefinition;
import org.springframework.stereotype.Component;

/**
 * Creates MongoDB indexes that can't be expressed via annotations,
 * such as the text index for full-text search.
 */
@Component
public class MongoIndexConfig {

    private final MongoTemplate mongoTemplate;

    public MongoIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        TextIndexDefinition textIndex = new TextIndexDefinition.TextIndexDefinitionBuilder()
                .onField("title", 3F)    // title gets highest weight
                .onField("artist", 2F)   // artist gets medium weight
                .onField("album", 1F)    // album gets base weight
                .named("text_search")
                .build();

        mongoTemplate.indexOps("music_files").ensureIndex(textIndex);
    }
}
