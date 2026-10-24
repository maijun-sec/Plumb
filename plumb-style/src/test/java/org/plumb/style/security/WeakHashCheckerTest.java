package org.plumb.style.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstDispatcher;
import org.plumb.style.StyleContext;

/** WeakHashChecker — literal weak-algo getInstance() should fire. */
class WeakHashCheckerTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private long run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new WeakHashChecker()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-security-weak-hash"))
        .count();
  }

  @Test
  void firesOnLiteralMd5() {
    String src =
        "import java.security.MessageDigest;\n"
            + "class N {\n"
            + "  void m() throws Exception {\n"
            + "    MessageDigest.getInstance(\"MD5\");\n"
            + "  }\n"
            + "}";
    assertEquals(1, run(src));
  }

  @Test
  void firesOnLiteralSha1() {
    String src =
        "import java.security.MessageDigest;\n"
            + "class N {\n"
            + "  void m() throws Exception {\n"
            + "    MessageDigest.getInstance(\"SHA-1\");\n"
            + "  }\n"
            + "}";
    assertEquals(1, run(src));
  }

  @Test
  void firesOnVariableInitializedToWeakAlgo() {
    String src =
        "import java.security.MessageDigest;\n"
            + "class N {\n"
            + "  void m() throws Exception {\n"
            + "    String algo = \"MD5\";\n"
            + "    MessageDigest.getInstance(algo);\n"
            + "  }\n"
            + "}";
    assertEquals(1, run(src));
  }

  @Test
  void doesNotFireOnStrongAlgo() {
    String src =
        "import java.security.MessageDigest;\n"
            + "class N {\n"
            + "  void m() throws Exception {\n"
            + "    MessageDigest.getInstance(\"SHA-256\");\n"
            + "  }\n"
            + "}";
    assertEquals(0, run(src));
  }

  @Test
  void doesNotFireOnEmpty() {
    assertEquals(0, run("class N {}"));
  }
}
