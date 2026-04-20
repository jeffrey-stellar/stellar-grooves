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
 * Parses the smart-playlist DSL.
 *
 * <p>Grammar (space-separated clauses, AND-combined):
 * <pre>
 *   query     := clause (WS+ clause)*
 *   clause    := predicate | sortClause | limitClause
 *   predicate := ['-'] field ':' value
 *   field     := genre | artist | album | title | year | rating | tag | lastPlayed | playCount
 *   value     := range | comparator | quotedString | bareWord | relativeDuration
 *   range     := int '..' int                 (year, rating, playCount)
 *   comparator:= ('>=' | '>' | '<=' | '<') (int | duration)
 *   duration  := digits ('d' | 'w' | 'mo' | 'y')
 *   sortClause  := 'sort:' sortField (':' direction)?
 *   sortField   := rating | year | playcount | lastplayed | artist | album | title | random
 *   direction   := 'asc' | 'desc'
 *   limitClause := 'limit:' positiveInt
 * </pre>
 * Quoted strings (double quotes) allow spaces and colons inside values.
 * A leading {@code -} on a predicate negates it ({@code -tag:skip}).
 * {@code sort:random} requires an accompanying {@code limit:} clause.
 */
@Component
public class SmartPlaylistQueryParser {

    private static final Pattern INT_RANGE = Pattern.compile("^(-?\\d+)\\.\\.(-?\\d+)$");
    private static final Pattern INT_CMP = Pattern.compile("^(>=|<=|>|<)(-?\\d+)$");
    private static final Pattern DUR_CMP = Pattern.compile("^(>=|<=|>|<)(\\d+)(d|w|mo|y)$");
    private static final Pattern INT_EQ = Pattern.compile("^-?\\d+$");
    private static final Pattern POS_INT = Pattern.compile("^\\d+$");

    public static final int MAX_QUERY_LENGTH = 1000;
    public static final int MAX_LIMIT = 100_000;

    public ParsedQuery parse(String query) {
        if (query == null || query.isBlank()) return ParsedQuery.empty();
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new QueryParseException("Query exceeds " + MAX_QUERY_LENGTH + " characters");
        }
        List<String> tokens = tokenize(query);
        List<QueryPredicate> predicates = new ArrayList<>(tokens.size());
        Optional<SortSpec> sort = Optional.empty();
        OptionalInt limit = OptionalInt.empty();

        for (String token : tokens) {
            boolean negated = false;
            String working = token;
            if (working.startsWith("-") && working.length() > 1 && Character.isLetter(working.charAt(1))) {
                negated = true;
                working = working.substring(1);
            }

            int colon = indexOfUnquotedColon(working);
            if (colon <= 0 || colon == working.length() - 1) {
                throw new QueryParseException("Expected 'field:value' in clause: " + token);
            }
            String field = working.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = working.substring(colon + 1);

            if ("sort".equals(field)) {
                if (negated) throw new QueryParseException("sort clause cannot be negated");
                if (sort.isPresent()) throw new QueryParseException("Only one sort clause is allowed");
                sort = Optional.of(parseSort(value));
                continue;
            }
            if ("limit".equals(field)) {
                if (negated) throw new QueryParseException("limit clause cannot be negated");
                if (limit.isPresent()) throw new QueryParseException("Only one limit clause is allowed");
                limit = OptionalInt.of(parseLimit(value));
                continue;
            }

            QueryPredicate predicate = parseClause(field, value);
            predicates.add(negated ? new QueryPredicate.Not(predicate) : predicate);
        }

        if (sort.isPresent() && sort.get().isRandom() && limit.isEmpty()) {
            throw new QueryParseException("sort:random requires a limit: clause");
        }

        return new ParsedQuery(List.copyOf(predicates), sort, limit);
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
