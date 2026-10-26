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
 *       completely different paths, and hooking only one of them makes the capture silently
 *       useless for the other.
 *   <li>The module must not reach for raw ASM. ByteBuddy's {@code Advice} inlines the hook and
 *       needs no super method, so a hand-written {@code MethodVisitor} is not just unnecessary
 *       -- it is a chance to corrupt the compiler's stack.
 * </ol>
 */
class CompilerEntryPointsTest {

  @Test
  void coversTheCommandLineDriver() {
    assertThat(CompilerEntryPoints.forType("com.sun.tools.javac.Main"))
        .isNotEmpty();
    assertThat(CompilerEntryPoints.forType("com.sun.tools.javac.main.Main"))
        .isNotEmpty();
  }

  @Test
  void coversTheJavaxToolsApi() {
    // The path Gradle, Maven and the IDEs take.
    assertThat(CompilerEntryPoints.forType("com.sun.tools.javac.api.JavacTool"))
        .isNotEmpty();
  }

  @Test
  void coversTheLegacyJavaCompilerRunApi() {
    assertThat(CompilerEntryPoints.forType("javax.tools.JavaCompiler")).isNotEmpty();
  }

  @Test
  void coversEclipsesCompiler() {
    assertThat(CompilerEntryPoints.forType("org.eclipse.jdt.internal.compiler.batch.Main"))
        .isNotEmpty();
  }

  @Test
  void coversTheSjavacServer() {
    assertThat(CompilerEntryPoints.forType("com.sun.tools.sjavac.client.ClientMain"))
        .isNotEmpty();
    assertThat(CompilerEntryPoints.forType("com.sun.tools.sjavac.server.ServerMain"))
        .isNotEmpty();
  }

  @Test
  void everyEntryPointNamesAKnownCompilerClass() {
    for (CompilerEntryPoints.EntryPoint entry : CompilerEntryPoints.all()) {
      assertThat(entry.typeName()).as("entry point %s", entry.describe()).isNotBlank();
      assertThat(entry.methodName()).as("entry point %s", entry.describe()).isNotBlank();
      assertThat(entry.argumentCount()).as("entry point %s", entry.describe()).isPositive();
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
        CompilerEntryPoints.forType("com.sun.tools.javac.api.JavacTool").get(0);
    assertThat(api.kind()).isEqualTo(CompilerEntryPoints.EntryPoint.Kind.FILE_OBJECTS);
    assertThat(api.optionsIndex()).isBetween(0, api.argumentCount() - 1);
    assertThat(api.sourcesIndex()).isBetween(0, api.argumentCount() - 1);
    assertThat(api.optionsIndex()).isNotEqualTo(api.sourcesIndex());
  }

  @Test
  void typesAreDistinct() {
    List<String> types = CompilerEntryPoints.types();
    assertThat(types).doesNotHaveDuplicates();
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
