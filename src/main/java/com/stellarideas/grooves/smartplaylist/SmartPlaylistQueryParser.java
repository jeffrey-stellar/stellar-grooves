package com.stellarideas.grooves.smartplaylist;

import com.stellarideas.grooves.model.Genre;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the smart-playlist DSL into a {@link ParsedQuery} carrying an expression tree.
 *
 * <p>Grammar:
 * <pre>
 *   query       := clause*
 *   clause      := sortClause | limitClause | expression-token
 *   expression  := orTerm ( OR orTerm )*
 *   orTerm      := andTerm ( AND? andTerm )*          (AND is optional — whitespace implies it)
 *   andTerm     := '-'? primary
 *   primary     := '(' expression ')' | predicate
 *   predicate   := field ':' value
 *   field       := genre | artist | album | title | year | rating | tag | lastPlayed | playCount
 *   value       := range | comparator | quotedString | bareWord | relativeDuration
 *   range       := int '..' int
 *   comparator  := ('&gt;=' | '&gt;' | '&lt;=' | '&lt;') (int | duration)
 *   duration    := digits ('d' | 'w' | 'mo' | 'y')
 *   sortClause  := 'sort:' sortField (':' direction)?
 *   limitClause := 'limit:' positiveInt
 * </pre>
 *
 * <p>AND binds tighter than OR. Parentheses override precedence. Quoted strings
 * (double quotes) allow spaces and colons inside values. A leading {@code -} on
 * a clause or parenthesised group negates it. {@code OR}/{@code ||} and
 * {@code AND}/{@code &amp;&amp;} are case-insensitive keywords. {@code sort:} and
 * {@code limit:} are top-level only (cannot appear inside parens or across OR).
 * {@code sort:random} requires an accompanying {@code limit:}.
 */
@Component
public class SmartPlaylistQueryParser {

    private static final Pattern INT_RANGE = Pattern.compile("^(-?\\d+)\\.\\.(-?\\d+)$");
    private static final Pattern INT_CMP = Pattern.compile("^(>=|<=|>|<)(-?\\d+)$");
    private static final Pattern DUR_CMP = Pattern.compile("^(>=|<=|>|<)(\\d+)(d|w|mo|y)$");
    private static final Pattern INT_EQ = Pattern.compile("^-?\\d+$");
    private static final Pattern POS_INT = Pattern.compile("^\\d+$");
    /** Matches the body of an {@code @phrase} reference (after the {@code @}). */
    private static final Pattern PHRASE_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");

    public static final int MAX_QUERY_LENGTH = 1000;
    public static final int MAX_LIMIT = 100_000;

    public ParsedQuery parse(String query) {
        if (query == null || query.isBlank()) return ParsedQuery.empty();
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new QueryParseException("Query exceeds " + MAX_QUERY_LENGTH + " characters");
        }

        List<String> allTokens = tokenize(query);

        // Extract top-level sort:/limit: clauses. These are meta-clauses that must
        // live outside parens and outside OR branches — we pull them out of the
        // token stream before parsing the expression.
        Optional<SortSpec> sort = Optional.empty();
        OptionalInt limit = OptionalInt.empty();
        List<String> exprTokens = new ArrayList<>(allTokens.size());
        int depth = 0;
        for (String tok : allTokens) {
            if (tok.equals("(")) { depth++; exprTokens.add(tok); continue; }
            if (tok.equals(")")) {
                depth--;
                if (depth < 0) throw new QueryParseException("Unmatched ')'");
                exprTokens.add(tok);
                continue;
            }

            MetaClause meta = maybeMetaClause(tok);
            if (meta == null) { exprTokens.add(tok); continue; }

            if (depth > 0) {
                throw new QueryParseException(meta.field() + " clause must be at the top level, not inside parentheses");
            }
            if (meta.negated()) {
                throw new QueryParseException(meta.field() + " clause cannot be negated");
            }
            if (meta.field().equals("sort")) {
                if (sort.isPresent()) throw new QueryParseException("Only one sort clause is allowed");
                sort = Optional.of(parseSort(meta.value()));
            } else {
                if (limit.isPresent()) throw new QueryParseException("Only one limit clause is allowed");
                limit = OptionalInt.of(parseLimit(meta.value()));
            }
        }
        if (depth != 0) throw new QueryParseException("Unmatched '('");

        if (sort.isPresent() && sort.get().isRandom() && limit.isEmpty()) {
            throw new QueryParseException("sort:random requires a limit: clause");
        }

        Optional<QueryExpr> expression;
        if (exprTokens.isEmpty()) {
            expression = Optional.empty();
        } else {
            Cursor cursor = new Cursor(exprTokens);
            QueryExpr expr = parseExpression(cursor);
            if (!cursor.atEnd()) {
                throw new QueryParseException("Unexpected token: " + cursor.peek());
            }
            expression = Optional.of(expr);
        }

        return new ParsedQuery(expression, sort, limit);
    }

    // ---------- tokenizer ----------

    /**
     * Split query into tokens. Whitespace, {@code (}, and {@code )} are separators
     * outside of double-quoted strings. {@code (} and {@code )} are emitted as
     * standalone single-character tokens.
     */
    static List<String> tokenize(String query) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                cur.append(c);
                continue;
            }
            if (inQuotes) {
                cur.append(c);
                continue;
            }
            if (Character.isWhitespace(c)) {
                flushToken(cur, out);
                continue;
            }
            if (c == '(' || c == ')') {
                flushToken(cur, out);
                out.add(String.valueOf(c));
                continue;
            }
            cur.append(c);
        }
        if (inQuotes) throw new QueryParseException("Unterminated quoted string");
        flushToken(cur, out);
        return out;
    }

    private static void flushToken(StringBuilder cur, List<String> out) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    // ---------- recursive descent ----------

    private QueryExpr parseExpression(Cursor c) {
        QueryExpr first = parseOrTerm(c);
        if (!c.peekIs("OR", "||")) return first;
        List<QueryExpr> children = new ArrayList<>();
        children.add(first);
        while (c.matchKeyword("OR", "||")) {
            children.add(parseOrTerm(c));
        }
        return new QueryExpr.Or(children);
    }

    /** A run of andTerms, separated by optional AND/&amp;&amp; or just adjacency. */
    private QueryExpr parseOrTerm(Cursor c) {
        QueryExpr first = parseAndTerm(c);
        List<QueryExpr> children = null;
        while (true) {
            if (c.atEnd() || c.peekIs(")", "OR", "||")) break;
            c.matchKeyword("AND", "&&"); // optional explicit AND between terms
            if (c.atEnd() || c.peekIs(")", "OR", "||")) {
                throw new QueryParseException("Expected expression after AND");
            }
            QueryExpr next = parseAndTerm(c);
            if (children == null) {
                children = new ArrayList<>();
                children.add(first);
            }
            children.add(next);
        }
        return children == null ? first : new QueryExpr.And(children);
    }

    private QueryExpr parseAndTerm(Cursor c) {
        if (c.peekIs("-")) {
            c.consume();
            return new QueryExpr.Not(parsePrimary(c));
        }
        return parsePrimary(c);
    }

    private QueryExpr parsePrimary(Cursor c) {
        if (c.atEnd()) throw new QueryParseException("Expected expression");
        String tok = c.peek();
        if (tok.equals("(")) {
            c.consume();
            QueryExpr inner = parseExpression(c);
            if (!c.peekIs(")")) throw new QueryParseException("Expected ')'");
            c.consume();
            return inner;
        }
        if (isOperatorKeyword(tok) || tok.equals(")")) {
            throw new QueryParseException("Unexpected token: " + tok);
        }
        c.consume();
        return parseLeaf(tok);
    }

    private QueryExpr parseLeaf(String token) {
        boolean negated = false;
        String working = token;
        if (working.startsWith("-") && working.length() > 1
                && (Character.isLetter(working.charAt(1)) || working.charAt(1) == '@')) {
            negated = true;
            working = working.substring(1);
        }
        if (working.startsWith("@")) {
            String name = working.substring(1);
            if (name.isEmpty() || !PHRASE_NAME.matcher(name).matches()) {
                throw new QueryParseException("Invalid phrase name: " + token
                        + " (expected lowercase letters, digits, '-', '_')");
            }
            QueryExpr ref = new QueryExpr.PhraseRef(name);
            return negated ? new QueryExpr.Not(ref) : ref;
        }
        int colon = indexOfUnquotedColon(working);
        if (colon <= 0 || colon == working.length() - 1) {
            throw new QueryParseException("Expected 'field:value' in clause: " + token);
        }
        String field = working.substring(0, colon).toLowerCase(Locale.ROOT);
        String value = working.substring(colon + 1);
        QueryPredicate pred = parsePredicate(field, value);
        QueryExpr leaf = new QueryExpr.Leaf(pred);
        return negated ? new QueryExpr.Not(leaf) : leaf;
    }

    private QueryPredicate parsePredicate(String field, String value) {
        return switch (field) {
            case "genre"      -> parseGenre(value);
            case "artist"     -> new QueryPredicate.TextContains(QueryPredicate.TextField.ARTIST, requireText(value, field));
            case "album"      -> new QueryPredicate.TextContains(QueryPredicate.TextField.ALBUM, requireText(value, field));
            case "title"      -> new QueryPredicate.TextContains(QueryPredicate.TextField.TITLE, requireText(value, field));
            case "year"       -> parseNumeric(QueryPredicate.NumField.YEAR, value);
            case "rating"     -> parseNumeric(QueryPredicate.NumField.RATING, value);
            case "playcount"  -> parseNumeric(QueryPredicate.NumField.PLAY_COUNT, value);
            case "tag"        -> new QueryPredicate.TagEq(requireText(value, field).toLowerCase(Locale.ROOT));
            case "lastplayed" -> parseLastPlayed(value);
            default           -> throw new QueryParseException("Unknown field: " + field);
        };
    }

    // ---------- meta-clause (sort/limit) detection ----------

    private record MetaClause(String field, String value, boolean negated) {}

    /** If {@code token} is a top-level {@code sort:} or {@code limit:} clause, return it; else null. */
    private static MetaClause maybeMetaClause(String token) {
        boolean negated = false;
        String working = token;
        if (working.startsWith("-") && working.length() > 1 && Character.isLetter(working.charAt(1))) {
            negated = true;
            working = working.substring(1);
        }
        int colon = indexOfUnquotedColon(working);
        if (colon <= 0 || colon == working.length() - 1) return null;
        String field = working.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!field.equals("sort") && !field.equals("limit")) return null;
        return new MetaClause(field, working.substring(colon + 1), negated);
    }

    // ---------- sort/limit value parsers ----------

    private static SortSpec parseSort(String value) {
        String v = unquote(value);
        if (v.isBlank()) throw new QueryParseException("Empty sort value");
        String[] parts = v.split(":", 2);
        SortSpec.Field field = parseSortField(parts[0]);
        SortSpec.Direction dir = parts.length == 2
                ? parseDirection(parts[1], field)
                : defaultDirection(field);
        return new SortSpec(field, dir);
    }

    private static SortSpec.Field parseSortField(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "rating"     -> SortSpec.Field.RATING;
            case "year"       -> SortSpec.Field.YEAR;
            case "playcount"  -> SortSpec.Field.PLAY_COUNT;
            case "lastplayed" -> SortSpec.Field.LAST_PLAYED;
            case "artist"     -> SortSpec.Field.ARTIST;
            case "album"      -> SortSpec.Field.ALBUM;
            case "title"      -> SortSpec.Field.TITLE;
            case "random"     -> SortSpec.Field.RANDOM;
            default -> throw new QueryParseException("Unknown sort field: " + raw);
        };
    }

    private static SortSpec.Direction parseDirection(String raw, SortSpec.Field field) {
        if (field == SortSpec.Field.RANDOM) {
            throw new QueryParseException("sort:random does not accept a direction");
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "asc"  -> SortSpec.Direction.ASC;
            case "desc" -> SortSpec.Direction.DESC;
            default -> throw new QueryParseException("Unknown sort direction: " + raw);
        };
    }

    private static SortSpec.Direction defaultDirection(SortSpec.Field field) {
        return switch (field) {
            case RATING, PLAY_COUNT, LAST_PLAYED -> SortSpec.Direction.DESC;
            case YEAR, ARTIST, ALBUM, TITLE -> SortSpec.Direction.ASC;
            case RANDOM -> SortSpec.Direction.ASC; // unused
        };
    }

    private static int parseLimit(String value) {
        String v = unquote(value);
        if (!POS_INT.matcher(v).matches()) {
            throw new QueryParseException("limit requires a positive integer: " + value);
        }
        long parsed;
        try {
            parsed = Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new QueryParseException("limit is out of range: " + value);
        }
        if (parsed <= 0 || parsed > MAX_LIMIT) {
            throw new QueryParseException("limit must be between 1 and " + MAX_LIMIT);
        }
        return (int) parsed;
    }

    // ---------- predicate value parsers ----------

    private static QueryPredicate.GenreEq parseGenre(String value) {
        String v = unquote(value).toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return new QueryPredicate.GenreEq(Genre.valueOf(v));
        } catch (IllegalArgumentException e) {
            throw new QueryParseException("Unknown genre: " + value);
        }
    }

    private static QueryPredicate parseNumeric(QueryPredicate.NumField field, String value) {
        String v = unquote(value);
        Matcher m;
        if ((m = INT_RANGE.matcher(v)).matches()) {
            int lo = Integer.parseInt(m.group(1));
            int hi = Integer.parseInt(m.group(2));
            if (lo > hi) throw new QueryParseException("Range lower bound exceeds upper: " + v);
            return new QueryPredicate.IntRange(field, lo, hi);
        }
        if ((m = INT_CMP.matcher(v)).matches()) {
            return new QueryPredicate.IntCompare(field, compareOp(m.group(1)), Integer.parseInt(m.group(2)));
        }
        if (INT_EQ.matcher(v).matches()) {
            return new QueryPredicate.IntEq(field, Integer.parseInt(v));
        }
        throw new QueryParseException("Invalid numeric value for " + field.name().toLowerCase() + ": " + value);
    }

    private static QueryPredicate parseLastPlayed(String value) {
        Matcher m = DUR_CMP.matcher(unquote(value));
        if (!m.matches()) {
            throw new QueryParseException("Invalid lastPlayed value (expected e.g. >6mo, <30d): " + value);
        }
        Duration d = toDuration(Integer.parseInt(m.group(2)), m.group(3));
        return switch (m.group(1)) {
            case ">", ">=" -> new QueryPredicate.LastPlayedBefore(d);
            case "<", "<=" -> new QueryPredicate.LastPlayedSince(d);
            default -> throw new QueryParseException("Unsupported comparator in lastPlayed: " + m.group(1));
        };
    }

    private static Duration toDuration(int n, String unit) {
        return switch (unit) {
            case "d"  -> Duration.ofDays(n);
            case "w"  -> Duration.ofDays((long) n * 7);
            case "mo" -> Duration.ofDays((long) n * 30);
            case "y"  -> Duration.ofDays((long) n * 365);
            default   -> throw new QueryParseException("Unknown duration unit: " + unit);
        };
    }

    private static QueryPredicate.CompareOp compareOp(String s) {
        return switch (s) {
            case ">"  -> QueryPredicate.CompareOp.GT;
            case ">=" -> QueryPredicate.CompareOp.GTE;
            case "<"  -> QueryPredicate.CompareOp.LT;
            case "<=" -> QueryPredicate.CompareOp.LTE;
            default   -> throw new QueryParseException("Unknown comparator: " + s);
        };
    }

    // ---------- helpers ----------

    private static int indexOfUnquotedColon(String s) {
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            else if (c == ':' && !inQuotes) return i;
        }
        return -1;
    }

    private static String requireText(String value, String field) {
        String v = unquote(value);
        if (v.isBlank()) throw new QueryParseException("Empty value for " + field);
        return v;
    }

    static String unquote(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static boolean isOperatorKeyword(String tok) {
        return tok.equalsIgnoreCase("OR") || tok.equals("||")
                || tok.equalsIgnoreCase("AND") || tok.equals("&&")
                || tok.equals("-");
    }

    /** Simple token cursor for the recursive-descent parser. */
    private static final class Cursor {
        private final List<String> tokens;
        private int pos;

        Cursor(List<String> tokens) { this.tokens = tokens; }
        boolean atEnd() { return pos >= tokens.size(); }
        String peek() { return tokens.get(pos); }
        String consume() { return tokens.get(pos++); }

        boolean peekIs(String... opts) {
            if (atEnd()) return false;
            String t = tokens.get(pos);
            for (String o : opts) {
                if (o.equals("OR") || o.equals("AND")) {
                    if (t.equalsIgnoreCase(o)) return true;
                } else if (o.equals(t)) {
                    return true;
                }
            }
            return false;
        }

        boolean matchKeyword(String... opts) {
            if (peekIs(opts)) { pos++; return true; }
            return false;
        }
    }
}
