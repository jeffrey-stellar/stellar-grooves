package com.stellarideas.grooves.smartplaylist;

import com.stellarideas.grooves.model.SmartPlaylistPhrase;
import com.stellarideas.grooves.repository.SmartPlaylistPhraseRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Expands {@link QueryExpr.PhraseRef} nodes against a user's phrase library.
 *
 * <p>Phrases can reference other phrases. Resolution is recursive with two guards:
 * a per-traversal {@link #MAX_DEPTH} chain length, and a per-call
 * {@link #MAX_RESOLUTIONS} cap on total references expanded — together these
 * prevent runaway expansion from a phrase library with cyclic or deeply branching
 * dependencies.
 *
 * <p>Cycle detection uses a visiting-set so {@code @a → @b → @a} fails loudly
 * with a clear message rather than recursing until the depth cap.
 *
 * <p>Phrase bodies are themselves parsed as queries; if a body contains a
 * top-level {@code sort:} or {@code limit:} clause it is rejected here — those
 * are concerns of the smart playlist that uses the phrase, not the phrase itself.
 */
@Component
public class PhraseExpander {

    /** Maximum chain length for a single phrase reference path. */
    public static final int MAX_DEPTH = 8;

    /** Maximum total number of {@code PhraseRef} nodes resolved in one expansion. */
    public static final int MAX_RESOLUTIONS = 64;

    private final SmartPlaylistPhraseRepository repository;
    private final SmartPlaylistQueryParser parser;

    public PhraseExpander(SmartPlaylistPhraseRepository repository,
                          SmartPlaylistQueryParser parser) {
        this.repository = repository;
        this.parser = parser;
    }

    /**
     * Expand all {@code @phrase} references in {@code expr} against
     * {@code ownerUserId}'s phrase library. Returns a new tree with no
     * {@link QueryExpr.PhraseRef} nodes.
     *
     * @throws QueryParseException for undefined references, cycles,
     *                             over-deep chains, or if a phrase body itself
     *                             carries a top-level sort/limit
     */
    public QueryExpr expand(QueryExpr expr, String ownerUserId) {
        if (expr == null) return null;
        Counter counter = new Counter();
        return expand(expr, ownerUserId, new ArrayDeque<>(), 0, counter);
    }

    /** Convenience for callers that only have an Optional (matches ParsedQuery.expression()). */
    public Optional<QueryExpr> expand(Optional<QueryExpr> expr, String ownerUserId) {
        return expr.map(e -> expand(e, ownerUserId));
    }

    private QueryExpr expand(QueryExpr expr, String ownerUserId, Deque<String> visiting,
                             int depth, Counter counter) {
        if (depth > MAX_DEPTH) {
            throw new QueryParseException(
                    "Phrase reference chain exceeds " + MAX_DEPTH + " levels — likely a runaway");
        }
        if (expr instanceof QueryExpr.PhraseRef ref) {
            counter.bump();
            if (visiting.contains(ref.name())) {
                throw new QueryParseException(
                        "Phrase reference cycle: " + visiting + " → @" + ref.name());
            }
            SmartPlaylistPhrase phrase = repository.findByUserIdAndName(ownerUserId, ref.name())
                    .orElseThrow(() -> new QueryParseException(
                            "Undefined phrase: @" + ref.name()));

            ParsedQuery parsed = parser.parse(phrase.getBody());
            if (parsed.sort().isPresent() || parsed.limit().isPresent()) {
                throw new QueryParseException(
                        "Phrase @" + ref.name() + " contains sort: or limit: which are not allowed in phrase bodies");
            }
            QueryExpr body = parsed.expression()
                    .orElseThrow(() -> new QueryParseException(
                            "Phrase @" + ref.name() + " has an empty body"));

            visiting.push(ref.name());
            try {
                return expand(body, ownerUserId, visiting, depth + 1, counter);
            } finally {
                visiting.pop();
            }
        }
        if (expr instanceof QueryExpr.Leaf) return expr;
        if (expr instanceof QueryExpr.Not not) {
            QueryExpr inner = expand(not.child(), ownerUserId, visiting, depth, counter);
            return new QueryExpr.Not(inner);
        }
        if (expr instanceof QueryExpr.And and) {
            return new QueryExpr.And(expandAll(and.children(), ownerUserId, visiting, depth, counter));
        }
        if (expr instanceof QueryExpr.Or or) {
            return new QueryExpr.Or(expandAll(or.children(), ownerUserId, visiting, depth, counter));
        }
        throw new IllegalStateException("Unhandled expression node: " + expr);
    }

    private List<QueryExpr> expandAll(List<QueryExpr> children, String ownerUserId,
                                      Deque<String> visiting, int depth, Counter counter) {
        List<QueryExpr> out = new ArrayList<>(children.size());
        for (QueryExpr child : children) {
            out.add(expand(child, ownerUserId, visiting, depth, counter));
        }
        return out;
    }

    /**
     * Tracks how many phrase refs have been resolved across the whole expansion.
     * Pathological libraries (e.g. branching fan-out across many siblings) can hit
     * this even without a cycle or a deep chain.
     */
    private static final class Counter {
        int n;
        void bump() {
            n++;
            if (n > MAX_RESOLUTIONS) {
                throw new QueryParseException(
                        "Too many phrase references in this query (over " + MAX_RESOLUTIONS + ")");
            }
        }
    }
}
