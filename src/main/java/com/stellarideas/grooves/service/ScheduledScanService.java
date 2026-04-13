package com.stellarideas.grooves.service;

import com.stellarideas.grooves.dto.ScanResult;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class ScheduledScanService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledScanService.class);

    private final UserRepository userRepository;
    private final MusicScannerService musicScannerService;

    public ScheduledScanService(UserRepository userRepository, MusicScannerService musicScannerService) {
        this.userRepository = userRepository;
        this.musicScannerService = musicScannerService;
    }

    @Scheduled(fixedRate = 60000)
    public void checkScheduledScans() {
        List<User> users = userRepository.findAll();
        for (User user : users) {
            if (user.getScanSchedule() == null || user.getScanSchedule().isBlank()) {
                continue;
            }
            if (user.getScanPath() == null || user.getScanPath().isBlank()) {
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
                if (nextExecution != null && !nextExecution.isAfter(now)) {
                    logger.info("Running scheduled scan for user '{}' on path '{}'",
                            user.getUsername(), user.getScanPath());
                    ScanResult result = musicScannerService.scanDirectory(user, user.getScanPath());
                    user.setLastScheduledScan(Instant.now());
                    userRepository.save(user);
                    logger.info("Scheduled scan complete for user '{}': {} saved, {} skipped, {} errors",
                            user.getUsername(), result.getSaved(), result.getSkipped(), result.getErrors());
                }
            } catch (Exception e) {
                logger.error("Scheduled scan failed for user '{}': {}", user.getUsername(), e.getMessage());
            }
        }
    }
}
