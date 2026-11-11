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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.performance.SynchronizationOnStringOrBoxedCheck;

/**
 * The rule has to survive a file that declares a field whose type is not a class type.
 *
 * <p><b>What went wrong.</b> The rule collects the names of the fields whose type is a forbidden
 * one. The type name comes from a helper that answers null for a type that is not a class type -- a
 * primitive, an array, a type variable -- and the set of forbidden names is an immutable {@code
 * Set.of(...)}, whose {@code contains} throws a NullPointerException when it is handed null. So
 * every file that declares a primitive field killed the rule, and the dispatcher caught the
 * exception and logged it: the file produced no findings for this rule at all, and the report did
 * not say which files were affected.
 *
 * <p>The rule is a security rule, so what was lost was the report of an unsafe lock.
 */
class SynchronizationOnStringOrBoxedCheckTest {

  private final JavaSourceParser parser = new JavaSourceParser();
  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<Finding> run(AstChecker check, String src) {
    ParsedSource ps = parser.parseFromString(Path.of("T.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    org.plumb.rules.RuleDef r = check.rule();
    if (r != null) ctx.forceEnable(r.ruleId().id());
    AstDispatcher d = new AstDispatcher();
    d.register(check);
    d.run(ctx);
    return ctx.findings();
  }

  @Test
  @DisplayName("a file that declares a primitive field does not kill the rule")
  void aPrimitiveFieldDoesNotKillTheRule() {
    List<Finding> findings =
        run(
            new SynchronizationOnStringOrBoxedCheck(),
            "class T { int count; void m() { synchronized (\"a lock\") { count++; } } }");

    assertThat(findings)
        .as("the rule has to report the string lock in a file that happens to declare an int")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a boxed field is still a forbidden lock")
  void aBoxedFieldIsStillAForbiddenLock() {
    List<Finding> findings =
        run(
            new SynchronizationOnStringOrBoxedCheck(),
            "class T { Integer lock; void m() { synchronized (lock) { } } }");

    assertThat(findings)
        .as(
            "the boxed primitives are in the forbidden set, and narrowing to reference types"
                + " must not narrow them away")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a private object lock is not reported")
  void aPrivateObjectLockIsNotReported() {
    List<Finding> findings =
        run(
            new SynchronizationOnStringOrBoxedCheck(),
            "class T { private final Object lock = new Object(); void m() { synchronized (lock) { }"
                + " } }");

    assertThat(findings)
        .as("the rule says to use a private Object lock, so it must not fire")
        .isEmpty();
  }
}
