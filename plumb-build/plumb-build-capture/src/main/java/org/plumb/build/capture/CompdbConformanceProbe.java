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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Java half of the cross-language conformance check for the compilation database's path and
 * string rules.
 *
 * <p><b>Why there are two halves.</b> The compilation database is consumed by tools that do not
 * share code with the writer. A rule that differs by one character -- a path that keeps a backslash
 * where the reader expects a slash, a control character that is escaped differently -- produces a
 * file that looks valid and reads as empty. So the two rules this class implements are checked
 * against the reference implementation of the format, one input at a time, byte for byte.
 *
 * <p><b>What the reference is.</b> The probe is a small program built against the reference
 * implementation's own path and JSON sources; it prints the reference's answers in hex, one record
 * per line. This class prints the same records for the same inputs, and the test compares them.
 *
 * <p><b>Why hex.</b> A trailing space, an empty result and a missing line are all easy to confuse
 * when the output is text. Hex makes every byte visible.
 */
public final class CompdbConformanceProbe {

  private CompdbConformanceProbe() {}

  /**
   * The probe's answer for one input.
   *
   * @param kind {@code N} for a path to normalize, {@code Q} for a string to quote
   * @param input the input
   * @return the record, in the probe's wire form
   */
  static String record(char kind, String input) {
    String result = kind == 'Q' ? CompdbWriter.quote(input) : CompdbWriter.normalizePath(input);
    return kind + " " + toHex(result);
  }

  private static String toHex(String s) {
    StringBuilder sb = new StringBuilder(s.length() * 2);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      sb.append(Character.forDigit((c >> 4) & 0xF, 16));
      sb.append(Character.forDigit(c & 0xF, 16));
    }
    return sb.toString();
  }

  /**
   * Run the Java half over an input file.
   *
   * <p><b>Why the input is escaped.</b> The input file carries one case per line, so a case that
   * contains a line separator cannot be written literally -- the reader would split it into two. So
   * the file carries an <b>escaped</b> form and both halves decode it the same way. The escape is
   * the one a shell user already knows: a backslash escapes the next character.
   *
   * @param inputFile the input file, one escaped case per line
   * @return the records, in the probe's wire form
   * @throws IOException if the file cannot be read
   */
  public static List<String> run(Path inputFile) throws IOException {
    List<String> records = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(Files.newInputStream(inputFile), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()) {
          continue;
        }
        char kind = line.charAt(0);
        if (kind != 'N' && kind != 'Q') {
          continue;
        }
        if (line.length() < 2 || line.charAt(1) != ' ') {
          continue;
        }
        records.add(record(kind, unescape(line.substring(2))));
      }
    }
    return records;
  }

  /**
   * Decode an escaped case.
   *
   * <p>A backslash escapes the character after it, so {@code \n} is a newline and {@code \\} is a
   * backslash. Every other character stands for itself.
   *
   * @param s the escaped text
   * @return the decoded text
   */
  static String unescape(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char next = s.charAt(++i);
        switch (next) {
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case '\\' -> sb.append('\\');
          case '"' -> sb.append('"');
          case 'u' -> {
            if (i + 4 < s.length()) {
              String hex = s.substring(i + 1, i + 5);
              try {
                sb.append((char) Integer.parseInt(hex, 16));
                i += 4;
              } catch (NumberFormatException e) {
                sb.append("\\u");
              }
            } else {
              sb.append("\\u");
            }
          }
          default -> sb.append(next);
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Encode a case for the input file.
   *
   * @param kind {@code N} for a path to normalize, {@code Q} for a string to quote
   * @param input the input
   * @return the escaped line
   */
  static String escape(char kind, String input) {
    StringBuilder sb = new StringBuilder();
    sb.append(kind).append(' ');
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '"' -> sb.append("\\\"");
        default -> {
          if (c < 0x20 || c > 0x7e) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }

  /**
   * Run the reference half and return its records.
   *
   * @param probe the probe executable
   * @param inputFile the input file
   * @return the records, in the probe's wire form
   * @throws IOException if the probe cannot be run
   * @throws InterruptedException if the probe is interrupted
   */
  public static List<String> runReference(Path probe, Path inputFile)
      throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(probe.toString(), inputFile.toString());
    builder.redirectErrorStream(false);
    Process process = builder.start();
    List<String> records = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isEmpty()) {
          records.add(line);
        }
      }
    }
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IOException("the reference probe exited with " + exit);
    }
    return records;
  }

  /**
   * The input cases the conformance check runs.
   *
   * <p>They are the cases where the two implementations could plausibly differ: the separators, the
   * current and parent segments, the drive letter, the repeated separators, and the control
   * characters that have to be escaped.
   *
   * <p>The list is in the escaped wire form the input file uses, so that a case carrying a line
   * separator survives the trip.
   *
   * @return the escaped input lines
   */
  public static List<String> conformanceInputs() {
    return List.of(
        "N D:\\work\\proj",
        "N D:/work/proj",
        "N D:\\work\\.\\proj",
        "N D:/work/./proj",
        "N D:\\work\\build\\..\\proj",
        "N D:/work/build/../proj",
        "N D:\\work\\..\\..\\proj",
        "N D:/work/../../proj",
        "N D:\\work\\Proj",
        "N D:/work/Proj",
        "N /work/proj",
        "N /work/./proj",
        "N /work/build/../proj",
        "N /work/../../proj",
        "N a/../b",
        "N ../b",
        "N ..",
        "N .",
        "N /",
        "N C:",
        "N C:/",
        "N C:\\work\\..",
        "N C:/work/..",
        "N D:\\work\\\\proj",
        "N D://work//proj",
        "N D:\\work\\build\\..\\..\\..\\proj",
        "N \\",
        "N a b",
        "Q a\"b",
        "Q a\\b",
        "Q a\\nb",
        "Q a\\tb",
        "Q a\\u0001b",
        "Q \\u4e2d\\u6587",
        "Q plain",
        "Q ");
  }

  /**
   * Write the conformance cases to a file in the escaped wire form.
   *
   * @param file the file to write
   * @throws IOException if the file cannot be written
   */
  public static void writeInputs(Path file) throws IOException {
    Files.writeString(file, String.join("\n", conformanceInputs()));
  }
}
