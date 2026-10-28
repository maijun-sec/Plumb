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
 * CWE-252: unchecked return value.
 *
 * <p>Detects method calls whose return value is DISCARDED when the method
 * indicates success or failure. The value is discarded in three shapes,
 * and all three are real defects:
 *
 * <pre>
 *   f.delete();                          // the statement drops the value
 *   boolean ok = f.delete();             // stored, and 'ok' is never read
 *   log(f.delete());                     // the outer call drops the value
 * </pre>
 *
 * <p>Before this rule, only the FIRST shape was detected, and it was
 * detected on the METHOD NAME alone. Both halves were wrong:
 *
 * <ul>
 *   <li>It fired on a {@code void} method. A {@code void close()} has no
 *       return value, so there is nothing to check -- and a custom {@code
 *       void get()} is a common name that said nothing about failure.
 *   <li>It missed the two shapes above that are MORE common in real code
 *       than the bare statement.
 * </ul>
 *
 * <p><b>How "discarded" is decided.</b> The question is whether the value
 * can influence any observable behaviour:
 *
 * <ul>
 *   <li>a bare expression statement drops the value;
 *   <li>a value stored in a local that is never read is dropped;
 *   <li>a value passed to a call that ignores it is dropped.
 * </ul>
 *
 * <p>A value that IS observed is not a finding: an {@code if} condition, a
 * {@code return}, an assignment that is later read, or an argument to a
 * call that uses it.
 *
 * <p><b>Which calls must be checked.</b> The method NAME is still the
 * primary signal, but the name alone is not enough to say the call HAS a
 * return value -- {@code void close()} does not. So the rule resolves the
 * call's return type and skips a call that returns {@code void}. When the
 * type cannot be resolved, the call is treated as having a return value:
 * an unresolved type is a missing fact, never a proof of safety.
 *
 * <p><b>Void methods are a DIFFERENT CWE.</b> A void method reports
 * failure by THROWING, not by returning a flag: {@code InputStream.close()},
 * {@code Connection.commit()}, {@code Writer.flush()} and
 * {@code Files.delete(Path)} are all void. Discarding their "return value"
 * is not possible, and failing to check for the exception they throw is
 * CWE-703 (improper check of exceptional conditions) -- a different rule
 * with a different mechanism. This rule therefore says nothing about them.
 */
package org.plumb.style.security;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** CWE-252: unchecked return value. */
public final class UncheckedReturnCheck implements AstChecker {

  /**
   * Method-name whitelist: calls whose return value indicates success or failure.
   *
   * <p>The name is the primary signal because a source-only scan usually cannot resolve the
   * receiver's type -- the third-party jar is not on the analysis classpath. The name alone is NOT
   * enough to say the call has a return value, though: the rule also resolves the return type and
   * skips a {@code void} call.
   */
  private static final Set<String> MUST_CHECK =
      Set.of(
          // File I/O
          "delete",
          "deleteIfExists",
          "mkdir",
          "mkdirs",
          "renameTo",
          "createNewFile",
          // SQL / IO close
          "close",
          "commit",
          "rollback",
          "execute",
          "executeUpdate",
          "executeQuery",
          "executeLargeUpdate",
          // Stream / Writer flush -- reports whether bytes were written
          "flush",
          // Map / List get -- null when key missing / OOB
          "get",
          // Iterator next -- throws NoSuchElementException
          "next",
          // Stream / Reader read -- bytes read / -1 EOF
          "read",
          "readLine",
          // Collection mutation -- returns whether the collection changed
          "add",
          "addAll",
          "remove",
          "removeAll",
          "removeIf",
          "retainAll",
          "set",
          "offer",
          "poll",
          "put");

  /**
   * Callee names whose ARGUMENTS are ignored.
   *
   * <p>Passing a return value to one of these drops it: the call prints or logs the value and acts
   * on neither the value nor its own return value. So {@code log(f.delete())} discards the
   * deletion's success flag just as surely as {@code f.delete();} does.
   *
   * <p>This is the inverse of the "argument position" case: {@code String.valueOf(f.delete())} USES
   * the value, because the string it returns depends on it. The rule therefore only treats an
   * argument as discarded when the enclosing call is one of these.
   */
  private static final Set<String> IGNORES_ARGUMENTS =
      Set.of(
          "println",
          "print",
          "printf",
          "log",
          "trace",
          "debug",
          "info",
          "warn",
          "error",
          "fatal",
          "addSuppressed");

  /**
   * Callee names that USE their arguments.
   *
   * <p>These are the counterparts of {@link #IGNORES_ARGUMENTS}: their return value depends on the
   * argument, so passing a must-check call's result to them is NOT a discard. A small explicit list
   * is enough -- the rule only needs to know the argument is observed, and the common cases are
   * conversion and comparison.
   */
  private static final Set<String> USES_ARGUMENTS =
      Set.of(
          "valueOf",
          "compare",
          "compareTo",
          "equals",
          "equalsIgnoreCase",
          "toString",
          "hashCode",
          "assertEquals",
          "requireNonNull",
          "checkState",
          "checkArgument",
          "verify",
          "assertTrue",
          "assertFalse");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-unchecked-return", "UNCHECKED_RETURN"),
          "Return value of a security-sensitive method (close / "
              + "delete / mkdir / commit / read / ...) is "
              + "discarded; the value indicates success/failure "
              + "and must be checked (CWE-252)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-252"),
          "org.plumb.style.security.UncheckedReturnCheck",
          true,
          List.of("dfa-security-unchecked-return", "unchecked-return"));

  @Override
  public String name() {
    return "unchecked-return";
  }

  @Override
  public void init(StyleContext ctx) {
    // stateless
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) {
      return;
    }
    BlockStmt body = n.getBody().get();

    // The locals that are READ somewhere in the method. A must-check call
    // whose result is stored in a local that is never read discards the
    // value just as surely as a bare statement does.
    Set<String> readLocals = readLocalNames(body);

    for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
      if (!isMustCheck(call)) {
        continue;
      }
      if (!hasReturnValue(call, ctx)) {
        // A void method has no return value, so there is nothing to
        // check. `void close()` is not an unchecked return.
        continue;
      }
      if (!isDiscarded(call, body, readLocals, ctx)) {
        continue;
      }
      report(call, ctx);
    }
  }

  /**
   * Whether {@code call}'s name is on the must-check list.
   *
   * <p>The name is matched exactly, so {@code deleteAll} does not match {@code delete}.
   */
  private boolean isMustCheck(MethodCallExpr call) {
    return MUST_CHECK.contains(call.getNameAsString());
  }

  /**
   * Whether {@code call} actually has a return value.
   *
   * <p>A {@code void} method has nothing to check, so a call to one is not a finding. When the
   * return type cannot be resolved -- the third-party jar is not on the analysis classpath, which
   * is the normal situation for a source-only scan -- the call is treated as HAVING a return value.
   * An unresolved type is a missing fact, never a proof of safety.
   *
   * @param call the call to inspect
   * @param ctx the style context, which owns the type solver
   */
  private boolean hasReturnValue(MethodCallExpr call, StyleContext ctx) {
    try {
      ResolvedMethodDeclaration decl = call.resolve();
      ResolvedType returnType = decl.getReturnType();
      return returnType != null && !returnType.isVoid();
    } catch (RuntimeException ex) {
      // Unresolvable: assume there IS a return value. The name-based
      // whitelist already says this call is worth looking at, and the
      // alternative -- assuming it is void -- would suppress a real
      // finding on the strength of a missing fact.
      return true;
    }
  }

  /**
   * Whether {@code call}'s value is discarded.
   *
   * <p>Three shapes, and the value is discarded in each:
   *
   * <ol>
   *   <li>the call is the whole expression of an expression statement;
   *   <li>the call's value is stored in a local that is never read;
   *   <li>the call is an argument to a call that ignores its arguments.
   * </ol>
   *
   * <p>Everything else observes the value: an {@code if} condition, a {@code return}, an assignment
   * that is read later, or an argument to a call that uses it.
   */
  private boolean isDiscarded(
      MethodCallExpr call, BlockStmt body, Set<String> readLocals, StyleContext ctx) {
    // (1) The bare statement shape. The call's value is the statement's
    // value, and an expression statement drops it.
    if (call.getParentNode().orElse(null) instanceof ExpressionStmt) {
      return true;
    }

    // (2) The assigned-but-never-read shape.
    VariableDeclarationExpr assigned = assignedVariable(call);
    if (assigned != null) {
      for (com.github.javaparser.ast.body.VariableDeclarator declarator : assigned.getVariables()) {
        if (!readLocals.contains(declarator.getNameAsString())) {
          return true;
        }
      }
      return false;
    }

    // (3) The argument shape. The value is discarded only when the
    // enclosing call ignores its arguments.
    MethodCallExpr enclosing = enclosingCall(call);
    if (enclosing != null) {
      String callee = enclosing.getNameAsString();
      if (IGNORES_ARGUMENTS.contains(callee)) {
        return true;
      }
      if (USES_ARGUMENTS.contains(callee)) {
        return false;
      }
      // An unknown callee: it may well use the argument. The value is
      // NOT discarded unless the enclosing call's own result is known
      // not to depend on it -- and a call whose result is unused cannot
      // be shown to ignore its arguments.
      return false;
    }

    return false;
  }

  /**
   * The variable declaration whose initializer is {@code call}, or {@code null}.
   *
   * <p>Only a DIRECT initializer counts. A call nested inside a larger initializer ({@code boolean
   * ok = !f.delete();}) is not a plain store, and the rule does not claim to understand it.
   */
  private VariableDeclarationExpr assignedVariable(MethodCallExpr call) {
    com.github.javaparser.ast.Node parent = call.getParentNode().orElse(null);
    if (parent instanceof VariableDeclarator) {
      com.github.javaparser.ast.Node grand = parent.getParentNode().orElse(null);
      if (grand instanceof VariableDeclarationExpr) {
        return (VariableDeclarationExpr) grand;
      }
    }
    return null;
  }

  /**
   * The call that {@code call} is an argument of, or {@code null}.
   *
   * <p>Only a DIRECT argument counts. A call nested deeper (inside a lambda, for instance) is not a
   * plain argument position, and the rule does not claim to understand it.
   */
  private MethodCallExpr enclosingCall(MethodCallExpr call) {
    com.github.javaparser.ast.Node parent = call.getParentNode().orElse(null);
    if (parent instanceof com.github.javaparser.ast.expr.MethodCallExpr) {
      MethodCallExpr enclosing = (MethodCallExpr) parent;
      // Only when the call really is one of the arguments -- a receiver
      // (`getStream().close()`) is not an argument.
      if (enclosing.getArguments().contains(call)) {
        return enclosing;
      }
    }
    return null;
  }

  /**
   * The names of the locals that are READ somewhere in {@code body}.
   *
   * <p>A local that is only ever written is not observed, so a must-check call stored in it
   * discards its value. A local that is read anywhere in the method IS observed -- and the check is
   * deliberately method-wide rather than flow-sensitive, because a value read on one path still
   * counts as observed on this analysis.
   */
  private Set<String> readLocalNames(BlockStmt body) {
    Set<String> read = new HashSet<>();
    for (NameExpr name : body.findAll(NameExpr.class)) {
      // A name in a DECLARATION is not a read. The declarator's own name
      // is not a NameExpr, so every NameExpr here is a use -- except when
      // it is the target of an assignment, which is a write.
      com.github.javaparser.ast.Node parent = name.getParentNode().orElse(null);
      if (parent instanceof com.github.javaparser.ast.expr.AssignExpr assign) {
        if (assign.getTarget() == name) {
          continue;
        }
      }
      read.add(name.getNameAsString());
    }
    return read;
  }

  /** Report the discarded return value. */
  private void report(MethodCallExpr call, StyleContext ctx) {
    int line = call.getBegin().map(p -> p.line).orElse(-1);
    int col = call.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Return value of "
            + call.getNameAsString()
            + "() is discarded; the value indicates "
            + "success/failure and must be checked (CWE-252)",
        RuleDef.Severity.MEDIUM,
        "unchecked-return: " + call.getNameAsString() + "()");
  }
}
