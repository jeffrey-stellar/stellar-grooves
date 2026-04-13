package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.Genre;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MusicCatalogService catalog loading edge cases.
 */
class MusicCatalogServiceLoadTest {

    @Test
    void serviceReturnsEmptyGenresWhenCatalogFailsToLoad() throws Exception {
        MusicCatalogService service = new MusicCatalogService();

        // Point to a nonexistent custom catalog path to trigger load failure
        Field pathField = MusicCatalogService.class.getDeclaredField("customCatalogPath");
        pathField.setAccessible(true);
        pathField.set(service, "/nonexistent/catalog.json");

        // loadCatalog should handle IOException gracefully
        var loadMethod = MusicCatalogService.class.getDeclaredMethod("loadCatalog");
        loadMethod.setAccessible(true);
        loadMethod.invoke(service);

        // After failed load, all lookups should return empty
        Set<Genre> genres = service.identifyGenres("Metallica");
        assertTrue(genres.isEmpty(), "Should return empty genres when catalog failed to load");
        assertFalse(service.isKnownArtist("Metallica"), "Should not know any artists when catalog failed to load");
    }

    @Test
    void serviceLoadsDefaultCatalogSuccessfully() throws Exception {
        MusicCatalogService service = new MusicCatalogService();

        Field pathField = MusicCatalogService.class.getDeclaredField("customCatalogPath");
        pathField.setAccessible(true);
        pathField.set(service, "");

        var loadMethod = MusicCatalogService.class.getDeclaredMethod("loadCatalog");
        loadMethod.setAccessible(true);
        loadMethod.invoke(service);

        // Default catalog should load known artists
        assertTrue(service.isKnownArtist("Metallica"), "Should know Metallica from default catalog");
        assertFalse(service.identifyGenres("Metallica").isEmpty(), "Should have genres for Metallica");
    }
}
