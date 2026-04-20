package com.stellarideas.grooves.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates user-supplied filesystem scan paths against traversal, symlink, and
 * system-directory attacks. Used at both the controller boundary and the service
 * layer (defense-in-depth, since scheduled scans skip the controller).
 *
 * <p>If {@code stellar.grooves.scan.allowedBaseDirs} is configured, scan paths
 * must resolve under one of those directories. Otherwise, the blocklist of
 * sensitive system paths applies.
 */
@Component
public class ScanPathValidator {

    private static final Logger logger = LoggerFactory.getLogger(ScanPathValidator.class);

    private static final Set<String> BLOCKED_PATHS = Set.of(
            "/etc", "/root", "/var", "/usr", "/bin", "/sbin",
            "/boot", "/dev", "/proc", "/sys", "/run", "/lib",
            "/opt", "/srv", "/tmp"
    );

    private final MessageHelper msg;
    private final List<Path> allowedBaseDirs;

    public ScanPathValidator(MessageHelper msg,
                             @Value("${stellar.grooves.scan.allowedBaseDirs:}") String allowedBaseDirsCsv) {
        this.msg = msg;
        this.allowedBaseDirs = parseAllowedBaseDirs(allowedBaseDirsCsv);
        if (!this.allowedBaseDirs.isEmpty()) {
            logger.info("Scan allowlist enabled. Scan paths must resolve under: {}", this.allowedBaseDirs);
        }
    }

    private static List<Path> parseAllowedBaseDirs(String csv) {
        List<Path> result = new ArrayList<>();
        if (csv == null || csv.isBlank()) return result;
        for (String entry : csv.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            try {
                Path p = Paths.get(trimmed).toRealPath();
                result.add(p);
            } catch (IOException e) {
                logger.warn("Ignoring configured allowed base dir '{}': {}", trimmed, e.getMessage());
            }
        }
        return List.copyOf(result);
    }

    /**
     * Validate the path and return its canonical, real filesystem location.
     * Throws {@link IllegalArgumentException} for any input the caller must reject (4xx).
     * Throws {@link IOException} only for unexpected filesystem I/O errors (5xx).
     */
    public Path validate(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(msg.msg("scan.path.empty"));
        }
        Path requested = Paths.get(path).normalize().toAbsolutePath();
        if (requested.toString().contains("..")) {
            throw new IllegalArgumentException(msg.msg("scan.path.traversal"));
        }
        if (!Files.exists(requested) || !Files.isDirectory(requested)) {
            throw new IllegalArgumentException(msg.msg("scan.path.notfound"));
        }
        Path canonical = requested.toRealPath();
        if (!canonical.equals(requested)) {
            throw new IllegalArgumentException(msg.msg("scan.path.symlink"));
        }
        if (!allowedBaseDirs.isEmpty()) {
            boolean allowed = allowedBaseDirs.stream().anyMatch(canonical::startsWith);
            if (!allowed) {
                throw new IllegalArgumentException(msg.msg("scan.path.not_allowed"));
            }
        } else {
            String canonicalStr = canonical.toString();
            for (String blocked : BLOCKED_PATHS) {
                if (canonicalStr.equals(blocked) || canonicalStr.startsWith(blocked + "/")) {
                    throw new IllegalArgumentException(msg.msg("scan.path.not_allowed"));
                }
            }
        }
        return canonical;
    }
}
