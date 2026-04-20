package com.stellarideas.grooves.smartplaylist;

import com.stellarideas.grooves.model.Genre;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SmartPlaylistQueryParserTest {

    private final SmartPlaylistQueryParser parser = new SmartPlaylistQueryParser();

    @Test
    void emptyQueryReturnsEmptyList() {
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("   ").isEmpty());
    }

    @Test
    void genreEqualsCaseInsensitive() {
        List<QueryPredicate> p = parser.parse("genre:thrash_metal");
        assertEquals(1, p.size());
        assertEquals(new QueryPredicate.GenreEq(Genre.THRASH_METAL), p.get(0));
    }

    @Test
    void genreAcceptsSpaceVariantInQuotes() {
        List<QueryPredicate> p = parser.parse("genre:\"Thrash Metal\"");
        assertEquals(new QueryPredicate.GenreEq(Genre.THRASH_METAL), p.get(0));
    }

    @Test
    void unknownGenreThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("genre:disco"));
    }

    @Test
    void artistQuotedHandlesSpaces() {
        List<QueryPredicate> p = parser.parse("artist:\"The Who\"");
        assertEquals(new QueryPredicate.TextContains(QueryPredicate.TextField.ARTIST, "The Who"), p.get(0));
    }

    @Test
    void yearRangeParses() {
        List<QueryPredicate> p = parser.parse("year:1984..1990");
        assertEquals(new QueryPredicate.IntRange(QueryPredicate.NumField.YEAR, 1984, 1990), p.get(0));
    }

    @Test
    void yearRangeRejectsInvertedBounds() {
        assertThrows(QueryParseException.class, () -> parser.parse("year:1990..1984"));
    }

    @Test
    void ratingComparatorsParse() {
        assertEquals(new QueryPredicate.IntCompare(QueryPredicate.NumField.RATING,
                        QueryPredicate.CompareOp.GTE, 4),
                parser.parse("rating:>=4").get(0));
        assertEquals(new QueryPredicate.IntCompare(QueryPredicate.NumField.RATING,
                        QueryPredicate.CompareOp.LT, 3),
                parser.parse("rating:<3").get(0));
    }

    @Test
    void ratingBareIntIsEquality() {
        assertEquals(new QueryPredicate.IntEq(QueryPredicate.NumField.RATING, 5),
                parser.parse("rating:5").get(0));
    }

    @Test
    void tagLowercased() {
        assertEquals(new QueryPredicate.TagEq("acoustic"),
                parser.parse("tag:Acoustic").get(0));
    }

    @Test
    void lastPlayedBeforeWithMonths() {
        List<QueryPredicate> p = parser.parse("lastPlayed:>6mo");
        assertEquals(new QueryPredicate.LastPlayedBefore(Duration.ofDays(180)), p.get(0));
    }

    @Test
    void lastPlayedSinceWithDays() {
        List<QueryPredicate> p = parser.parse("lastPlayed:<30d");
        assertEquals(new QueryPredicate.LastPlayedSince(Duration.ofDays(30)), p.get(0));
    }

    @Test
    void lastPlayedRejectsBareValue() {
        assertThrows(QueryParseException.class, () -> parser.parse("lastPlayed:6mo"));
    }

    @Test
    void compoundQueryAndsAllClauses() {
        List<QueryPredicate> p = parser.parse(
                "genre:thrash_metal year:1984..1990 rating:>=4 lastPlayed:>6mo");
        assertEquals(4, p.size());
        assertTrue(p.get(0) instanceof QueryPredicate.GenreEq);
        assertTrue(p.get(1) instanceof QueryPredicate.IntRange);
        assertTrue(p.get(2) instanceof QueryPredicate.IntCompare);
        assertTrue(p.get(3) instanceof QueryPredicate.LastPlayedBefore);
    }

    @Test
    void unknownFieldThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("bogus:value"));
    }

    @Test
    void missingColonThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("genre"));
    }

    @Test
    void emptyValueThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("artist:"));
    }

    @Test
    void unterminatedQuoteThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("artist:\"The Who"));
    }

    @Test
    void queryLengthIsBounded() {
        String giant = "artist:a ".repeat(200);
        assertThrows(QueryParseException.class, () -> parser.parse(giant));
    }

    @Test
    void invalidNumericThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("rating:five"));
    }

    @Test
    void yearWeeksAndYearsDurations() {
        assertEquals(new QueryPredicate.LastPlayedBefore(Duration.ofDays(14)),
                parser.parse("lastPlayed:>2w").get(0));
        assertEquals(new QueryPredicate.LastPlayedSince(Duration.ofDays(365)),
                parser.parse("lastPlayed:<1y").get(0));
    }
}
