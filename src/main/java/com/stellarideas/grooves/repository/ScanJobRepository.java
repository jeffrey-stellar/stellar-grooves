package com.stellarideas.grooves.repository;

import com.stellarideas.grooves.model.ScanJob;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ScanJobRepository extends MongoRepository<ScanJob, String> {

    Optional<ScanJob> findTopByUserIdOrderByStartedAtDesc(String userId);

    /**
     * Return the currently-active scan for this user, if any. "Active" means either
     * QUEUED or RUNNING. There should be at most one; the sort key is a tiebreaker.
     */
    Optional<ScanJob> findTopByUserIdAndStatusInOrderByStartedAtDesc(
            String userId, List<ScanJob.Status> statuses);

    long countByUserIdAndStatusIn(String userId, List<ScanJob.Status> statuses);
}
