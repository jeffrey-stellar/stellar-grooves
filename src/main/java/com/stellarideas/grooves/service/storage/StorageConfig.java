package com.stellarideas.grooves.service.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the active {@link FileSource} from {@code stellar.grooves.storage.type}.
 *
 * <p>Default (and {@code type=local}) → {@link LocalFileSource}: the self-host
 * filesystem backend, unchanged. {@code type=s3} → {@link ObjectStorageFileSource}
 * against the configured S3-compatible bucket.</p>
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    @ConditionalOnProperty(name = "stellar.grooves.storage.type", havingValue = "local", matchIfMissing = true)
    public FileSource localFileSource() {
        return new LocalFileSource();
    }

    @Bean
    @ConditionalOnProperty(name = "stellar.grooves.storage.type", havingValue = "s3")
    public FileSource s3FileSource(StorageProperties properties) {
        if (properties.getS3().getBucket() == null || properties.getS3().getBucket().isBlank()) {
            throw new IllegalStateException(
                    "stellar.grooves.storage.type=s3 requires a bucket — set S3_BUCKET.");
        }
        return ObjectStorageFileSource.from(properties.getS3());
    }
}
