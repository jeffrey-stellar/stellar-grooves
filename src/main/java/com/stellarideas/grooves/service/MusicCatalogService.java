package com.stellarideas.grooves.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.GenreCorrection;
import com.stellarideas.grooves.repository.GenreCorrectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Loads artist→genre mappings from a JSON catalog file.
 * By default reads from the bundled classpath resource (catalog.json).
 * Override with stellar.grooves.catalogPath to load a custom file.
 */
@Service
public class MusicCatalogService {

    private static final Logger logger = LoggerFactory.getLogger(MusicCatalogService.class);

    @Value("${stellar.grooves.catalogPath:}")
    private String customCatalogPath;

    private final GenreCorrectionRepository correctionRepository;
    private final Map<String, Set<Genre>> bandGenreMap = new HashMap<>();

    public MusicCatalogService(GenreCorrectionRepository correctionRepository) {
        this.correctionRepository = correctionRepository;
    }

    @PostConstruct
    void loadCatalog() {
        Resource resource;
        if (customCatalogPath != null && !customCatalogPath.isBlank()) {
            resource = new FileSystemResource(customCatalogPath);
            logger.info("Loading artist catalog from custom path: {}", customCatalogPath);
        } else {
            resource = new ClassPathResource("catalog.json");
            logger.info("Loading artist catalog from classpath: catalog.json");
        }

        try (InputStream in = resource.getInputStream()) {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, List<String>> raw = mapper.readValue(in, new TypeReference<>() {});

            for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
                Set<Genre> genres = entry.getValue().stream()
                        .map(s -> {
                            try {
                                return Genre.valueOf(s);
                            } catch (IllegalArgumentException e) {
                                logger.warn("Unknown genre '{}' for artist '{}', skipping", s, entry.getKey());
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));

                if (!genres.isEmpty()) {
                    bandGenreMap.put(entry.getKey().toLowerCase(), genres);
                }
            }
            logger.info("Loaded {} artists from catalog", bandGenreMap.size());
        } catch (IOException e) {
            logger.error("Failed to load artist catalog: {}", e.getMessage(), e);
        }
    }

    public Set<Genre> identifyGenres(String artistName) {
        if (artistName == null || artistName.isBlank()) return Collections.emptySet();

        // Check user corrections first — they override the static catalog
        Optional<GenreCorrection> correction = correctionRepository.findByArtistLower(artistName.toLowerCase());
        if (correction.isPresent()) {
            return Set.of(correction.get().getGenre());
        }

        return bandGenreMap.getOrDefault(artistName.toLowerCase(), Collections.emptySet());
    }

    public boolean isKnownArtist(String artistName) {
        if (artistName == null || artistName.isBlank()) return false;
        return bandGenreMap.containsKey(artistName.toLowerCase())
                || correctionRepository.findByArtistLower(artistName.toLowerCase()).isPresent();
    }

    /**
     * Record a genre correction for an artist. Future scans will use this
     * genre instead of the static catalog.
     */
    public void recordCorrection(String artist, Genre genre, String userId) {
        if (artist == null || artist.isBlank() || genre == null) return;
        Optional<GenreCorrection> existing = correctionRepository.findByArtistLower(artist.toLowerCase());
        if (existing.isPresent()) {
            GenreCorrection c = existing.get();
            c.setGenre(genre);
            c.setCorrectedByUserId(userId);
            c.setCorrectedAt(java.time.Instant.now());
            correctionRepository.save(c);
        } else {
            correctionRepository.save(new GenreCorrection(artist, genre, userId));
        }
        logger.info("Genre correction recorded: artist='{}' genre={} by user={}", artist, genre, userId);
    }

    /**
     * Reload the catalog from disk. Called by admin endpoint for hot-reload
     * after the catalog file has been updated.
     */
    public void reloadCatalog() {
        bandGenreMap.clear();
        loadCatalog();
    }

    /** Returns a snapshot of the in-memory catalog for admin viewing. */
    public Map<String, Set<Genre>> getCatalog() {
        return Collections.unmodifiableMap(bandGenreMap);
    }

    /** Add or update an artist entry in the in-memory catalog. */
    public void putCatalogEntry(String artist, Set<Genre> genres) {
        if (artist == null || artist.isBlank() || genres == null || genres.isEmpty()) return;
        bandGenreMap.put(artist.toLowerCase(), new LinkedHashSet<>(genres));
        logger.info("Catalog entry updated: artist='{}' genres={}", artist, genres);
    }

    /** Remove an artist from the in-memory catalog. */
    public boolean removeCatalogEntry(String artist) {
        if (artist == null || artist.isBlank()) return false;
        boolean removed = bandGenreMap.remove(artist.toLowerCase()) != null;
        if (removed) {
            logger.info("Catalog entry removed: artist='{}'", artist);
        }
        return removed;
    }

    /** Returns the number of artists in the catalog. */
    public int catalogSize() {
        return bandGenreMap.size();
    }
}
