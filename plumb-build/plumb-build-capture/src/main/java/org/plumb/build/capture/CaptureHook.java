package org.plumb.build.capture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The static hook the instrumented javac bytecode calls into.
 *
 * <p>This class is injected into the <b>bootstrap</b> class loader so that
 * {@code com.sun.tools.javac.Main} -- which lives in the {@code jdk.compiler}
 * module and is loaded by the application class loader -- can call it. The
 * bootstrap loader is the ancestor of every loader, so a class placed there is
 * reachable from everywhere.
 *
 * <p>That visibility comes with a constraint: <b>this class must not reference
 * anything that is not also on the bootstrap loader</b>. In particular it cannot
 * use SLF4J or {@link CaptureSink}; it writes the capture file itself.
 *
 * <p>Keep this class dependency-free.
 */
public final class CaptureHook {

  /** Environment variable naming the directory captures are written to. */
  public static final String ENV_OUTPUT_DIR = "PLUMB_CAPTURE_DIR";

  /**
   * The argv of the most recent capture, used to suppress duplicates.
   *
   * <p>A single command-line compile reaches <em>both</em> hooked entry points:
   * {@code com.sun.tools.javac.Main#compile} delegates to
   * {@code com.sun.tools.javac.main.Main#compile}. Both calls carry the same
   * argv, so without this the capture would be written twice.
   */
  private static volatile String lastCaptured;

  private CaptureHook() {}

  /**
   * Record a captured javac invocation.
   *
   * @param args the argv javac received
   */
  public static void onArgs(String[] args) {
    if (args == null || args.length == 0) {
      return;
    }
    String signature = String.join("\u0000", args);
    if (signature.equals(lastCaptured)) {
      // The same argv seen again -- the other hooked entry point.
      return;
    }
    lastCaptured = signature;

    String dir = System.getenv(ENV_OUTPUT_DIR);
    if (dir == null || dir.isBlank()) {
      // No destination configured: the capture stays in this JVM's memory.
      return;
    }
    try {
      Path out = Path.of(dir);
      Files.createDirectories(out);
      Path file = out.resolve("javac-" + System.nanoTime() + ".json");
      Files.writeString(file, toJson(List.of(args)), StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      // Never let the capture break the compile.
      System.err.println("[plumb-capture] could not write capture: " + e.getMessage());
    }
  }

  /**
   * Render the argv as a JSON array of strings.
   *
   * <p>Hand-rolled so this class stays dependency-free.
   */
  static String toJson(List<String> arguments) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < arguments.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(escape(arguments.get(i))).append('"');
    }
    return sb.append(']').toString();
  }

  private static String escape(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }
}
