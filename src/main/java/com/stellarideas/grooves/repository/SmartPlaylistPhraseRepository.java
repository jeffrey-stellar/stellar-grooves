package com.stellarideas.grooves.repository;

import com.stellarideas.grooves.model.SmartPlaylistPhrase;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SmartPlaylistPhraseRepository extends MongoRepository<SmartPlaylistPhrase, String> {
    List<SmartPlaylistPhrase> findByUserIdOrderByNameAsc(String userId);
    Optional<SmartPlaylistPhrase> findByIdAndUserId(String id, String userId);
    Optional<SmartPlaylistPhrase> findByUserIdAndName(String userId, String name);
    boolean existsByUserIdAndName(String userId, String name);
    long deleteByUserId(String userId);
}
