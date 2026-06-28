package com.stellarideas.grooves.service.coverart;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ItunesCoverArtProviderTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private ItunesCoverArtProvider provider;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new ItunesCoverArtProvider(builder, 10_485_760);
    }

    @Test
    void fetchesUpscaledArtworkForMatchingResult() {
        String json = "{\"results\":[{\"artistName\":\"Metallica\",\"collectionName\":\"Master of Puppets\","
                + "\"artworkUrl100\":\"https://is1.example.com/art/100x100bb.jpg\"}]}";
        server.expect(requestTo(containsString("itunes.apple.com/search")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        // The provider must upscale 100x100 -> 600x600 before downloading.
        server.expect(requestTo(containsString("600x600bb")))
                .andRespond(withSuccess(PNG, MediaType.IMAGE_PNG));

        Optional<FetchedArt> art = provider.fetch("Metallica", "Master of Puppets");

        assertTrue(art.isPresent());
        assertEquals("image/png", art.get().mimeType());
        server.verify();
    }

    @Test
    void rejectsResultWhoseAlbumDoesNotMatch() {
        String json = "{\"results\":[{\"artistName\":\"Some DJ\",\"collectionName\":\"Totally Different Record\","
                + "\"artworkUrl100\":\"https://is1.example.com/art/100x100bb.jpg\"}]}";
        server.expect(requestTo(containsString("itunes.apple.com/search")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        // No artwork download expected.
        assertTrue(provider.fetch("Metallica", "Master of Puppets").isEmpty());
        server.verify();
    }

    @Test
    void returnsEmptyOnNoResults() {
        server.expect(requestTo(containsString("itunes.apple.com/search")))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        assertTrue(provider.fetch("Nobody", "Nothing").isEmpty());
        server.verify();
    }

    @Test
    void reportsItsName() {
        assertEquals("itunes", provider.name());
    }
}
