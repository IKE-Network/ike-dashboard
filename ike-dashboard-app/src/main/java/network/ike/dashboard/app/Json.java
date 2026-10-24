/*
 * Copyright © 2026 IKE Network (support@ike.network)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.dashboard.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON to read Nexus's search answers: objects become maps, arrays lists, numbers
 * {@code Long} or {@code Double}, and {@code null} stays {@code null}.
 */
final class Json {

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /**
     * Reads a JSON document.
     *
     * @param text the document
     * @return its value
     * @throws IllegalArgumentException if the text is not JSON
     */
    static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        json.space();
        if (json.at != text.length()) {
            throw json.error("trailing text");
        }
        return value;
    }

    private Object value() {
        space();
        if (at >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(at);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> object = new LinkedHashMap<>();
        at++;
        space();
        if (peek() == '}') {
            at++;
            return object;
        }
        while (true) {
            space();
            String key = string();
            space();
            expect(':');
            object.put(key, value());
            space();
            if (peek() == ',') {
                at++;
            } else {
                expect('}');
                return object;
            }
        }
    }

    private List<Object> array() {
        List<Object> array = new ArrayList<>();
        at++;
        space();
        if (peek() == ']') {
            at++;
            return array;
        }
        while (true) {
            array.add(value());
            space();
            if (peek() == ',') {
                at++;
            } else {
                expect(']');
                return array;
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder string = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return string.toString();
            }
            if (c != '\\') {
                string.append(c);
                continue;
            }
            char escaped = text.charAt(at++);
            switch (escaped) {
                case 'b' -> string.append('\b');
                case 'f' -> string.append('\f');
                case 'n' -> string.append('\n');
                case 'r' -> string.append('\r');
                case 't' -> string.append('\t');
                case 'u' -> {
                    string.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> string.append(escaped);
            }
        }
    }

    private Object number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        String number = text.substring(start, at);
        if (number.isEmpty()) {
            throw error("unexpected character");
        }
        return number.contains(".") || number.contains("e") || number.contains("E")
                ? (Object) Double.parseDouble(number) : (Object) Long.parseLong(number);
    }

    private Object word(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw error("unexpected character");
        }
        at += word.length();
        return value;
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        at++;
    }

    private char peek() {
        return at < text.length() ? text.charAt(at) : '\0';
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("Not JSON at " + at + ": " + what);
    }
}
