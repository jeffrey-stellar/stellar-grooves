package com.stellarideas.grooves.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated bounded executor for background music scans. Isolates scan work from the
 * Tomcat HTTP thread pool so a long-running scan can't starve request handling.
 */
@Configuration
@EnableAsync
public class ScanAsyncConfig {

    @Value("${stellar.grooves.scan.async.corePoolSize:2}")
    private int corePoolSize;

    @Value("${stellar.grooves.scan.async.maxPoolSize:4}")
    private int maxPoolSize;

    @Value("${stellar.grooves.scan.async.queueCapacity:10}")
    private int queueCapacity;

    @Bean(name = "scanTaskExecutor")
    public TaskExecutor scanTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("scan-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
