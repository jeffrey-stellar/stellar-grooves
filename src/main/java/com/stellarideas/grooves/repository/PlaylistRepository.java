package com.stellarideas.grooves.repository;

import com.stellarideas.grooves.model.Playlist;
import com.stellarideas.grooves.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PlaylistRepository extends MongoRepository<Playlist, String> {
    List<Playlist> findByUser(User user);
    Optional<Playlist> findByIdAndUser(String id, User user);
    void deleteByUser(User user);
}
