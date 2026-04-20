package com.stellarideas.grooves.smartplaylist;

import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Converts a parsed {@link QueryExpr} tree into a MongoDB {@link Criteria} scoped
 * to a user. The root always includes ownership ({@code userId}) and soft-delete
 * ({@code deleted != true}) filters.
 *
 * <p>The translator flattens the common case — a pure conjunction of leaf predicates
 * (with optional single-level negations) — into a flat criteria with chained
 * {@code .and()} calls and a single {@code $nor} for negations. This keeps queries
 * compatible with compound indexes. When OR or nested groups appear, the expression
 * is emitted under an {@code $and} wrapper alongside the ownership base.
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

    public Criteria translate(Optional<QueryExpr> expression, String userId) {
        Criteria base = Criteria.where("userId").is(userId).and("deleted").ne(true);
        if (expression.isEmpty()) return base;

        QueryExpr root = expression.get();
        if (canFlatten(root)) {
            applyFlatTerms(base, root);
            return base;
        }
        return new Criteria().andOperator(base, translateExpr(root));
    }

    public Criteria translate(QueryExpr expression, String userId) {
        return translate(Optional.ofNullable(expression), userId);
    }

    // ---------- flat fast-path ----------

    private static boolean canFlatten(QueryExpr e) {
        if (e instanceof QueryExpr.Leaf) return true;
        if (e instanceof QueryExpr.Not not) return not.child() instanceof QueryExpr.Leaf;
        if (e instanceof QueryExpr.And and) {
            for (QueryExpr child : and.children()) {
                if (child instanceof QueryExpr.Leaf) continue;
                if (child instanceof QueryExpr.Not n && n.child() instanceof QueryExpr.Leaf) continue;
                return false;
            }
            return true;
        }
        return false;
    }

    private void applyFlatTerms(Criteria base, QueryExpr e) {
        List<QueryPredicate> positives = new ArrayList<>();
        List<QueryPredicate> negatives = new ArrayList<>();
        collectFlat(e, positives, negatives);
        for (QueryPredicate p : positives) applyPredicate(base, p);
        if (!negatives.isEmpty()) {
            Criteria[] nor = new Criteria[negatives.size()];
            for (int i = 0; i < negatives.size(); i++) {
                Criteria c = new Criteria();
                applyPredicate(c, negatives.get(i));
                nor[i] = c;
            }
            base.norOperator(nor);
        }
    }

    private void collectFlat(QueryExpr e, List<QueryPredicate> pos, List<QueryPredicate> neg) {
        if (e instanceof QueryExpr.Leaf leaf) {
            pos.add(leaf.predicate());
        } else if (e instanceof QueryExpr.Not not && not.child() instanceof QueryExpr.Leaf leaf) {
            neg.add(leaf.predicate());
        } else if (e instanceof QueryExpr.And and) {
            for (QueryExpr child : and.children()) collectFlat(child, pos, neg);
        } else {
            throw new IllegalStateException("Not flattenable: " + e);
        }
    }

    // ---------- general recursive translation ----------

    private Criteria translateExpr(QueryExpr e) {
        if (e instanceof QueryExpr.Leaf leaf) {
            Criteria c = new Criteria();
            applyPredicate(c, leaf.predicate());
            return c;
        }
        if (e instanceof QueryExpr.And and) {
            Criteria[] kids = and.children().stream()
                    .map(this::translateExpr)
                    .toArray(Criteria[]::new);
            return new Criteria().andOperator(kids);
        }
        if (e instanceof QueryExpr.Or or) {
            Criteria[] kids = or.children().stream()
                    .map(this::translateExpr)
                    .toArray(Criteria[]::new);
            return new Criteria().orOperator(kids);
        }
        if (e instanceof QueryExpr.Not not) {
            return new Criteria().norOperator(translateExpr(not.child()));
        }
        throw new IllegalStateException("Unhandled expression: " + e);
    }

    // ---------- predicate → criteria ----------

    private void applyPredicate(Criteria root, QueryPredicate p) {
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
            root.and("lastPlayedAt").gte(Instant.now(clock).minus(lps.window()));
        } else if (p instanceof QueryPredicate.LastPlayedBefore lpb) {
            root.and("lastPlayedAt").lt(Instant.now(clock).minus(lpb.window()));
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
