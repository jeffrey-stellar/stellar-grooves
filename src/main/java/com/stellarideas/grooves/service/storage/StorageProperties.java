package com.stellarideas.grooves.service.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for where a user's audio lives.
 *
 * <p>{@code type=local} (the default) uses the local filesystem and is what
 * self-host runs — unchanged. {@code type=s3} selects an S3-compatible
 * object-storage backend (the {@link #s3} settings below). The object-storage
 * implementation itself arrives in a later phase; this just defines the knobs.</p>
 */
@ConfigurationProperties(prefix = "stellar.grooves.storage")
public class StorageProperties {

    /** Storage backend: "local" or "s3". */
    private String type = "local";

    private S3 s3 = new S3();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public S3 getS3() {
        return s3;
    }

    public void setS3(S3 s3) {
        this.s3 = s3;
    }

    /** S3-compatible connection settings (used only when {@code type=s3}). */
    public static class S3 {
        /** Custom endpoint for non-AWS providers (Backblaze B2, Wasabi, MinIO). Blank = AWS. */
        private String endpoint = "";
        private String region = "us-east-1";
        private String bucket = "";
        /** Optional key prefix to scope the library within the bucket. */
        private String prefix = "";
        private String accessKey = "";
        private String secretKey = "";
        /** Lifetime of generated presigned stream URLs, in seconds. */
        private long presignTtlSeconds = 900;
        /** Objects larger than this are not downloaded for tag reading (skipped with a warning). */
        private long maxTagBytes = 52_428_800; // 50 MB

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public long getPresignTtlSeconds() {
            return presignTtlSeconds;
        }

        public void setPresignTtlSeconds(long presignTtlSeconds) {
            this.presignTtlSeconds = presignTtlSeconds;
        }

        public long getMaxTagBytes() {
            return maxTagBytes;
        }

        public void setMaxTagBytes(long maxTagBytes) {
            this.maxTagBytes = maxTagBytes;
        }
    }
}
