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
package org.plumb.tac.frontend.kotlin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A source file staged on disk so the Analysis API can resolve it.
 *
 * <p><b>Why a file has to exist on disk.</b> The Analysis API is a PSI-based API over
 * IntelliJ's virtual file system: it resolves a file that is <i>in the project's content
 * scope</i>, not a string. There is no public API for handing it an in-memory buffer.
 * So the frontend writes the source text to a scratch file under the session's source
 * root, resolves that, and removes the scratch file again.
 *
 * <p><b>Why the scratch file is not the original.</b> The caller's {@code path} may point
 * at a file that does not exist on disk (the CLI may be handed source text for a path it
 * never wrote), or at a file whose content differs from the {@code source} string (a
 * compiler plugin's virtual source, an unsaved editor buffer). Writing the text to a
 * scratch file makes the analysed content exactly the text the caller passed in, which is
 * what "parse this source" means.
 *
 * <p><b>The scratch file's name.</b> It keeps the original file's name, so the package
 * declaration inside the source and the file name agree the way the Kotlin compiler
 * expects. It lives in a directory named after the original, so two files with the same
 * name in different packages do not collide.
 */
final class ScratchFile {

  /** The staged file's path. */
  private final Path path;

  /** The file the scratch was staged from, for the message in the path. */
  private final Path origin;

  private ScratchFile(Path path, Path origin) {
    this.path = path;
    this.origin = origin;
  }

  /** The staged file's path. */
  Path path() {
    return path;
  }

  /**
 * Stages {@code source} under {@code root}.
   *
   * <p>The staged file's path is <b>unique per call</b>. The Analysis API's session
   * caches the PSI of every file it has seen, and the virtual file system caches the
   * file itself; a second parse that reused the same path would be handed the <i>first</i>
   * parse's file. So each parse gets its own directory, and the file inside it keeps the
   * original's name so that the package declaration and the file name agree the way the
   * Kotlin compiler expects.
   *
   * @param root the session's source root -- the scratch file must be under it, or the
   *     Analysis API will not resolve it
   * @param origin the original file's path, used to derive the scratch file's name
   * @param source the source text
   * @return the staged file, or {@code null} when it could not be staged
   */
  static ScratchFile write(Path root, Path origin, String source) {
    if (root == null || origin == null) {
      return null;
    }
    String fileName = fileNameOf(origin);
    // A directory per staged file, so two files with the same name do not collide and
    // a re-parse never hits the session's cache.
    Path directory =
        root.resolve(".plumb-kotlin-scratch").resolve(sanitize(fileName)).resolve(nextId());
    try {
      Files.createDirectories(directory);
      Path file = directory.resolve(fileName);
      Files.write(file, source.getBytes(StandardCharsets.UTF_8));
      return new ScratchFile(file, origin);
    } catch (IOException ex) {
      return null;
    }
  }

  /** A process-unique identifier, used to give each staged file its own directory. */
  private static String nextId() {
    return Long.toHexString(COUNTER.incrementAndGet()) + "-" + Long.toHexString(System.nanoTime());
  }

  /** The counter behind {@link #nextId()}. */
  private static final java.util.concurrent.atomic.AtomicLong COUNTER =
      new java.util.concurrent.atomic.AtomicLong();

  /**
   * Removes the staged file.
   *
   * <p>Best-effort: a failure to clean up must not fail the parse. The per-parse
   * directory is removed too, so the scratch area does not accumulate.
   */
  void delete() {
    try {
      Files.deleteIfExists(path);
      Path directory = path.getParent();
      if (directory != null) {
        Files.deleteIfExists(directory);
        // The per-file-name directory above it, if it is now empty.
        Path parent = directory.getParent();
        if (parent != null) {
          deleteIfEmpty(parent);
          // And the scratch root above that. It is the one directory the
          // frontend leaves in the SOURCE TREE, so a scan that writes into
          // the project it analyses must not leave it behind when it is done.
          Path scratchRoot = parent.getParent();
          if (scratchRoot != null) {
            deleteIfEmpty(scratchRoot);
          }
        }
      }
    } catch (IOException ex) {
      // Best effort.
    }
  }

  /**
   * Removes a directory when it is empty.
   *
   * @param directory the directory
   */
  private static void deleteIfEmpty(Path directory) {
    try (java.util.stream.Stream<java.nio.file.Path> children = Files.list(directory)) {
      if (children.findAny().isEmpty()) {
        Files.deleteIfExists(directory);
      }
    } catch (IOException ex) {
      // Best effort: a directory that is not empty, or that cannot be read,
      // stays. It is scratch space, not a result.
    }
  }

  /** The original file's name, or a generic one. */
  private static String fileNameOf(Path origin) {
    Path name = origin.getFileName();
    if (name == null) {
      return "Scratch.kt";
    }
    String text = name.toString();
    if (!text.endsWith(".kt") && !text.endsWith(".kts")) {
      text = text + ".kt";
    }
    return sanitize(text);
  }

  /** Replaces the characters that are not legal in a file name. */
  private static String sanitize(String name) {
    StringBuilder sb = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
        sb.append(c);
      } else {
        sb.append('_');
      }
    }
    return sb.length() == 0 ? "Scratch.kt" : sb.toString();
  }

  /** The original file's path. */
  Path origin() {
    return origin;
  }

  /**
   * Copies a file into a directory.
   *
   * <p>Used when the session's content scope has to be extended with a file that lives
   * outside the configured source root.
   *
   * @param file the file to copy
   * @param directory the destination directory
   * @return the copied file's path, or {@code null} on failure
   */
  static Path copyInto(Path file, Path directory) {
    try {
      Files.createDirectories(directory);
      Path target = directory.resolve(fileNameOf(file));
      Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
      return target;
    } catch (IOException ex) {
      return null;
    }
  }
}
