package org.plumb.style.naming;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import java.util.List;
import java.util.regex.Pattern;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Package name should be all lowercase.
 *
 * <p>Disabled by default (R-834) ? 0 TP / 100% FP on Juliet R-832 baseline. M5 part-4 migration
 * (2026-10-17): per-node-type callback already; this checker was already in modern form. Kept
 * {@code visit(CompilationUnit)} because {@code PackageDeclaration} is visited once per file at the
 * top level (the dispatcher hands the {@code CompilationUnit} directly; we don't need {@code
 * findAll} here).
 */
public final class PackageCaseCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-package-case", "PACKAGE_CASE"),
          "package name should be all lowercase",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.PackageCaseCheck",
          false,
          List.of(
              "ast-naming-package-case",
              "package-case")); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832
  // baseline)

  private static final Pattern LOWERCASE =
      Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

  @Override
  public String name() {
    return "package-case";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    if (n.getPackageDeclaration().isEmpty()) return;
    PackageDeclaration pkg = n.getPackageDeclaration().get();
    String name = pkg.getNameAsString();
    if (LOWERCASE.matcher(name).matches()) return;
    int line = pkg.getBegin().map(p -> p.line).orElse(-1);
    int col = pkg.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Package name '" + name + "' should be all lowercase",
        RuleDef.Severity.LOW,
        name);
  }
}
