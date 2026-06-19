package com.stellarideas.grooves.service.storage;

import java.time.Instant;

/**
 * A single object listed from an S3-compatible bucket.
 *
 * @param key          the object key (full path within the bucket)
 * @param size         size in bytes
 * @param etag         entity tag (quotes stripped) — used as a cheap change-detector on re-scan
 * @param lastModified last-modified timestamp
 */
public record RemoteObject(String key, long size, String etag, Instant lastModified) {}
