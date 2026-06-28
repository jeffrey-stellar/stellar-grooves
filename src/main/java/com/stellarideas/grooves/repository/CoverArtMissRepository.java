package com.stellarideas.grooves.repository;

import com.stellarideas.grooves.model.CoverArtMiss;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CoverArtMissRepository extends MongoRepository<CoverArtMiss, String> {
    Optional<CoverArtMiss> findByUserIdAndArtistAndAlbum(String userId, String artist, String album);
    long deleteByUserIdAndArtistAndAlbum(String userId, String artist, String album);
    long deleteByUserId(String userId);
}
