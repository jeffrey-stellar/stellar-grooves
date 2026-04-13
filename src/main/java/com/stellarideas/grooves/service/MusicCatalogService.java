package com.stellarideas.grooves.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarideas.grooves.model.Genre;
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

    private final Map<String, Set<Genre>> bandGenreMap = new HashMap<>();

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
            logger.error("Failed to load artist catalog: {}", e.getMessage());
        }
    }

    public Set<Genre> identifyGenres(String artistName) {
        if (artistName == null || artistName.isBlank()) return Collections.emptySet();
        return bandGenreMap.getOrDefault(artistName.toLowerCase(), Collections.emptySet());
    }

    public boolean isKnownArtist(String artistName) {
        if (artistName == null || artistName.isBlank()) return false;
        return bandGenreMap.containsKey(artistName.toLowerCase());
    }
}
