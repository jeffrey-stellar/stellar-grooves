package com.stellarideas.grooves.smartplaylist;

import java.util.List;

/**
 * Parsed expression tree for the smart-playlist DSL. Built by
 * {@link SmartPlaylistQueryParser} and consumed by {@link SmartPlaylistQueryTranslator}.
 *
 * <p>The grammar supports AND (implicit via whitespace, or explicit {@code AND}/{@code &&}),
 * OR ({@code OR}/{@code ||}), negation (leading {@code -}), and parenthesised grouping.
 * AND binds tighter than OR.
 */
public sealed interface QueryExpr {

    /** A single field:value predicate. */
    record Leaf(QueryPredicate predicate) implements QueryExpr {}

    /** Conjunction of two or more subexpressions. Never constructed with &lt;2 children. */
    record And(List<QueryExpr> children) implements QueryExpr {}

    /** Disjunction of two or more subexpressions. Never constructed with &lt;2 children. */
    record Or(List<QueryExpr> children) implements QueryExpr {}

    /** Logical negation of a subexpression. */
    record Not(QueryExpr child) implements QueryExpr {}
}
