package com.stellarideas.grooves.repository;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.mongodb.repository.Query;

public interface MusicFileRepository extends MongoRepository<MusicFile, String>, MusicFileRepositoryCustom {
    List<MusicFile> findByUserIdAndDeletedFalse(String userId);
    Page<MusicFile> findByUserIdAndDeletedFalse(String userId, Pageable pageable);
    Page<MusicFile> findByUserIdAndGenreAndDeletedFalse(String userId, Genre genre, Pageable pageable);
    boolean existsByFilePathAndUserId(String filePath, String userId);
    boolean existsByTitleAndArtistAndUserId(String title, String artist, String userId);
    Optional<MusicFile> findByIdAndUserIdAndDeletedFalse(String id, String userId);
    List<MusicFile> findByIdInAndUserId(List<String> ids, String userId);
    long deleteByUserId(String userId);
    long countByUserId(String userId);
    List<MusicFile> findByUserIdAndFilePathIn(String userId, Set<String> filePaths);
    List<MusicFile> findByUserIdAndArtistAndAlbumAndDeletedFalse(String userId, String artist, String album);

    @Aggregation(pipeline = {
        "{ '$match': { 'userId': { '$in': ?0 } } }",
        "{ '$group': { '_id': '$userId', 'count': { '$sum': 1 } } }"
    })
    List<org.bson.Document> countByUserIdIn(java.util.Collection<String> userIds);

    // Keep the original methods for internal use (scanning, etc.)
    List<MusicFile> findByUserId(String userId);
    Optional<MusicFile> findByIdAndUserId(String id, String userId);

    List<MusicFile> findByUserIdAndDeletedTrue(String userId);
    Page<MusicFile> findByUserIdAndDeletedTrue(String userId, Pageable pageable);

    @org.springframework.data.mongodb.repository.Meta(maxExecutionTimeMs = 5000)
    @Query("{ 'userId': ?0, 'deleted': false, '$or': [ " +
           "{ 'title': { '$regex': ?1, '$options': 'i' } }, " +
           "{ 'artist': { '$regex': ?1, '$options': 'i' } }, " +
           "{ 'album': { '$regex': ?1, '$options': 'i' } } ] }")
    Page<MusicFile> searchByUserIdAndQuery(String userId, String query, Pageable pageable);
}
