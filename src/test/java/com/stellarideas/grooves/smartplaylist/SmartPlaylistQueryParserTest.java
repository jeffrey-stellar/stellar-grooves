package com.stellarideas.grooves.smartplaylist;

import com.stellarideas.grooves.model.Genre;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SmartPlaylistQueryParserTest {

    private final SmartPlaylistQueryParser parser = new SmartPlaylistQueryParser();

    // ---------- helpers ----------

    private QueryExpr expr(String query) {
        ParsedQuery q = parser.parse(query);
        assertTrue(q.expression().isPresent(), "expected a parsed expression");
        return q.expression().get();
    }

    private QueryPredicate leaf(String query) {
        QueryExpr e = expr(query);
        assertInstanceOf(QueryExpr.Leaf.class, e, () -> "expected single Leaf, got " + e);
        return ((QueryExpr.Leaf) e).predicate();
    }

    private QueryExpr child(QueryExpr.And and, int i) {
        return and.children().get(i);
    }

    private QueryPredicate leafOf(QueryExpr e) {
        assertInstanceOf(QueryExpr.Leaf.class, e, () -> "expected Leaf, got " + e);
        return ((QueryExpr.Leaf) e).predicate();
    }

    // ---------- empty ----------

    @Test
    void emptyQueryReturnsEmpty() {
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("   ").isEmpty());
    }

    // ---------- single predicates ----------

    @Test
    void genreEqualsCaseInsensitive() {
        assertEquals(new QueryPredicate.GenreEq(Genre.THRASH_METAL), leaf("genre:thrash_metal"));
    }

    @Test
    void genreAcceptsSpaceVariantInQuotes() {
        assertEquals(new QueryPredicate.GenreEq(Genre.THRASH_METAL), leaf("genre:\"Thrash Metal\""));
    }

    @Test
    void unknownGenreThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("genre:disco"));
    }

    @Test
    void artistQuotedHandlesSpaces() {
        assertEquals(new QueryPredicate.TextContains(QueryPredicate.TextField.ARTIST, "The Who"),
                leaf("artist:\"The Who\""));
    }

    @Test
    void yearRangeParses() {
        assertEquals(new QueryPredicate.IntRange(QueryPredicate.NumField.YEAR, 1984, 1990),
                leaf("year:1984..1990"));
    }

    @Test
    void yearRangeRejectsInvertedBounds() {
        assertThrows(QueryParseException.class, () -> parser.parse("year:1990..1984"));
    }

    @Test
    void ratingComparatorsParse() {
        assertEquals(new QueryPredicate.IntCompare(QueryPredicate.NumField.RATING,
                        QueryPredicate.CompareOp.GTE, 4),
                leaf("rating:>=4"));
        assertEquals(new QueryPredicate.IntCompare(QueryPredicate.NumField.RATING,
                        QueryPredicate.CompareOp.LT, 3),
                leaf("rating:<3"));
    }

    @Test
    void ratingBareIntIsEquality() {
        assertEquals(new QueryPredicate.IntEq(QueryPredicate.NumField.RATING, 5),
                leaf("rating:5"));
    }

    @Test
    void tagLowercased() {
        assertEquals(new QueryPredicate.TagEq("acoustic"), leaf("tag:Acoustic"));
    }

    @Test
    void lastPlayedBeforeWithMonths() {
        assertEquals(new QueryPredicate.LastPlayedBefore(Duration.ofDays(180)), leaf("lastPlayed:>6mo"));
    }

    @Test
    void lastPlayedSinceWithDays() {
        assertEquals(new QueryPredicate.LastPlayedSince(Duration.ofDays(30)), leaf("lastPlayed:<30d"));
    }

    @Test
    void lastPlayedRejectsBareValue() {
        assertThrows(QueryParseException.class, () -> parser.parse("lastPlayed:6mo"));
    }

    @Test
    void weeksAndYearsDurations() {
        assertEquals(new QueryPredicate.LastPlayedBefore(Duration.ofDays(14)), leaf("lastPlayed:>2w"));
        assertEquals(new QueryPredicate.LastPlayedSince(Duration.ofDays(365)), leaf("lastPlayed:<1y"));
    }

    // ---------- implicit AND ----------

    @Test
    void compoundQueryAndsAllClauses() {
        QueryExpr e = expr("genre:thrash_metal year:1984..1990 rating:>=4 lastPlayed:>6mo");
        assertInstanceOf(QueryExpr.And.class, e);
        QueryExpr.And and = (QueryExpr.And) e;
        assertEquals(4, and.children().size());
        assertInstanceOf(QueryPredicate.GenreEq.class, leafOf(child(and, 0)));
        assertInstanceOf(QueryPredicate.IntRange.class, leafOf(child(and, 1)));
        assertInstanceOf(QueryPredicate.IntCompare.class, leafOf(child(and, 2)));
        assertInstanceOf(QueryPredicate.LastPlayedBefore.class, leafOf(child(and, 3)));
    }

    @Test
    void explicitAndKeywordIsEquivalentToWhitespace() {
        QueryExpr a = expr("genre:hard_rock AND rating:>=4");
        QueryExpr b = expr("genre:hard_rock rating:>=4");
        assertEquals(a, b);
    }

    @Test
    void ampAmpTreatedAsAnd() {
        QueryExpr e = expr("genre:hard_rock && rating:>=4");
        assertInstanceOf(QueryExpr.And.class, e);
        assertEquals(2, ((QueryExpr.And) e).children().size());
    }

    // ---------- OR ----------

    @Test
    void orKeywordCaseInsensitive() {
        QueryExpr upper = expr("genre:thrash_metal OR genre:hard_rock");
        QueryExpr lower = expr("genre:thrash_metal or genre:hard_rock");
        assertEquals(upper, lower);
        assertInstanceOf(QueryExpr.Or.class, upper);
    }

    @Test
    void orPipePipeIsEquivalent() {
        QueryExpr e = expr("genre:thrash_metal || genre:hard_rock");
        assertInstanceOf(QueryExpr.Or.class, e);
        assertEquals(2, ((QueryExpr.Or) e).children().size());
    }

    @Test
    void orCombinesTwoLeaves() {
        QueryExpr e = expr("genre:thrash_metal OR genre:hard_rock");
        assertInstanceOf(QueryExpr.Or.class, e);
        QueryExpr.Or or = (QueryExpr.Or) e;
        assertEquals(List.of(
                new QueryPredicate.GenreEq(Genre.THRASH_METAL),
                new QueryPredicate.GenreEq(Genre.HARD_ROCK)),
                or.children().stream().map(this::leafOf).toList());
    }

    @Test
    void andBindsTighterThanOr() {
        // "a b OR c" should parse as (a AND b) OR c
        QueryExpr e = expr("genre:thrash_metal rating:>=4 OR genre:hard_rock");
        assertInstanceOf(QueryExpr.Or.class, e);
        QueryExpr.Or or = (QueryExpr.Or) e;
        assertEquals(2, or.children().size());
        assertInstanceOf(QueryExpr.And.class, or.children().get(0));
        assertInstanceOf(QueryExpr.Leaf.class, or.children().get(1));
    }

    @Test
    void orWithAndOnBothSides() {
        // "a b OR c d" → (a AND b) OR (c AND d)
        QueryExpr e = expr("genre:thrash_metal rating:>=4 OR genre:hard_rock rating:>=3");
        QueryExpr.Or or = (QueryExpr.Or) e;
        assertEquals(2, or.children().size());
        assertInstanceOf(QueryExpr.And.class, or.children().get(0));
        assertInstanceOf(QueryExpr.And.class, or.children().get(1));
    }

    @Test
    void multipleOrsFlattenIntoSingleOrNode() {
        QueryExpr e = expr("genre:thrash_metal OR genre:hard_rock OR genre:heavy_metal");
        assertInstanceOf(QueryExpr.Or.class, e);
        assertEquals(3, ((QueryExpr.Or) e).children().size());
    }

    // ---------- parentheses ----------

    @Test
    void parensGroupExpression() {
        QueryExpr e = expr("(genre:thrash_metal OR genre:hard_rock) rating:>=4");
        assertInstanceOf(QueryExpr.And.class, e);
        QueryExpr.And and = (QueryExpr.And) e;
        assertEquals(2, and.children().size());
        assertInstanceOf(QueryExpr.Or.class, and.children().get(0));
        assertInstanceOf(QueryExpr.Leaf.class, and.children().get(1));
    }

    @Test
    void parensOverridePrecedence() {
        // Without parens: "a OR b c" = a OR (b AND c)
        // With parens: "(a OR b) c" = (a OR b) AND c
        QueryExpr without = expr("genre:thrash_metal OR genre:hard_rock rating:>=4");
        QueryExpr with = expr("(genre:thrash_metal OR genre:hard_rock) rating:>=4");
        assertNotEquals(without, with);
        assertInstanceOf(QueryExpr.Or.class, without);
        assertInstanceOf(QueryExpr.And.class, with);
    }

    @Test
    void parensTightWithoutSpaces() {
        QueryExpr spaced = expr("( genre:thrash_metal OR genre:hard_rock )");
        QueryExpr tight  = expr("(genre:thrash_metal OR genre:hard_rock)");
        assertEquals(spaced, tight);
    }

    @Test
    void unmatchedOpenParenThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("(genre:thrash_metal"));
    }

    @Test
    void unmatchedCloseParenThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("genre:thrash_metal)"));
    }

    @Test
    void emptyParensThrow() {
        assertThrows(QueryParseException.class, () -> parser.parse("()"));
    }

    @Test
    void orWithNothingAfterThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("genre:thrash_metal OR"));
    }

    @Test
    void andWithNothingAfterThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("genre:thrash_metal AND"));
    }

    // ---------- negation ----------

    @Test
    void negatedLeafWrapsInNot() {
        QueryExpr e = expr("-tag:skip");
        assertInstanceOf(QueryExpr.Not.class, e);
        QueryExpr.Not not = (QueryExpr.Not) e;
        assertEquals(new QueryPredicate.TagEq("skip"), leafOf(not.child()));
    }

    @Test
    void negatedGenreAndPositiveClauseCoexist() {
        QueryExpr e = expr("genre:hard_rock -tag:skip");
        assertInstanceOf(QueryExpr.And.class, e);
        QueryExpr.And and = (QueryExpr.And) e;
        assertEquals(2, and.children().size());
        assertInstanceOf(QueryExpr.Leaf.class, and.children().get(0));
        assertInstanceOf(QueryExpr.Not.class, and.children().get(1));
    }

    @Test
    void negatedArtistWithQuotedValue() {
        QueryExpr e = expr("-artist:\"Justin Bieber\"");
        QueryExpr.Not not = (QueryExpr.Not) e;
        assertEquals(new QueryPredicate.TextContains(QueryPredicate.TextField.ARTIST, "Justin Bieber"),
                leafOf(not.child()));
    }

    @Test
    void leadingDashBeforeLetterNegates() {
        QueryExpr e = expr("-rating:5");
        assertInstanceOf(QueryExpr.Not.class, e);
    }

    @Test
    void negativeIntValueNotConfusedWithNegation() {
        // rating:-1 — the '-' is inside the value, not a negation
        assertEquals(new QueryPredicate.IntEq(QueryPredicate.NumField.RATING, -1),
                leaf("rating:-1"));
    }

    @Test
    void negatedGroup() {
        QueryExpr e = expr("-(genre:thrash_metal OR tag:skip)");
        assertInstanceOf(QueryExpr.Not.class, e);
        QueryExpr.Not not = (QueryExpr.Not) e;
        assertInstanceOf(QueryExpr.Or.class, not.child());
    }

    @Test
    void negatedGroupWithLeadingSpaceDash() {
        // "- (genre:thrash)" is equivalent to "-(genre:thrash)"
        QueryExpr e = expr("- (genre:thrash_metal)");
        assertInstanceOf(QueryExpr.Not.class, e);
        QueryExpr.Not not = (QueryExpr.Not) e;
        assertEquals(new QueryPredicate.GenreEq(Genre.THRASH_METAL), leafOf(not.child()));
    }

    // ---------- errors ----------

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

    // ---------- sort ----------

    @Test
    void sortWithDefaultDirection() {
        ParsedQuery q = parser.parse("sort:rating");
        assertTrue(q.sort().isPresent());
        assertEquals(SortSpec.Field.RATING, q.sort().get().field());
        assertEquals(SortSpec.Direction.DESC, q.sort().get().direction());
        assertTrue(q.expression().isEmpty());
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
        assertThrows(QueryParseException.class, () -> parser.parse("sort:rating sort:year"));
    }

    @Test
    void unknownSortFieldThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("sort:bpm"));
    }

    @Test
    void sortCannotBeNegated() {
        assertThrows(QueryParseException.class, () -> parser.parse("-sort:rating"));
    }

    @Test
    void sortInsideParensRejected() {
        assertThrows(QueryParseException.class,
                () -> parser.parse("(genre:thrash_metal sort:rating)"));
    }

    @Test
    void sortOnOneSideOfOrRejected() {
        // sort: is still top-level only; placing it inside a paren-group or nested
        // should fail. Top-level placement after an OR is allowed since there are
        // no parens — but mixing sort/limit into an OR branch via parens is not.
        assertThrows(QueryParseException.class,
                () -> parser.parse("(genre:thrash_metal OR genre:hard_rock limit:10)"));
    }

    // ---------- limit ----------

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
        assertTrue(q.expression().isPresent());
        assertInstanceOf(QueryExpr.And.class, q.expression().get());
        assertEquals(2, ((QueryExpr.And) q.expression().get()).children().size());
        assertEquals(SortSpec.Field.RATING, q.sort().get().field());
        assertEquals(50, q.limit().orElseThrow());
    }
}
