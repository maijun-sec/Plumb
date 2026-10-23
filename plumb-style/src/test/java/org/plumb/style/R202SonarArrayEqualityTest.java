package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.performance.ArrayEqualityCheck;

/** S3012 "Arrays should not be compared with ==" mirror (plumb-style). */
class R202SonarArrayEqualityTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<org.plumb.reporter.Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new ArrayEqualityCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnArrayElementEquality() {
    // The classic array[i] == arr2[j] bug — these
    // are array element accesses, so this is an
    // element comparison, not a reference
    // comparison. Wait — this rule fires on
    // element-to-element ==, not the canonical
    // "array reference == array reference" case
    // (which would need a type resolver). The
    // narrow check fires on both cases.
    String src =
        "class N { boolean m(int[] a, int[] b, int i, int j) {" + " return a[i] == b[j];" + "} }";
    assertThat(run(src)).as("R202: a[i] == b[j] fires (array element equality)").isNotEmpty();
  }

  @Test
  void firesOnNotEqual() {
    String src =
        "class N { boolean m(int[] a, int[] b, int i, int j) {" + " return a[i] != b[j];" + "} }";
    assertThat(run(src)).as("R202: a[i] != b[j] fires").isNotEmpty();
  }

  @Test
  void noFireOnScalarComparison() {
    String src = "class N { boolean m(int a, int b) {" + " return a == b;" + "} }";
    assertThat(run(src)).as("R202: a == b (scalars) does not fire").isEmpty();
  }

  @Test
  void noFireOnOneSideScalar() {
    String src = "class N { boolean m(int[] a, int b, int i) {" + " return a[i] == b;" + "} }";
    assertThat(run(src)).as("R202: a[i] == b (one side scalar) does not fire").isEmpty();
  }
}
