package com.stellarideas.grooves.repository;

import com.stellarideas.grooves.model.Playlist;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PlaylistRepository extends MongoRepository<Playlist, String> {
    List<Playlist> findByUserId(String userId);
    Optional<Playlist> findByIdAndUserId(String id, String userId);
    long deleteByUserId(String userId);
    Optional<Playlist> findByShareToken(String shareToken);
}
