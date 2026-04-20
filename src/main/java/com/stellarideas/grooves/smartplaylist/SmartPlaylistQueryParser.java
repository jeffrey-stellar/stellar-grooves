package com.stellarideas.grooves.smartplaylist;

import com.stellarideas.grooves.model.Genre;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the smart-playlist DSL.
 *
 * <p>Grammar (space-separated AND clauses):
 * <pre>
 *   query     := clause (WS+ clause)*
 *   clause    := field ':' value
 *   field     := genre | artist | album | title | year | rating | tag | lastPlayed | playCount
 *   value     := range | comparator | quotedString | bareWord | relativeDuration
 *   range     := int '..' int                 (year, rating, playCount)
 *   comparator:= ('>=' | '>' | '<=' | '<') (int | duration)
 *   duration  := digits ('d' | 'w' | 'mo' | 'y')
 * </pre>
 * Quoted strings (double quotes) allow spaces and colons inside values.
 */
@Component
public class SmartPlaylistQueryParser {

    private static final Pattern INT_RANGE = Pattern.compile("^(-?\\d+)\\.\\.(-?\\d+)$");
    private static final Pattern INT_CMP = Pattern.compile("^(>=|<=|>|<)(-?\\d+)$");
    private static final Pattern DUR_CMP = Pattern.compile("^(>=|<=|>|<)(\\d+)(d|w|mo|y)$");
    private static final Pattern INT_EQ = Pattern.compile("^-?\\d+$");

    public static final int MAX_QUERY_LENGTH = 1000;

    public List<QueryPredicate> parse(String query) {
        if (query == null || query.isBlank()) return List.of();
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new QueryParseException("Query exceeds " + MAX_QUERY_LENGTH + " characters");
        }
        List<String> tokens = tokenize(query);
        List<QueryPredicate> predicates = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            int colon = indexOfUnquotedColon(token);
            if (colon <= 0 || colon == token.length() - 1) {
                throw new QueryParseException("Expected 'field:value' in clause: " + token);
            }
            String field = token.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = token.substring(colon + 1);
            predicates.add(parseClause(field, value));
        }
        return predicates;
    }

    /** Split query on whitespace, respecting double-quoted runs. */
    static List<String> tokenize(String query) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                cur.append(c);
            } else if (!inQuotes && Character.isWhitespace(c)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (inQuotes) throw new QueryParseException("Unterminated quoted string");
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static int indexOfUnquotedColon(String s) {
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            else if (c == ':' && !inQuotes) return i;
        }
        return -1;
    }

    private QueryPredicate parseClause(String field, String value) {
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
}
