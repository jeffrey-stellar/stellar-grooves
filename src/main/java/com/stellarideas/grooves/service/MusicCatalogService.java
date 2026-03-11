package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.Genre;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MusicCatalogService {
    private final Map<String, Set<Genre>> bandGenreMap = new HashMap<>();

    public MusicCatalogService() {
        initializeData();
    }

    private void initializeData() {
        // --- 1960s & 1970s ---
        addBand("The Rolling Stones", Genre.CLASSIC_ROCK);
        addBand("The Beatles", Genre.CLASSIC_ROCK);
        addBand("The Who", Genre.CLASSIC_ROCK, Genre.HARD_ROCK);
        addBand("The Doors", Genre.CLASSIC_ROCK);
        addBand("Cream", Genre.CLASSIC_ROCK, Genre.HARD_ROCK);
        addBand("Pink Floyd", Genre.CLASSIC_ROCK);
        addBand("Led Zeppelin", Genre.CLASSIC_ROCK, Genre.HARD_ROCK, Genre.HEAVY_METAL);
        addBand("Fleetwood Mac", Genre.CLASSIC_ROCK);
        addBand("Deep Purple", Genre.HARD_ROCK, Genre.HEAVY_METAL);
        addBand("AC/DC", Genre.HARD_ROCK, Genre.CLASSIC_ROCK);
        addBand("Black Sabbath", Genre.HEAVY_METAL, Genre.HARD_ROCK);
        addBand("Judas Priest", Genre.HEAVY_METAL);

        // --- 1980s ---
        addBand("Mötley Crüe", Genre.HAIR_METAL, Genre.HARD_ROCK);
        addBand("Poison", Genre.HAIR_METAL);
        addBand("Metallica", Genre.THRASH_METAL, Genre.HEAVY_METAL);
        addBand("Slayer", Genre.THRASH_METAL);
        addBand("Megadeth", Genre.THRASH_METAL, Genre.HEAVY_METAL);
        addBand("Iron Maiden", Genre.HEAVY_METAL);
        addBand("Guns N' Roses", Genre.HARD_ROCK, Genre.HAIR_METAL);

        // ... and so on for all 30+ bands from previous session
    }

    private void addBand(String name, Genre... genres) {
        bandGenreMap.put(name.toLowerCase(), new HashSet<>(Arrays.asList(genres)));
    }

    public Set<Genre> identifyGenres(String artistName) {
        if (artistName == null) return Collections.emptySet();
        return bandGenreMap.getOrDefault(artistName.toLowerCase(), Collections.emptySet());
    }
}
