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
import java.util.Locale;
import java.util.Set;
import org.plumb.tac.frontend.csharp.CSharpProjectProfile;

/**
 * Reads a C# compilation out of a captured compiler command.
 *
 * <h2>Why the compiler's own command line is the source of truth</h2>
 *
 * <p>A C# project is compiled once, by one invocation of the compiler, and that invocation carries
 * everything the compilation depends on: the reference assemblies, the preprocessor symbols, the
 * language version, the nullable context, and the source generators. A project reference in the
 * build file becomes an ordinary reference assembly on that command line, so the dependency graph
 * of the solution is visible there too.
 *
 * <p>Reconstructing those inputs by reading the build file instead would be a guess, and the
 * difference between a guess and the truth is visible in the results: a missing preprocessor symbol
 * set drops every function behind a conditional compilation block from the analysis without a
 * single diagnostic.
 *
 * <h2>What the option spellings are</h2>
 *
 * <p>The C# compiler accepts {@code /option} and {@code -option} interchangeably, and the option
 * and its value may be joined ({@code /langversion:12.0}) or separate ({@code /langversion 12.0}).
 * Every shape is read, because the spelling is chosen by whoever built the project.
 *
 * <p>The reference assemblies and the preprocessor symbols may each be given as a list in one
 * option: {@code /reference:a.dll,b.dll} and {@code /define:DEBUG;TRACE}. Both lists are split.
 */
public final class CsharpCompilePlan {

  private CsharpCompilePlan() {}

  /**
   * One C# project, as the compiler was told to build it.
   *
   * @param profile the compilation inputs the frontend needs
   * @param output the assembly the compilation produces, or null when it was not given
   * @param sourceFiles the source files the compilation compiled
   * @param directory the directory the compiler ran in
   */
  public record Project(
      CSharpProjectProfile profile, Path output, List<Path> sourceFiles, Path directory) {

    public Project {
      sourceFiles = sourceFiles == null ? List.of() : List.copyOf(sourceFiles);
    }
  }

  /**
   * Whether a captured command is a C# compilation.
   *
   * <p>The compiler's own name is the first signal, and the source files are the second. Both are
   * checked because a build may drive the compiler through a wrapper, and because a command that
   * compiles {@code .cs} files is a C# compilation whatever it is called.
   *
   * @param entry the captured command
   * @return true when the command compiles C#
   */
  public static boolean isCsharpCommand(CompilationDatabaseEntry entry) {
    if (entry == null || entry.arguments() == null || entry.arguments().isEmpty()) {
      return false;
    }
    String tool = toolName(entry.arguments().get(0));
    if (tool.equals("csc") || tool.equals("mcs") || tool.equals("dmcs")) {
      return true;
    }
    for (String argument : entry.arguments()) {
      if (isCsharpSource(argument)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Read every C# project out of the captured commands.
   *
   * @param entries the captured commands
   * @return the projects, in the order they were captured
   */
  public static List<Project> projectsOf(List<CompilationDatabaseEntry> entries) {
    List<Project> out = new ArrayList<>();
    if (entries == null) {
      return out;
    }
    for (CompilationDatabaseEntry entry : entries) {
      if (!isCsharpCommand(entry)) {
        continue;
      }
      out.add(projectOf(entry));
    }
    return out;
  }

  /**
   * Read one C# project out of a captured command.
   *
   * @param entry the captured command
   * @return the project
   */
  public static Project projectOf(CompilationDatabaseEntry entry) {
    List<String> arguments = entry.arguments();
    Path directory = entry.directory() == null ? Path.of(".") : Path.of(entry.directory());

    List<Path> references = new ArrayList<>();
    // The symbols are a SET. A symbol given twice -- in one option's list or in
    // two options -- is one symbol, and a profile that listed it twice would
    // misstate the build it read.
    Set<String> defines = new LinkedHashSet<>();
    List<Path> analyzers = new ArrayList<>();
    List<Path> sourceFiles = new ArrayList<>();
    String langVersion = null;
    String nullableContext = null;
    boolean unsafeAllowed = false;
    Path output = null;

    for (int i = 0; i < arguments.size(); i++) {
      String argument = arguments.get(i);
      if (argument == null || argument.isEmpty()) {
        continue;
      }
      String option = optionName(argument);
      if (option == null) {
        // A positional argument that names a C# file is a source file. The
        // compiler's own options are all recognised above, so anything left
        // that ends in ".cs" is a file.
        //
        // A source file is NEVER looked for inside an option's value: the
        // option "/recurse:*.cs" names a pattern, not a file, and treating
        // the pattern as a source file would put a file that does not exist
        // into the compilation.
        if (isCsharpSource(argument)) {
          sourceFiles.add(resolve(directory, argument));
        }
        continue;
      }
      // The option's value is either joined to it ("/langversion:12") or the
      // next token ("/langversion 12"). Both spellings are read, because the
      // spelling is chosen by whoever built the project.
      String value = optionValue(argument);
      if (value == null && needsSeparateValue(option) && i + 1 < arguments.size()) {
        value = arguments.get(++i);
      }
      switch (option) {
        case "reference", "r" -> references.addAll(resolveAll(directory, splitList(value)));
        case "define", "d" -> defines.addAll(splitSymbols(value));
        case "analyzer" -> analyzers.addAll(resolveAll(directory, splitList(value)));
        case "langversion" -> langVersion = value;
        case "nullable" -> nullableContext = value;
        case "out" -> output = resolve(directory, value);
        case "unsafe" -> unsafeAllowed = true;
        case "unsafe-" -> unsafeAllowed = false;
        default -> {
          // An option this reader does not need. Its value, joined or
          // separate, is not read: an unknown option is not a compilation
          // input, and guessing at its shape would be worse than ignoring it.
        }
      }
    }

    CSharpProjectProfile profile =
        new CSharpProjectProfile(
            references,
            List.copyOf(defines),
            langVersion,
            nullableContext,
            unsafeAllowed,
            analyzers);
    return new Project(profile, output, sourceFiles, directory);
  }

  /**
   * The option's name, without the leading slash or dash.
   *
   * @param argument the argument
   * @return the option's name, or null when the argument is not an option
   */
  static String optionName(String argument) {
    if (argument == null || argument.length() < 2) {
      return null;
    }
    char first = argument.charAt(0);
    if (first != '/' && first != '-') {
      return null;
    }
    int colon = argument.indexOf(':');
    String name = colon < 0 ? argument.substring(1) : argument.substring(1, colon);
    return name.isEmpty() ? null : name.toLowerCase(Locale.ROOT);
  }

  /**
   * The option's value, whether it was joined to the option or given as the next token.
   *
   * @param argument the argument
   * @return the value, or null when the argument carries none
   */
  static String optionValue(String argument) {
    int colon = argument.indexOf(':');
    if (colon < 0) {
      return null;
    }
    String value = argument.substring(colon + 1).trim();
    return unquote(value);
  }

  /**
   * Whether an option takes its value as the next argument.
   *
   * @param option the option's name
   * @return true when the value is a separate token
   */
  static boolean needsSeparateValue(String option) {
    return option.equals("reference")
        || option.equals("r")
        || option.equals("define")
        || option.equals("d")
        || option.equals("analyzer")
        || option.equals("langversion")
        || option.equals("nullable")
        || option.equals("out");
  }

  /**
   * Split a list-valued option.
   *
   * <p>The compiler accepts both a comma and a semicolon as the separator, and the separator that
   * is used depends on the platform the build ran on.
   *
   * @param value the option's value
   * @return the items
   */
  static List<String> splitList(String value) {
    List<String> out = new ArrayList<>();
    if (value == null || value.isBlank()) {
      return out;
    }
    for (String part : value.split("[,;]")) {
      String item = unquote(part.trim());
      if (!item.isEmpty()) {
        out.add(item);
      }
    }
    return out;
  }

  /**
   * Split the preprocessor symbols.
   *
   * <p>A symbol may carry a value ({@code TRACE=1}), and the value is kept: it is part of the
   * symbol's identity as far as the compiler is concerned.
   *
   * @param value the option's value
   * @return the symbols
   */
  static List<String> splitSymbols(String value) {
    Set<String> out = new LinkedHashSet<>();
    out.addAll(splitList(value));
    return List.copyOf(out);
  }

  /**
   * Whether an argument names a C# source file.
   *
   * @param argument the argument
   * @return true when it is a C# file
   */
  static boolean isCsharpSource(String argument) {
    if (argument == null) {
      return false;
    }
    String lower = argument.toLowerCase(Locale.ROOT);
    return lower.endsWith(".cs") && !lower.endsWith(".csproj");
  }

  /**
   * The compiler's name, without its directory or its extension.
   *
   * @param executable the executable as it was written
   * @return the name
   */
  static String toolName(String executable) {
    if (executable == null) {
      return "";
    }
    String name = executable.replace('\\', '/');
    int slash = name.lastIndexOf('/');
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    if (name.toLowerCase(Locale.ROOT).endsWith(".exe")) {
      name = name.substring(0, name.length() - 4);
    }
    return name.toLowerCase(Locale.ROOT);
  }

  /**
   * Resolve a path against the compile's directory.
   *
   * @param directory the compile's directory
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

  private static List<Path> resolveAll(Path directory, List<String> paths) {
    List<Path> out = new ArrayList<>();
    for (String path : paths) {
      Path resolved = resolve(directory, path);
      if (resolved != null) {
        out.add(resolved);
      }
    }
    return out;
  }

  /**
   * Strip the quotes a path may still carry.
   *
   * <p>The compiler's command line spells a path with spaces as {@code "a b.dll"}, and the quotes
   * are part of the option's value rather than part of the path.
   *
   * @param value the value
   * @return the value without its surrounding quotes
   */
  static String unquote(String value) {
    if (value == null || value.length() < 2) {
      return value;
    }
    if (value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
