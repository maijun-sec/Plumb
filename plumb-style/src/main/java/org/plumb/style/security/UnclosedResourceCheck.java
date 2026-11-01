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

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.TryStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * A resource is acquired and never released (CWE-772 / CWE-775).
 *
 * <p>The defect is a stream, socket, reader or file handle that is opened and then goes out of
 * scope without a {@code close()}. The two CWE ids name the same defect class: CWE-772 is "Missing
 * Release of Resource" and CWE-775 is "Missing Release of File Descriptor or Handle". A rule keeps
 * ONE id per defect class, so both numbers are carried on the same rule.
 *
 * <p><b>What counts as a resource.</b> An allocation whose type is a {@code Closeable}. The type is
 * read from the declared type when there is one, and from the constructor's type otherwise; the
 * simple name is matched against a set of known closeable shapes so that an unresolvable type does
 * not produce a guess.
 *
 * <p><b>What counts as released.</b> One of:
 *
 * <ul>
 *   <li>a {@code close()} call on the variable, anywhere in the enclosing method -- including
 *       inside a {@code finally} block, which is the idiomatic way to release it;
 *   <li>the variable being the resource of a try-with-resources statement, which releases it
 *       automatically;
 *   <li>the variable being passed to a method whose name starts with {@code close}, {@code
 *       release}, {@code dispose} or {@code shutdown}, which is how a caller hands ownership away.
 * </ul>
 *
 * <p>The rule reports at the allocation site: that is where the reader has to look.
 */
public final class UnclosedResourceCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-unclosed-resource", "UNCLOSED_RESOURCE"),
          "A resource is acquired and never closed (CWE-772)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-772", "CWE-775"),
          "org.plumb.style.security.UnclosedResourceCheck",
          true,
          List.of("ast-error-unclosed-resource", "unclosed-resource"));

  /**
   * Simple type names that are resources. The list is deliberately a list of NAMES, not of classes:
   * the analysed code may be compiled against a classpath the analyser cannot see, and a name still
   * identifies the shape.
   */
  private static final Set<String> RESOURCE_SIMPLE_NAMES =
      new HashSet<>(
          List.of(
              "InputStream",
              "OutputStream",
              "Reader",
              "Writer",
              "BufferedInputStream",
              "BufferedOutputStream",
              "BufferedReader",
              "BufferedWriter",
              "FileInputStream",
              "FileOutputStream",
              "FileReader",
              "FileWriter",
              "RandomAccessFile",
              "InputStreamReader",
              "OutputStreamWriter",
              "PrintWriter",
              "PrintStream",
              "Scanner",
              "Socket",
              "ServerSocket",
              "DatagramSocket",
              "ZipFile",
              "JarFile",
              "ZipInputStream",
              "GZIPInputStream",
              "Connection",
              "Statement",
              "PreparedStatement",
              "ResultSet"));

  /** Method-name prefixes that transfer ownership away from the caller. */
  private static final List<String> RELEASE_PREFIXES =
      List.of("close", "release", "dispose", "shutdown", "cancel", "destroy");

  @Override
  public String name() {
    return "unclosed-resource";
  }

  @Override
  public void init(StyleContext ctx) {
    // stateless
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // The unit of analysis is the method: everything allocated inside it
    // must be released inside it.
    analyseScope(n, ctx);
  }

  @Override
  public void visit(com.github.javaparser.ast.body.ConstructorDeclaration n, StyleContext ctx) {
    analyseScope(n, ctx);
  }

  private void analyseScope(Node scope, StyleContext ctx) {
    List<ObjectCreationExpr> allocations = scope.findAll(ObjectCreationExpr.class);

    for (ObjectCreationExpr alloc : allocations) {
      String simpleName = resourceSimpleName(alloc);
      if (simpleName == null) continue;

      // A temporary that is an ARGUMENT to another allocation is not this
      // rule's business: `new BufferedReader(new FileReader(f))` hands the
      // inner reader to the outer one, which owns it.
      if (isArgumentToAnotherAllocation(alloc)) continue;

      String varName = allocatedVariableName(scope, alloc);
      if (varName == null) continue; // an allocation nobody holds: not this rule

      // A try-with-resources releases automatically.
      if (isTryWithResource(scope, varName)) continue;

      if (isReleased(scope, varName)) continue;

      int line = alloc.getBegin().map(p -> p.line).orElse(-1);
      int col = alloc.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "'" + varName + "' is a " + simpleName + " that is never closed " + "(CWE-772)",
          RuleDef.Severity.MEDIUM,
          varName + ":" + simpleName);
    }
  }

  /** True when the allocation is an argument to another allocation. */
  private static boolean isArgumentToAnotherAllocation(ObjectCreationExpr alloc) {
    for (ObjectCreationExpr outer : alloc.findAll(ObjectCreationExpr.class)) {
      if (outer == alloc) continue;
      for (com.github.javaparser.ast.expr.Expression arg : outer.getArguments()) {
        if (arg == alloc) return true;
      }
    }
    return false;
  }

  /**
   * The simple name of the allocated type, when that type is a known resource shape. Returns null
   * when the type is not a resource we recognise.
   */
  private static String resourceSimpleName(ObjectCreationExpr alloc) {
    if (!alloc.getType().isClassOrInterfaceType()) return null;
    String typeName = alloc.getType().asClassOrInterfaceType().getNameAsString();
    // The bare name: no package prefix, no type arguments. JavaParser keeps
    // the arguments on the name, so `ArrayList<String>` has to become
    // `ArrayList` before it can be compared with a known shape.
    String simple = simpleTypeName(typeName);
    for (String candidate : RESOURCE_SIMPLE_NAMES) {
      if (candidate.equals(simple)) return simple;
    }
    return null;
  }

  /** The bare type name: no package prefix, no type arguments. */
  private static String simpleTypeName(String name) {
    String s = name;
    int lt = s.indexOf('<');
    if (lt >= 0) s = s.substring(0, lt);
    int dot = s.lastIndexOf('.');
    if (dot >= 0) s = s.substring(dot + 1);
    return s.trim();
  }

  /**
   * The name of the variable that holds the allocation, or null when the allocation is not assigned
   * to a variable we can track.
   *
   * <p>Two shapes assign a resource to a name. The first is a declaration with an initialiser:
   * {@code BufferedReader r = new BufferedReader(...)}. The second is an assignment to a variable
   * declared elsewhere: {@code readerInputStream = new InputStreamReader(...)}. The second is the
   * shape a resource that is declared null and filled later takes, and it is the one a
   * method-scoped rule has to recognise.
   */
  private static String allocatedVariableName(Node scope, ObjectCreationExpr alloc) {
    // Shape one: the initialiser of a variable declarator.
    for (VariableDeclarator vd : scope.findAll(VariableDeclarator.class)) {
      if (vd.getInitializer().isPresent() && vd.getInitializer().get() == alloc) {
        return vd.getNameAsString();
      }
    }
    // Shape two: the right-hand side of an assignment.
    for (AssignExpr assign : scope.findAll(AssignExpr.class)) {
      if (assign.getValue() != alloc) continue;
      if (assign.getTarget() instanceof NameExpr ne) {
        return ne.getNameAsString();
      }
      if (assign.getTarget() instanceof FieldAccessExpr fa) {
        return fa.getNameAsString();
      }
    }
    return null;
  }

  /**
   * True when the variable is a try-with-resources resource.
   *
   * <p>The match is by VARIABLE, not by node identity: the resource of a try-with-resources is
   * often a wrapper -- {@code try (BufferedReader r = new BufferedReader(new FileReader(f)))} --
   * and the allocation the rule looks at is the inner one.
   */
  private static boolean isTryWithResource(Node scope, String varName) {
    for (TryStmt tryStmt : scope.findAll(TryStmt.class)) {
      for (com.github.javaparser.ast.expr.Expression r : tryStmt.getResources()) {
        if (r instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr vde) {
          for (com.github.javaparser.ast.body.VariableDeclarator vd : vde.getVariables()) {
            if (varName.equals(vd.getNameAsString())) return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * True when the variable is closed, handed to a try-with-resources, or passed to a method that
   * takes ownership of it.
   */
  private static boolean isReleased(Node scope, String varName) {
    for (Node n : scope.findAll(Node.class)) {
      if (n instanceof com.github.javaparser.ast.expr.MethodCallExpr call) {
        String callee = call.getNameAsString().toLowerCase(Locale.ROOT);
        for (String prefix : RELEASE_PREFIXES) {
          if (callee.startsWith(prefix)) {
            // `something.close()` releases `something`; `close(x)` releases `x`.
            if (call.getScope().isPresent()) {
              String scopeText = call.getScope().get().toString();
              if (scopeText.equals(varName)) return true;
            }
            for (com.github.javaparser.ast.expr.Expression arg : call.getArguments()) {
              if (arg.toString().equals(varName)) return true;
            }
          }
        }
      }
    }
    return false;
  }
}
