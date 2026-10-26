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
 * Reads back the captures written by {@link CaptureSink}.
 *
 * <p>This is the analyser-side counterpart of the agent: it turns the JSON
 * files in a capture directory back into {@link JavacOptions}.
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
  public static List<JavacOptions> readAll(Path directory) {
    List<JavacOptions> out = new ArrayList<>();
    if (directory == null || !Files.isDirectory(directory)) {
      LOG.debug("Capture: no capture directory at {}", directory);
      return out;
    }
    try (Stream<Path> stream = Files.list(directory)) {
      List<Path> files =
          stream.filter(p -> p.getFileName().toString().startsWith("javac-"))
              .filter(p -> p.getFileName().toString().endsWith(".json"))
              .sorted(Comparator.comparingLong(CaptureReader::lastModified))
              .toList();
      for (Path file : files) {
        JavacOptions options = read(file);
        if (options != null) {
          out.add(options);
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
  public static JavacOptions readLatest(Path directory) {
    List<JavacOptions> all = readAll(directory);
    return all.isEmpty() ? null : all.get(all.size() - 1);
  }

  /**
   * Read one capture file.
   *
   * @param file the JSON file
   * @return the parsed options, or {@code null} when the file is unreadable
   */
  public static JavacOptions read(Path file) {
    try {
      String json = Files.readString(file, StandardCharsets.UTF_8).trim();
      List<String> arguments = parseJsonArray(json);
      return JavacOptions.parse(arguments);
    } catch (IOException e) {
      LOG.warn("Capture: could not read {}: {}", file, e.getMessage());
      return null;
    }
  }

  /**
   * Parse a JSON array of strings.
   *
   * <p>Hand-rolled for the same reason the writer is: this module must load
   * into an arbitrary compiler JVM without dragging in a JSON library.
   */
  static List<String> parseJsonArray(String json) {
    List<String> out = new ArrayList<>();
    if (json == null) {
      return out;
    }
    String trimmed = json.trim();
    if (trimmed.startsWith("[")) {
      trimmed = trimmed.substring(1);
    }
    if (trimmed.endsWith("]")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }

    StringBuilder current = new StringBuilder();
    boolean inString = false;
    boolean escaping = false;

    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (inString) {
        if (escaping) {
          switch (c) {
            case '"' -> current.append('"');
            case '\\' -> current.append('\\');
            case '/' -> current.append('/');
            case 'n' -> current.append('\n');
            case 'r' -> current.append('\r');
            case 't' -> current.append('\t');
            case 'b' -> current.append('\b');
            case 'f' -> current.append('\f');
            case 'u' -> {
              if (i + 4 < trimmed.length()) {
                current.append((char) Integer.parseInt(trimmed.substring(i + 1, i + 5), 16));
                i += 4;
              }
            }
            default -> current.append(c);
          }
          escaping = false;
        } else if (c == '\\') {
          escaping = true;
        } else if (c == '"') {
          inString = false;
          out.add(current.toString());
          current.setLength(0);
        } else {
          current.append(c);
        }
      } else if (c == '"') {
        inString = true;
      }
    }
    return out;
  }

  private static long lastModified(Path p) {
    try {
      return Files.getLastModifiedTime(p).toMillis();
    } catch (IOException e) {
      return 0L;
    }
  }
}
