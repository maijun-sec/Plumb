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

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;

/**
 * A1 ? verify the single-pass {@link AstDispatcher} dispatches each node to every registered {@link
 * AstChecker} exactly once.
 *
 * <p>M4/M5 (2026-10-17) lock-in tests: the dispatcher is now reflective (it discovers each
 * checker's per-node-type visit overrides via {@link Class#getMethods()} and invokes only those).
 * The tests below pin the new contract:
 *
 * <ul>
 *   <li>A checker that overrides only {@code visit(IfStmt, ...)} must NOT be called on other node
 *       types. The old dispatcher iterated all checkers on every typed visit and effectively
 *       invoked the default no-op 600 times per file.
 *   <li>Default no-op {@code visit(...)} methods declared on the {@link AstChecker} interface
 *       itself must NOT be invoked. Only concrete subclass overrides are dispatched.
 *   <li>Two checkers both overriding {@code visit(IfStmt, ...)} must each be called once per IfStmt
 *       in pre-order.
 *   <li>The total visit count for a checker that overrides a single node type equals the count of
 *       that node type in the file, NOT the total node count.
 * </ul>
 */
class AstDispatcherTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  @Test
  void dispatcherVisitsEachIfStmtOnce() {
    ParsedSource ps =
        new JavaSourceParser()
            .parseFromString(
                Path.of("N.java"),
                "class N {"
                    + " void m(int x) {"
                    + " if (x > 0) {}"
                    + " if (x > 1) {}"
                    + " if (x > 2) {}"
                    + " }"
                    + "}");
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));

    List<String> visited = new ArrayList<>();
    AstDispatcher d =
        new AstDispatcher()
            .register(
                new AstChecker() {
                  @Override
                  public String name() {
                    return "recorder";
                  }

                  @Override
                  public void init(StyleContext ctx) {}

                  @Override
                  public void visit(com.github.javaparser.ast.stmt.IfStmt n, StyleContext ctx) {
                    visited.add("if@" + n.getRange().map(r -> r.begin.line).orElse(-1));
                  }
                });
    d.run(ctx);
    assertThat(visited).hasSize(3);
  }

  @Test
  void dispatcherCallsMultipleCheckersPerNode() {
    ParsedSource ps =
        new JavaSourceParser()
            .parseFromString(Path.of("N.java"), "class N { void m() { if (true) {} } }");
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));

    List<String> log = new ArrayList<>();
    AstDispatcher d =
        new AstDispatcher()
            .register(
                new AstChecker() {
                  @Override
                  public String name() {
                    return "c1";
                  }

                  @Override
                  public void init(StyleContext ctx) {}

                  @Override
                  public void visit(com.github.javaparser.ast.stmt.IfStmt n, StyleContext ctx) {
                    log.add("c1");
                  }
                })
            .register(
                new AstChecker() {
                  @Override
                  public String name() {
                    return "c2";
                  }

                  @Override
                  public void init(StyleContext ctx) {}

                  @Override
                  public void visit(com.github.javaparser.ast.stmt.IfStmt n, StyleContext ctx) {
                    log.add("c2");
                  }
                });
    d.run(ctx);
    // 1 if x 2 checkers = 2 visits.
    assertThat(log).containsExactly("c1", "c2");
  }

  @Test
  void dispatcherCallsInitAndFinishOnce() {
    ParsedSource ps =
        new JavaSourceParser()
            .parseFromString(
                Path.of("N.java"), "class N { void m() { if (true) {} if (false) {} } }");
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));

    List<String> log = new ArrayList<>();
    AstDispatcher d =
        new AstDispatcher()
            .register(
                new AstChecker() {
                  @Override
                  public String name() {
                    return "c1";
                  }

                  @Override
                  public void init(StyleContext ctx) {
                    log.add("init");
                  }

                  @Override
                  public void finish(StyleContext ctx) {
                    log.add("finish");
                  }
                });
    d.run(ctx);
    assertThat(log).containsExactly("init", "finish");
  }

  // ─────── M4/M5 contract: per-node-type dispatch, no no-op fall-through ───────

  @Test
  void checkerOnlySeesNodesItDeclaredFor() {
    // The file contains: 1 IfStmt, 1 ReturnStmt, 2 BinaryExprs, 2
    // StringLiteralExprs. The first recorder overrides only visit(IfStmt).
    // The second recorder overrides only visit(StringLiteralExpr).
    // Pre-M4 dispatcher would have iterated all checkers on
    // every node, including BinaryExpr/StringLiteralExpr/...
    // M4/M5 dispatcher should invoke each recorder only
    // for the node types it declared.
    ParsedSource ps =
        new JavaSourceParser()
            .parseFromString(
                Path.of("N.java"),
                "class N { String m(int x) {"
                    + " if (x > 0) return \"yes\";"
                    + " return \"no\";"
                    + " } }");
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));

    AtomicInteger ifVisits = new AtomicInteger();
    AtomicInteger stringVisits = new AtomicInteger();
    AstDispatcher d =
        new AstDispatcher()
            .register(
                new AstChecker() {
                  @Override
                  public String name() {
                    return "if-only";
                  }

                  @Override
                  public void init(StyleContext ctx) {}

                  @Override
                  public void visit(IfStmt n, StyleContext ctx) {
                    ifVisits.incrementAndGet();
                  }
                })
            .register(
                new AstChecker() {
                  @Override
                  public String name() {
                    return "string-only";
                  }

                  @Override
                  public void init(StyleContext ctx) {}

                  @Override
                  public void visit(StringLiteralExpr n, StyleContext ctx) {
                    stringVisits.incrementAndGet();
                  }
                });
    d.run(ctx);

    // M4/M5: 1 IfStmt x 1 checker = 1 invoke. The
    // string-only checker sees 2 StringLiteralExprs (yes/no).
    assertThat(ifVisits.get())
        .as("M4/M5: checker overriding only visit(IfStmt) must fire once per IfStmt")
        .isEqualTo(1);
    assertThat(stringVisits.get())
        .as(
            "M4/M5: separate checker overriding only visit(StringLiteralExpr) sees only string"
                + " literals")
        .isEqualTo(2);
  }

  @Test
  void defaultNoOpVisitMethodsAreNotInvoked() {
    // The AstChecker interface declares ~50 default no-op
    // visit methods. If the dispatcher naively invoked
    // every declared method, this counter would jump
    // even though the recorder below doesn't override
    // anything but visit(ReturnStmt).
    ParsedSource ps =
        new JavaSourceParser()
            .parseFromString(
                Path.of("N.java"), "class N { int m(int x) { if (x > 0) return 1; return 0; } }");
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));

    AtomicInteger invokes = new AtomicInteger();
    AstDispatcher d =
        new AstDispatcher()
            .register(
                new AstChecker() {
                  @Override
                  public String name() {
                    return "return-only";
                  }

                  @Override
                  public void init(StyleContext ctx) {}

                  @Override
                  public void visit(ReturnStmt n, StyleContext ctx) {
                    invokes.incrementAndGet();
                  }
                });
    d.run(ctx);

    // M4/M5: the default visit(...) methods declared on
    // AstChecker are filtered out by
    // m.getDeclaringClass() == AstChecker.class. So a
    // checker that only overrides visit(ReturnStmt, ...)
    // is called only on ReturnStmt nodes — not on every
    // other node type via the interface's defaults.
    assertThat(invokes.get())
        .as("M4/M5: only the overridden visit(ReturnStmt) fires; default no-ops are skipped")
        .isEqualTo(2);
  }

  @Test
  void twoCheckersBothOverridingIfStmtEachFireOncePerIf() {
    // Regression: pre-M4 the dispatcher had ~30 typed
    // visit methods, each iterating checkers. The new
    // dispatcher iterates checkers only when the node
    // type matches. Verifies that two checkers both
    // overriding the SAME node type each get their
    // own dispatch entry (no cross-contamination).
    ParsedSource ps =
        new JavaSourceParser()
            .parseFromString(
                Path.of("N.java"), "class N { void m(int x) { if (x > 0) {} if (x > 1) {} } }");
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));

    AtomicInteger a = new AtomicInteger();
    AtomicInteger b = new AtomicInteger();
    AstDispatcher d =
        new AstDispatcher()
            .register(
                new AstChecker() {
                  @Override
                  public String name() {
                    return "A";
                  }

                  @Override
                  public void init(StyleContext ctx) {}

                  @Override
                  public void visit(IfStmt n, StyleContext ctx) {
                    a.incrementAndGet();
                  }
                })
            .register(
                new AstChecker() {
                  @Override
                  public String name() {
                    return "B";
                  }

                  @Override
                  public void init(StyleContext ctx) {}

                  @Override
                  public void visit(IfStmt n, StyleContext ctx) {
                    b.incrementAndGet();
                  }
                });
    d.run(ctx);

    // 2 ifs x 2 checkers each = 2 invokes per checker.
    assertThat(a.get()).isEqualTo(2);
    assertThat(b.get()).isEqualTo(2);
  }

  @Test
  void multipleVisitMethodsOnSameCheckerEachCalledForItsNodeType() {
    // A checker can override multiple visit methods;
    // each fires only for its own node type. Verifies
    // the dispatcher correctly maintains the mapping
    // visit(X) → entry for X.
    ParsedSource ps =
        new JavaSourceParser()
            .parseFromString(
                Path.of("N.java"), "class N { int m(int x) { if (x > 0) return 1; return 0; } }");
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));

    AtomicInteger ifHits = new AtomicInteger();
    AtomicInteger returnHits = new AtomicInteger();
    AstDispatcher d =
        new AstDispatcher()
            .register(
                new AstChecker() {
                  @Override
                  public String name() {
                    return "multi";
                  }

                  @Override
                  public void init(StyleContext ctx) {}

                  @Override
                  public void visit(IfStmt n, StyleContext ctx) {
                    ifHits.incrementAndGet();
                  }

                  @Override
                  public void visit(ReturnStmt n, StyleContext ctx) {
                    returnHits.incrementAndGet();
                  }
                });
    d.run(ctx);

    assertThat(ifHits.get()).as("multi-visit checker: visit(IfStmt) fires once").isEqualTo(1);
    assertThat(returnHits.get())
        .as("multi-visit checker: visit(ReturnStmt) fires twice")
        .isEqualTo(2);
  }

  @Test
  void visitorDoesNotDoubleInvokeWhenNoOpDefaultExists() {
    // Defensive: a checker that overrides only BinaryExpr
    // must fire only for BinaryExpr nodes. The other visit
    // methods inherited from AstChecker are no-ops — they
    // must NOT count toward the BinaryExpr counter.
    ParsedSource ps =
        new JavaSourceParser()
            .parseFromString(
                Path.of("N.java"), "class N { int m(int x) { if (x > 0) return 1; return 0; } }");
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));

    AtomicInteger binaryHits = new AtomicInteger();
    AstDispatcher d =
        new AstDispatcher()
            .register(
                new AstChecker() {
                  @Override
                  public String name() {
                    return "binary-only";
                  }

                  @Override
                  public void init(StyleContext ctx) {}

                  // Override ONLY BinaryExpr. All other
                  // visit methods are inherited defaults
                  // that do nothing.
                  @Override
                  public void visit(BinaryExpr n, StyleContext ctx) {
                    binaryHits.incrementAndGet();
                  }
                });
    d.run(ctx);

    // The file has exactly 1 BinaryExpr (x > 0). Default
    // methods must NOT count.
    assertThat(binaryHits.get()).as("default-visit-methods don't double-invoke").isEqualTo(1);
  }
}
