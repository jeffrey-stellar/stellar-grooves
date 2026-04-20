package com.stellarideas.grooves.smartplaylist;

import com.stellarideas.grooves.model.Genre;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SmartPlaylistQueryTranslatorTest {

    private static final Instant NOW = Instant.parse("2026-04-19T00:00:00Z");

    private final SmartPlaylistQueryTranslator translator = new SmartPlaylistQueryTranslator(
            Clock.fixed(NOW, ZoneOffset.UTC));

    // ---------- helpers ----------

    private static QueryExpr leaf(QueryPredicate p) { return new QueryExpr.Leaf(p); }
    private static QueryExpr and(QueryExpr... children) { return new QueryExpr.And(List.of(children)); }
    private static QueryExpr or(QueryExpr... children)  { return new QueryExpr.Or(List.of(children)); }
    private static QueryExpr not(QueryExpr child) { return new QueryExpr.Not(child); }

    private Document doc(QueryExpr expr, String userId) {
        return translator.translate(expr, userId).getCriteriaObject();
    }

    // ---------- base scope ----------

    @Test
    void emptyExpressionProducesOwnershipAndNotDeleted() {
        Criteria c = translator.translate(Optional.empty(), "user-1");
        Document d = c.getCriteriaObject();
        assertEquals("user-1", d.get("userId"));
        Document deleted = (Document) d.get("deleted");
        assertEquals(true, deleted.get("$ne"));
    }

    @Test
    void nullExpressionFallsBackToBase() {
        Document d = translator.translate((QueryExpr) null, "u").getCriteriaObject();
        assertEquals("u", d.get("userId"));
    }

    // ---------- flat single-leaf predicates ----------

    @Test
    void genreEqualityAddsGenreField() {
        Document d = doc(leaf(new QueryPredicate.GenreEq(Genre.THRASH_METAL)), "u");
        assertEquals(Genre.THRASH_METAL, d.get("genre"));
        assertEquals("u", d.get("userId"));
    }

    @Test
    void textContainsProducesCaseInsensitiveRegex() {
        Document d = doc(leaf(new QueryPredicate.TextContains(
                QueryPredicate.TextField.ARTIST, "AC/DC (Live)")), "u");
        Object clause = d.get("artist");
        assertInstanceOf(java.util.regex.Pattern.class, clause);
        java.util.regex.Pattern p = (java.util.regex.Pattern) clause;
        assertEquals(java.util.regex.Pattern.quote("AC/DC (Live)"), p.pattern());
        assertEquals(java.util.regex.Pattern.CASE_INSENSITIVE,
                p.flags() & java.util.regex.Pattern.CASE_INSENSITIVE);
    }

    @Test
    void ratingRangeUsesGteAndLte() {
        Document d = doc(leaf(new QueryPredicate.IntRange(QueryPredicate.NumField.RATING, 3, 5)), "u");
        Document clause = (Document) d.get("rating");
        assertEquals(3, clause.get("$gte"));
        assertEquals(5, clause.get("$lte"));
    }

    @Test
    void yearRangeSerializesAsString() {
        Document d = doc(leaf(new QueryPredicate.IntRange(QueryPredicate.NumField.YEAR, 1984, 1990)), "u");
        Document clause = (Document) d.get("year");
        assertEquals("1984", clause.get("$gte"));
        assertEquals("1990", clause.get("$lte"));
    }

    @Test
    void tagEqUsesExactMatch() {
        Document d = doc(leaf(new QueryPredicate.TagEq("acoustic")), "u");
        assertEquals("acoustic", d.get("customTags"));
    }

    @Test
    void lastPlayedBeforeUsesLtOnThreshold() {
        Document d = doc(leaf(new QueryPredicate.LastPlayedBefore(Duration.ofDays(180))), "u");
        Document clause = (Document) d.get("lastPlayedAt");
        assertEquals(NOW.minus(Duration.ofDays(180)), clause.get("$lt"));
    }

    @Test
    void lastPlayedSinceUsesGteOnThreshold() {
        Document d = doc(leaf(new QueryPredicate.LastPlayedSince(Duration.ofDays(30))), "u");
        Document clause = (Document) d.get("lastPlayedAt");
        assertEquals(NOW.minus(Duration.ofDays(30)), clause.get("$gte"));
    }

    // ---------- negation (flat) ----------

    @Test
    void notLeafProducesNorClauseAtTopLevel() {
        Document d = doc(not(leaf(new QueryPredicate.TagEq("skip"))), "u");
        assertEquals("u", d.get("userId"));
        @SuppressWarnings("unchecked")
        List<Document> nor = (List<Document>) d.get("$nor");
        assertNotNull(nor);
        assertEquals(1, nor.size());
        assertEquals("skip", nor.get(0).get("customTags"));
    }

    @Test
    void multipleNegationsShareOneNor() {
        Document d = doc(and(
                not(leaf(new QueryPredicate.TagEq("skip"))),
                not(leaf(new QueryPredicate.GenreEq(Genre.OTHER)))), "u");
        @SuppressWarnings("unchecked")
        List<Document> nor = (List<Document>) d.get("$nor");
        assertNotNull(nor);
        assertEquals(2, nor.size());
    }

    @Test
    void positiveAndNegativeCoexist() {
        Document d = doc(and(
                leaf(new QueryPredicate.GenreEq(Genre.HARD_ROCK)),
                not(leaf(new QueryPredicate.TagEq("skip")))), "u");
        assertEquals(Genre.HARD_ROCK, d.get("genre"));
        assertNotNull(d.get("$nor"));
    }

    @Test
    void multiplePredicatesAreAllPresent() {
        Document d = doc(and(
                leaf(new QueryPredicate.GenreEq(Genre.THRASH_METAL)),
                leaf(new QueryPredicate.IntCompare(QueryPredicate.NumField.RATING,
                        QueryPredicate.CompareOp.GTE, 4)),
                leaf(new QueryPredicate.TagEq("live"))), "u");
        assertEquals("u", d.get("userId"));
        assertEquals(Genre.THRASH_METAL, d.get("genre"));
        assertEquals("live", d.get("customTags"));
        Document rating = (Document) d.get("rating");
        assertEquals(4, rating.get("$gte"));
    }

    // ---------- OR (wrapped) ----------

    @Test
    void orAtRootWrapsBaseAndExpressionUnderAnd() {
        Document d = doc(or(
                leaf(new QueryPredicate.GenreEq(Genre.THRASH_METAL)),
                leaf(new QueryPredicate.GenreEq(Genre.HARD_ROCK))), "u");

        @SuppressWarnings("unchecked")
        List<Document> topAnd = (List<Document>) d.get("$and");
        assertNotNull(topAnd, "expected $and wrapper when OR appears at the root");
        assertEquals(2, topAnd.size());

        Document base = topAnd.get(0);
        assertEquals("u", base.get("userId"));

        Document orDoc = topAnd.get(1);
        @SuppressWarnings("unchecked")
        List<Document> orList = (List<Document>) orDoc.get("$or");
        assertNotNull(orList);
        assertEquals(2, orList.size());
        assertEquals(Genre.THRASH_METAL, orList.get(0).get("genre"));
        assertEquals(Genre.HARD_ROCK, orList.get(1).get("genre"));
    }

    @Test
    void andOfOrAndLeafWrapsCorrectly() {
        // (thrash OR hard_rock) AND rating:>=4
        Document d = doc(and(
                or(leaf(new QueryPredicate.GenreEq(Genre.THRASH_METAL)),
                   leaf(new QueryPredicate.GenreEq(Genre.HARD_ROCK))),
                leaf(new QueryPredicate.IntCompare(QueryPredicate.NumField.RATING,
                        QueryPredicate.CompareOp.GTE, 4))), "u");

        @SuppressWarnings("unchecked")
        List<Document> topAnd = (List<Document>) d.get("$and");
        assertNotNull(topAnd);
        // base + expression ($and of Or + rating)
        assertEquals(2, topAnd.size());
        assertEquals("u", topAnd.get(0).get("userId"));

        Document innerAnd = topAnd.get(1);
        @SuppressWarnings("unchecked")
        List<Document> andList = (List<Document>) innerAnd.get("$and");
        assertNotNull(andList);
        assertEquals(2, andList.size());
        assertNotNull(andList.get(0).get("$or"));
        assertNotNull(andList.get(1).get("rating"));
    }

    @Test
    void notOfOrUsesNorAroundInnerOr() {
        // NOT (tag:skip OR tag:demo)
        Document d = doc(not(or(
                leaf(new QueryPredicate.TagEq("skip")),
                leaf(new QueryPredicate.TagEq("demo")))), "u");

        @SuppressWarnings("unchecked")
        List<Document> topAnd = (List<Document>) d.get("$and");
        assertNotNull(topAnd);
        assertEquals(2, topAnd.size());

        Document negWrapper = topAnd.get(1);
        @SuppressWarnings("unchecked")
        List<Document> nor = (List<Document>) negWrapper.get("$nor");
        assertNotNull(nor);
        assertEquals(1, nor.size());

        @SuppressWarnings("unchecked")
        List<Document> innerOr = (List<Document>) nor.get(0).get("$or");
        assertNotNull(innerOr);
        assertEquals(2, innerOr.size());
    }
}
