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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MusicBrainzCoverArtProviderTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4, 5, 6, 7, 8};

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private MusicBrainzCoverArtProvider provider;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new MusicBrainzCoverArtProvider(builder, "test@example.com", 10_485_760);
    }

    @Test
    void fetchesFrontImageForHighScoreRelease() {
        server.expect(requestTo(containsString("musicbrainz.org/ws/2/release")))
                .andRespond(withSuccess("{\"releases\":[{\"id\":\"mbid-1\",\"score\":100}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("coverartarchive.org/release/mbid-1/front-500")))
                .andRespond(withSuccess(JPEG, MediaType.IMAGE_JPEG));

        Optional<FetchedArt> art = provider.fetch("Metallica", "Master of Puppets");

        assertTrue(art.isPresent());
        assertEquals("image/jpeg", art.get().mimeType());
        assertEquals(JPEG.length, art.get().data().length);
        server.verify();
    }

    @Test
    void skipsLowScoreReleasesAndReturnsEmpty() {
        server.expect(requestTo(containsString("musicbrainz.org/ws/2/release")))
                .andRespond(withSuccess("{\"releases\":[{\"id\":\"mbid-low\",\"score\":40}]}", MediaType.APPLICATION_JSON));
        // No CAA call expected.
        assertTrue(provider.fetch("Obscure", "Demo").isEmpty());
        server.verify();
    }

    @Test
    void returnsEmptyWhenArchiveHasNoFront() {
        server.expect(requestTo(containsString("musicbrainz.org/ws/2/release")))
                .andRespond(withSuccess("{\"releases\":[{\"id\":\"mbid-1\",\"score\":95}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("coverartarchive.org/release/mbid-1/front-500")))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertTrue(provider.fetch("Artist", "Album").isEmpty());
        server.verify();
    }

    @Test
    void rejectsNonImageBytesFromArchive() {
        server.expect(requestTo(containsString("musicbrainz.org/ws/2/release")))
                .andRespond(withSuccess("{\"releases\":[{\"id\":\"mbid-1\",\"score\":95}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("front-500")))
                .andRespond(withSuccess("<html>not an image</html>".getBytes(), MediaType.TEXT_HTML));

        assertTrue(provider.fetch("Artist", "Album").isEmpty());
        server.verify();
    }

    @Test
    void reportsItsName() {
        assertEquals("musicbrainz", provider.name());
    }
}
