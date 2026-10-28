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
/* R790 unit test for UncaughtExceptionCheck fix. */
package org.plumb.style.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.StyleContext;

class R790UncaughtExceptionCheckTest {

  private static StyleContext ctxFor(String src) {
    JavaParser parser = new JavaParser();
    ParseResult<CompilationUnit> result = parser.parse(src);
    assertThat(result.isSuccessful()).as("parse %s", src).isTrue();
    ParsedSource ps =
        new ParsedSource(
            java.nio.file.Paths.get("Test.java"),
            "Test.java",
            result.getResult().get(),
            new ArrayList<>());
    return new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
  }

  private static List<Finding> run(String src) {
    StyleContext ctx = ctxFor(src);
    try {
      // R-816: use AstDispatcher so per-node visit overloads fire.
      new org.plumb.style.AstDispatcher()
          .register(UncaughtExceptionCheck.class.getDeclaredConstructor().newInstance())
          .run(ctx);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return ctx.findings();
  }

  @Test
  void inetAddressGetByNameFires() {
    // Static call site: VariableDeclarator above carries the
    // receiver name "InetAddress".
    String src =
        "import java.net.InetAddress;\n"
            + "class T {\n"
            + "  void bad() throws Exception {\n"
            + "    InetAddress addr = InetAddress.getByName(\"localhost\");\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(src);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).ruleId()).isEqualTo("ast-style-cwe600-uncaught-exception");
  }

  @Test
  void insideTryCatchDoesNotFire() {
    String src =
        "import java.net.InetAddress;\n"
            + "class T {\n"
            + "  void good() {\n"
            + "    try {\n"
            + "      InetAddress addr = InetAddress.getByName(\"localhost\");\n"
            + "    } catch (Exception e) {}\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(src);
    assertThat(findings).isEmpty();
  }

  @Test
  void classForNameFires() {
    String src =
        "class T {\n"
            + "  void bad() throws Exception {\n"
            + "    Class<?> c = Class.forName(\"java.lang.String\");\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(src);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).ruleId()).isEqualTo("ast-style-cwe600-uncaught-exception");
  }

  @Test
  void processBuilderStartFires() {
    String src =
        "class T {\n"
            + "  void bad() throws Exception {\n"
            + "    ProcessBuilder pb = new ProcessBuilder(\"cmd\");\n"
            + "    pb.start();\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(src);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).ruleId()).isEqualTo("ast-style-cwe600-uncaught-exception");
  }

  @Test
  void unrelatedGetByNameDoesNotFire() {
    // No "InetAddress" in the receiving declarator type — should NOT fire.
    String src =
        "import other.HostResolver;\n"
            + "class T {\n"
            + "  void ok() throws Exception {\n"
            + "    HostResolver h = HostResolver.getByName(\"localhost\");\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(src);
    assertThat(findings).isEmpty();
  }
}
