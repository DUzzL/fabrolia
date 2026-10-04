package com.axalotl.fabrolia.common.regions;

import java.util.ArrayList;
import java.util.List;

/**
 * Options of one selector as written in the exact parsed argument range
 * ({@code @a[scores={x=1..},tag=!y]}). Vanilla compiles the options into opaque
 * predicates; the analyzer needs their names and values, so it reads the
 * argument text vanilla itself accepted. Name/UUID targets have kind
 * {@code '\0'}.
 */
public record FunctionSelectorOptions(char kind, List<Option> options) {

    public record Option(String key, boolean negated, String value) {}

    public static FunctionSelectorOptions parse(String text) {
        String t = text.trim();
        if (t.length() < 2 || t.charAt(0) != '@') {
            return new FunctionSelectorOptions('\0', List.of());
        }
        char kind = t.charAt(1);
        int open = t.indexOf('[');
        if (open < 0) return new FunctionSelectorOptions(kind, List.of());
        int close = t.lastIndexOf(']');
        if (close < open) {
            throw new IllegalArgumentException("unbalanced selector text: " + text);
        }
        ArrayList<Option> out = new ArrayList<>();
        String body = t.substring(open + 1, close);
        int i = 0;
        while (i < body.length()) {
            int eq = body.indexOf('=', i);
            if (eq < 0) break;
            String key = body.substring(i, eq).trim();
            int v = eq + 1;
            while (v < body.length() && body.charAt(v) == ' ') v++;
            boolean negated = v < body.length() && body.charAt(v) == '!';
            if (negated) v++;
            int end = valueEnd(body, v);
            out.add(new Option(key, negated, body.substring(v, end).trim()));
            i = end + 1;
        }
        return new FunctionSelectorOptions(kind, List.copyOf(out));
    }

    /** Index of the ',' ending a value at depth 0 (or the body end). */
    private static int valueEnd(String s, int from) {
        int depth = 0;
        char quote = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') { i++; continue; }
                if (c == quote) quote = 0;
                continue;
            }
            switch (c) {
                case '"', '\'' -> quote = c;
                case '{', '[' -> depth++;
                case '}', ']' -> depth--;
                case ',' -> {
                    if (depth == 0) return i;
                }
                default -> { }
            }
        }
        return s.length();
    }

    public boolean has(String key) {
        for (Option o : options) if (o.key().equals(key)) return true;
        return false;
    }

    public List<Option> all(String key) {
        ArrayList<Option> out = new ArrayList<>(2);
        for (Option o : options) if (o.key().equals(key)) out.add(o);
        return out;
    }

    /** Objective names of a {@code scores={a=1..,b=2}} value. */
    public static List<String> scoreObjectives(String value) {
        ArrayList<String> out = new ArrayList<>();
        String v = value.trim();
        if (v.startsWith("{")) v = v.substring(1);
        if (v.endsWith("}")) v = v.substring(0, v.length() - 1);
        for (String part : v.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) out.add(unquote(part.substring(0, eq).trim()));
        }
        return out;
    }

    static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'')
                && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
