package com.stellarideas.grooves.service;

import com.stellarideas.grooves.dto.ScanResult;
import com.stellarideas.grooves.model.ScanJob;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

@Service
public class ScheduledScanService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledScanService.class);

    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final long BASE_BACKOFF_MINUTES = 5;

    private final Set<String> activeScans = ConcurrentHashMap.newKeySet();

    /** Tracks consecutive failure count per user for exponential backoff. */
    private final ConcurrentHashMap<String, Integer> failureCounts = new ConcurrentHashMap<>();
    /** Tracks when a user's backoff period expires. */
    private final ConcurrentHashMap<String, Instant> backoffUntil = new ConcurrentHashMap<>();

    private final UserRepository userRepository;
    private final MusicScannerService musicScannerService;
    private final TaskExecutor scanTaskExecutor;

    public ScheduledScanService(UserRepository userRepository,
                                MusicScannerService musicScannerService,
                                @Qualifier("scanTaskExecutor") TaskExecutor scanTaskExecutor) {
        this.userRepository = userRepository;
        this.musicScannerService = musicScannerService;
        this.scanTaskExecutor = scanTaskExecutor;
    }

    @Scheduled(fixedRate = 60000)
    public void checkScheduledScans() {
        // Only load users who have a scan schedule configured
        var users = userRepository.findByScanScheduleNotNull();
        for (User user : users) {
            if (user.getScanSchedule().isBlank()) {
                continue;
            }
            if (user.getScanPath() == null || user.getScanPath().isBlank()) {
                continue;
            }

            // Check if user is in backoff period
            Instant backoff = backoffUntil.get(user.getId());
            if (backoff != null && Instant.now().isBefore(backoff)) {
                continue;
            }

            try {
                CronExpression cron = CronExpression.parse(user.getScanSchedule());
                LocalDateTime now = LocalDateTime.now();
                Instant lastScan = user.getLastScheduledScan();
                LocalDateTime lastScanTime = lastScan != null
                        ? LocalDateTime.ofInstant(lastScan, ZoneId.systemDefault())
                        : now.minusYears(1);

                LocalDateTime nextExecution = cron.next(lastScanTime);
                if (nextExecution == null || nextExecution.isAfter(now)) {
                    continue;
                }
                if (!activeScans.add(user.getId())) {
                    logger.info("Skipping scheduled scan for user '{}' — scan already in progress",
                            user.getUsername());
                    continue;
                }
                try {
                    scanTaskExecutor.execute(() -> runScheduledScan(user));
                } catch (RejectedExecutionException e) {
                    activeScans.remove(user.getId());
                    logger.warn("Scheduled scan for user '{}' rejected by scan executor (pool full); will retry next tick",
                            user.getUsername());
                }
            } catch (Exception e) {
                recordFailure(user, e);
            }
        }
    }

    private void runScheduledScan(User user) {
        try {
            MDC.put("correlationId", UUID.randomUUID().toString());
            MDC.put("audit.user", user.getUsername());
            MDC.put("scanType", "scheduled");
            logger.info("Running scheduled scan for user '{}' on path '{}'",
                    user.getUsername(), user.getScanPath());
            ScanResult result = musicScannerService.scanDirectorySync(user, user.getScanPath(), ScanJob.Type.SCHEDULED);
            user.setLastScheduledScan(Instant.now());
            userRepository.save(user);
            failureCounts.remove(user.getId());
            backoffUntil.remove(user.getId());
            logger.info("Scheduled scan complete for user '{}': {} saved, {} skipped, {} errors",
                    user.getUsername(), result.getSaved(), result.getSkipped(), result.getErrors());
        } catch (Exception e) {
            recordFailure(user, e);
        } finally {
            activeScans.remove(user.getId());
            MDC.clear();
        }
    }

    private void recordFailure(User user, Exception e) {
        int failures = failureCounts.merge(user.getId(), 1, Integer::sum);
        long backoffMinutes = BASE_BACKOFF_MINUTES * (1L << Math.min(failures - 1, MAX_CONSECUTIVE_FAILURES - 1));
        backoffUntil.put(user.getId(), Instant.now().plusSeconds(backoffMinutes * 60));
        logger.error("Scheduled scan failed for user '{}' (failure #{}, next retry in {} min): {}",
                user.getUsername(), failures, backoffMinutes, e.getMessage(), e);
    }
}
