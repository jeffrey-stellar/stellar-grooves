package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.PlayEvent;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.PlayEventRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class PlayHistoryService {

    private final PlayEventRepository playEventRepository;
    private final MusicFileRepository musicFileRepository;
    private final MongoTemplate mongoTemplate;

    public PlayHistoryService(PlayEventRepository playEventRepository,
                              MusicFileRepository musicFileRepository,
                              MongoTemplate mongoTemplate) {
        this.playEventRepository = playEventRepository;
        this.musicFileRepository = musicFileRepository;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Record a play event for a track the user owns. Inserts a PlayEvent row and
     * atomically increments playCount + sets lastPlayedAt on the MusicFile.
     *
     * @return true if recorded, false if the file doesn't exist or isn't owned by the user
     */
    public boolean recordPlay(String userId, String musicFileId, int listenedMs, boolean completed) {
        if (musicFileRepository.findByIdAndUserIdAndDeletedFalse(musicFileId, userId).isEmpty()) {
            return false;
        }
        Instant now = Instant.now();
        playEventRepository.save(new PlayEvent(userId, musicFileId, now, Math.max(0, listenedMs), completed));

        Query query = new Query(Criteria.where("_id").is(musicFileId).and("userId").is(userId));
        Update update = new Update().inc("playCount", 1).set("lastPlayedAt", now);
        mongoTemplate.updateFirst(query, update, MusicFile.class);
        return true;
    }
}
