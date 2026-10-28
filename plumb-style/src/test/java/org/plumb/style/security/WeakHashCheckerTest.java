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
