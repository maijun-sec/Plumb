package org.plumb.build.capture;

import java.nio.file.Path;
import java.util.List;

/**
 * One captured compile, in the form a static analyser needs.
 *
 * <p>Unlike a raw argv dump, this carries the <em>effective</em> compile: the classpath that was
 * actually used (not just the {@code -classpath} argument), the source files, the encoding, the
 * language level and the output directory.
 *
 * @param api which entry point produced this capture: {@code cli} or {@code api}
 * @param argv the raw compiler arguments
 * @param encoding the source encoding, or {@code null} when the platform default applies
 * @param languageLevel the {@code --release} / {@code -source} level, or {@code null}
 * @param outputDirectory the {@code -d} directory, or {@code null}
 * @param classpath the classpath the compiler used
 * @param sourcePath the source path, or empty
 * @param modulePath the module path, or empty
 * @param sourceFiles the compiled source files
 * @param javaVersion the {@code java.version} of the compiling JVM
 * @param javaHome the {@code java.home} of the compiling JVM
 * @param workingDirectory the {@code user.dir} of the compiling JVM
 */
public record CaptureEntry(
    String api,
    List<String> argv,
    String encoding,
    String languageLevel,
    Path outputDirectory,
    List<Path> classpath,
    List<Path> sourcePath,
    List<Path> modulePath,
    List<Path> sourceFiles,
    String javaVersion,
    String javaHome,
    String workingDirectory) {

  /**
   * Render the capture as JSON.
   *
   * <p>Hand-rolled so the module has no JSON dependency: the recorder runs inside an arbitrary
   * compiler JVM and must stay self-contained.
   */
  public String toJson() {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"api\": ").append(quote(api)).append(",\n");
    sb.append("  \"argv\": ").append(jsonArray(argv)).append(",\n");
    sb.append("  \"encoding\": ").append(quote(encoding)).append(",\n");
    sb.append("  \"languageLevel\": ").append(quote(languageLevel)).append(",\n");
    sb.append("  \"outputDirectory\": ").append(quote(path(outputDirectory))).append(",\n");
    sb.append("  \"classpath\": ").append(jsonArray(paths(classpath))).append(",\n");
    sb.append("  \"sourcePath\": ").append(jsonArray(paths(sourcePath))).append(",\n");
    sb.append("  \"modulePath\": ").append(jsonArray(paths(modulePath))).append(",\n");
    sb.append("  \"sourceFiles\": ").append(jsonArray(paths(sourceFiles))).append(",\n");
    sb.append("  \"javaVersion\": ").append(quote(javaVersion)).append(",\n");
    sb.append("  \"javaHome\": ").append(quote(javaHome)).append(",\n");
    sb.append("  \"workingDirectory\": ").append(quote(workingDirectory)).append("\n");
    sb.append("}\n");
    return sb.toString();
  }

  private static List<String> paths(List<Path> in) {
    List<String> out = new java.util.ArrayList<>(in.size());
    for (Path p : in) {
      out.add(path(p));
    }
    return out;
  }

  private static String path(Path p) {
    return p == null ? null : p.toString();
  }

  private static String jsonArray(List<String> values) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(quote(values.get(i)));
    }
    return sb.append(']').toString();
  }

  private static String quote(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder(s.length() + 8);
    sb.append('"');
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
    return sb.append('"').toString();
  }
}
