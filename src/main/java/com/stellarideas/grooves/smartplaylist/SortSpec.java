package com.stellarideas.grooves.smartplaylist;

/**
 * Optional ordering for a smart-playlist query. Produced by {@link SmartPlaylistQueryParser}
 * from a {@code sort:FIELD[:DIRECTION]} clause. {@link Field#RANDOM} selects
 * MongoDB {@code $sample} sampling and requires an accompanying limit.
 */
public record SortSpec(Field field, Direction direction) {

    public enum Field {
        RATING, YEAR, PLAY_COUNT, LAST_PLAYED, ARTIST, ALBUM, TITLE, RANDOM
    }

    public enum Direction { ASC, DESC }

    public boolean isRandom() {
        return field == Field.RANDOM;
    }
}
