package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.Genre;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MusicCatalogServiceTest {

    private MusicCatalogService service;

    @BeforeEach
    void setUp() {
        service = new MusicCatalogService();
        service.loadCatalog();
    }

    @Test
    void knownArtistReturnsExpectedGenre() {
        Set<Genre> genres = service.identifyGenres("Metallica");
        assertTrue(genres.contains(Genre.THRASH_METAL), "Metallica should be THRASH_METAL");
    }

    @Test
    void knownMultiGenreArtistReturnsMultipleGenres() {
        Set<Genre> genres = service.identifyGenres("Led Zeppelin");
        assertTrue(genres.size() > 1, "Led Zeppelin should have multiple genres");
        assertTrue(genres.contains(Genre.CLASSIC_ROCK));
        assertTrue(genres.contains(Genre.HARD_ROCK));
    }

    @Test
    void unknownArtistReturnsEmptySet() {
        Set<Genre> genres = service.identifyGenres("Unknown Band XYZ");
        assertTrue(genres.isEmpty());
    }

    @Test
    void nullArtistReturnsEmptySet() {
        Set<Genre> genres = service.identifyGenres(null);
        assertTrue(genres.isEmpty());
    }

    @Test
    void blankArtistReturnsEmptySet() {
        Set<Genre> genres = service.identifyGenres("   ");
        assertTrue(genres.isEmpty());
    }

    @Test
    void lookupIsCaseInsensitive() {
        Set<Genre> lower = service.identifyGenres("metallica");
        Set<Genre> upper = service.identifyGenres("METALLICA");
        Set<Genre> mixed = service.identifyGenres("Metallica");
        assertEquals(lower, upper);
        assertEquals(lower, mixed);
        assertFalse(lower.isEmpty());
    }

    @Test
    void isKnownArtistReturnsTrueForKnownBand() {
        assertTrue(service.isKnownArtist("Iron Maiden"));
    }

    @Test
    void isKnownArtistReturnsFalseForUnknownBand() {
        assertFalse(service.isKnownArtist("My Garage Band"));
    }

    @Test
    void isKnownArtistReturnsFalseForNull() {
        assertFalse(service.isKnownArtist(null));
    }
}
