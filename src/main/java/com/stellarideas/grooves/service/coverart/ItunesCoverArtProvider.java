package com.stellarideas.grooves.service.coverart;

import com.fasterxml.jackson.databind.JsonNode;
import com.stellarideas.grooves.util.ImageTypeDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Locale;
import java.util.Optional;

/**
 * Looks up cover art via the public iTunes Search API. No API key required; very high hit rate
 * for mainstream releases. We confirm the result's artist + album loosely match the request
 * (normalised compare) before accepting it, and upscale the artwork URL from 100px to 600px.
 *
 * <p>Note: iTunes' terms are oriented toward the iTunes Store / affiliate program; this is a
 * pragmatic, opt-in fallback for a personal self-hosted library.
 */
@Component
public class ItunesCoverArtProvider implements AlbumArtProvider {

    private static final Logger logger = LoggerFactory.getLogger(ItunesCoverArtProvider.class);
    private static final String SEARCH_URL = "https://itunes.apple.com/search";
    private static final int MAX_RESULTS = 5;

    private final RestClient client;
    private final int maxImageBytes;

    public ItunesCoverArtProvider(
            RestClient.Builder coverArtRestClientBuilder,
            @Value("${stellar.grooves.coverArt.maxBytesPerImage:10485760}") int maxImageBytes) {
        this.client = coverArtRestClientBuilder.build();
        this.maxImageBytes = maxImageBytes;
    }

    @Override
    public String name() {
        return "itunes";
    }

    @Override
    public Optional<FetchedArt> fetch(String artist, String album) {
        try {
            String term = (artist + " " + album).trim();
            JsonNode resp = client.get()
                    .uri(SEARCH_URL + "?term={t}&entity=album&limit=" + MAX_RESULTS, term)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            if (resp == null) return Optional.empty();

            String wantArtist = normalize(artist);
            String wantAlbum = normalize(album);
            for (JsonNode r : resp.path("results")) {
                String gotArtist = normalize(r.path("artistName").asText(""));
                String gotAlbum = normalize(r.path("collectionName").asText(""));
                if (gotAlbum.isEmpty() || !looselyMatches(wantAlbum, gotAlbum)) continue;
                if (!wantArtist.isEmpty() && !looselyMatches(wantArtist, gotArtist)) continue;

                String url = r.path("artworkUrl100").asText(null);
                if (url == null || url.isBlank()) continue;
                Optional<FetchedArt> art = download(url.replace("100x100bb", "600x600bb"));
                if (art.isPresent()) return art;
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            logger.debug("iTunes lookup failed for '{} - {}': {}", artist, album, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<FetchedArt> download(String url) {
        try {
            byte[] data = client.get().uri(url).retrieve().body(byte[].class);
            if (data == null || data.length == 0 || data.length > maxImageBytes) return Optional.empty();
            String mime = ImageTypeDetector.detectMime(data);
            if (mime == null) return Optional.empty();
            return Optional.of(new FetchedArt(data, mime));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static boolean looselyMatches(String want, String got) {
        return want.equals(got) || got.contains(want) || want.contains(got);
    }

    /** Lowercase and strip everything but letters/digits, for tolerant title comparison. */
    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
