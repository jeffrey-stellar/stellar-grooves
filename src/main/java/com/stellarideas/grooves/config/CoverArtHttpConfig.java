package com.stellarideas.grooves.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * HTTP client used by the optional external cover-art providers. Defines a single
 * {@link RestClient.Builder} with conservative connect/read timeouts so a slow or
 * unresponsive third-party service can't stall the background fetch job.
 *
 * <p>Providers build their own {@link RestClient} from this builder and use absolute
 * URLs per request (they never mutate the builder), so the shared instance is safe.
 */
@Configuration
public class CoverArtHttpConfig {

    @Bean
    public RestClient.Builder coverArtRestClientBuilder() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
    }

    /**
     * Small dedicated executor for the external cover-art fetch job, isolated from the scan
     * and HTTP pools. Single worker (one throttled run at a time); extra requests queue.
     */
    @Bean(name = "coverArtTaskExecutor")
    public TaskExecutor coverArtTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("coverart-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
