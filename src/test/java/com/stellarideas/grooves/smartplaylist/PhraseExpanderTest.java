package com.stellarideas.grooves.smartplaylist;

import com.stellarideas.grooves.model.SmartPlaylistPhrase;
import com.stellarideas.grooves.repository.SmartPlaylistPhraseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PhraseExpanderTest {

    private SmartPlaylistPhraseRepository repository;
    private SmartPlaylistQueryParser parser;
    private PhraseExpander expander;

    private final Map<String, String> phrases = new HashMap<>();

    @BeforeEach
    void setUp() {
        repository = mock(SmartPlaylistPhraseRepository.class);
        parser = new SmartPlaylistQueryParser();
        expander = new PhraseExpander(repository, parser);
        phrases.clear();
    }

    /** Register a phrase and wire the mock repo to return it for any user. */
    private void definePhrase(String name, String body) {
        phrases.put(name, body);
        SmartPlaylistPhrase p = new SmartPlaylistPhrase();
        p.setId("phrase-" + name);
        p.setUserId("u1");
        p.setName(name);
        p.setBody(body);
        when(repository.findByUserIdAndName(eq("u1"), eq(name))).thenReturn(Optional.of(p));
    }

    private QueryExpr expand(String query) {
        ParsedQuery parsed = parser.parse(query);
        return expander.expand(parsed.expression(), "u1").orElseThrow();
    }

    @Test
    void simpleReferenceExpandsToBody() {
        definePhrase("thrash-core", "genre:thrash_metal");

        QueryExpr expanded = expand("@thrash-core");

        assertInstanceOf(QueryExpr.Leaf.class, expanded);
        QueryPredicate pred = ((QueryExpr.Leaf) expanded).predicate();
        assertInstanceOf(QueryPredicate.GenreEq.class, pred);
    }

    @Test
    void referenceCombinedWithLiteralExpression() {
        definePhrase("thrash-core", "genre:thrash_metal");

        QueryExpr expanded = expand("@thrash-core rating:>=4");

        assertInstanceOf(QueryExpr.And.class, expanded);
        QueryExpr.And and = (QueryExpr.And) expanded;
        assertInstanceOf(QueryExpr.Leaf.class, and.children().get(0));
        assertInstanceOf(QueryExpr.Leaf.class, and.children().get(1));
        // Both should be Leaf — the @ ref expanded into a Leaf
    }

    @Test
    void nestedReferencesExpandRecursively() {
        definePhrase("eighties", "year:1980..1989");
        definePhrase("eighties-thrash", "@eighties genre:thrash_metal");

        QueryExpr expanded = expand("@eighties-thrash");

        // After full expansion, no PhraseRef nodes survive.
        assertNoPhraseRefs(expanded);
        assertInstanceOf(QueryExpr.And.class, expanded);
    }

    @Test
    void undefinedReferenceRaisesQueryParseException() {
        QueryParseException ex = assertThrows(QueryParseException.class,
                () -> expand("@missing"));
        assertTrue(ex.getMessage().contains("@missing"));
    }

    @Test
    void cycleIsDetectedAndRejected() {
        definePhrase("a", "@b");
        definePhrase("b", "@a");

        QueryParseException ex = assertThrows(QueryParseException.class,
                () -> expand("@a"));
        assertTrue(ex.getMessage().toLowerCase().contains("cycle"));
    }

    @Test
    void deepChainExceedsMaxDepth() {
        // Build a chain longer than MAX_DEPTH
        for (int i = 0; i < PhraseExpander.MAX_DEPTH + 2; i++) {
            String body = i == PhraseExpander.MAX_DEPTH + 1
                    ? "rating:>=4"
                    : "@p" + (i + 1);
            definePhrase("p" + i, body);
        }

        assertThrows(QueryParseException.class, () -> expand("@p0"));
    }

    @Test
    void phraseBodyWithSortClauseRejected() {
        definePhrase("bad", "rating:>=4 sort:rating");
        QueryParseException ex = assertThrows(QueryParseException.class, () -> expand("@bad"));
        assertTrue(ex.getMessage().contains("sort"));
    }

    @Test
    void phraseBodyWithLimitClauseRejected() {
        definePhrase("bad", "rating:>=4 limit:50");
        QueryParseException ex = assertThrows(QueryParseException.class, () -> expand("@bad"));
        assertTrue(ex.getMessage().contains("limit"));
    }

    @Test
    void negatedReferenceExpands() {
        definePhrase("skip", "tag:skip");

        QueryExpr expanded = expand("-@skip");

        assertInstanceOf(QueryExpr.Not.class, expanded);
        QueryExpr inner = ((QueryExpr.Not) expanded).child();
        assertInstanceOf(QueryExpr.Leaf.class, inner);
    }

    @Test
    void emptyExpressionExpandsToEmpty() {
        Optional<QueryExpr> result = expander.expand(Optional.empty(), "u1");
        assertTrue(result.isEmpty());
    }

    @Test
    void leafOnlyTreeUntouched() {
        ParsedQuery parsed = parser.parse("rating:>=4");
        QueryExpr before = parsed.expression().orElseThrow();
        QueryExpr after = expander.expand(before, "u1");
        assertEquals(before, after);
    }

    private static void assertNoPhraseRefs(QueryExpr e) {
        if (e instanceof QueryExpr.PhraseRef) fail("Unresolved phrase ref: " + e);
        if (e instanceof QueryExpr.Not n) assertNoPhraseRefs(n.child());
        if (e instanceof QueryExpr.And a) a.children().forEach(PhraseExpanderTest::assertNoPhraseRefs);
        if (e instanceof QueryExpr.Or o) o.children().forEach(PhraseExpanderTest::assertNoPhraseRefs);
    }
}
