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
package org.plumb.main;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a compilation database.
 *
 * <p><b>Why the analyser reads the DATABASE and not the captures.</b> The database is the
 * interchange format. A build can produce it, another tool can produce it, and the analyser reads
 * whichever it was given. Reading the agent's private capture files instead would tie the analyser
 * to one producer, which is exactly the coupling the format exists to remove.
 *
 * <p><b>What the reader tolerates.</b> The format allows two shapes for the command: an array of
 * tokens, or a joined string. Both are read, because a database produced by another tool may use
 * either. An entry with no command at all is skipped -- it describes no compile. An entry whose
 * source file is not named is still kept, with a null file, because the compile itself may still
 * name the file among its arguments.
 */
public final class CompilationDatabaseReader {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CompilationDatabaseReader() {}

  /**
   * Read a compilation database.
   *
   * @param file the database
   * @return the entries, in the order they appear
   * @throws IOException if the file cannot be read or is not a database
   */
  public static List<CompilationDatabaseEntry> read(Path file) throws IOException {
    if (file == null) {
      throw new IOException("no compilation database to read");
    }
    if (!Files.exists(file)) {
      throw new IOException("the compilation database does not exist: " + file);
    }
    String text = Files.readString(file);
    return parse(text);
  }

  /**
   * Parse a compilation database's text.
   *
   * @param text the database's JSON
   * @return the entries
   * @throws IOException if the text is not a database
   */
  public static List<CompilationDatabaseEntry> parse(String text) throws IOException {
    JsonNode root;
    try {
      root = MAPPER.readTree(stripByteOrderMark(text));
    } catch (IOException e) {
      throw new IOException("the compilation database is not valid JSON: " + e.getMessage(), e);
    }
    List<CompilationDatabaseEntry> out = new ArrayList<>();
    if (root == null || !root.isArray()) {
      // A file that is not an array is not a compilation database. The format
      // has exactly one shape, and accepting another one would mean guessing.
      throw new IOException("the compilation database must be a JSON array of compile entries");
    }
    for (JsonNode node : root) {
      if (!node.isObject()) {
        continue;
      }
      String directory = text(node, "directory");
      List<String> arguments = argumentsOf(node);
      String file = text(node, "file");
      if (arguments.isEmpty() && file == null) {
        // An entry with neither a command nor a file describes no compile.
        continue;
      }
      out.add(new CompilationDatabaseEntry(directory, arguments, file));
    }
    return out;
  }

  /**
   * The entry's arguments.
   *
   * <p>The array form is preferred. The joined form is read as a fallback, and it is split the way
   * a shell would split it -- honouring the quotes -- because that is the only way to recover the
   * tokens.
   */
  private static List<String> argumentsOf(JsonNode node) {
    JsonNode array = node.get("arguments");
    if (array != null && array.isArray()) {
      List<String> out = new ArrayList<>(array.size());
      for (JsonNode item : array) {
        if (item != null && item.isTextual()) {
          out.add(item.asText());
        }
      }
      return out;
    }
    JsonNode command = node.get("command");
    if (command != null && command.isTextual()) {
      return splitCommandLine(command.asText());
    }
    return List.of();
  }

  /**
   * Split a joined command line into its tokens.
   *
   * <p>A token is quoted when it holds a space, a tab, a quote or an apostrophe. Inside the quotes
   * a backslash and a quote are backslash-escaped, because the line has to survive a round trip
   * through a shell.
   *
   * @param command the joined command line
   * @return the tokens
   */
  public static List<String> splitCommandLine(String command) {
    List<String> out = new ArrayList<>();
    if (command == null || command.isEmpty()) {
      return out;
    }
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    boolean started = false;
    for (int i = 0; i < command.length(); i++) {
      char c = command.charAt(i);
      if (c == '\\' && i + 1 < command.length() && inQuotes) {
        char next = command.charAt(++i);
        if (next == '"' || next == '\\') {
          current.append(next);
          continue;
        }
        current.append(c);
        continue;
      }
      if (c == '"') {
        inQuotes = !inQuotes;
        started = true;
        continue;
      }
      if (!inQuotes && (c == ' ' || c == '\t')) {
        if (started) {
          out.add(current.toString());
          current.setLength(0);
          started = false;
        }
        continue;
      }
      current.append(c);
      started = true;
    }
    if (started) {
      out.add(current.toString());
    }
    return out;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }

  /**
   * Remove the byte order mark a text file may start with.
   *
   * <p>A database written by a tool on Windows may start with a UTF-8 byte order mark -- the
   * PowerShell 5.1 default adds one, and so do several other producers. The mark is not part of the
   * JSON, and a reader that refuses it would reject a database that is perfectly good. The format
   * is an interchange format, so it is read the way its producers write it.
   *
   * @param text the text
   * @return the text without a leading byte order mark
   */
  static String stripByteOrderMark(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    char first = text.charAt(0);
    return first == '\uFEFF' ? text.substring(1) : text;
  }
}
