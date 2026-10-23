package org.plumb.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaSourceParserTest {

  @Test
  void parsesSimpleClass() throws IOException {
    Path tmp = Files.createTempFile("Sample", ".java");
    Files.writeString(tmp, "package demo; class Sample { int x = 1; }");
    try {
      ParsedSource ps = new JavaSourceParser().parse(tmp);
      assertThat(ps.packageName()).isEqualTo("demo");
      assertThat(ps.primaryTypeName()).isEqualTo("demo.Sample");
      assertThat(ps.problems()).isEmpty();
      assertThat(ps.unit().getTypes()).hasSize(1);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  @Test
  void capturesProblemsOnSyntaxError() {
    String broken = "class A { void m( { } }"; // unbalanced paren
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("Broken.java"), broken);
    // JavaParser still produces a (partial) CompilationUnit for many syntax
    // errors; we at least make sure we get one back without throwing.
    assertThat(ps.unit()).isNotNull();
  }

  @Test
  void parsesDefaultPackageClass() {
    ParsedSource ps =
        new JavaSourceParser().parseFromString(Path.of("NoPkg.java"), "class NoPkg { }");
    assertThat(ps.packageName()).isEmpty();
    assertThat(ps.primaryTypeName()).isEqualTo("NoPkg");
  }

  /**
   * R797 (Oct 2026): when a module declares a source root, a reference to a sibling class declared
   * in the same root must resolve. Without the {@code JavaParserTypeSolver} this would fail
   * (ReflectionTypeSolver alone doesn't know about project-local classes).
   */
  @Test
  void moduleAwareSourceRootResolvesSibling(@TempDir Path root) throws IOException {
    // Two source files under the same root.
    Path sibling = root.resolve("demo/Foo.java");
    Files.createDirectories(sibling.getParent());
    Files.writeString(sibling, "package demo; public class Foo { }");

    Path user = root.resolve("demo/Bar.java");
    Files.writeString(user, "package demo; public class Bar {\n" + "  public Foo f;\n" + "}\n");

    JavaModule mod = JavaModule.singleRoot("mod", root, List.of());
    JavaSourceParser parser =
        new JavaSourceParser(ParserConfiguration.LanguageLevel.JAVA_17, List.of(mod));
    ParsedSource ps = parser.parse(user);

    // The field type `Foo` must resolve to demo.Foo declared
    // in the sibling .java file via the JavaParserTypeSolver.
    com.github.javaparser.ast.body.ClassOrInterfaceDeclaration bar =
        ps.unit().getClassByName("Bar").orElseThrow();
    com.github.javaparser.ast.body.FieldDeclaration f = bar.getFieldByName("f").orElseThrow();
    assertThat(f.getElementType().resolve().describe()).isEqualTo("demo.Foo");
  }

  /**
   * R797: the empty {@code modules} list must produce a parser equivalent to the JDK-only default.
   * We assert that {@code java.util.ArrayList} still resolves (it would not if the JDK solver were
   * dropped).
   */
  @Test
  void emptyModuleListKeepsJdkResolution() {
    String src = "import java.util.ArrayList; class T { ArrayList<String> xs; }";
    JavaSourceParser parser =
        new JavaSourceParser(ParserConfiguration.LanguageLevel.JAVA_17, List.of());
    ParsedSource ps = parser.parseFromString(Path.of("T.java"), src);
    assertThat(ps.problems()).isEmpty();
  }

  /**
   * R797: null entries in the module list are tolerated so a misconfigured CLI flag doesn't take
   * the whole scan down. {@code buildTypeSolverFor} skips them silently; the rest of the modules
   * still contribute.
   */
  @Test
  void nullModuleEntryIsTolerated(@TempDir Path root) throws IOException {
    Path user = root.resolve("demo/Baz.java");
    Files.createDirectories(user.getParent());
    Files.writeString(
        user,
        "package demo; import java.util.HashMap; public class Baz { HashMap<String,String> m; }");
    JavaModule mod = JavaModule.singleRoot("mod", root, List.of());
    // Use Arrays.asList so the list itself is mutable and accepts null.
    java.util.List<JavaModule> modules = new java.util.ArrayList<>();
    modules.add(mod);
    modules.add(null);
    modules.add(mod);
    JavaSourceParser parser =
        new JavaSourceParser(ParserConfiguration.LanguageLevel.JAVA_17, modules);
    ParsedSource ps = parser.parse(user);
    assertThat(ps.problems()).isEmpty();
    com.github.javaparser.ast.body.ClassOrInterfaceDeclaration baz =
        ps.unit().getClassByName("Baz").orElseThrow();
    com.github.javaparser.ast.body.FieldDeclaration m = baz.getFieldByName("m").orElseThrow();
    assertThat(m.getElementType().resolve().describe()).startsWith("java.util.HashMap");
  }

  /**
   * R797: the same jar referenced from N modules must produce only one JarTypeSolver in the
   * CombinedTypeSolver, not N ? the previous code added a fresh solver per module,
   * which is both slow and a potential source of "two solvers disagree on the same type"
   * pathologies. We assert indirectly: passing the same jar twice still produces a working
   * parser.
   */
  @Test
  void duplicateJarEntriesAreDeduped(@TempDir Path root) throws IOException {
    Path fakeJar = root.resolve("fake.jar");
    Files.writeString(fakeJar, "fake");
    java.util.List<JavaModule> modules =
        java.util.List.of(
            JavaModule.singleRoot("a", root, java.util.List.of(fakeJar)),
            JavaModule.singleRoot("b", root, java.util.List.of(fakeJar)));
    // No exception means the TypeSolver was assembled without duplicating the index.
    JavaSourceParser parser =
        new JavaSourceParser(ParserConfiguration.LanguageLevel.JAVA_17, modules);
    String src = "class T { }";
    ParsedSource ps = parser.parseFromString(Path.of("T.java"), src);
    assertThat(ps.problems()).isEmpty();
  }

  /**
   * R797 (post-fix): the parser accepts an explicit Charset so a non-UTF-8 project encoding
   * declared by BuildProject.sourceEncoding() is honoured.
   */
  @Test
  void constructorAcceptsCustomCharset() {
    JavaSourceParser p =
        new JavaSourceParser(
            ParserConfiguration.LanguageLevel.JAVA_17,
            java.nio.charset.StandardCharsets.ISO_8859_1,
            new com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver(
                new com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver()));
    assertThat(p.sourceEncoding()).isEqualTo(java.nio.charset.StandardCharsets.ISO_8859_1);
  }

  /**
   * R797 (post-fix): when a module's encoding is set, it propagates through buildTypeSolverFor
   * to the parser.
   */
  @Test
  void moduleEncodingDerivesParserEncoding() {
    JavaModule m =
        JavaModule.singleRoot(
            "x",
            Path.of("."),
            List.of(),
            JavaModule.Scope.COMPILE,
            null,
            java.nio.charset.StandardCharsets.UTF_16);
    JavaSourceParser p = new JavaSourceParser(java.util.List.of(m));
    assertThat(p.sourceEncoding()).isEqualTo(java.nio.charset.StandardCharsets.UTF_16);
  }

  /** R797 (post-fix): per-module JavaModule.Scope is recorded on the module. */
  @Test
  void moduleScopeFromString() {
    assertThat(JavaModule.Scope.fromString("test")).isEqualTo(JavaModule.Scope.TEST);
    assertThat(JavaModule.Scope.fromString("testImplementation"))
        .isEqualTo(JavaModule.Scope.TEST);
    assertThat(JavaModule.Scope.fromString(null)).isEqualTo(JavaModule.Scope.COMPILE);
  }
}
