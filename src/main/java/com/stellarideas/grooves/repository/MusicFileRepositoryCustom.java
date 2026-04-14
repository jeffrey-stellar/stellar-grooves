package com.stellarideas.grooves.repository;

import java.util.List;
import java.util.Map;

public interface MusicFileRepositoryCustom {
    /**
     * Find duplicate tracks (same title+artist, case-insensitive) for a user using
     * a MongoDB aggregation pipeline. Returns only groups with 2+ entries.
     */
    List<Map<String, Object>> findDuplicatesByUserId(String userId);

    /**
     * Paginated version of duplicate detection.
     *
     * @param userId the user's ID
     * @param skip   number of duplicate groups to skip
     * @param limit  max number of duplicate groups to return
     * @return map with "duplicates" list and "total" count
     */
    Map<String, Object> findDuplicatesByUserId(String userId, int skip, int limit);

    /**
     * Full-text search using MongoDB text index with relevance scoring.
     * Falls back to regex search if the text index is unavailable.
     */
    org.springframework.data.domain.Page<com.stellarideas.grooves.model.MusicFile> textSearch(
            String userId, String query, org.springframework.data.domain.Pageable pageable);

    /**
     * Find duplicate files by SHA-256 hash for a user.
     * Returns groups of files that share the same fileHash.
     */
    Map<String, Object> findHashDuplicatesByUserId(String userId, int skip, int limit);

    /**
     * Aggregate library statistics: genre distribution, top artists, decade distribution.
     */
    Map<String, Object> getStatistics(String userId);

    /**
     * Search with optional filters for genre, artist, year, and file format.
     * Combines text/regex search with additional criteria.
     */
    org.springframework.data.domain.Page<com.stellarideas.grooves.model.MusicFile> filteredSearch(
            String userId, String query,
            com.stellarideas.grooves.model.Genre genre, String artist, String year, String fileExtension,
            org.springframework.data.domain.Pageable pageable);
}
