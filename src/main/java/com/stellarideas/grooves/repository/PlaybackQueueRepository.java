package com.stellarideas.grooves.repository;

import com.stellarideas.grooves.model.PlaybackQueue;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface PlaybackQueueRepository extends MongoRepository<PlaybackQueue, String> {
    Optional<PlaybackQueue> findByUserId(String userId);
    long deleteByUserId(String userId);
}
