package com.stellarideas.grooves.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarideas.grooves.model.Genre;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

class CatalogJsonIntegrityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void catalogUsesKnownGenresAndUniqueNonBlankArtists() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("catalog.json")) {
            assertNotNull(in, "catalog.json should be available on the test classpath");

            JsonParser parser = MAPPER.getFactory().createParser(in);
            assertEquals(JsonToken.START_OBJECT, parser.nextToken(), "catalog.json should be a JSON object");

            Map<String, String> artistsByLowercaseName = new HashMap<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                assertEquals(
                        JsonToken.FIELD_NAME,
                        parser.currentToken(),
                        "catalog entries should use artist names as keys");

                String artist = parser.currentName();
                assertFalse(artist.isBlank(), "catalog.json must not contain a blank artist key");

                String normalizedArtist = artist.toLowerCase(Locale.ROOT);
                String existingArtist = artistsByLowercaseName.putIfAbsent(normalizedArtist, artist);
                if (existingArtist != null) {
                    fail("catalog.json contains duplicate artist keys ignoring case: '"
                            + existingArtist + "' and '" + artist + "'");
                }

                assertEquals(
                        JsonToken.START_ARRAY,
                        parser.nextToken(),
                        "catalog entry for '" + artist + "' should be a genre array");
                assertFalse(
                        parser.nextToken() == JsonToken.END_ARRAY,
                        "catalog entry for '" + artist + "' should list at least one genre");
                do {
                    assertEquals(
                            JsonToken.VALUE_STRING,
                            parser.currentToken(),
                            "genre for '" + artist + "' should be a string");

                    String genre = parser.getValueAsString();
                    assertDoesNotThrow(
                            () -> Genre.valueOf(genre),
                            "catalog entry for '" + artist + "' uses unknown genre '" + genre + "'");
                } while (parser.nextToken() != JsonToken.END_ARRAY);
            }
        }
    }
}
