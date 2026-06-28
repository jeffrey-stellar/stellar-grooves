package com.stellarideas.grooves.service.storage;

import java.nio.file.Path;

/**
 * Result of resolving how to serve a track's audio bytes.
 *
 * <p>The local filesystem source resolves to a validated {@link #localPath} the
 * caller serves directly. The object-storage source resolves to a short-lived
 * presigned {@link #redirectUrl} the caller 302-redirects to, so the browser
 * streams straight from the bucket. Exactly one of the two is set on {@code OK}.</p>
 */
public record StreamResolution(Status status, Path localPath, String redirectUrl) {

    public enum Status {
        /** The track can be served; {@link #localPath()} or {@link #redirectUrl()} is set. */
        OK,
        /** The underlying file is missing or unreadable. */
        NOT_FOUND,
        /** The request isn't allowed (e.g. no music directory, or out-of-scope path). */
        FORBIDDEN
    }

    public static StreamResolution ok(Path localPath) {
        return new StreamResolution(Status.OK, localPath, null);
    }

    public static StreamResolution redirect(String url) {
        return new StreamResolution(Status.OK, null, url);
    }

    public static StreamResolution notFound() {
        return new StreamResolution(Status.NOT_FOUND, null, null);
    }

    public static StreamResolution forbidden() {
        return new StreamResolution(Status.FORBIDDEN, null, null);
    }
}
