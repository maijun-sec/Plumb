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
 * C1 / -C1: AST-style CWE-789
 * (Uncontrolled Memory Allocation) checker.
 *
 * <p>Fires on three patterns:
 * <ol>
 *   <li>A {@code while(true)} loop whose body
 *       contains a large array allocation
 *       ({@code new byte[N]} etc. with
 *       {@code N >= 65536}).</li>
 *   <li>A large array allocation anywhere
 *       ({@code new TYPE[Integer.MAX_VALUE]}).</li>
 *   <li>A {@code new ArrayList(N)} where {@code N}
 *       is a large literal (e.g. {@code new ArrayList
 *       (Integer.MAX_VALUE)}) or a known-large
 *       local var.</li>
 * </ol>
 *
 * <p>Generic, no Juliet-specific patterns.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class UnboundedMemAllocCheck implements AstChecker {

  private static final RuleDef RULE =
      new RuleDef(
          new RuleId("ast-security-unbounded-mem-alloc", "UNBOUNDED_MEM_ALLOC"),
          "Unbounded memory allocation (CWE-789)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.SECURITY,
          List.of("CWE-789"),
          "org.plumb.style.security.UnboundedMemAllocCheck",
          true,
          List.of("ast-security-unbounded-mem-alloc", "unbounded-mem-alloc"));

  /** Minimum allocation size to flag (64 KB). */
  private static final long MIN_ALLOC_SIZE = 65536L;

  /** Below this size we don't track (small buffers etc). */
  private static final long MIN_ALLOC_SIZE_STRICT = 1024L;

  private final Set<String> reported = new HashSet<>();
  // local / field var name -> int/long value (if initialized to a large literal)
  private final Map<String, Long> constantInts = new HashMap<>();

  @Override
  public String name() {
    return "unbounded-mem-alloc";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
    constantInts.clear();
    // Walk all variable declarations (fields + locals)
    // AND `data = Integer.MAX_VALUE;` assignment
    // statements to capture the constant.
    if (ctx.source() != null && ctx.source().unit() != null) {
      var root = ctx.source().unit();
      for (FieldDeclaration fd : root.findAll(FieldDeclaration.class)) {
        collectConstantInts(fd);
      }
      for (VariableDeclarator vd : root.findAll(VariableDeclarator.class)) {
        String typeName = vd.getTypeAsString();
        String name = vd.getNameAsString();
        if (vd.getInitializer().isPresent()
            && ("int".equals(typeName) || "long".equals(typeName))) {
          if (constantInts.containsKey(name)) continue;
          long v = parseLiteralLong(vd.getInitializer().get());
          if (v >= MIN_ALLOC_SIZE_STRICT) {
            constantInts.put(name, v);
          }
        }
      }
      // (c) Statement-form assignments:
      //     `data = Integer.MAX_VALUE;` (where
      //     `data` is a local var declared
      //     earlier without init). We don't track
      //     reassignments, only the first assign.
      for (com.github.javaparser.ast.stmt.ExpressionStmt es :
          root.findAll(com.github.javaparser.ast.stmt.ExpressionStmt.class)) {
        if (es.getExpression() instanceof com.github.javaparser.ast.expr.AssignExpr ae
            && ae.getOperator() == com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN) {
          var tgt = ae.getTarget();
          var val = ae.getValue();
          if (tgt instanceof NameExpr ne && !constantInts.containsKey(ne.getNameAsString())) {
            long v = parseLiteralLong(val);
            if (v >= MIN_ALLOC_SIZE_STRICT) {
              constantInts.put(ne.getNameAsString(), v);
            }
          }
        }
      }
    }
  }

  private void collectConstantInts(FieldDeclaration fd) {
    for (VariableDeclarator vd : fd.getVariables()) {
      String typeName = vd.getTypeAsString();
      String name = vd.getNameAsString();
      if (vd.getInitializer().isPresent() && ("int".equals(typeName) || "long".equals(typeName))) {
        long v = parseLiteralLong(vd.getInitializer().get());
        if (v >= MIN_ALLOC_SIZE_STRICT) {
          constantInts.put(name, v);
        }
      }
    }
  }

  private static long parseLiteralLong(Node expr) {
    if (expr instanceof IntegerLiteralExpr ili) {
      try {
        return Long.parseLong(ili.getValue());
      } catch (NumberFormatException ex) {
        return -1;
      }
    }
    if (expr instanceof LongLiteralExpr lli) {
      try {
        return Long.parseLong(lli.getValue());
      } catch (NumberFormatException ex) {
        return -1;
      }
    }
    // Integer.MAX_VALUE / Integer.MIN_VALUE / Long.MAX_VALUE / Long.MIN_VALUE
    if (expr instanceof NameExpr ne) {
      String s = ne.getNameAsString();
      return switch (s) {
        case "Integer.MAX_VALUE" -> Integer.MAX_VALUE;
        case "Integer.MIN_VALUE" -> Integer.MIN_VALUE;
        case "Long.MAX_VALUE" -> Long.MAX_VALUE;
        case "Long.MIN_VALUE" -> Long.MIN_VALUE;
        default -> -1;
      };
    }
    // Integer.MAX_VALUE may be parsed as FieldAccessExpr
    // (Integer.MAX_VALUE = Integer . MAX_VALUE).
    if (expr instanceof com.github.javaparser.ast.expr.FieldAccessExpr fae) {
      String scope = fae.getScope().toString();
      String name = fae.getNameAsString();
      String full = scope + "." + name;
      return switch (full) {
        case "Integer.MAX_VALUE" -> Integer.MAX_VALUE;
        case "Integer.MIN_VALUE" -> Integer.MIN_VALUE;
        case "Long.MAX_VALUE" -> Long.MAX_VALUE;
        case "Long.MIN_VALUE" -> Long.MIN_VALUE;
        default -> -1;
      };
    }
    return -1;
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    // Only `while (true) { ... }`. Any other condition
    // (variable, method call, comparison) is treated as
    // possibly bounded.
    if (!(n.getCondition() instanceof BooleanLiteralExpr ble)) return;
    if (!ble.getValue()) return;
    var body = n.getBody();
    // Walk the body for any large array allocation.
    for (ArrayCreationExpr child : body.findAll(ArrayCreationExpr.class)) {
      long size = arraySize(child);
      if (size >= MIN_ALLOC_SIZE) {
        fire(
            n.getBegin().map(p -> p.line).orElse(-1),
            n.getBegin().map(p -> p.column).orElse(-1),
            ctx,
            "while(true) loop allocates " + size + " bytes/elements per iteration",
            "while(true) with new TYPE[" + size + "]");
        return;
      }
    }
  }

  @Override
  public void visit(ArrayCreationExpr n, StyleContext ctx) {
    // Fire on top-level large array allocations too,
    // not just inside while(true). `new TYPE[N]`
    // with N large is a memory-anti-pattern even
    // outside a loop (the bad() method may run
    // once per request).
    long size = arraySize(n);
    if (size >= MIN_ALLOC_SIZE) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      fire(
          line,
          col,
          ctx,
          "array allocation of " + size + " bytes/elements",
          "new TYPE[" + size + "]");
    }
  }

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    String typeName = n.getType().getNameAsString();
    if (!("ArrayList".equals(typeName)
        || "LinkedList".equals(typeName)
        || "Vector".equals(typeName)
        || "HashMap".equals(typeName)
        || "HashSet".equals(typeName)
        || "TreeMap".equals(typeName)
        || "TreeSet".equals(typeName)
        || "StringBuilder".equals(typeName)
        || "StringBuffer".equals(typeName))) {
      return;
    }
    // First arg is the initial capacity / size
    if (n.getArguments().isEmpty()) return;
    var firstArg = n.getArgument(0);
    long size = -1;
    if (firstArg instanceof IntegerLiteralExpr ili) {
      try {
        size = Long.parseLong(ili.getValue());
      } catch (NumberFormatException ex) {
        /* ignore */
      }
    } else if (firstArg instanceof LongLiteralExpr lli) {
      try {
        size = Long.parseLong(lli.getValue());
      } catch (NumberFormatException ex) {
        /* ignore */
      }
    } else if (firstArg instanceof NameExpr ne) {
      String s = ne.getNameAsString();
      size =
          switch (s) {
            case "Integer.MAX_VALUE" -> Integer.MAX_VALUE;
            case "Integer.MIN_VALUE" -> Integer.MIN_VALUE;
            case "Long.MAX_VALUE" -> Long.MAX_VALUE;
            case "Long.MIN_VALUE" -> Long.MIN_VALUE;
            default -> constantInts.getOrDefault(s, -1L);
          };
    }
    if (size >= MIN_ALLOC_SIZE) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      fire(
          line,
          col,
          ctx,
          typeName + " allocated with initial capacity " + size,
          "new " + typeName + "(" + size + ")");
    }
  }

  private void fire(int line, int col, StyleContext ctx, String detail, String display) {
    String key = line + "|" + col + "|unbounded";
    if (reported.add(key)) {
      ctx.report(
          line,
          col,
          RULE,
          "Unbounded memory allocation: "
              + detail
              + " (CWE-789) \u2014 verify the size is bounded by a sensible limit",
          RuleDef.Severity.HIGH,
          display);
    }
  }

  /**
   * Returns the size of an array allocation expression if it's a literal integer / long, otherwise
   * -1.
   */
  private static long arraySize(ArrayCreationExpr n) {
    // `new byte[1024]` : single-dim with size
    if (n.getLevels().size() != 1) return -1;
    var dim = n.getLevels().get(0);
    var sizeExpr = dim.getDimension().orElse(null);
    if (sizeExpr == null) return -1;
    return parseLiteralLong(sizeExpr);
  }
}
