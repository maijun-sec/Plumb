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
/*
 * R-812 Batch 3 ? hermetic test for
 * {@link CleartextSocketUsageCheck}.
 *
 * <p>Four cases:</p>
 * <ol>
 *   <li>{@code new Socket(...)} in a function whose
 *       local variable is named {@code password} fires
 *       CWE-319.</li>
 *   <li>{@code new DatagramSocket(...)} with a sensitive
 *       variable also fires.</li>
 *   <li>{@code new Socket(...)} with no sensitive nearby
 *       does NOT fire (heuristic suppresses the FP).</li>
 *   <li>{@code new SomeOtherClass(...)} does NOT fire.</li>
 * </ol>
 */
package org.plumb.style.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Tests for {@link CleartextSocketUsageCheck}. */
class CleartextSocketUsageCheckTest {
  private final AstChecker check = new CleartextSocketUsageCheck();
  private final ParserConfiguration cfg =
      new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  private final JavaParser parser = new JavaParser(cfg);

  private List<Finding> run(String src) {
    CompilationUnit cu = parser.parse(src).getResult().get();
    ParsedSource ps = new ParsedSource(Path.of("Test.java"), src, cu, List.<Problem>of());
    RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);
    StyleContext ctx = new StyleContext(ps, rules);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new org.plumb.style.AstDispatcher().register(check).run(ctx);
    return ctx.findings();
  }

  @Test
  void socketInFunctionWithPassword_fires() {
    List<Finding> f =
        run(
            "class T { void m() { "
                + "String password = \"hunter2\"; "
                + "java.net.Socket s = new java.net.Socket(\"host\", 80); "
                + "} }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).isEqualTo("CWE-319");
  }

  @Test
  void datagramSocketInFunctionWithToken_fires() {
    // Variable name is exactly `token` so the
    // \btoken\b word boundary matches.
    List<Finding> f =
        run(
            "class T { void m() { "
                + "String token = \"xyz\"; "
                + "java.net.DatagramSocket ds = new java.net.DatagramSocket(); "
                + "} }");
    assertThat(f).hasSize(1);
  }

  @Test
  void socketWithNoSensitiveVar_doesNotFire() {
    // No password / token / etc. nearby — the heuristic
    // suppresses this finding.
    List<Finding> f =
        run(
            "class T { void m() { "
                + "int count = 42; "
                + "java.net.Socket s = new java.net.Socket(\"host\", 80); "
                + "} }");
    assertThat(f).as("no sensitive nearby: heuristic suppresses").isEmpty();
  }

  @Test
  void unrelatedConstructor_doesNotFire() {
    // ObjectCreationExpr of StringBuilder is not a Socket.
    List<Finding> f =
        run(
            "class T { void m() { "
                + "String password = \"hunter2\"; "
                + "StringBuilder sb = new StringBuilder(); "
                + "} }");
    assertThat(f).as("only StringBuilder created; no cleartext socket").isEmpty();
  }
}
