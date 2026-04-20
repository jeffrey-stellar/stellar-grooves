package com.stellarideas.grooves.smartplaylist;

import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Converts a parsed predicate list into a MongoDB {@link Criteria} scoped to a user.
 * All predicates are AND'd; ownership and soft-delete filters are always appended.
 */
@Component
public class SmartPlaylistQueryTranslator {

    private final Clock clock;

    public SmartPlaylistQueryTranslator() {
        this(Clock.systemUTC());
    }

    public SmartPlaylistQueryTranslator(Clock clock) {
        this.clock = clock;
    }

    public Criteria translate(List<QueryPredicate> predicates, String userId) {
        Criteria base = Criteria.where("userId").is(userId).and("deleted").ne(true);
        if (predicates == null || predicates.isEmpty()) return base;

        for (QueryPredicate p : predicates) {
            applyTo(base, p);
        }
        return base;
    }

    private void applyTo(Criteria root, QueryPredicate p) {
        if (p instanceof QueryPredicate.GenreEq g) {
            root.and("genre").is(g.genre());
        } else if (p instanceof QueryPredicate.TextContains tc) {
            root.and(fieldName(tc.field())).regex(Pattern.quote(tc.value()), "i");
        } else if (p instanceof QueryPredicate.IntEq ie) {
            root.and(fieldName(ie.field())).is(intValueFor(ie.field(), ie.value()));
        } else if (p instanceof QueryPredicate.IntRange ir) {
            Object lo = intValueFor(ir.field(), ir.min());
            Object hi = intValueFor(ir.field(), ir.max());
            root.and(fieldName(ir.field())).gte(lo).lte(hi);
        } else if (p instanceof QueryPredicate.IntCompare ic) {
            Object v = intValueFor(ic.field(), ic.value());
            Criteria c = root.and(fieldName(ic.field()));
            switch (ic.op()) {
                case GT  -> c.gt(v);
                case GTE -> c.gte(v);
                case LT  -> c.lt(v);
                case LTE -> c.lte(v);
            }
        } else if (p instanceof QueryPredicate.TagEq te) {
            root.and("customTags").is(te.tag());
        } else if (p instanceof QueryPredicate.LastPlayedSince lps) {
            Instant threshold = Instant.now(clock).minus(lps.window());
            root.and("lastPlayedAt").gte(threshold);
        } else if (p instanceof QueryPredicate.LastPlayedBefore lpb) {
            Instant threshold = Instant.now(clock).minus(lpb.window());
            root.and("lastPlayedAt").lt(threshold);
        } else {
            throw new IllegalStateException("Unhandled predicate: " + p);
        }
    }

    private static String fieldName(QueryPredicate.TextField f) {
        return switch (f) {
            case ARTIST -> "artist";
            case ALBUM  -> "album";
            case TITLE  -> "title";
        };
    }

    private static String fieldName(QueryPredicate.NumField f) {
        return switch (f) {
            case YEAR       -> "year";
            case RATING     -> "rating";
            case PLAY_COUNT -> "playCount";
        };
    }

    /** Year is stored as a String; other numeric fields are stored as int. */
    private static Object intValueFor(QueryPredicate.NumField f, int v) {
        return f == QueryPredicate.NumField.YEAR ? String.valueOf(v) : v;
    }
}
