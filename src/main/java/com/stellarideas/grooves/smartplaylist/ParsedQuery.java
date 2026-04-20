package com.stellarideas.grooves.smartplaylist;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Output of {@link SmartPlaylistQueryParser#parse(String)}. Carries the filter
 * expression tree plus optional ordering and result-count limit parsed from the
 * top-level {@code sort:} and {@code limit:} clauses.
 */
public record ParsedQuery(
        Optional<QueryExpr> expression,
        Optional<SortSpec> sort,
        OptionalInt limit) {

    public static ParsedQuery empty() {
        return new ParsedQuery(Optional.empty(), Optional.empty(), OptionalInt.empty());
    }

    public boolean isEmpty() {
        return expression.isEmpty() && sort.isEmpty() && limit.isEmpty();
    }
}
