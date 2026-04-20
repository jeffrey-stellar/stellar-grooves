package com.stellarideas.grooves.smartplaylist;

import com.stellarideas.grooves.model.Genre;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SmartPlaylistQueryParserTest {

    private final SmartPlaylistQueryParser parser = new SmartPlaylistQueryParser();

    private List<QueryPredicate> predicates(String query) {
        return parser.parse(query).predicates();
    }

    @Test
    void emptyQueryReturnsEmpty() {
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("   ").isEmpty());
    }

    @Test
    void genreEqualsCaseInsensitive() {
        List<QueryPredicate> p = predicates("genre:thrash_metal");
        assertEquals(1, p.size());
        assertEquals(new QueryPredicate.GenreEq(Genre.THRASH_METAL), p.get(0));
    }

    @Test
    void genreAcceptsSpaceVariantInQuotes() {
        assertEquals(new QueryPredicate.GenreEq(Genre.THRASH_METAL),
                predicates("genre:\"Thrash Metal\"").get(0));
    }

    @Test
    void unknownGenreThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("genre:disco"));
    }

    @Test
    void artistQuotedHandlesSpaces() {
        assertEquals(new QueryPredicate.TextContains(QueryPredicate.TextField.ARTIST, "The Who"),
                predicates("artist:\"The Who\"").get(0));
    }

    @Test
    void yearRangeParses() {
        assertEquals(new QueryPredicate.IntRange(QueryPredicate.NumField.YEAR, 1984, 1990),
                predicates("year:1984..1990").get(0));
    }

    @Test
    void yearRangeRejectsInvertedBounds() {
        assertThrows(QueryParseException.class, () -> parser.parse("year:1990..1984"));
    }

    @Test
    void ratingComparatorsParse() {
        assertEquals(new QueryPredicate.IntCompare(QueryPredicate.NumField.RATING,
                        QueryPredicate.CompareOp.GTE, 4),
                predicates("rating:>=4").get(0));
        assertEquals(new QueryPredicate.IntCompare(QueryPredicate.NumField.RATING,
                        QueryPredicate.CompareOp.LT, 3),
                predicates("rating:<3").get(0));
    }

    @Test
    void ratingBareIntIsEquality() {
        assertEquals(new QueryPredicate.IntEq(QueryPredicate.NumField.RATING, 5),
                predicates("rating:5").get(0));
    }

    @Test
    void tagLowercased() {
        assertEquals(new QueryPredicate.TagEq("acoustic"),
                predicates("tag:Acoustic").get(0));
    }

    @Test
    void lastPlayedBeforeWithMonths() {
        assertEquals(new QueryPredicate.LastPlayedBefore(Duration.ofDays(180)),
                predicates("lastPlayed:>6mo").get(0));
    }

    @Test
    void lastPlayedSinceWithDays() {
        assertEquals(new QueryPredicate.LastPlayedSince(Duration.ofDays(30)),
                predicates("lastPlayed:<30d").get(0));
    }

    @Test
    void lastPlayedRejectsBareValue() {
        assertThrows(QueryParseException.class, () -> parser.parse("lastPlayed:6mo"));
    }

    @Test
    void compoundQueryAndsAllClauses() {
        List<QueryPredicate> p = predicates(
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
                predicates("lastPlayed:>2w").get(0));
        assertEquals(new QueryPredicate.LastPlayedSince(Duration.ofDays(365)),
                predicates("lastPlayed:<1y").get(0));
    }

    // ---- negation ----

    @Test
    void negatedTagWrapsInNot() {
        QueryPredicate p = predicates("-tag:skip").get(0);
        assertTrue(p instanceof QueryPredicate.Not);
        assertEquals(new QueryPredicate.TagEq("skip"), ((QueryPredicate.Not) p).inner());
    }

    @Test
    void negatedGenreAndPositiveClauseCoexist() {
        List<QueryPredicate> p = predicates("genre:hard_rock -tag:skip");
        assertEquals(2, p.size());
        assertTrue(p.get(0) instanceof QueryPredicate.GenreEq);
        assertTrue(p.get(1) instanceof QueryPredicate.Not);
    }

    @Test
    void negatedArtistWithQuotedValue() {
        QueryPredicate p = predicates("-artist:\"Justin Bieber\"").get(0);
        assertTrue(p instanceof QueryPredicate.Not);
        assertEquals(new QueryPredicate.TextContains(QueryPredicate.TextField.ARTIST, "Justin Bieber"),
                ((QueryPredicate.Not) p).inner());
    }

    @Test
    void leadingDashBeforeNonLetterIsNotNegation() {
        // e.g. a clause that starts with ':' or a comparator is invalid for other reasons,
        // but -rating:5 should still be read as negation (next char is letter)
        QueryPredicate p = predicates("-rating:5").get(0);
        assertTrue(p instanceof QueryPredicate.Not);
    }

    @Test
    void negationDoesNotBreakNumericValues() {
        // Year has no meaningful negative, but the parser still allows negative ints via INT_EQ;
        // a rating of -1 as a value is distinct from a negated clause.
        assertEquals(new QueryPredicate.IntEq(QueryPredicate.NumField.RATING, -1),
                predicates("rating:-1").get(0));
    }

    // ---- sort ----

    @Test
    void sortWithDefaultDirection() {
        ParsedQuery q = parser.parse("sort:rating");
        assertTrue(q.sort().isPresent());
        assertEquals(SortSpec.Field.RATING, q.sort().get().field());
        assertEquals(SortSpec.Direction.DESC, q.sort().get().direction());
    }

    @Test
    void sortWithExplicitDirection() {
        ParsedQuery q = parser.parse("sort:year:asc");
        assertEquals(SortSpec.Field.YEAR, q.sort().get().field());
        assertEquals(SortSpec.Direction.ASC, q.sort().get().direction());
    }

    @Test
    void sortTextFieldsDefaultToAsc() {
        assertEquals(SortSpec.Direction.ASC,
                parser.parse("sort:artist").sort().get().direction());
    }

    @Test
    void sortRandomWithoutLimitThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("sort:random"));
    }

    @Test
    void sortRandomWithLimitParses() {
        ParsedQuery q = parser.parse("sort:random limit:50");
        assertTrue(q.sort().isPresent());
        assertTrue(q.sort().get().isRandom());
        assertEquals(50, q.limit().orElseThrow());
    }

    @Test
    void sortRandomWithDirectionThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("sort:random:desc limit:10"));
    }

    @Test
    void multipleSortClausesReject() {
        assertThrows(QueryParseException.class,
                () -> parser.parse("sort:rating sort:year"));
    }

    @Test
    void unknownSortFieldThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("sort:bpm"));
    }

    @Test
    void sortCannotBeNegated() {
        assertThrows(QueryParseException.class, () -> parser.parse("-sort:rating"));
    }

    // ---- limit ----

    @Test
    void limitParses() {
        assertEquals(50, parser.parse("limit:50").limit().orElseThrow());
    }

    @Test
    void limitZeroRejected() {
        assertThrows(QueryParseException.class, () -> parser.parse("limit:0"));
    }

    @Test
    void limitNegativeRejected() {
        assertThrows(QueryParseException.class, () -> parser.parse("limit:-5"));
    }

    @Test
    void limitAboveMaxRejected() {
        assertThrows(QueryParseException.class,
                () -> parser.parse("limit:" + (SmartPlaylistQueryParser.MAX_LIMIT + 1)));
    }

    @Test
    void multipleLimitClausesReject() {
        assertThrows(QueryParseException.class, () -> parser.parse("limit:10 limit:20"));
    }

    @Test
    void limitCannotBeNegated() {
        assertThrows(QueryParseException.class, () -> parser.parse("-limit:10"));
    }

    @Test
    void sortLimitAndPredicatesParseTogether() {
        ParsedQuery q = parser.parse("genre:heavy_metal rating:>=4 sort:rating limit:50");
        assertEquals(2, q.predicates().size());
        assertEquals(SortSpec.Field.RATING, q.sort().get().field());
        assertEquals(50, q.limit().orElseThrow());
    }
}
