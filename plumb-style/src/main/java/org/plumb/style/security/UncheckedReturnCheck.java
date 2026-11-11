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
 * <p><b>Who decides whether an ARGUMENT is dropped.</b> The callee's BODY
 * decides, and the callee's name is only the fallback. When the resolver
 * can reach the callee's declaration -- a method of the analysed source --
 * the declaration says whether the parameter is read at all, and whether
 * every read merely forwards it to a call that ignores its arguments. So:
 *
 * <pre>
 *   private void log(boolean deleted) { System.out.println(deleted); }
 *       // forwards it and nothing else -> the value is dropped
 *   private void log(boolean deleted) { if (!deleted) throw new IllegalStateException(); }
 *       // ACTS on it -> the value is observed, and reporting it is a false positive
 * </pre>
 *
 * <p>The name lists cannot tell those two apart: both callees are called
 * {@code log}. The body can, so the body wins. The name lists are what is
 * left for a callee the resolver cannot reach -- a JDK method, a jar, a
 * receiver it cannot resolve -- and there the name is the only fact there
 * is.
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

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserMethodDeclaration;
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
   * <p>This is the FALLBACK for a callee the resolver cannot reach. When the callee IS the analysed
   * source, its body says what happens to the parameter, and the body wins -- a callee named {@code
   * log} that throws when the value is false acts on the value, and reporting that would be a false
   * positive.
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
      if (!isDiscarded(call, readLocals)) {
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
   *   <li>the call is an argument to a call that ignores it.
   * </ol>
   *
   * <p>Everything else observes the value: an {@code if} condition, a {@code return}, an assignment
   * that is read later, or an argument to a call that uses it.
   *
   * @param call the call whose value is in question
   * @param readLocals the names of the locals that are read somewhere in the method
   */
  private boolean isDiscarded(MethodCallExpr call, Set<String> readLocals) {
    // The value's holder. A wrapper does not change WHAT the value is, only
    // how it is spelled: `!f.delete()` and `(f.delete())` carry the same
    // value, and the same holder decides whether it is observed.
    Node value = call;
    Node parent = value.getParentNode().orElse(null);
    while (parent instanceof EnclosedExpr || parent instanceof UnaryExpr) {
      value = parent;
      parent = value.getParentNode().orElse(null);
    }

    // (1) The bare statement shape. The call's value is the statement's
    // value, and an expression statement drops it.
    if (parent instanceof ExpressionStmt) {
      return true;
    }

    // (2) The stored-but-never-read shape.
    String holder = receivingLocalName(parent);
    if (holder != null) {
      return !readLocals.contains(holder);
    }

    // (3) The argument shape. The value is discarded only when the
    // enclosing call ignores it.
    if (parent instanceof MethodCallExpr enclosing && enclosing.getArguments().contains(value)) {
      return enclosingIgnoresArgument(value, enclosing);
    }

    return false;
  }

  /**
   * The name of the LOCAL variable that receives the value, or {@code null}.
   *
   * <p>Two ways to receive it: a declaration ({@code boolean ok = f.delete();}) and a plain
   * assignment to a local ({@code ok = f.delete();}). A compound assignment ({@code ok += ...})
   * READS its target as well, so the value is used there and this returns {@code null}.
   *
   * <p>A name on the left of an assignment is a local only when it resolves to one. The same
   * spelling is a FIELD when the assignment writes a field, and the rule does not claim to know
   * whether a field is read afterwards.
   */
  private String receivingLocalName(Node parent) {
    if (parent instanceof VariableDeclarator declarator
        && declarator.getParentNode().orElse(null)
            instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr) {
      // A LOCAL declaration. A field's declarator hangs off a
      // FieldDeclaration, and a field is not a local: whether it is read
      // afterwards is a question about the whole program, not about this
      // method.
      return declarator.getNameAsString();
    }
    if (parent instanceof AssignExpr assign
        && assign.getOperator() == AssignExpr.Operator.ASSIGN
        && assign.getTarget() instanceof NameExpr target
        && isLocalVariable(target)) {
      return target.getNameAsString();
    }
    return null;
  }

  /**
   * Whether {@code name} is a local variable rather than a field.
   *
   * <p>Both are spelled the same way at an assignment's left-hand side, so the resolver is what
   * tells them apart.
   */
  private boolean isLocalVariable(NameExpr name) {
    try {
      ResolvedValueDeclaration decl = name.resolve();
      if (decl == null) {
        return false;
      }
      // A field is not a local. The same spelling at an assignment's
      // left-hand side is a field when it resolves to one, and whether a
      // field is read afterwards is a question about the whole program.
      return !(decl instanceof ResolvedFieldDeclaration);
    } catch (RuntimeException ex) {
      // Unresolvable: the fact is not available, and the rule does not
      // claim it. A field write is not a local store, and guessing
      // otherwise would report a value that is probably observed.
      return false;
    }
  }

  /**
   * Whether the enclosing call IGNORES the argument the value sits in.
   *
   * <p>The callee's body decides, and the callee's name is only the fallback.
   *
   * @param value the argument expression, possibly wrapped
   * @param enclosing the call the value is an argument of
   */
  private boolean enclosingIgnoresArgument(Node value, MethodCallExpr enclosing) {
    Boolean byBody = ignoresArgumentByBody(value, enclosing);
    if (byBody != null) {
      return byBody;
    }
    // The callee's declaration is out of reach -- a JDK method, a jar, an
    // unresolved receiver. The name is the only fact there is.
    String callee = enclosing.getNameAsString();
    if (IGNORES_ARGUMENTS.contains(callee)) {
      return true;
    }
    if (USES_ARGUMENTS.contains(callee)) {
      return false;
    }
    // An unknown callee: it may well use the argument. The value is NOT
    // discarded unless the enclosing call's own result is known not to
    // depend on it -- and a call whose result is unused cannot be shown to
    // ignore its arguments.
    return false;
  }

  /**
   * Whether the callee's BODY shows that it ignores the argument, or {@code null} when the body
   * cannot be reached.
   *
   * <p>{@code null} means "no fact": the callee is not the analysed source, or it has no body (an
   * abstract method, an interface method, a native method), or the resolver could not reach it. In
   * that case the caller falls back to the name lists.
   *
   * <p>A parameter that is never read is ignored. A parameter whose every read merely FORWARDS it
   * to a call that ignores its arguments is ignored too: a logger that prints the value and does
   * nothing else drops it, exactly as the name lists say.
   *
   * <p>A parameter that is read anywhere else is ACTED ON: the value reaches a condition, a return,
   * or a callee that may use it. The value is observed, and reporting it would be a false positive.
   */
  private Boolean ignoresArgumentByBody(Node value, MethodCallExpr enclosing) {
    int index = enclosing.getArguments().indexOf(value);
    if (index < 0) {
      return null;
    }
    MethodDeclaration callee = sourceBodyOf(enclosing);
    if (callee == null || callee.getBody().isEmpty()) {
      return null;
    }
    List<Parameter> parameters = callee.getParameters();
    if (index >= parameters.size()) {
      return null;
    }
    String parameter = parameters.get(index).getNameAsString();
    for (NameExpr read : callee.findAll(NameExpr.class)) {
      if (!read.getNameAsString().equals(parameter)) {
        continue;
      }
      if (isWrite(read)) {
        continue;
      }
      if (!isForwardedToIgnoringCall(read)) {
        // The value reaches a condition, a return, or a callee that may
        // use it: the callee ACTS on it.
        return Boolean.FALSE;
      }
    }
    return Boolean.TRUE;
  }

  /**
   * The callee's declaration, when it is a method of the analysed source.
   *
   * <p>The symbol solver hands back the AST node for a method it parsed from the project's own
   * source, and that node carries the body. A method from the JDK or from a jar has no AST, so this
   * returns {@code null} for it.
   */
  private MethodDeclaration sourceBodyOf(MethodCallExpr enclosing) {
    try {
      ResolvedMethodDeclaration decl = enclosing.resolve();
      if (decl instanceof JavaParserMethodDeclaration source) {
        return source.getWrappedNode();
      }
    } catch (RuntimeException ex) {
      // Unresolvable: the fact is not available.
    }
    return null;
  }

  /**
   * Whether the read is a WRITE rather than a use.
   *
   * <p>An assignment's target is written, not read, so a parameter that is only assigned is still a
   * parameter that is never used.
   */
  private boolean isWrite(NameExpr name) {
    Node parent = name.getParentNode().orElse(null);
    if (parent instanceof AssignExpr assign && assign.getTarget() == name) {
      // A compound assignment READS its target as well as writing it, so
      // only a plain assignment makes the name a pure write.
      return assign.getOperator() == AssignExpr.Operator.ASSIGN;
    }
    return false;
  }

  /** Whether the read merely FORWARDS the value to a call that ignores its arguments. */
  private boolean isForwardedToIgnoringCall(NameExpr read) {
    Node parent = read.getParentNode().orElse(null);
    if (!(parent instanceof MethodCallExpr enclosing)) {
      return false;
    }
    if (!enclosing.getArguments().contains(read)) {
      return false;
    }
    return IGNORES_ARGUMENTS.contains(enclosing.getNameAsString());
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
      // it is the target of a plain assignment, which is a write.
      if (isWrite(name)) {
        continue;
      }
      read.add(name.getNameAsString());
    }
    return read;
  }

  /**
   * Report the discarded return value.
   *
   * <p>The message names the CALLEE'S RETURN TYPE and its DECLARING TYPE, because those are the
   * facts the rule already resolved and the reader cannot see. The rule fires because the resolved
   * return type is not {@code void}, so "the value is discarded" means nothing without knowing what
   * the value is: {@code deleteIfExists()} returns a boolean and {@code commit()} returns void, and
   * only one of them can be "discarded".
   *
   * <p>The AI false-positive reducer reads the message. Without the return type it has to guess
   * what {@code add()} or {@code deleteIfExists()} returns -- and it guesses wrong often enough to
   * dismiss real findings as "the method is void" and to call a finding about a different method
   * "insufficient information about add()". The fact was already known; it was simply not reported.
   */
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
            + "success/failure and must be checked (CWE-252)"
            + returnTypeSuffix(call),
        RuleDef.Severity.MEDIUM,
        "unchecked-return: " + call.getNameAsString() + "()");
  }

  /**
   * The suffix that names the resolved return type and the declaring type.
   *
   * <p>Empty when the call cannot be resolved -- an unresolved call is reported without the suffix
   * rather than with a wrong one.
   */
  private String returnTypeSuffix(MethodCallExpr call) {
    try {
      ResolvedMethodDeclaration decl = call.resolve();
      ResolvedType returnType = decl.getReturnType();
      if (returnType == null) {
        return "";
      }
      String declaring = decl.declaringType().getQualifiedName();
      return " [returns " + returnType.describe() + " from " + declaring + "]";
    } catch (RuntimeException ex) {
      // Unresolvable: the suffix is omitted. A wrong suffix would be worse
      // than no suffix, because the reader would believe it.
      return "";
    }
  }
}
