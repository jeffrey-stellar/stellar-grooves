package com.stellarideas.grooves.repository;

import com.stellarideas.grooves.model.SmartPlaylist;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SmartPlaylistRepository extends MongoRepository<SmartPlaylist, String> {
    List<SmartPlaylist> findByUserIdOrderByNameAsc(String userId);
    Optional<SmartPlaylist> findByIdAndUserId(String id, String userId);
    long deleteByUserId(String userId);
    boolean existsByUserIdAndName(String userId, String name);
}
