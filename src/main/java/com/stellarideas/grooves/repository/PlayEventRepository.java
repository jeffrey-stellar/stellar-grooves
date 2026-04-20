package com.stellarideas.grooves.repository;

import com.stellarideas.grooves.model.PlayEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PlayEventRepository extends MongoRepository<PlayEvent, String> {
    Page<PlayEvent> findByUserIdOrderByPlayedAtDesc(String userId, Pageable pageable);
    long deleteByUserId(String userId);
    long deleteByUserIdAndMusicFileId(String userId, String musicFileId);
}
