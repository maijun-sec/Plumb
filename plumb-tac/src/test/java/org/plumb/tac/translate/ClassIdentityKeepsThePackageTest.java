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
package org.plumb.tac.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.MergedProgram;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * The declaring class of a function is the class's IDENTITY, and the identity carries the package.
 *
 * <h2>Why this is pinned here</h2>
 *
 * <p>The merged view keys a function on {@code "<enclosing class> :: <signature>"}, and the merged
 * view is what the analysis runs on. Two classes with the same SIMPLE name in different packages
 * are two different classes -- and they have the same signature for the same method. So with the
 * simple name alone, the second class's functions were written over the first class's: the whole
 * class dropped out of the analysis, its functions never ran, and nothing reported it. A missing
 * class is worse than a wrong finding, because a wrong finding at least says something.
 *
 * <p>The real corpus has exactly this shape: Juliet's CWE486 declares {@code
 * CWE486_Compare_Classes_by_Name__Helper} twice -- once in {@code
 * testcases.CWE486_Compare_Classes_by_Name} and once in {@code
 * testcases.CWE486_Compare_Classes_by_Name.HelperClass} -- and both declare {@code public String
 * toString()}.
 */
@DisplayName("Java: the declaring class carries its package")
class ClassIdentityKeepsThePackageTest {

  /** A class with one method, in the package the source declares. */
  private static String sourceIn(String packageName) {
    return "package "
        + packageName
        + ";\n\n"
        + "public class Helper {\n"
        + "  public String toString() {\n"
        + "    return \"helper\";\n"
        + "  }\n"
        + "}\n";
  }

  /** Parse a source file and lower it into a program. */
  private static TacProgram lower(Path dir, String fileName, String source) throws IOException {
    Path file = dir.resolve(fileName);
    Files.writeString(file, source);
    ParsedSource parsed = new JavaSourceParser().parse(file);
    return new TacTranslator(parsed).translate();
  }

  /** The function with the given name, or null. */
  private static TacFunction find(TacProgram program, String name) {
    for (TacFunction fn : program.functions()) {
      if (name.equals(fn.name())) {
        return fn;
      }
    }
    return null;
  }

  @Test
  @DisplayName("a class in a package is named with the package")
  void aClassInAPackageIsNamedWithThePackage(@TempDir Path dir) throws IOException {
    TacProgram program = lower(dir, "Helper.java", sourceIn("pkg.one"));

    TacFunction toString = find(program, "toString");
    assertThat(toString).as("the method must be in the program").isNotNull();
    assertThat(toString.enclosingClass())
        .as("the declaring class is the class's identity, and the package is part of it")
        .isEqualTo("pkg.one.Helper");
  }

  @Test
  @DisplayName("two classes with the same name in different packages both survive the merge")
  void twoSameNamedClassesInDifferentPackagesBothSurviveTheMerge(@TempDir Path dir)
      throws IOException {
    TacProgram one = lower(dir, "HelperOne.java", sourceIn("pkg.one"));
    TacProgram two = lower(dir, "HelperTwo.java", sourceIn("pkg.two"));

    MergedProgram merged = new MergedProgram(List.of(one, two));

    assertThat(merged.functions())
        .as(
            "the two classes have the same simple name and the same method signature, so only"
                + " the package tells them apart -- without it one whole class is dropped from the"
                + " analysis")
        .hasSize(2);
    assertThat(merged.functions())
        .extracting(TacFunction::enclosingClass)
        .containsExactlyInAnyOrder("pkg.one.Helper", "pkg.two.Helper");
  }
}
