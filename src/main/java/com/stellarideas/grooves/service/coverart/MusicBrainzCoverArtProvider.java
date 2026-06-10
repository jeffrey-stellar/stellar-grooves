package com.stellarideas.grooves.service.coverart;

import com.fasterxml.jackson.databind.JsonNode;
import com.stellarideas.grooves.util.ImageTypeDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Looks up cover art via MusicBrainz (release search) + the Cover Art Archive (front image).
 *
 * <p>MusicBrainz requires a descriptive {@code User-Agent} with contact info and rate-limits
 * anonymous clients to ~1 req/s (the caller throttles). We only accept releases scoring at or
 * above {@link #MIN_SCORE} to avoid wrong matches, and pull the 500px CAA thumbnail so the
 * stored image stays well under the per-image quota without in-process downscaling.
 */
@Component
public class MusicBrainzCoverArtProvider implements AlbumArtProvider {

    private static final Logger logger = LoggerFactory.getLogger(MusicBrainzCoverArtProvider.class);
    private static final String SEARCH_URL = "https://musicbrainz.org/ws/2/release/";
    private static final String CAA_URL = "https://coverartarchive.org/release/";
    private static final int MIN_SCORE = 90;
    private static final int MAX_RELEASES = 5;

    private final RestClient client;
    private final String userAgent;
    private final int maxImageBytes;

    public MusicBrainzCoverArtProvider(
            RestClient.Builder coverArtRestClientBuilder,
            @Value("${stellar.grooves.coverArt.external.contact:stellar-grooves@example.com}") String contact,
            @Value("${stellar.grooves.coverArt.maxBytesPerImage:10485760}") int maxImageBytes) {
        this.client = coverArtRestClientBuilder.build();
        this.userAgent = "StellarGrooves ( " + contact + " )";
        this.maxImageBytes = maxImageBytes;
    }

    @Override
    public String name() {
        return "musicbrainz";
    }

    @Override
    public Optional<FetchedArt> fetch(String artist, String album) {
        try {
            String query = "release:\"" + lucene(album) + "\" AND artist:\"" + lucene(artist) + "\"";
            JsonNode resp = client.get()
                    .uri(SEARCH_URL + "?query={q}&fmt=json&limit=" + MAX_RELEASES, query)
                    .header(HttpHeaders.USER_AGENT, userAgent)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            if (resp == null) return Optional.empty();
            for (JsonNode release : resp.path("releases")) {
                if (release.path("score").asInt(0) < MIN_SCORE) continue;
                String mbid = release.path("id").asText(null);
                if (mbid == null || mbid.isBlank()) continue;
                Optional<FetchedArt> art = fetchFront(mbid);
                if (art.isPresent()) return art;
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            logger.debug("MusicBrainz lookup failed for '{} - {}': {}", artist, album, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<FetchedArt> fetchFront(String mbid) {
        try {
            byte[] data = client.get()
                    .uri(CAA_URL + mbid + "/front-500")
                    .header(HttpHeaders.USER_AGENT, userAgent)
                    .retrieve()
                    .body(byte[].class);
            if (data == null || data.length == 0 || data.length > maxImageBytes) return Optional.empty();
            String mime = ImageTypeDetector.detectMime(data);
            if (mime == null) return Optional.empty();
            return Optional.of(new FetchedArt(data, mime));
        } catch (RuntimeException e) {
            // A 404 here just means this release has no art in the archive — try the next one.
            return Optional.empty();
        }
    }

    /** Strip characters that would break the Lucene query (quotes/backslashes). */
    private static String lucene(String s) {
        return s == null ? "" : s.replace("\\", " ").replace("\"", " ").trim();
    }
}
