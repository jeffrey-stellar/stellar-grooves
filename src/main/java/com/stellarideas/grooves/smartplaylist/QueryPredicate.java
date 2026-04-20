package com.stellarideas.grooves.smartplaylist;

import com.stellarideas.grooves.model.Genre;

import java.time.Duration;

/**
 * Parsed predicate tree for the smart-playlist DSL. Each clause in a query (e.g.
 * {@code genre:thrash_metal}, {@code rating:>=4}, {@code lastPlayed:>6mo}) produces
 * one of these records. The translator converts a list of predicates into a
 * MongoDB {@code Criteria}.
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

    /** lastPlayed:&gt;Xmo — not played within the window (lastPlayedAt &lt; now - window, or null). */
    record LastPlayedBefore(Duration window) implements QueryPredicate {}

    /** Wraps a clause prefixed with {@code -} (logical NOT). The inner predicate is any non-Not predicate. */
    record Not(QueryPredicate inner) implements QueryPredicate {}
}
