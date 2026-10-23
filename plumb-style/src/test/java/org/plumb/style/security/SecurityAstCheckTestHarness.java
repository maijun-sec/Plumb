/*
 * Shared test helper for security-style AstCheckers.
 * Builds a parsed JavaParser CompilationUnit, drives
 * the check via {@link AstDispatcher}, and returns the
 * resulting findings.
 *
 * <p>Each security check's test instantiates the
 * harness with the AstChecker under test, and the
 * helper registers the checker with the dispatcher
 * and runs it. The dispatcher walks the unit once and
 * invokes the checker's per-type {@code visit(...)}
 * overloads based on the AST node types in the snippet.
 * This keeps individual tests small.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import java.nio.file.Path;
import java.util.List;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.AstDispatcher;
import org.plumb.style.StyleContext;

final class SecurityAstCheckTestHarness {

  private final AstChecker check;
  private final ParserConfiguration cfg =
      new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  private final JavaParser parser = new JavaParser(cfg);

  SecurityAstCheckTestHarness(AstChecker check) {
    this.check = check;
  }

  List<Finding> run(String src) {
    CompilationUnit cu = parser.parse(src).getResult().get();
    ParsedSource ps = new ParsedSource(Path.of("Test.java"), src, cu, List.<Problem>of());
    RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);
    StyleContext ctx = new StyleContext(ps, rules);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(check).run(ctx);
    return ctx.findings();
  }
}
