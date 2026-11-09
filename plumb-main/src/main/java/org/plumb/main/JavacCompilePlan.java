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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.plumb.build.capture.CaptureEntry;

/**
 * Reads a JVM compilation out of a captured compiler command.
 *
 * <h2>Why this is a separate shape</h2>
 *
 * <p>The C# and Go compilations need inputs that do not fit the JVM's: a C# compilation names its
 * reference assemblies and its preprocessor symbols, and a Go compilation names a package and a set
 * of export data files. Those are modelled by their own plans.
 *
 * <p>The JVM's compilation does fit the existing capture shape, so it is read straight into a
 * {@link CaptureEntry} -- the record the JVM frontend path already consumes.
 *
 * <h2>What is read</h2>
 *
 * <p>The classpath, the module path, the processor path, the boot class path, the output directory,
 * the encoding, the language level and the source files. Those are the things that decide whether a
 * type resolves, and a resolution that fails is a finding that is silently missing.
 */
public final class JavacCompilePlan {

  private JavacCompilePlan() {}

  /**
   * Whether a captured command is a JVM compilation.
   *
   * @param entry the captured command
   * @return true when the command compiles Java, Kotlin or Scala
   */
  public static boolean isJvmCommand(CompilationDatabaseEntry entry) {
    if (entry == null || entry.arguments() == null || entry.arguments().isEmpty()) {
      return false;
    }
    String tool = CsharpCompilePlan.toolName(entry.arguments().get(0));
    if (tool.equals("javac")
        || tool.equals("ecj")
        || tool.equals("sjavac")
        || tool.equals("kotlinc")
        || tool.equals("scala")
        || tool.equals("scalac")) {
      return true;
    }
    for (String argument : entry.arguments()) {
      if (isJvmSource(argument)) {
        return true;
      }
    }
    // The compilation unit decides, too.
    //
    // A database entry names the file it compiled, and a file's extension is
    // what says which language it is in. So an entry whose unit is a Java,
    // Kotlin or Scala file IS a JVM compilation, even when its arguments do
    // not lead with the compiler's name.
    //
    // That is not a corner case: a build that compiles inside the JVM -- a
    // Maven build, for one -- hands the compiler's options over as a list
    // rather than as a command line, and a database written from that list
    // has no compiler to name. Without this the entry is read as "not a
    // compile at all" and the whole module silently disappears.
    return isJvmSource(entry.file());
  }

  /**
   * Read one JVM compilation out of a captured command.
   *
   * @param entry the captured command
   * @return the capture
   */
  public static CaptureEntry captureOf(CompilationDatabaseEntry entry) {
    List<String> arguments = entry.arguments();
    Path directory = entry.directory() == null ? Path.of(".") : Path.of(entry.directory());

    List<Path> classpath = new ArrayList<>();
    List<Path> modulePath = new ArrayList<>();
    List<Path> processorPath = new ArrayList<>();
    List<Path> bootClassPath = new ArrayList<>();
    List<Path> sourceFiles = new ArrayList<>();
    List<Path> sourcePath = new ArrayList<>();
    List<String> addModules = new ArrayList<>();
    String encoding = null;
    String languageLevel = null;
    Path outputDirectory = null;
    boolean preview = false;

    for (int i = 0; i < arguments.size(); i++) {
      String argument = arguments.get(i);
      if (argument == null || argument.isEmpty()) {
        continue;
      }
      String name = optionName(argument);
      if (name == null) {
        // A POSITIONAL argument: the source file.
        //
        // The option's name is null for every argument that is not an option,
        // and a JVM compilation always names its sources positionally -- so
        // this is the common case, not the exceptional one. Switching on the
        // name without looking at it first would throw on every source file.
        if (isJvmSource(argument)) {
          sourceFiles.add(resolve(directory, argument));
        }
        continue;
      }
      // The option's value. An option takes its value either joined to it
      // ("-cp=a.jar") or as the NEXT argument ("-cp a.jar"), and the second
      // form is the one a build system uses. So the value has to be read
      // BEFORE the option is handled -- reading it afterwards meant that
      // "-cp a.jar" contributed an empty classpath and the "a.jar" was
      // skipped as if it were an argument of its own.
      String value = optionValue(argument);
      boolean separateValue = false;
      if (value == null && takesSeparateValue(name) && i + 1 < arguments.size()) {
        value = arguments.get(i + 1);
        separateValue = true;
      }
      switch (name) {
        case "classpath", "cp", "class-path" -> classpath.addAll(resolved(directory, value));
        case "sourcepath", "source-path" -> sourcePath.addAll(resolved(directory, value));
        case "module-path", "p" -> modulePath.addAll(resolved(directory, value));
        case "processor-path", "processorpath" -> processorPath.addAll(resolved(directory, value));
        case "boot-class-path", "bootclasspath" -> bootClassPath.addAll(resolved(directory, value));
        case "d" -> outputDirectory = resolve(directory, value);
        case "encoding" -> encoding = value;
        case "release", "source" -> languageLevel = value;
        case "add-modules" -> addModules.addAll(splitList(value));
        case "enable-preview" -> preview = true;
        default -> {
          if (isJvmSource(argument)) {
            sourceFiles.add(resolve(directory, argument));
          }
        }
      }
      if (separateValue) {
        i++;
      }
    }

    String compiler = CsharpCompilePlan.toolName(arguments.get(0));
    if (sourceFiles.isEmpty() && isJvmSource(entry.file())) {
      // The arguments named no source file, but the entry names the unit it
      // compiled. That is the case a build that compiles inside the JVM
      // produces: the sources were handed to the compiler as a list, not as
      // command line arguments. Without this the capture compiles nothing.
      sourceFiles.add(resolve(directory, entry.file()));
    }
    String language = languageOf(compiler, sourceFiles);
    if (sourcePath.isEmpty()) {
      sourcePath.addAll(derivedSourceRoots(sourceFiles, directory));
    }
    return new CaptureEntry(
        "cli",
        compiler,
        language,
        arguments,
        encoding,
        languageLevel,
        outputDirectory,
        classpath,
        sourcePath,
        modulePath,
        processorPath,
        bootClassPath,
        sourceFiles,
        addModules,
        List.of(),
        List.of(),
        preview,
        true,
        null,
        null,
        directory.toString());
  }

  /**
   * The language a JVM compilation compiles.
   *
   * @param compiler the compiler's name
   * @param sourceFiles the source files
   * @return the language name
   */
  private static String languageOf(String compiler, List<Path> sourceFiles) {
    switch (compiler) {
      case "kotlinc" -> {
        return "kotlin";
      }
      case "scala", "scalac" -> {
        return "scala";
      }
      default -> {
        // The extension decides it: javac compiles Java, and the other two
        // front ends are recognised by their own names above.
        for (Path file : sourceFiles) {
          String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
          if (name.endsWith(".kt") || name.endsWith(".kts")) {
            return "kotlin";
          }
          if (name.endsWith(".scala")) {
            return "scala";
          }
        }
        return "java";
      }
    }
  }

  /**
   * The source roots the compilation implies, when the command does not state them.
   *
   * <p><b>Why a source root has to be recovered at all.</b> The frontends use it as the root they
   * see the project through: the Kotlin frontend stages the file it analyses under a source root,
   * because the Analysis API only resolves a file that is under the session's content scope; and
   * the Scala frontend copies the project's Java sources under a source root, because that is where
   * the compiler looks for a class that exists only as source.
   *
   * <p>Without one, both fall back to the scanner's own working directory -- and then the Kotlin
   * frontend writes its scratch file into whatever directory the scanner happened to run in, and
   * the Scala frontend copies every Java file it finds there into the compile. A scan that writes
   * into the directory it was started from is a scan nobody can run twice.
   *
   * <p><b>Why the package path has to be peeled off.</b> A file's own directory is its *package*
   * directory, not the source root: for {@code src/main/java/demo/core/Core.java} it is {@code
   * src/main/java/demo/core}. A type solver looks a type up at {@code root/<the type's package
   * path>}, so a root that already contains the package path makes every lookup miss by one segment
   * -- and the miss is silent, because an unresolved type is simply an unknown type.
   *
   * <p>So each file's package is peeled off its own directory, and the roots that are left share a
   * common ancestor: {@code src/main/java} for a JVM project.
   *
   * @param sourceFiles the compiled source files
   * @param compileDirectory the compile's own directory, used when the files share no root
   * @return the source roots; empty when nothing could be recovered
   */
  private static List<Path> derivedSourceRoots(List<Path> sourceFiles, Path compileDirectory) {
    if (sourceFiles == null || sourceFiles.isEmpty()) {
      return List.of();
    }
    Path ancestor = null;
    for (Path file : sourceFiles) {
      Path root = sourceRootOf(file);
      if (root == null) {
        continue;
      }
      ancestor = ancestor == null ? root : commonAncestor(ancestor, root);
      if (ancestor == null) {
        break;
      }
    }
    if (ancestor == null) {
      // The files share no root -- they are on different drives, or one of them
      // has no parent. The compile's own directory is the next best answer.
      ancestor = compileDirectory;
    }
    if (ancestor == null) {
      return List.of();
    }
    return List.of(ancestor);
  }

  /**
   * The source root a compiled file belongs to.
   *
   * <p>The file's own directory is the package directory, and the package says how many segments
   * sit between the root and the file. So the root is the file's directory with the package's own
   * segments peeled off.
   *
   * <p>The peel is checked against the directory as it is: a segment has to be there to be peeled.
   * So a directory that is already the root is left alone, and a file whose package cannot be read
   * keeps its own directory -- a root that is too shallow still finds the file's own type, while a
   * root that is too deep finds nothing at all.
   *
   * @param file the compiled source file
   * @return the source root, or {@code null} when the file has no parent
   */
  private static Path sourceRootOf(Path file) {
    if (file == null) {
      return null;
    }
    Path directory = file.getParent();
    if (directory == null) {
      return null;
    }
    String packageName = packageOf(file);
    if (packageName == null || packageName.isEmpty()) {
      return directory;
    }
    String[] segments = packageName.split("\\.");
    Path root = directory;
    for (int i = segments.length - 1; i >= 0; i--) {
      Path parent = root.getParent();
      if (parent == null || root.getFileName() == null) {
        return directory;
      }
      if (!root.getFileName().toString().equals(segments[i])) {
        return directory;
      }
      root = parent;
    }
    return root;
  }

  /**
   * The package a source file declares.
   *
   * <p>The declaration is on its own line and always comes before the first type, so the search is
   * for a line that starts with the keyword. A line inside a block comment cannot start with the
   * keyword -- a comment line starts with the comment's own mark -- so a commented-out declaration
   * is not mistaken for a real one.
   *
   * @param file the source file
   * @return the package name, or {@code null} when it declares none
   */
  private static String packageOf(Path file) {
    try {
      String text = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
      java.util.regex.Matcher matcher =
          java.util.regex.Pattern.compile(
                  "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*)\\s*;")
              .matcher(text);
      if (matcher.find()) {
        return matcher.group(1);
      }
    } catch (IOException | RuntimeException e) {
      // A file that cannot be read says nothing about its package.
      return null;
    }
    return null;
  }

  /**
   * The deepest directory that contains both paths.
   *
   * @param one the first path
   * @param other the second path
   * @return the common ancestor, or {@code null} when they share none
   */
  private static Path commonAncestor(Path one, Path other) {
    Path current = one;
    while (current != null && !other.startsWith(current)) {
      current = current.getParent();
    }
    return current;
  }

  /**
   * The option's name, without the leading dash.
   *
   * @param argument the argument
   * @return the option's name, or null
   */
  private static String optionName(String argument) {
    if (argument == null || argument.length() < 2 || argument.charAt(0) != '-') {
      return null;
    }
    String name = argument.startsWith("--") ? argument.substring(2) : argument.substring(1);
    int equals = name.indexOf('=');
    if (equals >= 0) {
      name = name.substring(0, equals);
    }
    return name.isEmpty() ? null : name.toLowerCase(Locale.ROOT);
  }

  /**
   * The option's value, when it was joined to the option.
   *
   * @param argument the argument
   * @return the value, or null
   */
  private static String optionValue(String argument) {
    int equals = argument.indexOf('=');
    if (equals < 0) {
      return null;
    }
    return argument.substring(equals + 1).trim();
  }

  /**
   * Whether an option takes its value as the next argument.
   *
   * @param name the option's name
   * @return true when the value is a separate token
   */
  private static boolean takesSeparateValue(String name) {
    return name.equals("classpath")
        || name.equals("cp")
        || name.equals("class-path")
        || name.equals("sourcepath")
        || name.equals("source-path")
        || name.equals("module-path")
        || name.equals("p")
        || name.equals("processor-path")
        || name.equals("processorpath")
        || name.equals("boot-class-path")
        || name.equals("bootclasspath")
        || name.equals("d")
        || name.equals("encoding")
        || name.equals("release")
        || name.equals("source")
        || name.equals("add-modules");
  }

  /**
   * Split a path list.
   *
   * <p>The separator is the platform's own, and only that one: a Windows path contains a colon, so
   * splitting on both separators would cut {@code C:\build\classes} in half.
   *
   * @param value the option's value
   * @return the paths
   */
  private static List<Path> paths(String value) {
    List<Path> out = new ArrayList<>();
    if (value == null || value.isBlank()) {
      return out;
    }
    for (String part : value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
      String item = part.trim();
      if (!item.isEmpty()) {
        out.add(Path.of(item));
      }
    }
    return out;
  }

  /**
   * Split a path list, and resolve every entry against the compile's directory.
   *
   * <p>A relative path in a compiler command is relative to the compiler's own working directory,
   * which is the compile's directory. Resolving it there is what makes the entry mean the same
   * thing to the analysis as it meant to the compiler; leaving it relative makes it mean "relative
   * to wherever the scanner happens to run", which is a different path.
   *
   * @param directory the compile's directory
   * @param value the option's value
   * @return the resolved paths
   */
  private static List<Path> resolved(Path directory, String value) {
    List<Path> out = new ArrayList<>();
    for (Path path : paths(value)) {
      out.add(resolve(directory, path.toString()));
    }
    return out;
  }

  /**
   * Split a comma-separated list.
   *
   * @param value the option's value
   * @return the items
   */
  private static List<String> splitList(String value) {
    List<String> out = new ArrayList<>();
    if (value == null || value.isBlank()) {
      return out;
    }
    for (String part : value.split(",")) {
      String item = part.trim();
      if (!item.isEmpty()) {
        out.add(item);
      }
    }
    return out;
  }

  /**
   * Whether an argument names a JVM source file.
   *
   * @param argument the argument
   * @return true when it is a Java, Kotlin or Scala file
   */
  private static boolean isJvmSource(String argument) {
    if (argument == null) {
      return false;
    }
    String lower = argument.toLowerCase(Locale.ROOT);
    return lower.endsWith(".java")
        || lower.endsWith(".kt")
        || lower.endsWith(".kts")
        || lower.endsWith(".scala");
  }

  /**
   * Resolve a path against the compile's directory.
   *
   * @param directory the compile's directory
   * @param path the path as it was written
   * @return the resolved path
   */
  private static Path resolve(Path directory, String path) {
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
