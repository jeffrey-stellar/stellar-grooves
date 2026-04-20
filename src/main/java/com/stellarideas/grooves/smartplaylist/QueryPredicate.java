package com.stellarideas.grooves.smartplaylist;

import com.stellarideas.grooves.model.Genre;

import java.time.Duration;

/**
 * Leaf predicate for the smart-playlist DSL. Each {@code field:value} clause
 * produces one of these records. Composition (AND/OR/NOT) lives on
 * {@link QueryExpr} — leaves are pure single-field predicates.
 */
public sealed interface QueryPredicate {

    enum TextField { ARTIST, ALBUM, TITLE }
    enum NumField { YEAR, RATING, PLAY_COUNT }
    enum CompareOp { GT, GTE, LT, LTE }

    record GenreEq(Genre genre) implements QueryPredicate {}

    record TextContains(TextField field, String value) implements QueryPredicate {}

    record IntEq(NumField field, int value) implements QueryPredicate {}

    record IntRange(NumField field, int min, int max) implements QueryPredicate {}

    record IntCompare(NumField field, CompareOp op, int value) implements QueryPredicate {}

    record TagEq(String tag) implements QueryPredicate {}

    /** lastPlayed:&lt;Xd — played within the window (lastPlayedAt &gt;= now - window). */
    record LastPlayedSince(Duration window) implements QueryPredicate {}

    /** lastPlayed:&gt;Xmo — not played within the window (lastPlayedAt &lt; now - window). */
    record LastPlayedBefore(Duration window) implements QueryPredicate {}
}
