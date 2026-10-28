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
package org.plumb.build.capture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads back the captures written by the agent.
 *
 * <p>This is the analyser-side counterpart of the agent: it turns the JSON files in a capture
 * directory back into {@link CaptureEntry} records.
 *
 * <p>The format is written by {@link CaptureEntry#toJson()}, which is deliberately a flat object
 * with string arrays -- so the reader is a small hand-rolled parser rather than a JSON library
 * dependency. The capture files are written from inside a compiler JVM, where a dependency would
 * have to be resolvable from an arbitrary classpath.
 */
public final class CaptureReader {

  private static final Logger LOG = LoggerFactory.getLogger(CaptureReader.class);

  private CaptureReader() {}

  /**
   * Read every capture in {@code directory}, oldest first.
   *
   * @param directory the capture directory
   * @return the parsed captures
   */
  public static List<CaptureEntry> readAll(Path directory) {
    List<CaptureEntry> out = new ArrayList<>();
    if (directory == null || !Files.isDirectory(directory)) {
      LOG.debug("Capture: no capture directory at {}", directory);
      return out;
    }
    try (Stream<Path> stream = Files.list(directory)) {
      List<Path> files =
          stream
              .filter(p -> p.getFileName().toString().startsWith("javac-"))
              .filter(p -> p.getFileName().toString().endsWith(".json"))
              .sorted(Comparator.comparingLong(CaptureReader::lastModified))
              .toList();
      for (Path file : files) {
        CaptureEntry entry = read(file);
        if (entry != null) {
          out.add(entry);
        }
      }
    } catch (IOException e) {
      LOG.warn("Capture: could not list {}: {}", directory, e.getMessage());
    }
    return out;
  }

  /**
   * Read the most recent capture in {@code directory}.
   *
   * @param directory the capture directory
   * @return the parsed capture, or {@code null} when there is none
   */
  public static CaptureEntry readLatest(Path directory) {
    List<CaptureEntry> all = readAll(directory);
    return all.isEmpty() ? null : all.get(all.size() - 1);
  }

  /**
   * Read one capture file.
   *
   * @param file the JSON file
   * @return the parsed capture, or {@code null} when the file is unreadable
   */
  public static CaptureEntry read(Path file) {
    try {
      String json = Files.readString(file, StandardCharsets.UTF_8);
      return parse(json);
    } catch (IOException e) {
      LOG.warn("Capture: could not read {}: {}", file, e.getMessage());
      return null;
    }
  }

  /**
   * Parse the capture JSON.
   *
   * <p>The format is a flat object; every value is either a string, null, or an array of strings.
   * The parser looks a key up by name and reads its value, so an unknown key is ignored and a
   * missing key yields null -- a capture written by an older version still parses.
   */
  static CaptureEntry parse(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    return new CaptureEntry(
        string(json, "api"),
        string(json, "compiler"),
        stringArray(json, "argv"),
        string(json, "encoding"),
        string(json, "languageLevel"),
        path(string(json, "outputDirectory")),
        paths(stringArray(json, "classpath")),
        paths(stringArray(json, "sourcePath")),
        paths(stringArray(json, "modulePath")),
        paths(stringArray(json, "processorPath")),
        paths(stringArray(json, "bootClassPath")),
        paths(stringArray(json, "sourceFiles")),
        stringArray(json, "addModules"),
        stringArray(json, "addExports"),
        stringArray(json, "addReads"),
        bool(json, "preview"),
        bool(json, "compilation"),
        string(json, "javaVersion"),
        string(json, "javaHome"),
        string(json, "workingDirectory"));
  }

  /**
   * The value of a top-level boolean key.
   *
   * <p>Absent means false, so a capture written before a flag existed still parses.
   */
  static boolean bool(String json, String key) {
    String marker = "\"" + key + "\":";
    int at = json.indexOf(marker);
    if (at < 0) {
      return false;
    }
    int i = at + marker.length();
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
      i++;
    }
    return i < json.length() && json.charAt(i) == 't';
  }

  private static Path path(String s) {
    return s == null ? null : Path.of(s);
  }

  private static List<Path> paths(List<String> values) {
    List<Path> out = new ArrayList<>(values.size());
    for (String v : values) {
      out.add(Path.of(v));
    }
    return out;
  }

  /**
   * The value of a top-level string key.
   *
   * <p>Returns null when the key is absent or its value is the JSON literal {@code null}.
   */
  static String string(String json, String key) {
    String marker = "\"" + key + "\":";
    int at = json.indexOf(marker);
    if (at < 0) {
      return null;
    }
    int i = at + marker.length();
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
      i++;
    }
    if (i >= json.length()) {
      return null;
    }
    if (json.charAt(i) == 'n' && json.startsWith("null", i)) {
      return null;
    }
    if (json.charAt(i) != '"') {
      // Not a string value -- read up to the next comma.
      int end = json.indexOf(',', i);
      if (end < 0) {
        end = json.length();
      }
      return json.substring(i, end).trim();
    }
    return unquote(json, i);
  }

  /** The value of a top-level array-of-strings key. */
  static List<String> stringArray(String json, String key) {
    String marker = "\"" + key + "\":";
    int at = json.indexOf(marker);
    if (at < 0) {
      return List.of();
    }
    int i = at + marker.length();
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
      i++;
    }
    if (i >= json.length() || json.charAt(i) != '[') {
      return List.of();
    }
    int end = json.indexOf(']', i);
    if (end < 0) {
      end = json.length();
    }
    String body = json.substring(i + 1, end);

    List<String> out = new ArrayList<>();
    int p = 0;
    while (p < body.length()) {
      while (p < body.length()
          && (Character.isWhitespace(body.charAt(p)) || body.charAt(p) == ',')) {
        p++;
      }
      if (p >= body.length() || body.charAt(p) != '"') {
        break;
      }
      String value = unquote(body, p);
      out.add(value);
      // unquote returns the decoded value; find where the literal ended
      p = skipString(body, p) + 1;
    }
    return out;
  }

  /** The index just past the closing quote of the string that starts at {@code start}. */
  private static int skipString(String s, int start) {
    int i = start + 1;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == '"') {
        return i;
      }
      i++;
    }
    return s.length();
  }

  /** Read the quoted string starting at {@code start} and decode its escapes. */
  private static String unquote(String s, int start) {
    int end = skipString(s, start);
    String raw = s.substring(start + 1, Math.min(end, s.length()));
    StringBuilder sb = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c != '\\' || i + 1 >= raw.length()) {
        sb.append(c);
        continue;
      }
      char n = raw.charAt(++i);
      switch (n) {
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 't' -> sb.append('\t');
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case 'u' -> {
          if (i + 4 < raw.length()) {
            sb.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
            i += 4;
          }
        }
        default -> sb.append(n);
      }
    }
    return sb.toString();
  }

  private static long lastModified(Path p) {
    try {
      return Files.getLastModifiedTime(p).toMillis();
    } catch (IOException e) {
      return 0L;
    }
  }
}
