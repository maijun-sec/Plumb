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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.frontend.go.GoModuleSources;
import org.plumb.tac.frontend.go.GoPackage;
import org.plumb.tac.frontend.go.GoProjectDiscovery;
import org.plumb.tac.frontend.go.GoProjectProfile;

/**
 * Reads a Go build out of the captured compiler and linker commands.
 *
 * <h2>Why both levels of the build are needed</h2>
 *
 * <p>The Go toolchain does two different kinds of work, and the capture sees both:
 *
 * <ul>
 *   <li>the <b>compiler</b> runs once per package, and says what that package was compiled against
 *       -- its import path, its language version, its source files, and the export data of its
 *       dependencies;
 *   <li>the <b>linker</b> runs once, and says what the program <em>is</em>: the set of packages
 *       that were actually linked into the binary.
 * </ul>
 *
 * <p>The difference matters. A dependency that was compiled but never linked -- a tool, a test, a
 * variant excluded by a build tag -- is not part of the program, and only the linker knows that.
 *
 * <h2>Why the import configuration is read</h2>
 *
 * <p>The {@code -importcfg} file names the export data of every dependency, one line per
 * dependency: {@code packagefile <import path>=<file>}. That mapping is what lets the front end
 * resolve a dependency's types <b>without reading its source</b> -- and resolving from the export
 * data is what makes the analysis agree with the build, because the export data is what the build
 * itself compiled against.
 *
 * <h2>Why the tool is recognised by its shape and not by its name</h2>
 *
 * <p>The compiler and the linker are called {@code compile} and {@code link}. Those names are far
 * too general to match on their own, so a command is only taken for a Go one when its executable
 * sits where the Go toolchain keeps its tools -- under {@code pkg/tool/<goos>_<goarch>/} -- or when
 * it carries a Go-specific option.
 */
public final class GoCompilePlan {

  private GoCompilePlan() {}

  /**
   * Whether a captured command is a Go compilation.
   *
   * @param entry the captured command
   * @return true when the command is the Go toolchain's compiler or linker
   */
  public static boolean isGoCommand(CompilationDatabaseEntry entry) {
    if (entry == null || entry.arguments() == null || entry.arguments().isEmpty()) {
      return false;
    }
    List<String> arguments = entry.arguments();
    String tool = CsharpCompilePlan.toolName(arguments.get(0));
    if (!tool.equals("compile") && !tool.equals("link")) {
      return false;
    }
    if (arguments.get(0).replace('\\', '/').contains("/pkg/tool/")) {
      return true;
    }
    for (String argument : arguments) {
      if (argument.startsWith("-importcfg") || argument.startsWith("-lang=go")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a captured command is the Go compiler (as opposed to the linker).
   *
   * @param entry the captured command
   * @return true when the command compiles a package
   */
  public static boolean isGoCompileCommand(CompilationDatabaseEntry entry) {
    return isGoCommand(entry)
        && CsharpCompilePlan.toolName(entry.arguments().get(0)).equals("compile");
  }

  /**
   * Whether a captured command is the Go linker.
   *
   * @param entry the captured command
   * @return true when the command links the binary
   */
  public static boolean isGoLinkCommand(CompilationDatabaseEntry entry) {
    return isGoCommand(entry)
        && CsharpCompilePlan.toolName(entry.arguments().get(0)).equals("link");
  }

  /**
   * Read the whole Go project out of the captured commands.
   *
   * @param entries the captured commands
   * @return the project
   */
  public static GoProjectProfile projectOf(List<CompilationDatabaseEntry> entries) {
    Map<String, GoPackage> packages = new LinkedHashMap<>();
    Set<Path> moduleRoots = new LinkedHashSet<>();
    List<String> linked = new ArrayList<>();
    List<String> lost = new ArrayList<>();
    Path binary = null;
    Path goroot = null;

    if (entries != null) {
      for (CompilationDatabaseEntry entry : entries) {
        if (!isGoCommand(entry)) {
          continue;
        }
        Path directory = entry.directory() == null ? Path.of(".") : Path.of(entry.directory());
        if (goroot == null) {
          goroot = toolchainRootOf(entry);
        }
        if (isGoLinkCommand(entry)) {
          Path config = importConfigOf(entry, directory);
          if (config != null) {
            linked.addAll(readImportConfig(config, lost).keySet());
          }
          Path out = outputOf(entry, directory);
          if (out != null) {
            binary = out;
          }
          continue;
        }
        GoPackage compiled = packageOf(entry, directory, lost);
        if (compiled == null) {
          continue;
        }
        packages.putIfAbsent(compiled.importPath(), compiled);
        if (compiled.directory() != null) {
          Path root = moduleRootOf(compiled.directory());
          if (root != null) {
            moduleRoots.add(root);
          }
        }
      }
    }
    return new GoProjectProfile(
        List.copyOf(packages.values()),
        List.copyOf(moduleRoots),
        List.copyOf(linked),
        binary,
        lost,
        goroot);
  }

  /**
   * The Go toolchain's root directory, as the captured command names it.
   *
   * <h2>Why the toolchain has to come from the capture</h2>
   *
   * <p>The standard library's source lives under the toolchain's root, and the front end has to
   * find it to type-check the project's dependencies. It cannot ask the environment: the {@code
   * GOROOT} variable is a leftover of whatever toolchain was installed last, it survives a
   * toolchain being removed, and a value that points at a directory that no longer exists makes the
   * lookup fail <b>silently</b> -- every standard library import goes unresolved, the calls into
   * the standard library lose their types, and the findings that depend on them disappear while the
   * scan reports success.
   *
   * <p>The captured command answers the question exactly. The toolchain's compiler is at {@code
   * <root>/pkg/tool/<goos>_<goarch>/compile}, so the root is the part before {@code /pkg/tool/}.
   * That is the toolchain that <b>built this project</b>, which is the one whose standard library
   * the project's code was compiled against.
   *
   * @param entry the captured command
   * @return the toolchain's root directory, or null when the command does not name one
   */
  static Path toolchainRootOf(CompilationDatabaseEntry entry) {
    if (entry == null || entry.arguments() == null || entry.arguments().isEmpty()) {
      return null;
    }
    String tool = entry.arguments().get(0);
    if (tool == null || tool.isBlank()) {
      return null;
    }
    String normalized = tool.replace('\\', '/');
    int marker = normalized.indexOf("/pkg/tool/");
    if (marker > 0) {
      return existingDirectory(normalized.substring(0, marker));
    }
    // The go command itself lives in <root>/bin/go.
    if (normalized.endsWith("/bin/go") || normalized.endsWith("/bin/go.exe")) {
      int bin = normalized.lastIndexOf("/bin/");
      if (bin > 0) {
        return existingDirectory(normalized.substring(0, bin));
      }
    }
    return null;
  }

  /**
   * A path, when it names a Go toolchain's root.
   *
   * <p>The rule itself -- what makes a directory a toolchain's root -- is not restated here. It
   * lives with the front end's own answer, because the capture lane and the PATH lane have to agree
   * on it: a copy that drifts hands the front end a root whose standard library is gone, and the
   * standard library's imports then fail SILENTLY.
   *
   * @param path the path as it was written
   * @return the path, or null when it does not name a toolchain
   */
  private static Path existingDirectory(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    try {
      Path candidate = Path.of(path.replace('/', java.io.File.separatorChar)).toAbsolutePath();
      return GoProjectDiscovery.isToolchainRoot(candidate) ? candidate.normalize() : null;
    } catch (RuntimeException e) {
      // A path the platform cannot even parse is not a toolchain.
      return null;
    }
  }

  /**
   * Read one package out of a captured compiler command.
   *
   * @param entry the captured command
   * @param directory the command's directory
   * @return the package, or null when the command does not compile one
   */
  static GoPackage packageOf(CompilationDatabaseEntry entry, Path directory) {
    return packageOf(entry, directory, new ArrayList<>());
  }

  /**
   * Read one package out of a captured compiler command.
   *
   * @param entry the captured command
   * @param directory the command's directory
   * @param lost the import configurations that could not be read, to fill
   * @return the package, or null when the command does not compile one
   */
  static GoPackage packageOf(CompilationDatabaseEntry entry, Path directory, List<String> lost) {
    List<String> arguments = entry.arguments();
    String importPath = null;
    String goVersion = null;
    Path importConfig = null;
    Path output = null;
    List<Path> files = new ArrayList<>();
    List<String> tags = new ArrayList<>();

    for (int i = 0; i < arguments.size(); i++) {
      String argument = arguments.get(i);
      if (argument == null || argument.isEmpty()) {
        continue;
      }
      if (!argument.startsWith("-")) {
        if (isGoSource(argument)) {
          files.add(resolve(directory, argument));
        }
        continue;
      }
      String name = flagName(argument);
      String value = flagValue(argument);
      switch (name) {
        case "p" -> importPath = value == null ? nextValue(arguments, ++i) : value;
        case "lang" -> goVersion = value == null ? nextValue(arguments, ++i) : value;
        case "importcfg" -> {
          String path = value == null ? nextValue(arguments, ++i) : value;
          importConfig = resolve(directory, path);
        }
        case "o" -> {
          String path = value == null ? nextValue(arguments, ++i) : value;
          output = resolve(directory, path);
        }
        case "tags" -> {
          String path = value == null ? nextValue(arguments, ++i) : value;
          if (path != null) {
            tags.addAll(List.of(path.split(",")));
          }
        }
        default -> {
          // A flag whose value is a separate token has to be consumed, or the
          // value would be read as a positional argument.
          if (value == null && takesSeparateValue(name) && i + 1 < arguments.size()) {
            i++;
          }
        }
      }
    }

    if (importPath == null || importPath.isBlank()) {
      // A package built from the command line has no import path of its own.
      importPath = "command-line-arguments";
    }
    Map<String, Path> imports =
        importConfig == null ? Map.of() : readImportConfig(importConfig, lost);
    Path packageDirectory = files.isEmpty() ? directory : files.get(0).getParent();
    // The export data is gone by the time the analysis reads the capture -- the build
    // deletes its temporary directory when it ends -- so the dependencies are resolved
    // from their source. A dependency that was REPLACED, or that belongs to another
    // module of a workspace, is not in the toolchain's module cache, so the modules
    // that live on disk are read from the module's own build description.
    Map<String, Path> modules = GoModuleSources.modulesOf(moduleRootOf(packageDirectory));
    return new GoPackage(importPath, packageDirectory, files, goVersion, tags, imports, modules);
  }

  /**
   * The import configuration a command names.
   *
   * @param entry the captured command
   * @param directory the command's directory
   * @return the configuration file, or null
   */
  static Path importConfigOf(CompilationDatabaseEntry entry, Path directory) {
    List<String> arguments = entry.arguments();
    for (int i = 0; i < arguments.size(); i++) {
      String argument = arguments.get(i);
      if (argument == null || !argument.startsWith("-")) {
        continue;
      }
      if (!flagName(argument).equals("importcfg")) {
        continue;
      }
      String value = flagValue(argument);
      if (value == null) {
        value = nextValue(arguments, ++i);
      }
      if (value != null) {
        return resolve(directory, value);
      }
    }
    return null;
  }

  /**
   * The output a command names.
   *
   * @param entry the captured command
   * @param directory the command's directory
   * @return the output file, or null
   */
  static Path outputOf(CompilationDatabaseEntry entry, Path directory) {
    List<String> arguments = entry.arguments();
    for (int i = 0; i < arguments.size(); i++) {
      String argument = arguments.get(i);
      if (argument == null || !argument.startsWith("-")) {
        continue;
      }
      if (!flagName(argument).equals("o")) {
        continue;
      }
      String value = flagValue(argument);
      if (value == null) {
        value = nextValue(arguments, ++i);
      }
      if (value != null) {
        return resolve(directory, value);
      }
    }
    return null;
  }

  /**
   * Read an import configuration file.
   *
   * <p>The format is one {@code packagefile <import path>=<file>} line per dependency. A line that
   * does not have that shape is skipped rather than guessed at.
   *
   * @param file the configuration file
   * @return the export data, keyed by the import path
   */
  public static Map<String, Path> readImportConfig(Path file) {
    Map<String, Path> out = new LinkedHashMap<>();
    if (file == null || !Files.isRegularFile(file)) {
      return out;
    }
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return out;
    }
    for (String line : lines) {
      String text = line.trim();
      if (text.isEmpty() || text.startsWith("#")) {
        continue;
      }
      int equals = text.indexOf('=');
      if (equals < 0) {
        continue;
      }
      String key = text.substring(0, equals).trim();
      String value = text.substring(equals + 1).trim();
      if (key.startsWith("packagefile ")) {
        key = key.substring("packagefile ".length()).trim();
      } else if (key.contains(" ")) {
        // A key with a space in it that is not the "packagefile" form names
        // something this reader does not understand, and guessing would be
        // worse than skipping it.
        continue;
      }
      if (key.isEmpty() || value.isEmpty()) {
        continue;
      }
      Path resolved = Path.of(value);
      if (!resolved.isAbsolute() && file.getParent() != null) {
        resolved = file.getParent().resolve(value).normalize();
      }
      out.put(key, resolved);
    }
    return out;
  }

  /**
   * Read an import configuration file, and record the ones that are gone.
   *
   * <h2>Why a missing configuration has to be recorded</h2>
   *
   * <p>The Go toolchain writes the import configuration into the build's <b>temporary</b>
   * directory, and the build deletes that directory when it ends. So a configuration that is gone
   * is the <b>normal</b> case by the time an analyser reads the capture: the capture names a file
   * that no longer exists.
   *
   * <p>Reading that as "the project has no dependencies" would be a lie, and a silent one: the
   * dependencies would be missing from the analysis, the calls into them would be unresolved, and
   * the findings that depend on them would be absent while the scan reported success. So the
   * configuration that was lost is recorded, and the caller says so.
   *
   * @param file the configuration file
   * @param lost the configurations that could not be read, to fill
   * @return the export data, keyed by the import path
   */
  static Map<String, Path> readImportConfig(Path file, List<String> lost) {
    if (file == null) {
      return Map.of();
    }
    if (!Files.isRegularFile(file)) {
      if (lost != null && !lost.contains(file.toString())) {
        lost.add(file.toString());
      }
      return Map.of();
    }
    return readImportConfig(file);
  }

  /**
   * The module root a package directory belongs to.
   *
   * <p>The root is the nearest ancestor directory that holds a {@code go.mod}. A package outside
   * any module -- the standard library, for instance -- has no root.
   *
   * @param directory the package's directory
   * @return the module root, or null
   */
  static Path moduleRootOf(Path directory) {
    if (directory == null) {
      return null;
    }
    Path current = directory.toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("go.mod"))) {
        return current;
      }
      current = current.getParent();
    }
    return null;
  }

  /**
   * The flag's name, without the leading dash and without its value.
   *
   * @param argument the argument
   * @return the flag's name
   */
  static String flagName(String argument) {
    int dash = argument.indexOf('-');
    int equals = argument.indexOf('=');
    String name;
    if (equals >= 0) {
      name = argument.substring(0, equals);
    } else {
      name = argument;
    }
    if (name.startsWith("--")) {
      name = name.substring(2);
    } else if (name.startsWith("-")) {
      name = name.substring(1);
    }
    return name.toLowerCase(Locale.ROOT);
  }

  /**
   * The flag's value, when it was joined to the flag.
   *
   * @param argument the argument
   * @return the value, or null
   */
  static String flagValue(String argument) {
    int equals = argument.indexOf('=');
    if (equals < 0) {
      return null;
    }
    String value = argument.substring(equals + 1).trim();
    return value.isEmpty() ? null : value;
  }

  /**
   * The next argument, when a flag's value is a separate token.
   *
   * @param arguments the arguments
   * @param index the index to read
   * @return the value, or null
   */
  private static String nextValue(List<String> arguments, int index) {
    if (index < 0 || index >= arguments.size()) {
      return null;
    }
    String value = arguments.get(index);
    return value == null || value.isEmpty() ? null : value;
  }

  /**
   * Whether a flag takes its value as the next argument.
   *
   * @param name the flag's name
   * @return true when the value is a separate token
   */
  static boolean takesSeparateValue(String name) {
    return name.equals("p")
        || name.equals("lang")
        || name.equals("importcfg")
        || name.equals("o")
        || name.equals("tags")
        || name.equals("i")
        || name.equals("trimpath")
        || name.equals("goversion")
        || name.equals("d");
  }

  /**
   * Whether an argument names a Go source file.
   *
   * @param argument the argument
   * @return true when it is a Go file
   */
  static boolean isGoSource(String argument) {
    if (argument == null) {
      return false;
    }
    String lower = argument.toLowerCase(Locale.ROOT);
    return lower.endsWith(".go") && !lower.endsWith("_test.go");
  }

  /**
   * Resolve a path against a command's directory.
   *
   * @param directory the directory
   * @param path the path as it was written
   * @return the resolved path
   */
  static Path resolve(Path directory, String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    Path candidate = Path.of(path);
    if (candidate.isAbsolute() || directory == null) {
      return candidate.normalize();
    }
    return directory.resolve(candidate).normalize();
  }
}
