package com.stellarideas.grooves.service.coverart;

import java.util.Optional;

/**
 * A source of album cover art looked up by artist + album name. Implementations call an
 * external service; they must be resilient (return {@link Optional#empty()} on any miss,
 * bad match, or error rather than throwing) and should only return art they're reasonably
 * confident matches the requested album, to avoid wrong covers.
 */
public interface AlbumArtProvider {

    /** Stable lowercase identifier, also stored as the cover-art {@code source} (e.g. "musicbrainz"). */
    String name();

    /** Look up cover art for the album, or empty if none found / no confident match / error. */
    Optional<FetchedArt> fetch(String artist, String album);
}
