/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.plumb.tac.frontend.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader and writer for the sidecar protocols.
 *
 * <h2>Why not a JSON library</h2>
 *
 * <p>The protocols are small, fixed schemas spoken between two halves that are built
 * independently: a native sidecar and a Java frontend. Pulling a JSON library into {@code
 * plumb-frontend} for them would add a dependency to a module that currently has none, and it would
 * buy nothing: the schema is a handful of flat objects, and the mapping into the IR is explicit
 * anyway.
 *
 * <p>Writing it out also means the wire format is visible in one file, on both sides, rather than
 * being split between a schema and an annotation processor.
 *
 * <h2>Who uses it</h2>
 *
 * <p>Every sidecar speaks the same framing and the same object shape, so the reader and the writer
 * are shared: the C# sidecar and the Go sidecar both use this one. The languages differ, the
 * protocol does not.
 *
 * <h2>What is supported</h2>
 *
 * <p>The full JSON grammar for <em>reading</em> (objects, arrays, strings with escapes including
 * {@code \\uXXXX}, numbers, booleans, null), and enough of it for <em>writing</em> (objects, arrays,
 * strings, numbers, booleans, null).
 *
 * <p>Reading is deliberately strict: a malformed document raises {@link JsonException} rather than
 * producing a half-built object. A silently-truncated response would look like an empty analysis.
 */
public final class Json {

  private Json() {}

  /**
   * A JSON value.
   *
   * <p>The variants are exactly what the protocol uses: an object, an array, a string, a number,
   * a boolean, or null.
   */
  public sealed interface Value
      permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {

    /** Whether the value is null. */
    default boolean isNull() {
      return this instanceof JsonNull;
    }
  }

  /** A JSON object. The member order is preserved. */
  public static final class JsonObject implements Value {
    private final Map<String, Value> members = new LinkedHashMap<>();

    /** Put a member. */
    public JsonObject put(String key, Value value) {
      members.put(key, value);
      return this;
    }

    /** Put a string member, skipping a null value. */
    public JsonObject put(String key, String value) {
      if (value != null) {
        members.put(key, new JsonString(value));
      }
      return this;
    }

    /** Put an integer member. */
    public JsonObject put(String key, int value) {
      members.put(key, new JsonNumber(Integer.toString(value)));
      return this;
    }

    /** Put a boolean member. */
    public JsonObject put(String key, boolean value) {
      members.put(key, value ? JsonBoolean.TRUE : JsonBoolean.FALSE);
      return this;
    }

    /** The member's value, or null when it is absent. */
    public Value get(String key) {
      return members.get(key);
    }

    /** The member's value as an object, or null. */
    public JsonObject getObject(String key) {
      Value value = members.get(key);
      return value instanceof JsonObject object ? object : null;
    }

    /** The member's value as an array, or null. */
    public JsonArray getArray(String key) {
      Value value = members.get(key);
      return value instanceof JsonArray array ? array : null;
    }

    /** The member's value as a string, or null. */
    public String getString(String key) {
      Value value = members.get(key);
      return value instanceof JsonString string ? string.value() : null;
    }

    /** The member's value as a boolean, or false when it is absent. */
    public boolean getBoolean(String key, boolean fallback) {
      Value value = members.get(key);
      return value instanceof JsonBoolean bool ? bool.value() : fallback;
    }

    /** The member's value as an integer, or the fallback when it is absent. */
    public int getInt(String key, int fallback) {
      Value value = members.get(key);
      if (value instanceof JsonNumber number) {
        try {
          return Integer.parseInt(number.text());
        } catch (NumberFormatException ex) {
          return fallback;
        }
      }
      return fallback;
    }

    /** The member names, in order. */
    public List<String> keys() {
      return new ArrayList<>(members.keySet());
    }

    /** Whether the object has no members. */
    public boolean isEmpty() {
      return members.isEmpty();
    }
  }

  /** A JSON array. */
  public static final class JsonArray implements Value {
    private final List<Value> items = new ArrayList<>();

    /** Add an item. */
    public JsonArray add(Value value) {
      items.add(value);
      return this;
    }

    /** The items. */
    public List<Value> items() {
      return items;
    }

    /** The item at the index, or null. */
    public Value get(int index) {
      return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    /** The item at the index as an object, or null. */
    public JsonObject getObject(int index) {
      Value value = get(index);
      return value instanceof JsonObject object ? object : null;
    }

    /** The number of items. */
    public int size() {
      return items.size();
    }
  }

  /** A JSON string. */
  public record JsonString(String value) implements Value {}

  /** A JSON number, kept in its text form. */
  public record JsonNumber(String text) implements Value {}

  /** A JSON boolean. */
  public record JsonBoolean(boolean value) implements Value {
    static final JsonBoolean TRUE = new JsonBoolean(true);
    static final JsonBoolean FALSE = new JsonBoolean(false);
  }

  /** The JSON null. */
  public record JsonNull() implements Value {
    static final JsonNull INSTANCE = new JsonNull();
  }

  /** A malformed JSON document. */
  public static final class JsonException extends RuntimeException {
    public JsonException(String message) {
      super(message);
    }
  }

  /**
   * Parse a JSON document.
   *
   * @param text the document
   * @return the root value
   * @throws JsonException when the document is malformed
   */
  public static Value parse(String text) {
    Parser parser = new Parser(text);
    Value value = parser.parseValue();
    parser.skipWhitespace();
    if (!parser.atEnd()) {
      throw new JsonException("trailing content at offset " + parser.position());
    }
    return value;
  }

  /**
   * Parse a JSON document that must be an object.
   *
   * @param text the document
   * @return the root object
   * @throws JsonException when the document is malformed or is not an object
   */
  public static JsonObject parseObject(String text) {
    Value value = parse(text);
    if (value instanceof JsonObject object) {
      return object;
    }
    throw new JsonException("the document is not a JSON object");
  }

  /**
   * Serialise a value.
   *
   * @param value the value
   * @return the JSON text
   */
  public static String write(Value value) {
    StringBuilder sb = new StringBuilder();
    writeValue(sb, value);
    return sb.toString();
  }

  private static void writeValue(StringBuilder sb, Value value) {
    if (value == null || value instanceof JsonNull) {
      sb.append("null");
      return;
    }
    if (value instanceof JsonObject object) {
      sb.append('{');
      boolean first = true;
      for (String key : object.keys()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        writeString(sb, key);
        sb.append(':');
        writeValue(sb, object.get(key));
      }
      sb.append('}');
      return;
    }
    if (value instanceof JsonArray array) {
      sb.append('[');
      for (int i = 0; i < array.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        writeValue(sb, array.get(i));
      }
      sb.append(']');
      return;
    }
    if (value instanceof JsonString string) {
      writeString(sb, string.value());
      return;
    }
    if (value instanceof JsonNumber number) {
      sb.append(number.text());
      return;
    }
    if (value instanceof JsonBoolean bool) {
      sb.append(bool.value() ? "true" : "false");
      return;
    }
    sb.append("null");
  }

  private static void writeString(StringBuilder sb, String value) {
    sb.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  /** A recursive-descent JSON parser. */
  private static final class Parser {
    private final String text;
    private int index;

    Parser(String text) {
      this.text = text;
    }

    int position() {
      return index;
    }

    boolean atEnd() {
      return index >= text.length();
    }

    void skipWhitespace() {
      while (index < text.length()) {
        char c = text.charAt(index);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
          index++;
        } else {
          break;
        }
      }
    }

    Value parseValue() {
      skipWhitespace();
      if (atEnd()) {
        throw new JsonException("the document ended unexpectedly");
      }
      char c = text.charAt(index);
      return switch (c) {
        case '{' -> parseObject();
        case '[' -> parseArray();
        case '"' -> new JsonString(parseString());
        case 't' -> {
          expect("true");
          yield JsonBoolean.TRUE;
        }
        case 'f' -> {
          expect("false");
          yield JsonBoolean.FALSE;
        }
        case 'n' -> {
          expect("null");
          yield JsonNull.INSTANCE;
        }
        default -> parseNumber();
      };
    }

    private JsonObject parseObject() {
      JsonObject object = new JsonObject();
      index++; // '{'
      skipWhitespace();
      if (!atEnd() && text.charAt(index) == '}') {
        index++;
        return object;
      }
      while (true) {
        skipWhitespace();
        if (atEnd() || text.charAt(index) != '"') {
          throw new JsonException("expected a member name at offset " + index);
        }
        String key = parseString();
        skipWhitespace();
        if (atEnd() || text.charAt(index) != ':') {
          throw new JsonException("expected ':' at offset " + index);
        }
        index++;
        object.put(key, parseValue());
        skipWhitespace();
        if (atEnd()) {
          throw new JsonException("the object was not closed");
        }
        char c = text.charAt(index);
        if (c == ',') {
          index++;
          continue;
        }
        if (c == '}') {
          index++;
          return object;
        }
        throw new JsonException("expected ',' or '}' at offset " + index);
      }
    }

    private JsonArray parseArray() {
      JsonArray array = new JsonArray();
      index++; // '['
      skipWhitespace();
      if (!atEnd() && text.charAt(index) == ']') {
        index++;
        return array;
      }
      while (true) {
        array.add(parseValue());
        skipWhitespace();
        if (atEnd()) {
          throw new JsonException("the array was not closed");
        }
        char c = text.charAt(index);
        if (c == ',') {
          index++;
          continue;
        }
        if (c == ']') {
          index++;
          return array;
        }
        throw new JsonException("expected ',' or ']' at offset " + index);
      }
    }

    private String parseString() {
      index++; // the opening quote
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (atEnd()) {
          throw new JsonException("the string was not closed");
        }
        char c = text.charAt(index++);
        if (c == '"') {
          return sb.toString();
        }
        if (c != '\\') {
          sb.append(c);
          continue;
        }
        if (atEnd()) {
          throw new JsonException("the escape sequence was not closed");
        }
        char escape = text.charAt(index++);
        switch (escape) {
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case '/' -> sb.append('/');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> {
            if (index + 4 > text.length()) {
              throw new JsonException("a \\u escape was truncated");
            }
            sb.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
            index += 4;
          }
          default -> throw new JsonException("unknown escape '\\" + escape + "'");
        }
      }
    }

    private JsonNumber parseNumber() {
      int start = index;
      if (!atEnd() && (text.charAt(index) == '-' || text.charAt(index) == '+')) {
        index++;
      }
      while (!atEnd()) {
        char c = text.charAt(index);
        if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
          index++;
        } else {
          break;
        }
      }
      if (start == index) {
        throw new JsonException("expected a value at offset " + index);
      }
      return new JsonNumber(text.substring(start, index));
    }

    private void expect(String literal) {
      if (!text.startsWith(literal, index)) {
        throw new JsonException("expected '" + literal + "' at offset " + index);
      }
      index += literal.length();
    }
  }
}
