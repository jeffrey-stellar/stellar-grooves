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

import static org.junit.jupiter.api.Assertions.*;

class SmartPlaylistQueryTranslatorTest {

    private final SmartPlaylistQueryTranslator translator = new SmartPlaylistQueryTranslator(
            Clock.fixed(Instant.parse("2026-04-19T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void emptyPredicatesProducesOwnershipAndNotDeleted() {
        Criteria c = translator.translate(List.of(), "user-1");
        Document doc = c.getCriteriaObject();
        assertEquals("user-1", doc.get("userId"));
        Document deleted = (Document) doc.get("deleted");
        assertEquals(true, deleted.get("$ne"));
    }

    @Test
    void genreEqualityAddsGenreField() {
        Criteria c = translator.translate(
                List.of(new QueryPredicate.GenreEq(Genre.THRASH_METAL)), "u");
        assertEquals(Genre.THRASH_METAL, c.getCriteriaObject().get("genre"));
    }

    @Test
    void textContainsProducesCaseInsensitiveRegex() {
        Criteria c = translator.translate(
                List.of(new QueryPredicate.TextContains(
                        QueryPredicate.TextField.ARTIST, "AC/DC (Live)")), "u");
        Object clause = c.getCriteriaObject().get("artist");
        assertTrue(clause instanceof java.util.regex.Pattern,
                "expected compiled Pattern, got " + (clause == null ? "null" : clause.getClass()));
        java.util.regex.Pattern p = (java.util.regex.Pattern) clause;
        assertEquals(java.util.regex.Pattern.quote("AC/DC (Live)"), p.pattern());
        assertEquals(java.util.regex.Pattern.CASE_INSENSITIVE,
                p.flags() & java.util.regex.Pattern.CASE_INSENSITIVE);
    }

    @Test
    void ratingRangeUsesGteAndLte() {
        Criteria c = translator.translate(
                List.of(new QueryPredicate.IntRange(QueryPredicate.NumField.RATING, 3, 5)), "u");
        Document clause = (Document) c.getCriteriaObject().get("rating");
        assertEquals(3, clause.get("$gte"));
        assertEquals(5, clause.get("$lte"));
    }

    @Test
    void yearRangeSerializesAsString() {
        Criteria c = translator.translate(
                List.of(new QueryPredicate.IntRange(QueryPredicate.NumField.YEAR, 1984, 1990)), "u");
        Document clause = (Document) c.getCriteriaObject().get("year");
        assertEquals("1984", clause.get("$gte"));
        assertEquals("1990", clause.get("$lte"));
    }

    @Test
    void tagEqUsesExactMatch() {
        Criteria c = translator.translate(
                List.of(new QueryPredicate.TagEq("acoustic")), "u");
        assertEquals("acoustic", c.getCriteriaObject().get("customTags"));
    }

    @Test
    void lastPlayedBeforeUsesLtOnThreshold() {
        Criteria c = translator.translate(
                List.of(new QueryPredicate.LastPlayedBefore(Duration.ofDays(180))), "u");
        Document clause = (Document) c.getCriteriaObject().get("lastPlayedAt");
        Instant expected = Instant.parse("2026-04-19T00:00:00Z").minus(Duration.ofDays(180));
        assertEquals(expected, clause.get("$lt"));
    }

    @Test
    void lastPlayedSinceUsesGteOnThreshold() {
        Criteria c = translator.translate(
                List.of(new QueryPredicate.LastPlayedSince(Duration.ofDays(30))), "u");
        Document clause = (Document) c.getCriteriaObject().get("lastPlayedAt");
        Instant expected = Instant.parse("2026-04-19T00:00:00Z").minus(Duration.ofDays(30));
        assertEquals(expected, clause.get("$gte"));
    }

    @Test
    void notPredicateProducesNorClause() {
        Criteria c = translator.translate(
                List.of(new QueryPredicate.Not(new QueryPredicate.TagEq("skip"))), "u");
        Document doc = c.getCriteriaObject();
        assertEquals("u", doc.get("userId"));
        @SuppressWarnings("unchecked")
        List<Document> nor = (List<Document>) doc.get("$nor");
        assertNotNull(nor, "expected $nor clause, got: " + doc);
        assertEquals(1, nor.size());
        assertEquals("skip", nor.get(0).get("customTags"));
    }

    @Test
    void multipleNegationsShareOneNor() {
        Criteria c = translator.translate(
                List.of(
                        new QueryPredicate.Not(new QueryPredicate.TagEq("skip")),
                        new QueryPredicate.Not(new QueryPredicate.GenreEq(Genre.OTHER))),
                "u");
        @SuppressWarnings("unchecked")
        List<Document> nor = (List<Document>) c.getCriteriaObject().get("$nor");
        assertNotNull(nor);
        assertEquals(2, nor.size());
    }

    @Test
    void positiveAndNegativeCoexist() {
        Criteria c = translator.translate(
                List.of(
                        new QueryPredicate.GenreEq(Genre.HARD_ROCK),
                        new QueryPredicate.Not(new QueryPredicate.TagEq("skip"))),
                "u");
        Document doc = c.getCriteriaObject();
        assertEquals(Genre.HARD_ROCK, doc.get("genre"));
        assertNotNull(doc.get("$nor"));
    }

    @Test
    void multiplePredicatesAreAllPresent() {
        Criteria c = translator.translate(
                List.of(
                        new QueryPredicate.GenreEq(Genre.THRASH_METAL),
                        new QueryPredicate.IntCompare(QueryPredicate.NumField.RATING,
                                QueryPredicate.CompareOp.GTE, 4),
                        new QueryPredicate.TagEq("live")),
                "u");
        Document doc = c.getCriteriaObject();
        assertEquals("u", doc.get("userId"));
        assertEquals(Genre.THRASH_METAL, doc.get("genre"));
        assertEquals("live", doc.get("customTags"));
        Document rating = (Document) doc.get("rating");
        assertEquals(4, rating.get("$gte"));
    }
}
