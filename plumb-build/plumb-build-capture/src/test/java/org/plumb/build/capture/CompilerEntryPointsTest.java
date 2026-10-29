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
package org.plumb.build.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the declarative entry-point list.
 *
 * <p>The list is the module's contract with the compiler: it says which methods the agent hooks.
 * Two things have to hold.
 *
 * <ol>
 *   <li>The list has to cover the entry points a real build actually goes through. A build tool
 *       that uses the {@code javax.tools} API and a build that shells out to {@code javac} take
 *       completely different paths, and hooking only one of them makes the capture silently useless
 *       for the other.
 *   <li>The module must not reach for raw ASM. ByteBuddy's {@code Advice} inlines the hook and
 *       needs no super method, so a hand-written {@code MethodVisitor} is not just unnecessary --
 *       it is a chance to corrupt the compiler's stack.
 * </ol>
 */
class CompilerEntryPointsTest {

  @Test
  void coversTheCommandLineDriver() {
    assertThat(CompilerEntryPoints.forType("com.sun.tools.javac.Main")).isNotEmpty();
    assertThat(CompilerEntryPoints.forType("com.sun.tools.javac.main.Main")).isNotEmpty();
  }

  @Test
  void coversTheJavaxToolsApi() {
    // The path Gradle, Maven and the IDEs take.
    assertThat(CompilerEntryPoints.forType("com.sun.tools.javac.api.JavacTool")).isNotEmpty();
  }

  @Test
  void coversTheLegacyJavaCompilerRunApi() {
    // The `run` method lives on JavacTool, not on javax.tools.JavaCompiler:
    // on a modern JDK the latter is an interface with only getTask and
    // getStandardFileManager. A hook on javax.tools.JavaCompiler#run would
    // never fire.
    assertThat(CompilerEntryPoints.forType("com.sun.tools.javac.api.JavacTool"))
        .anyMatch(e -> e.methodName().equals("run"));
    assertThat(CompilerEntryPoints.forType("javax.tools.JavaCompiler"))
        .as("javax.tools.JavaCompiler has no run method on a modern JDK")
        .isEmpty();
  }

  @Test
  void coversTheIdeTaskPath() {
    // An IDE creates a task and then drives it. Without these hooks the IDE
    // path produces no capture at all.
    assertThat(CompilerEntryPoints.forType("com.sun.tools.javac.api.JavacTaskImpl"))
        .extracting(CompilerEntryPoints.EntryPoint::methodName)
        .contains("call", "generate");
  }

  @Test
  void coversEclipsesCompiler() {
    assertThat(CompilerEntryPoints.forType("org.eclipse.jdt.internal.compiler.batch.Main"))
        .isNotEmpty();
  }

  @Test
  void coversTheSjavacServer() {
    assertThat(CompilerEntryPoints.forType("com.sun.tools.sjavac.client.ClientMain")).isNotEmpty();
    assertThat(CompilerEntryPoints.forType("com.sun.tools.sjavac.server.ServerMain")).isNotEmpty();
  }

  @Test
  void everyEntryPointNamesAKnownCompilerClass() {
    for (CompilerEntryPoints.EntryPoint entry : CompilerEntryPoints.all()) {
      assertThat(entry.typeName()).as("entry point %s", entry.describe()).isNotBlank();
      assertThat(entry.methodName()).as("entry point %s", entry.describe()).isNotBlank();
      assertThat(entry.argumentCount()).as("entry point %s", entry.describe()).isNotNegative();
    }
  }

  @Test
  void everyArgvEntryPointHasAKnownArgumentIndex() {
    for (CompilerEntryPoints.EntryPoint entry : CompilerEntryPoints.all()) {
      if (entry.kind() != CompilerEntryPoints.EntryPoint.Kind.ARGV) {
        continue;
      }
      assertThat(entry.optionsIndex())
          .as("entry point %s", entry.describe())
          .isBetween(0, entry.argumentCount() - 1);
    }
  }

  @Test
  void theApiEntryPointHasBothTheOptionsAndTheSources() {
    CompilerEntryPoints.EntryPoint api =
        CompilerEntryPoints.forType("com.sun.tools.javac.api.JavacTool").stream()
            .filter(e -> e.methodName().equals("getTask"))
            .findFirst()
            .orElseThrow();
    assertThat(api.kind()).isEqualTo(CompilerEntryPoints.EntryPoint.Kind.FILE_OBJECTS_WITH_TASK);
    assertThat(api.optionsIndex()).isBetween(0, api.argumentCount() - 1);
    assertThat(api.sourcesIndex()).isBetween(0, api.argumentCount() - 1);
    assertThat(api.optionsIndex()).isNotEqualTo(api.sourcesIndex());
  }

  /**
   * The entry points that carry no options must not claim an options index.
   *
   * <p>{@code JavacTaskImpl#call()} and {@code #generate()} take no options -- they were set on the
   * task when it was created. An entry point that said it had an options index would send the
   * advice looking for an argument that is not there.
   */
  @Test
  void theTaskEntryPointsCarryNoOptions() {
    for (CompilerEntryPoints.EntryPoint entry :
        CompilerEntryPoints.forType("com.sun.tools.javac.api.JavacTaskImpl")) {
      assertThat(entry.kind()).isEqualTo(CompilerEntryPoints.EntryPoint.Kind.NO_OPTIONS);
      assertThat(entry.optionsIndex()).as("entry point %s", entry.describe()).isEqualTo(-1);
      assertThat(entry.sourcesIndex()).as("entry point %s", entry.describe()).isEqualTo(-1);
    }
  }

  @Test
  void typesAreDistinct() {
    List<String> types = CompilerEntryPoints.types();
    assertThat(types).doesNotHaveDuplicates();
  }

  /**
   * The hooked methods must exist on the hooked classes, with the arity the entry point claims.
   *
   * <p>This is the test that would have caught the {@code javax.tools.JavaCompiler#run} mistake.
   * That entry point looked plausible -- the reference's own hook list names it -- but on a modern
   * JDK {@code javax.tools.JavaCompiler} is an <em>interface</em> with only {@code getTask} and
   * {@code getStandardFileManager}, and no {@code run} at all. The hook never fired, so the capture
   * was silently empty for every caller that used the run-style API.
   *
   * <p>Checking against the real classes is the only way to catch that: reading the list and
   * checking it is internally consistent would have passed.
   *
   * <p>The JDK classes are skipped when they are absent -- ECJ and sjavac are not part of a JDK.
   */
  @Test
  void everyHookedMethodExistsWithTheClaimedArity() {
    for (CompilerEntryPoints.EntryPoint entry : CompilerEntryPoints.all()) {
      Class<?> type;
      try {
        type = Class.forName(entry.typeName());
      } catch (ClassNotFoundException e) {
        // Not part of this JDK: ECJ, sjavac. Nothing to check.
        continue;
      }
      assertThat(hasMethodWithArity(type, entry.methodName(), entry.argumentCount()))
          .as(
              "the entry point claims %s#%s takes %d argument(s), but that is not the signature "
                  + "on this JDK",
              entry.typeName(), entry.methodName(), entry.argumentCount())
          .isTrue();
    }
  }

  /**
   * The {@code run} method must be on a class that actually has one.
   *
   * <p>{@code javax.tools.JavaCompiler} does not, so an entry point naming it would be a hook that
   * never fires.
   */
  @Test
  void theRunHookIsOnAClassThatHasARunMethod() throws ClassNotFoundException {
    CompilerEntryPoints.EntryPoint run =
        CompilerEntryPoints.all().stream()
            .filter(e -> e.methodName().equals("run"))
            .filter(e -> e.typeName().contains("javac") || e.typeName().contains("sjavac"))
            .findFirst()
            .orElseThrow();
    Class<?> type = Class.forName(run.typeName());
    assertThat(hasMethodWithArity(type, "run", run.argumentCount()))
        .as("%s must really have a run method", run.typeName())
        .isTrue();
  }

  /**
   * Whether the class declares a method with that name and that number of parameters.
   *
   * <p><b>Declared methods, not just public ones.</b> ByteBuddy instruments methods whatever their
   * visibility, and two of the hooked methods are not public: {@code
   * JavaCompiler#parse(JavaFileObject, CharSequence)} is protected, and the compiler's own parse
   * step is the one that has to be hooked to see the sources the compiler really compiled. A check
   * that only looked at the public methods would have called that entry point wrong.
   *
   * <p>The superclasses are searched too, because a method may be inherited.
   */
  private static boolean hasMethodWithArity(Class<?> type, String name, int arity) {
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      for (var m : c.getDeclaredMethods()) {
        if (m.getName().equals(name) && m.getParameterCount() == arity) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * The module must instrument with {@code Advice}, not with a hand-written ASM visitor.
   *
   * <p>This is a source-level check, not a behavioural one, and deliberately so: the failure it
   * guards against is a plausible-looking refactor that reintroduces a raw {@code MethodVisitor}
   * because someone assumed {@code Advice} could not handle a static method.
   *
   * <p>It matches on <em>imports</em>, not on any mention: the module's own javadoc explains why
   * raw ASM is not used, and that explanation must not make the check fail.
   */
  @Test
  void theModuleDoesNotUseRawAsm() throws Exception {
    var sourceRoot = Path.of("src", "main", "java");
    var javaFiles = new java.util.ArrayList<java.nio.file.Path>();
    try (var stream = java.nio.file.Files.walk(sourceRoot)) {
      stream.filter(p -> p.toString().endsWith(".java")).forEach(javaFiles::add);
    }
    assertThat(javaFiles).as("the module should have sources").isNotEmpty();

    for (java.nio.file.Path file : javaFiles) {
      List<String> lines = java.nio.file.Files.readAllLines(file);
      for (String line : lines) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("import ")) {
          continue;
        }
        assertThat(trimmed)
            .as("%s must not import raw ASM", file.getFileName())
            .doesNotContain("net.bytebuddy.jar.asm");
        assertThat(trimmed)
            .as("%s must not import raw ASM", file.getFileName())
            .doesNotContain("org.objectweb.asm");
      }
    }
  }
}
