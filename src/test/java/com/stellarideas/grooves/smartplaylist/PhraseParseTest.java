package com.stellarideas.grooves.smartplaylist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser tests focused on {@code @phrase} reference syntax. Resolution is tested
 * separately in {@link PhraseExpanderTest}.
 */
class PhraseParseTest {

    private final SmartPlaylistQueryParser parser = new SmartPlaylistQueryParser();

    @Test
    void singlePhraseRefAtRoot() {
        ParsedQuery q = parser.parse("@jazz-core");
        QueryExpr e = q.expression().orElseThrow();
        assertInstanceOf(QueryExpr.PhraseRef.class, e);
        assertEquals("jazz-core", ((QueryExpr.PhraseRef) e).name());
    }

    @Test
    void phraseRefCombinedWithLeafIsAndedTogether() {
        ParsedQuery q = parser.parse("@jazz-core rating:>=4");
        QueryExpr e = q.expression().orElseThrow();
        assertInstanceOf(QueryExpr.And.class, e);
        QueryExpr.And and = (QueryExpr.And) e;
        assertEquals(2, and.children().size());
        assertInstanceOf(QueryExpr.PhraseRef.class, and.children().get(0));
        assertInstanceOf(QueryExpr.Leaf.class, and.children().get(1));
    }

    @Test
    void phraseRefInOr() {
        ParsedQuery q = parser.parse("@a OR @b");
        QueryExpr e = q.expression().orElseThrow();
        assertInstanceOf(QueryExpr.Or.class, e);
        QueryExpr.Or or = (QueryExpr.Or) e;
        assertEquals(2, or.children().size());
        assertInstanceOf(QueryExpr.PhraseRef.class, or.children().get(0));
        assertInstanceOf(QueryExpr.PhraseRef.class, or.children().get(1));
    }

    @Test
    void inlineNegatedPhraseRef() {
        ParsedQuery q = parser.parse("-@skip-list");
        QueryExpr e = q.expression().orElseThrow();
        assertInstanceOf(QueryExpr.Not.class, e);
        QueryExpr.Not not = (QueryExpr.Not) e;
        assertInstanceOf(QueryExpr.PhraseRef.class, not.child());
        assertEquals("skip-list", ((QueryExpr.PhraseRef) not.child()).name());
    }

    @Test
    void phraseRefInsideParens() {
        ParsedQuery q = parser.parse("(@core OR @adjacent) rating:>=4");
        QueryExpr e = q.expression().orElseThrow();
        assertInstanceOf(QueryExpr.And.class, e);
        QueryExpr.And and = (QueryExpr.And) e;
        assertInstanceOf(QueryExpr.Or.class, and.children().get(0));
    }

    @Test
    void invalidPhraseNameRejected() {
        assertThrows(QueryParseException.class, () -> parser.parse("@Invalid"));      // uppercase
        assertThrows(QueryParseException.class, () -> parser.parse("@-bad"));         // leading dash
        assertThrows(QueryParseException.class, () -> parser.parse("@with.dot"));     // dot
        assertThrows(QueryParseException.class, () -> parser.parse("@"));             // empty
    }

    @Test
    void phraseRefInsideQuotedStringStaysLiteral() {
        // The artist value is quoted; the @ inside should NOT become a phrase ref —
        // it's part of the artist text. Verifies tokenizer correctness.
        ParsedQuery q = parser.parse("artist:\"@ Foo\"");
        QueryExpr e = q.expression().orElseThrow();
        assertInstanceOf(QueryExpr.Leaf.class, e);
        QueryPredicate pred = ((QueryExpr.Leaf) e).predicate();
        assertInstanceOf(QueryPredicate.TextContains.class, pred);
        assertEquals("@ Foo", ((QueryPredicate.TextContains) pred).value());
    }

    @Test
    void phraseRefAcceptsDigitsAndUnderscores() {
        ParsedQuery q = parser.parse("@year_1990s");
        QueryExpr e = q.expression().orElseThrow();
        assertInstanceOf(QueryExpr.PhraseRef.class, e);
        assertEquals("year_1990s", ((QueryExpr.PhraseRef) e).name());
    }
}
