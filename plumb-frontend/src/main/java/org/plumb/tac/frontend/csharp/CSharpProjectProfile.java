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
package org.plumb.tac.frontend.csharp;

import java.nio.file.Path;
import java.util.List;

/**
 * The inputs a C# compilation needs, as the build actually decided them.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A C# compilation is not a list of files. Roslyn needs five inputs: the source files, the
 * reference assemblies, the preprocessor symbols, the language version and the nullable context.
 * The frontend can supply the files on its own, but the other four are <b>facts about the
 * project</b> that only the build knows.
 *
 * <p>Without them the frontend has to invent values, and an invented value is a silent defect
 * rather than a loud one. Two shapes of that defect were measured on the C# sidecar:
 *
 * <ul>
 *   <li>an <b>empty</b> preprocessor symbol set is silent: a function whose body sits inside
 *       {@code #if DEBUG} is simply <b>not in the TAC</b>, and the compiler reports no error at
 *       all, because an inactive conditional region is not a mistake. The finding is gone and the
 *       scan still reports success;
 *   <li>a <b>missing reference assembly</b> is loud: the type does not bind, the compiler reports
 *       {@code CS0234}, and the frontend turns it into a problem.
 * </ul>
 *
 * <p>So the two are not interchangeable. This record exists so the frontend can be handed the
 * real values instead of inventing them, and so that "we did not capture them" is a state the
 * frontend can report instead of paper over.
 *
 * <h2>Where the values come from</h2>
 *
 * <p>A build capture. The C# compiler is invoked once per project, and that invocation carries the
 * reference assemblies ({@code /reference:}), the preprocessor symbols ({@code /define:}), the
 * language version ({@code /langversion:}), the nullable context ({@code /nullable:}) and the
 * source generators ({@code /analyzer:}). A project reference in the build file becomes an ordinary
 * reference assembly on that command line, so the dependency graph of the solution is visible here
 * too.
 *
 * @param references the reference assemblies the compilation binds against
 * @param defines the preprocessor symbols the compilation was given
 * @param langVersion the language version, as the build wrote it ({@code 12.0}), or null
 * @param nullableContext the nullable context ({@code enable}, {@code disable}, {@code warnings},
 *     {@code annotations}), or null
 * @param unsafeAllowed whether the compilation permits unsafe code
 * @param analyzers the source generators and analyzers the compilation was given
 */
public record CSharpProjectProfile(
    List<Path> references,
    List<String> defines,
    String langVersion,
    String nullableContext,
    boolean unsafeAllowed,
    List<Path> analyzers) {

  public CSharpProjectProfile {
    references = references == null ? List.of() : List.copyOf(references);
    defines = defines == null ? List.of() : List.copyOf(defines);
    analyzers = analyzers == null ? List.of() : List.copyOf(analyzers);
    if (langVersion != null && langVersion.isBlank()) {
      langVersion = null;
    }
    if (nullableContext != null && nullableContext.isBlank()) {
      nullableContext = null;
    }
  }

  /**
   * A profile with nothing captured.
   *
   * <p>This is the profile of a scan that had no build capture: the frontend knows the files but
   * not the project's compilation inputs. It is deliberately <b>empty and not null</b> so the
   * frontend can tell "captured, and the project really has no references" from "not captured".
   *
   * @return the empty profile
   */
  public static CSharpProjectProfile empty() {
    return new CSharpProjectProfile(List.of(), List.of(), null, null, false, List.of());
  }

  /**
   * Whether this profile carries any captured input at all.
   *
   * @return true when nothing was captured
   */
  public boolean isEmpty() {
    return references.isEmpty()
        && defines.isEmpty()
        && langVersion == null
        && nullableContext == null
        && analyzers.isEmpty();
  }

  /**
   * The references that are the output of another project in the same solution.
   *
   * <p>A project reference in the build file is compiled into an ordinary reference assembly, and
   * that assembly is what the dependency edge looks like from the compiler's side. So the
   * dependency graph of the solution can be recovered by matching the references against the
   * outputs of the other projects.
   *
   * @param outputs the assembly each project produces, keyed by the project's name
   * @return the names of the projects this one references
   */
  public List<String> referencedProjects(java.util.Map<String, Path> outputs) {
    if (outputs == null || outputs.isEmpty()) {
      return List.of();
    }
    java.util.Set<String> names = new java.util.LinkedHashSet<>();
    for (Path reference : references) {
      if (reference == null) {
        continue;
      }
      String fileName = reference.getFileName() == null ? "" : reference.getFileName().toString();
      for (java.util.Map.Entry<String, Path> entry : outputs.entrySet()) {
        Path output = entry.getValue();
        if (output == null) {
          continue;
        }
        String outputName = output.getFileName() == null ? "" : output.getFileName().toString();
        if (!outputName.isEmpty() && outputName.equals(fileName)) {
          names.add(entry.getKey());
        }
      }
    }
    return List.copyOf(names);
  }
}
