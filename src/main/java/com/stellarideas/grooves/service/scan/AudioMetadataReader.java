package com.stellarideas.grooves.service.scan;

import jakarta.annotation.PostConstruct;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads audio-file metadata (tags) via JAudioTagger with a per-file timeout.
 *
 * <p>JAudioTagger can hang or spin on malformed files; each read runs on a bounded
 * executor so a bad file can't stall the whole scan. Returned data is a lightweight
 * {@link AudioMetadata} record consumed by {@code MusicScannerService}.
 */
@Component
public class AudioMetadataReader implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(AudioMetadataReader.class);
    private static final int DEFAULT_PER_FILE_TIMEOUT_SECONDS = 30;

    @Value("${stellar.grooves.scan.perFileTimeoutSeconds:" + DEFAULT_PER_FILE_TIMEOUT_SECONDS + "}")
    private int perFileTimeoutSeconds;

    @Value("${stellar.grooves.scan.fileReaderThreads:2}")
    private int fileReaderThreads;

    private volatile ExecutorService executor;

    @PostConstruct
    public void init() {
        int threads = Math.max(1, fileReaderThreads);
        executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "audio-file-reader");
            t.setDaemon(true);
            return t;
        });
        logger.info("Audio metadata reader pool initialized with {} thread(s)", threads);
    }

    @Override
    public void destroy() {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Read tag data for {@code path}. Returns an {@link AudioMetadata} with trimmed
     * string fields (never null, empty-string if absent) plus the underlying {@link Tag}
     * for callers that want the raw tag (e.g. to extract cover art).
     */
    public AudioMetadata read(Path path) throws IOException {
        AudioFile f = readWithTimeout(path);
        Tag tag = f.getTag();
        return new AudioMetadata(
                safeGet(tag, FieldKey.ARTIST),
                safeGet(tag, FieldKey.ALBUM),
                safeGet(tag, FieldKey.TITLE),
                safeGet(tag, FieldKey.YEAR),
                tag);
    }

    private AudioFile readWithTimeout(Path path) throws IOException {
        try {
            Future<AudioFile> future = executor.submit(() -> AudioFileIO.read(path.toFile()));
            return future.get(perFileTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            throw new IOException("File read timed out after " + perFileTimeoutSeconds + "s");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Scan interrupted while reading file", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof IOException ioe) throw ioe;
            if (cause instanceof RuntimeException re) throw re;
            throw new IOException("Failed to read audio file", cause != null ? cause : ee);
        }
    }

    private static String safeGet(Tag tag, FieldKey key) {
        try {
            if (tag == null) return "";
            String val = tag.getFirst(key);
            return val != null ? val.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Lightweight carrier for parsed tag data. {@code tag} may be null for tagless files. */
    public record AudioMetadata(String artist, String album, String title, String year, Tag tag) {
        public boolean hasArtistAndTitle() {
            return artist != null && !artist.isBlank() && title != null && !title.isBlank();
        }
    }
}
