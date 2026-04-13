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
     * Aggregate library statistics: genre distribution, top artists, decade distribution.
     */
    Map<String, Object> getStatistics(String userId);
}
