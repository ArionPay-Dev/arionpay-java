package com.arionpay;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader and writer (RFC 8259), so the library needs no dependencies.
 *
 * <p>Objects become {@code LinkedHashMap<String, Object>}, arrays {@code ArrayList<Object>}, numbers {@code Long} when
 * they are whole and fit, otherwise {@code BigDecimal} (never a rounded double).
 */
final class Json {
    private static final int MAX_DEPTH = 64;

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    /** Parses UTF-8 bytes; invalid UTF-8 is an error. */
    static Object parse(byte[] utf8) throws ParseException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ParseException("not UTF-8");
        }
        return parse(text);
    }

    static Object parse(String text) throws ParseException {
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) {
            throw new ParseException("unexpected data after JSON at " + p.i);
        }
        return v;
    }

    private Object value(int depth) throws ParseException {
        if (depth > MAX_DEPTH) {
            throw new ParseException("nested too deep");
        }
        if (i >= s.length()) {
            throw new ParseException("unexpected end");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                return word("true", Boolean.TRUE);
            case 'f':
                return word("false", Boolean.FALSE);
            case 'n':
                return word("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw new ParseException("unexpected character at " + i);
        }
    }

    private Object word(String w, Object v) throws ParseException {
        if (!s.startsWith(w, i)) {
            throw new ParseException("unexpected character at " + i);
        }
        i += w.length();
        return v;
    }

    private Map<String, Object> object(int depth) throws ParseException {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw new ParseException("expected a key at " + i);
            }
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value(depth + 1));
            ws();
            char c = next();
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw new ParseException("expected , or } at " + (i - 1));
            }
        }
    }

    private List<Object> array(int depth) throws ParseException {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return a;
        }
        while (true) {
            ws();
            a.add(value(depth + 1));
            ws();
            char c = next();
            if (c == ']') {
                return a;
            }
            if (c != ',') {
                throw new ParseException("expected , or ] at " + (i - 1));
            }
        }
    }

    private String string() throws ParseException {
        i++; // opening quote
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw new ParseException("unterminated string");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c < 0x20) {
                throw new ParseException("control character in string at " + (i - 1));
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) {
                        throw new ParseException("bad \\u escape");
                    }
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException x) {
                        throw new ParseException("bad \\u escape");
                    }
                    i += 4;
                    break;
                default:
                    throw new ParseException("bad escape at " + (i - 1));
            }
        }
    }

    private Object number() throws ParseException {
        int start = i;
        if (peek() == '-') {
            i++;
        }
        if (peek() == '0') {
            i++;
        } else if (peek() >= '1' && peek() <= '9') {
            while (peek() >= '0' && peek() <= '9') {
                i++;
            }
        } else {
            throw new ParseException("bad number at " + start);
        }
        boolean whole = true;
        if (peek() == '.') {
            whole = false;
            i++;
            if (!(peek() >= '0' && peek() <= '9')) {
                throw new ParseException("bad number at " + start);
            }
            while (peek() >= '0' && peek() <= '9') {
                i++;
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            whole = false;
            i++;
            if (peek() == '+' || peek() == '-') {
                i++;
            }
            if (!(peek() >= '0' && peek() <= '9')) {
                throw new ParseException("bad number at " + start);
            }
            while (peek() >= '0' && peek() <= '9') {
                i++;
            }
        }
        String t = s.substring(start, i);
        if (whole && t.length() <= 18) {
            return Long.parseLong(t);
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException | ArithmeticException x) {
            throw new ParseException("bad number at " + start);
        }
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            i++;
        }
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private char next() throws ParseException {
        if (i >= s.length()) {
            throw new ParseException("unexpected end");
        }
        return s.charAt(i++);
    }

    private void expect(char c) throws ParseException {
        if (next() != c) {
            throw new ParseException("expected " + c + " at " + (i - 1));
        }
    }

    // ------------------------------------------------------------------------------------------------- writer

    /** Compact JSON text. Supports Map, Collection, arrays, CharSequence, Number, Boolean and null. */
    static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, 0);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Value nested too deep (or it contains itself).");
        }
        if (v == null) {
            b.append("null");
        } else if (v instanceof CharSequence || v instanceof Character || v instanceof Enum) {
            quote(b, v.toString());
        } else if (v instanceof Boolean) {
            b.append(v.toString());
        } else if (v instanceof BigDecimal) {
            b.append(((BigDecimal) v).toString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("JSON has no NaN or Infinity.");
            }
            b.append(new BigDecimal(v.toString()).stripTrailingZeros().toPlainString());
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte || v instanceof BigInteger) {
            b.append(v.toString());
        } else if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                quote(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue(), depth + 1);
            }
            b.append('}');
        } else if (v instanceof Collection) {
            b.append('[');
            boolean first = true;
            for (Object x : (Collection<?>) v) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                write(b, x, depth + 1);
            }
            b.append(']');
        } else if (v instanceof Object[]) {
            List<Object> l = new ArrayList<>();
            for (Object x : (Object[]) v) {
                l.add(x);
            }
            write(b, l, depth);
        } else {
            throw new IllegalArgumentException("Cannot send a " + v.getClass().getName() + " as JSON; use Map, List, String, Number or Boolean.");
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }
}
