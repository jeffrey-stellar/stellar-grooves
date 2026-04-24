package com.stellarideas.grooves.service;

import com.stellarideas.grooves.dto.ScanResult;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduledScanServiceTest {

    private ScheduledScanService service;
    private UserRepository userRepository;
    private MusicScannerService musicScannerService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        musicScannerService = mock(MusicScannerService.class);
        service = new ScheduledScanService(userRepository, musicScannerService, new SyncTaskExecutor());
    }

    @Test
    void skipsWhenNoUsersHaveSchedule() throws Exception {
        when(userRepository.findByScanScheduleNotNull()).thenReturn(List.of());

        service.checkScheduledScans();

        verify(musicScannerService, never()).scanDirectorySync(any(), anyString(), any());
    }

    @Test
    void skipsUsersWithNoScanPath() throws Exception {
        User user = User.builder().id("u1").username("user1")
                .scanSchedule("0 0 * * * *").build();
        when(userRepository.findByScanScheduleNotNull()).thenReturn(List.of(user));

        service.checkScheduledScans();

        verify(musicScannerService, never()).scanDirectorySync(any(), anyString(), any());
    }

    @Test
    void triggersScheduledScanWhenDue() throws Exception {
        User user = User.builder().id("u1").username("user1")
                .scanSchedule("* * * * * *") // every second — always due
                .scanPath("/music")
                .lastScheduledScan(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();
        when(userRepository.findByScanScheduleNotNull()).thenReturn(List.of(user));
        when(musicScannerService.scanDirectorySync(any(), eq("/music"), any()))
                .thenReturn(new ScanResult());

        service.checkScheduledScans();

        verify(musicScannerService).scanDirectorySync(user, "/music", com.stellarideas.grooves.model.ScanJob.Type.SCHEDULED);
        verify(userRepository).save(user);
        assertNotNull(user.getLastScheduledScan());
    }

    @Test
    void doesNotScanWhenNotYetDue() throws Exception {
        User user = User.builder().id("u1").username("user1")
                .scanSchedule("0 0 0 1 1 *") // once a year, Jan 1 midnight
                .scanPath("/music")
                .lastScheduledScan(Instant.now()) // just scanned
                .build();
        when(userRepository.findByScanScheduleNotNull()).thenReturn(List.of(user));

        service.checkScheduledScans();

        verify(musicScannerService, never()).scanDirectorySync(any(), anyString(), any());
    }

    @Test
    void handlesInvalidCronExpression() throws Exception {
        User user = User.builder().id("u1").username("user1")
                .scanSchedule("not-a-cron")
                .scanPath("/music")
                .build();
        when(userRepository.findByScanScheduleNotNull()).thenReturn(List.of(user));

        // Should not throw — error is caught and logged
        assertDoesNotThrow(() -> service.checkScheduledScans());
        verify(musicScannerService, never()).scanDirectorySync(any(), anyString(), any());
    }

    @Test
    void defaultsToOneYearAgoWhenNoLastScan() throws Exception {
        User user = User.builder().id("u1").username("user1")
                .scanSchedule("* * * * * *")
                .scanPath("/music")
                .build(); // no lastScheduledScan set
        when(userRepository.findByScanScheduleNotNull()).thenReturn(List.of(user));
        when(musicScannerService.scanDirectorySync(any(), eq("/music"), any()))
                .thenReturn(new ScanResult());

        service.checkScheduledScans();

        verify(musicScannerService).scanDirectorySync(user, "/music", com.stellarideas.grooves.model.ScanJob.Type.SCHEDULED);
    }

    @Test
    void concurrentInvocationsDoNotOverlap() throws Exception {
        User user = User.builder().id("u1").username("user1")
                .scanSchedule("* * * * * *")
                .scanPath("/music")
                .lastScheduledScan(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();
        when(userRepository.findByScanScheduleNotNull()).thenReturn(List.of(user));

        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        when(musicScannerService.scanDirectorySync(any(), eq("/music"), any())).thenAnswer(inv -> {
            int current = concurrentCount.incrementAndGet();
            maxConcurrent.updateAndGet(max -> Math.max(max, current));
            Thread.sleep(100); // simulate work
            concurrentCount.decrementAndGet();
            return new ScanResult();
        });

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            executor.submit(() -> {
                service.checkScheduledScans();
                latch.countDown();
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // With the synchronized guard, max concurrent should be 1
        assertEquals(1, maxConcurrent.get(), "Only one scan should run at a time per user");
    }
}
