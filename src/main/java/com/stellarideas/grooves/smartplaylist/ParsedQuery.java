package com.stellarideas.grooves.smartplaylist;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Output of {@link SmartPlaylistQueryParser#parse(String)}. Carries the filter
 * predicates plus optional ordering and result-count limit parsed from
 * {@code sort:} and {@code limit:} clauses.
 */
public record ParsedQuery(
        List<QueryPredicate> predicates,
        Optional<SortSpec> sort,
        OptionalInt limit) {

    public static ParsedQuery empty() {
        return new ParsedQuery(List.of(), Optional.empty(), OptionalInt.empty());
    }

    public boolean isEmpty() {
        return predicates.isEmpty() && sort.isEmpty() && limit.isEmpty();
    }
}
