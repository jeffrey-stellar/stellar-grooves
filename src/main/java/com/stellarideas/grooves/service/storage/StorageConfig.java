package com.stellarideas.grooves.service.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the active {@link FileSource} from {@code stellar.grooves.storage.type}.
 *
 * <p>Default (and {@code type=local}) → {@link LocalFileSource}: the self-host
 * filesystem backend, unchanged. {@code type=s3} is reserved for the
 * object-storage backend, which lands in a later phase — until then, selecting
 * it fails fast with a clear message instead of a cryptic missing-bean error.</p>
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
    public FileSource s3FileSource() {
        throw new IllegalStateException(
                "stellar.grooves.storage.type=s3 is not available in this build yet "
                        + "(the S3-compatible object-storage backend lands in a later release). "
                        + "Set STORAGE_TYPE=local for now.");
    }
}
