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
package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;

/** Smoke test the rules against a synthetic "BadCode.java" sample that exercises each rule. */
class R308SmokeTest {

  @Test
  void syntheticBadCodeTriggersAllRules() throws IOException {
    Path src = Path.of("src/test/resources/smoke/BadCode.java");
    String content = Files.readString(src);

    JavaSourceParser parser = new JavaSourceParser();
    ParsedSource ps = parser.parseFromString(src, content);
    RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);
    StyleContext ctx = new StyleContext(ps, rules);
    // R-834: force-enable every checker (smoke test only).
    for (var d0 : new AstCheckerRegistry().builtins().all()) {
      var rd = d0.rule();
      if (rd != null) ctx.forceEnable(rd.ruleId().id());
    }

    // Run all registered checkers via AstCheckerRegistry.
    AstDispatcher d = new AstDispatcher();
    new AstCheckerRegistry().builtins().all().forEach(d::register);
    d.run(ctx);

    Map<String, Long> byRule =
        ctx.findings().stream()
            .filter(f -> f.rule() != null)
            .collect(Collectors.groupingBy(f -> f.rule().ruleId().id(), Collectors.counting()));

    System.out.println("Findings by rule:");
    byRule.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByKey())
        .forEach(e -> System.out.println(" " + e.getKey() + " = " + e.getValue()));

    // The rules should all produce at least
    // one finding.
    assertTrue(
        byRule.containsKey("ast-style-magic-number"), "MagicNumberCheck should fire on `42`");
    assertTrue(
        byRule.containsKey("ast-bug-covariant-equals"),
        "CovariantEqualsCheck should fire on `equals(BadCode)`");
    assertTrue(
        byRule.containsKey("ast-style-unnecessary-return"),
        "UnnecessaryReturnCheck should fire on final return");
    assertTrue(
        byRule.containsKey("ast-performance-unnecessary-constructor"),
        "UnnecessaryConstructorCheck should fire on empty no-arg ctor");
    // SingletonClassCheck is verified by test
    // (BadCode here has public ctors so not all-private)
    assertTrue(
        byRule.containsKey("ast-design-loose-coupling"),
        "LooseCouplingCheck should fire on HashMap field");
    assertTrue(
        byRule.containsKey("ast-style-avoid-star-import"),
        "AvoidStarImportCheck should fire on `java.util.*`");
    assertTrue(
        byRule.containsKey("ast-style-system-out-println"),
        "SystemOutPrintlnCheck should fire on System.out.println");
    assertTrue(
        byRule.containsKey("ast-style-print-stack-trace"),
        "PrintStackTraceCheck should fire on e.printStackTrace()");
    assertTrue(
        byRule.containsKey("ast-style-iterator-idiomatic"),
        "IteratorIdiomaticCheck should fire on index-counter loop");
    // : new rules
    assertTrue(
        byRule.containsKey("ast-style-non-static-initializer"),
        "NonStaticInitializerCheck should fire on init block");
    assertTrue(
        byRule.containsKey("ast-bug-incorrect-return-check"),
        "IncorrectReturnValueCheckChecker should fire on b == true");
  }
}
