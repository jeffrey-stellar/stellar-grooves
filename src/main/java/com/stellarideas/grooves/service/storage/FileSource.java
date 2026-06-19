package com.stellarideas.grooves.service.storage;

import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.User;

import java.io.IOException;

/**
 * Abstraction over where a user's audio actually lives.
 *
 * <p>Today the only implementation is {@link LocalFileSource} (the local
 * filesystem), which keeps self-host behavior unchanged. A future
 * object-storage implementation (S3-compatible) will plug in behind this same
 * interface so the scanner and streaming endpoint don't care whether bytes come
 * from disk or from a bucket.</p>
 */
public interface FileSource {

    /**
     * Resolve how to serve the given track's audio for a user, applying any
     * source-specific access checks (e.g. the local source enforces that the
     * file sits under the user's configured music directory).
     *
     * @return an {@link StreamResolution} describing the outcome; the caller maps
     *         {@code NOT_FOUND}/{@code FORBIDDEN} to HTTP status and serves the
     *         resolved location on {@code OK}.
     */
    StreamResolution resolveStream(MusicFile file, User user) throws IOException;
}
