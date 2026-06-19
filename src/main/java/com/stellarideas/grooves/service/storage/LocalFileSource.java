package com.stellarideas.grooves.service.storage;

import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * {@link FileSource} backed by the local filesystem — the self-host default.
 *
 * <p>This is a behavior-preserving extraction of the access checks that used to
 * live inline in the streaming endpoint: the file must exist and be readable,
 * the user must have a music directory configured, and the (real, symlink-
 * resolved) file path must sit under that directory (path-traversal guard).</p>
 */
@Component
public class LocalFileSource implements FileSource {

    private static final Logger logger = LoggerFactory.getLogger(LocalFileSource.class);

    @Override
    public StreamResolution resolveStream(MusicFile file, User user) throws IOException {
        Path path = Paths.get(file.getFilePath()).normalize();
        if (!Files.exists(path) || !Files.isReadable(path)) {
            return StreamResolution.notFound();
        }
        if (user.getMusicDirectory() == null || user.getMusicDirectory().isBlank()) {
            logger.warn("Streaming blocked: user '{}' has no music directory configured", user.getUsername());
            return StreamResolution.forbidden();
        }
        Path musicDir = Paths.get(user.getMusicDirectory()).toRealPath();
        Path real = path.toRealPath();
        if (!real.startsWith(musicDir)) {
            logger.warn("Path traversal blocked: user '{}' attempted to stream '{}' outside music directory '{}'",
                    user.getUsername(), real, musicDir);
            return StreamResolution.forbidden();
        }
        return StreamResolution.ok(real);
    }
}
