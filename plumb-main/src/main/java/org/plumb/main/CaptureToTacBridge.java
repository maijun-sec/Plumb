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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.plumb.build.capture.CaptureEntry;
import org.plumb.tac.ir.SourceLanguage;

/**
 * Turns captured compiles into the inputs the TAC pipeline needs.
 *
 * <p><b>Why a capture is better than a source tree.</b> When the analyser is handed a source tree,
 * it has to <em>guess</em> the classpath: it reads a build file, interprets it, and hopes the guess
 * matches what the compiler actually saw. A capture is the compiler's own report of the compile it
 * performed -- the classpath it resolved, the source path it searched, the language level it used.
 * So this class does not guess; it reads.
 *
 * <p><b>What is carried over.</b> The classpath, the source path, the language level and the source
 * files. These are the four things that decide whether a type resolves, and a resolution that fails
 * is a finding that is silently missing.
 *
 * <p><b>Why the source root is inferred rather than captured.</b> The capture records the source
 * <em>path</em> -- the roots the compiler searched -- not the root a particular file lives under. A
 * file under the source path belongs to the module whose root contains it, and the module boundary
 * is what the analysis uses to decide whether a finding is in scope. So the root is the longest
 * source path that contains the file; when there is no source path, the file's own parent is used.
 */
public final class CaptureToTacBridge {

  private CaptureToTacBridge() {}

  /**
   * The language a capture describes.
   *
   * <p>The capture's own language field is authoritative when it is present: a Java compiler and a
   * Java <em>language</em> are different things, and a capture records both.
   *
   * @param entry the capture
   * @return the language, or {@code null} when the capture does not name one
   */
  public static SourceLanguage languageOf(CaptureEntry entry) {
    if (entry == null) {
      return null;
    }
    String language = entry.language();
    if (language == null || language.isBlank()) {
      // Fall back to the file extension: a capture that does not name its
      // language still says which files it compiled.
      return languageOfExtension(entry.sourceFiles());
    }
    return switch (language.toLowerCase(java.util.Locale.ROOT)) {
      case "java" -> SourceLanguage.JAVA;
      case "kotlin", "kt" -> SourceLanguage.KOTLIN;
      case "scala", "scala3", "scala2" -> SourceLanguage.SCALA;
      case "csharp", "c#", "cs" -> SourceLanguage.CSHARP;
      case "go", "golang" -> SourceLanguage.GO;
      default -> null;
    };
  }

  /**
   * The language of a set of files, decided by their extensions.
   *
   * @param files the files
   * @return the language, or {@code null} when the files do not agree on one
   */
  public static SourceLanguage languageOfExtension(List<Path> files) {
    if (files == null || files.isEmpty()) {
      return null;
    }
    Set<SourceLanguage> seen = new LinkedHashSet<>();
    for (Path file : files) {
      if (file == null) {
        continue;
      }
      String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
      if (name.endsWith(".java")) {
        seen.add(SourceLanguage.JAVA);
      } else if (name.endsWith(".kt") || name.endsWith(".kts")) {
        seen.add(SourceLanguage.KOTLIN);
      } else if (name.endsWith(".scala") || name.endsWith(".sc")) {
        seen.add(SourceLanguage.SCALA);
      } else if (name.endsWith(".cs")) {
        seen.add(SourceLanguage.CSHARP);
      } else if (name.endsWith(".go")) {
        seen.add(SourceLanguage.GO);
      }
    }
    // A capture that compiles two languages is not something the frontends can
    // serve with one context, so an ambiguous answer is no answer.
    return seen.size() == 1 ? seen.iterator().next() : null;
  }

  /**
   * The source roots the captured compiles searched.
   *
   * @param captures the captures
   * @return the distinct source path roots, in the order they were seen
   */
  public static List<Path> sourceRootsOf(List<CaptureEntry> captures) {
    Set<Path> roots = new LinkedHashSet<>();
    if (captures != null) {
      for (CaptureEntry entry : captures) {
        if (entry == null || entry.sourcePath() == null) {
          continue;
        }
        for (Path root : entry.sourcePath()) {
          if (root != null) {
            roots.add(root);
          }
        }
      }
    }
    return List.copyOf(roots);
  }

  /**
   * The classpath the captured compiles used.
   *
   * <p>The union of every capture's classpath. A capture that saw a dependency another one did not
   * still contributes it, because the analysis resolves against the whole project.
   *
   * @param captures the captures
   * @return the distinct classpath entries, in the order they were seen
   */
  public static List<Path> classpathOf(List<CaptureEntry> captures) {
    Set<Path> entries = new LinkedHashSet<>();
    if (captures != null) {
      for (CaptureEntry entry : captures) {
        if (entry == null || entry.classpath() == null) {
          continue;
        }
        for (Path entryPath : entry.classpath()) {
          if (entryPath != null) {
            entries.add(entryPath);
          }
        }
      }
    }
    return List.copyOf(entries);
  }

  /**
   * The source files the captured compiles compiled.
   *
   * <p>Only the files that are still there are returned. A capture outlives the build that produced
   * it, and a file that has since been deleted cannot be translated -- asking for it would produce
   * a parse error rather than an honest absence.
   *
   * @param captures the captures
   * @return the existing source files, deduplicated, in the order they were seen
   */
  public static List<Path> sourceFilesOf(List<CaptureEntry> captures) {
    Set<Path> files = new LinkedHashSet<>();
    if (captures != null) {
      for (CaptureEntry entry : captures) {
        if (entry == null || entry.sourceFiles() == null) {
          continue;
        }
        for (Path file : entry.sourceFiles()) {
          if (file != null && java.nio.file.Files.exists(file)) {
            files.add(file);
          }
        }
      }
    }
    return List.copyOf(files);
  }

  /**
   * The source root a file belongs to.
   *
   * <p>The longest source root that contains the file wins, because the roots can nest: a module's
   * root inside the project's root.
   *
   * @param file the file
   * @param sourceRoots the roots to choose among
   * @return the root, or the file's parent when no root contains it
   */
  public static Path rootOf(Path file, List<Path> sourceRoots) {
    if (file == null) {
      return null;
    }
    Path best = null;
    if (sourceRoots != null) {
      for (Path root : sourceRoots) {
        if (root == null) {
          continue;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!file.toAbsolutePath().normalize().startsWith(normalizedRoot)) {
          continue;
        }
        if (best == null || normalizedRoot.getNameCount() > best.getNameCount()) {
          best = normalizedRoot;
        }
      }
    }
    if (best != null) {
      return best;
    }
    Path parent = file.toAbsolutePath().normalize().getParent();
    return parent == null ? Path.of(".") : parent;
  }

  /**
   * The language level the captures were compiled at.
   *
   * <p>The highest level any capture reported wins. A project that compiles one module at 11 and
   * another at 17 is a project that has to be analysed at 17, because the lower module's code may
   * use the higher module's language features through it.
   *
   * @param captures the captures
   * @return the language level, or {@code null} when no capture reported one
   */
  public static String languageLevelOf(List<CaptureEntry> captures) {
    String best = null;
    if (captures != null) {
      for (CaptureEntry entry : captures) {
        if (entry == null || entry.languageLevel() == null) {
          continue;
        }
        String level = entry.languageLevel().trim();
        if (level.isEmpty()) {
          continue;
        }
        if (best == null || compareLevels(level, best) > 0) {
          best = level;
        }
      }
    }
    return best;
  }

  /**
   * Compare two language levels.
   *
   * <p>The levels are written in several shapes -- {@code 17}, {@code 1.8}, {@code 21}, {@code
   * 3.10} -- so the comparison is on the numbers, and a level that is not a number sorts below one
   * that is.
   */
  private static int compareLevels(String a, String b) {
    Double na = numericLevel(a);
    Double nb = numericLevel(b);
    if (na == null && nb == null) {
      return a.compareTo(b);
    }
    if (na == null) {
      return -1;
    }
    if (nb == null) {
      return 1;
    }
    return Double.compare(na, nb);
  }

  /**
   * The numeric value of a language level.
   *
   * <p>{@code 1.8} is the old spelling of {@code 8}, so the leading {@code 1.} is dropped before
   * the number is read.
   */
  private static Double numericLevel(String level) {
    String s = level.trim();
    if (s.startsWith("1.")) {
      s = s.substring(2);
    }
    try {
      return Double.valueOf(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * The source files of one capture, with the ones that are missing left out.
   *
   * @param entry the capture
   * @return the existing source files
   */
  public static List<Path> existingSourceFiles(CaptureEntry entry) {
    List<Path> out = new ArrayList<>();
    if (entry == null || entry.sourceFiles() == null) {
      return out;
    }
    for (Path file : entry.sourceFiles()) {
      if (file != null && java.nio.file.Files.exists(file)) {
        out.add(file);
      }
    }
    return out;
  }
}
