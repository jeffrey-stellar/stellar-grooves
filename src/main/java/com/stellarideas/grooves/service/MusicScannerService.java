package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.MusicFileRepository;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class MusicScannerService {

    private static final Logger logger = LoggerFactory.getLogger(MusicScannerService.class);

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".mp3", ".m4a", ".flac");
    private static final int DEFAULT_MAX_DEPTH = 20;
    private static final int HARD_MAX_DEPTH = 50;
    private static final int BATCH_SIZE = 200;

    @Value("${stellar.grooves.scan.maxDepth:" + DEFAULT_MAX_DEPTH + "}")
    private int maxDepth;

    private final MusicCatalogService catalogService;
    private final MusicFileRepository repository;

    public MusicScannerService(MusicCatalogService catalogService, MusicFileRepository repository) {
        this.catalogService = catalogService;
        this.repository = repository;
    }

    /**
     * Scans a directory for supported audio files, extracts metadata, and saves
     * them to the library for the given user.
     *
     * @return the number of files successfully imported
     */
    public int scanDirectory(User user, String directoryPath) throws Exception {
        Path root = Paths.get(directoryPath).normalize();
        int effectiveDepth = Math.min(maxDepth, HARD_MAX_DEPTH);

        int saved = 0;
        int skipped = 0;
        List<MusicFile> batch = new ArrayList<>(BATCH_SIZE);

        try (Stream<Path> walk = Files.walk(root, effectiveDepth)) {
            var it = walk
                    .filter(p -> !Files.isSymbolicLink(p))
                    .filter(p -> {
                        String name = p.toString().toLowerCase();
                        return SUPPORTED_EXTENSIONS.stream().anyMatch(name::endsWith);
                    })
                    .iterator();

            while (it.hasNext()) {
                Path path = it.next();
                try {
                    if (repository.existsByFilePathAndUser(path.toString(), user)) {
                        logger.debug("Skipping duplicate path: '{}' already imported for user '{}'", path.getFileName(), user.getUsername());
                        skipped++;
                        continue;
                    }
                    AudioFile f = AudioFileIO.read(path.toFile());
                    Tag tag = f.getTag();

                    String artist = safeGet(tag, FieldKey.ARTIST);
                    String album  = safeGet(tag, FieldKey.ALBUM);
                    String title  = safeGet(tag, FieldKey.TITLE);
                    String year   = safeGet(tag, FieldKey.YEAR);

                    if (!title.isBlank() && !artist.isBlank()
                            && repository.existsByTitleAndArtistAndUser(title, artist, user)) {
                        logger.debug("Skipping duplicate metadata: '{}' by '{}' already imported for user '{}'", title, artist, user.getUsername());
                        skipped++;
                        continue;
                    }

                    // Only the first-listed genre is used for classification
                    Set<Genre> genres = catalogService.identifyGenres(artist);
                    Genre genre = genres.isEmpty() ? Genre.OTHER : genres.iterator().next();

                    batch.add(MusicFile.builder()
                            .user(user)
                            .filePath(path.toString())
                            .fileName(path.getFileName().toString())
                            .artist(artist)
                            .album(album)
                            .title(title)
                            .year(year)
                            .genre(genre)
                            .build());

                    if (batch.size() >= BATCH_SIZE) {
                        repository.saveAll(batch);
                        saved += batch.size();
                        batch.clear();
                    }
                } catch (Exception e) {
                    logger.warn("Skipping file '{}': {}", path.getFileName(), e.getMessage());
                    skipped++;
                }
            }
        }

        if (!batch.isEmpty()) {
            repository.saveAll(batch);
            saved += batch.size();
        }

        logger.info("Scan complete for user '{}': {} saved, {} skipped", user.getUsername(), saved, skipped);
        return saved;
    }

    private String safeGet(Tag tag, FieldKey key) {
        try {
            if (tag == null) return "";
            String val = tag.getFirst(key);
            return val != null ? val.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
