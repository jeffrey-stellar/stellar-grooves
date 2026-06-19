package com.stellarideas.grooves.service.storage;

import java.nio.file.Path;

/**
 * Result of resolving how to serve a track's audio bytes.
 *
 * <p>For the local filesystem source this carries a resolved, validated
 * {@link Path} the caller serves directly. Object-storage sources (added in a
 * later phase) will resolve to a short-lived presigned redirect; this type will
 * grow to express that when that path lands.</p>
 */
public record StreamResolution(Status status, Path localPath) {

    public enum Status {
        /** The track can be served; {@link #localPath()} is set. */
        OK,
        /** The underlying file is missing or unreadable. */
        NOT_FOUND,
        /** The request isn't allowed (e.g. no music directory, or out-of-scope path). */
        FORBIDDEN
    }

    public static StreamResolution ok(Path localPath) {
        return new StreamResolution(Status.OK, localPath);
    }

    public static StreamResolution notFound() {
        return new StreamResolution(Status.NOT_FOUND, null);
    }

    public static StreamResolution forbidden() {
        return new StreamResolution(Status.FORBIDDEN, null);
    }
}
