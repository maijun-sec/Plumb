package org.plumb.style.performance;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * {@code StringBuffer} field in a {@link Serializable} class is not thread-safe ? use {@code
 * StringBuilder}.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(ClassOrInterfaceDeclaration.class)} before. The inner per-field
 * walk stays as a local loop because we need to compare against the enclosing class's
 * implements list.
 */
public final class StringBufferFieldInSerialClassCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-string-buffer-field-serial", "STRING_BUFFER_FIELD_SERIAL"),
          "StringBuffer is not safe in serializable class",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.StringBufferFieldInSerialClassCheck",
          true,
          List.of("ast-performance-string-buffer-field-serial", "string-buffer-field-serial")
      );

  @Override
  public String name() {
    return "string-buffer-field-serial";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    boolean serializable =
        c.getImplementedTypes().stream()
            .anyMatch(t -> "Serializable".equals(t.getNameAsString()));
    if (!serializable) return;
    for (FieldDeclaration f : c.getFields()) {
      if (!(f.getElementType() instanceof ClassOrInterfaceType ct)) continue;
      String name = ct.getNameAsString();
      if (!"StringBuffer".equals(name)) continue;
      int line = f.getBegin().map(p -> p.line).orElse(-1);
      int col = f.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "StringBuffer is not thread-safe; use StringBuilder in serial class",
          RuleDef.Severity.MEDIUM,
          name);
    }
  }
}