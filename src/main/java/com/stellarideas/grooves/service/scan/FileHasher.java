package com.stellarideas.grooves.service.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Streams a file through SHA-256. Returns {@code null} on failure (best-effort). */
@Component
public class FileHasher {

    private static final Logger logger = LoggerFactory.getLogger(FileHasher.class);
    private static final int BUFFER_SIZE = 8192;

    public String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(path)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int n;
                while ((n = is.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            logger.debug("Failed to compute file hash for '{}': {}", path.getFileName(), e.getMessage());
            return null;
        }
    }
}
