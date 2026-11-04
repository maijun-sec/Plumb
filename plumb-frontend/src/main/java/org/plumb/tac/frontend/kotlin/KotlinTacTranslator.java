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
package org.plumb.tac.frontend.kotlin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.kotlin.analysis.api.KaSession;
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertyGetterSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin;
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol;
import org.jetbrains.kotlin.analysis.api.types.KaType;
import org.jetbrains.kotlin.psi.KtBinaryExpression;
import org.jetbrains.kotlin.psi.KtBlockExpression;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtConstantExpression;
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtIfExpression;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.kotlin.psi.KtPrefixExpression;
import org.jetbrains.kotlin.psi.KtProperty;
import org.jetbrains.kotlin.psi.KtReturnExpression;
import org.jetbrains.kotlin.psi.KtSimpleNameExpression;
import org.jetbrains.kotlin.psi.KtStringTemplateEntry;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;
import org.jetbrains.kotlin.psi.KtValueArgument;
import org.jetbrains.kotlin.psi.KtWhenCondition;
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression;
import org.jetbrains.kotlin.psi.KtWhenEntry;
import org.jetbrains.kotlin.psi.KtWhenExpression;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;
import org.plumb.tac.translate.TacBuilder;

/**
 * Lowers one Kotlin function body to TAC.
 *
 * <p><b>Why PSI and not the Analysis API for the body.</b> The Analysis API gives types and
 * symbols; it does not give a structured, walkable statement tree that a three-address
 * lowering can consume. The Kotlin PSI ({@code KtBlockExpression}, {@code KtIfExpression},
 * {@code KtCallExpression}, ...) is exactly that tree, and it is what the Analysis API's
 * own {@code getPsi()} hands back. So the hybrid is: PSI for the <i>shape</i> of the body,
 * the Analysis API for the <i>meaning</i> of the types.
 *
 * <p><b>What this translator lowers.</b> The subset that the dataflow engine can act on:
 * assignments, calls, returns, branches, string templates, property reads and writes, and
 * the standard library calls that appear in security-relevant Kotlin (the
 * {@code requireNotNull} family, the string builders, the collection factories).
 *
 * <p><b>What it does not lower.</b> Coroutines, lambdas with captured state, inline classes
 * and the compiler's own lowering of {@code when} into a {@code tableswitch}. A construct
 * that is not lowered is reported as a problem rather than silently dropped -- see {@link
 * KotlinTacTranslator#degraded()}.
 *
 * <p><b>No new opcodes.</b> Everything here lowers into the existing instruction kinds:
 * {@code MOVE}, {@code BINOP}, {@code CALL}, {@code FIELDLOAD}, {@code FIELDSTORE},
 * {@code BRANCH}, {@code RETURN}, {@code THROW}, {@code NULL_CHECK}. A Kotlin {@code when}
 * becomes a branch chain; a Kotlin {@code if} becomes a branch; that is structure, not
 * information loss.
 */
final class KotlinTacTranslator {

  /** The Analysis API session the current file is analysed in. */
  private final KaSession session;

  /** Maps Kotlin types onto TAC types. */
  private final KotlinTypeMapper types;

  /** The builder the current function is being lowered into. */
  private TacBuilder builder;

  /** The name of the function currently being lowered; used for problem messages. */
  private String currentFunction = "<unknown>";

  /**
   * Local variables by source name.
   *
   * <p>Keyed by the Kotlin source name, not by a {@link TacOperand.TacVar}: the TAC's
   * variable identity is name + temp flag, and a Kotlin local's name <i>is</i> its TAC
   * name. The value carries the resolved {@link TacType} so a later refinement can be
   * applied without changing the identity.
   */
  private final Map<String, TacOperand.TacVar> locals = new HashMap<>();

  /** The problems found while lowering, in the order they were found. */
  private final List<String> problems = new ArrayList<>();

  /**
   * The SAME problems, in the IR's structured form.
   *
   * <p>Why both: the string form is what the SPI's plain {@code parse} returns and what a log line
   * wants; the structured form is what travels through the IR, so a source-less consumer can see
   * WHICH construct failed, in WHICH function, at WHICH line -- and can tell an unsupported
   * construct from a broken source.
   */
  private final List<org.plumb.tac.ir.TacProgram.FunctionProblem> structuredProblems =
      new ArrayList<>();

  /**
   * Whether the function being lowered used a construct the translator could not lower.
   *
   * <p>When true the function's precision is downgraded to {@link
   * org.plumb.tac.ir.IrPrecision#SOURCE_LOWERED}, so a checker knows not to trust a
   * precision-dependent fact about it.
   */
  private boolean degraded;

  KotlinTacTranslator(KaSession session) {
    this.session = session;
    this.types = new KotlinTypeMapper(session);
  }

  /** The problems found while lowering. */
  List<String> problems() {
    return List.copyOf(problems);
  }

  /**
   * The problems found while lowering, in the IR's structured form.
   *
   * <p>These travel with the IR, so a consumer that has only the IR can still see exactly which
   * construct the frontend could not lower, and where.
   */
  List<org.plumb.tac.ir.TacProgram.FunctionProblem> structuredProblems() {
    return List.copyOf(structuredProblems);
  }

  /**
   * Record a problem, in both the plain and the structured form.
   *
   * @param kind the problem kind, e.g. {@code UNHANDLED_NODE}
   * @param functionName the function the problem is in
   * @param nodeClass the construct that could not be lowered
   * @param message the human-readable description
   * @param line the source line, or -1
   * @param column the source column, or -1
   */
  private void report(
      String kind,
      String functionName,
      String nodeClass,
      String message,
      int line,
      int column) {
    problems.add(message);
    structuredProblems.add(
        new org.plumb.tac.ir.TacProgram.FunctionProblem(
            functionName, kind, message, nodeClass, line, column));
  }

  /** Whether a construct could not be lowered. */
  boolean degraded() {
    return degraded;
  }

  /** The type mapper, exposed so the frontend can reuse it for symbol-level mapping. */
  KotlinTypeMapper types() {
    return types;
  }

  /**
   * Lowers a function.
   *
   * @param name the qualified function name (e.g. {@code "com.example.Greeter.greet"})
   * @param signature the canonical signature (e.g. {@code "greet(String):String"})
   * @param returnType the function's declared return type
   * @param params the function's parameters, in declaration order
   * @param body the function's body expression; {@code null} for an abstract or native function
   * @param enclosingClass the short name of the declaring class, or {@code null}
   * @param synthetic whether the compiler generated this function
   * @return the lowered function
   */
  TacFunction translate(
      String name,
      String signature,
      TacType returnType,
      List<TacOperand.TacVar> params,
      KtExpression body,
      String enclosingClass,
      boolean synthetic) {
    return translate(name, signature, returnType, params, body, enclosingClass, synthetic, null);
  }

  /**
   * Translate one function, recording the MODULE it belongs to.
   *
   * <p>The module is what lets a single-module analysis report this function's findings and only
   * those, while the other modules are still read so the call graph keeps crossing the boundary.
   *
   * @param module the module the file came from, or {@code null} when there is no module boundary
   */
  TacFunction translate(
      String name,
      String signature,
      TacType returnType,
      List<TacOperand.TacVar> params,
      KtExpression body,
      String enclosingClass,
      boolean synthetic,
      String module) {

    this.currentFunction = name;
    this.locals.clear();
    this.problems.clear();
    this.structuredProblems.clear();
    this.degraded = false;

    TacBuilder.FunctionSpec spec =
        new TacBuilder.FunctionSpec(name, signature, returnType);
    TacBuilder b = new TacBuilder(spec, params);
    b.setModule(module);
    b.setSourceLanguage(SourceLanguage.KOTLIN);
    b.setIrPrecision(org.plumb.tac.ir.IrPrecision.SOURCE_FULL);
    if (synthetic) {
      b.markSynthetic();
      // Generated code gets its own origin, so the engine can give it a different summary
      // policy from hand-written code. It is still analysed by default: a generated accessor
      // carries the field's taint, and skipping it would drop that taint at every access.
      b.markGenerated(true);
    }
    if (enclosingClass != null) {
      setEnclosingClass(b, enclosingClass);
    }
    this.builder = b;

    // Register the parameters as locals so a body reference to them resolves to the
    // parameter's TAC variable rather than to a fresh temp.
    for (TacOperand.TacVar p : params) {
      this.locals.put(p.name(), p);
      b.addLocal(p);
    }

    if (body == null) {
      // An abstract function, or a function whose body the compiler generated. There is
      // nothing to lower.
      b.setIrPrecision(org.plumb.tac.ir.IrPrecision.SOURCE_LOWERED);
      this.degraded = true;
      return b.build();
    }

    try {
      if (body instanceof KtBlockExpression block) {
        translateBlock(block);
      } else {
        // An EXPRESSION BODY: `fun run(x: Int): Int = x * 2`.
        //
        // WHY THIS MATTERS. Kotlin allows a function body to be a single
        // expression. The frontend used to pass only the block body, which is
        // NULL for an expression body -- so the function was lowered as EMPTY.
        // Every call to such a function was therefore a call to a function with
        // no body: the data flow through it was cut, and a tainted value that
        // passed through it lost its taint with no trace. That is a SILENT
        // false negative, and expression-bodied functions are common enough in
        // Kotlin that it was not a corner case.
        //
        // The expression's value is the function's return value, so it is
        // lowered and returned.
        TacOperand value = translateExpression(body);
        if (value != null) {
          builder.emit(
              TacInstruction.builder(TacInstruction.Kind.RETURN)
                  .lhs1(value)
                  .line(lineOf(body))
                  .column(columnOf(body))
                  .build());
        }
      }
    } catch (RuntimeException ex) {
      // A frontend must be total: one malformed statement must not lose the whole
      // function, and must never escape to the caller.
      report(
          "TRANSLATION_ERROR",
          name,
          ex.getClass().getSimpleName(),
          "lowering failure in "
              + name
              + ": "
              + ex.getClass().getSimpleName()
              + (ex.getMessage() == null ? "" : ": " + ex.getMessage()),
          -1,
          -1);
    }

    if (degraded) {
      b.setIrPrecision(org.plumb.tac.ir.IrPrecision.SOURCE_LOWERED);
    }
    return b.build();
  }

  /**
   * Sets the builder's enclosing class.
   *
   * <p>The declaring class is recorded on the function itself, not only in its name. That matters
   * for more than readability: the call graph indexes functions by their enclosing class, the
   * merged program uses it to keep same-named methods on different classes apart, and the finding
   * reporter resolves a finding's file path through it.
   *
   * <p>When this was a no-op -- because {@code TacBuilder.setEnclosingClass} was package-private
   * and this module lives in another package -- a Kotlin function was dropped from the call graph's
   * merged-key index, and a Kotlin caller had <b>zero</b> call sites. No taint could cross the
   * language boundary, because the boundary was not in the graph at all.
   */
  private static void setEnclosingClass(TacBuilder b, String enclosingClass) {
    if (enclosingClass == null || enclosingClass.isEmpty()) return;
    b.setEnclosingClass(enclosingClass);
  }

  // ------------------------------------------------------------------
  // Statements
  // ------------------------------------------------------------------

  /** Lowers a block: each statement in order. */
  private void translateBlock(KtBlockExpression block) {
    for (KtExpression stmt : block.getStatements()) {
      translateStatement(stmt);
    }
  }

  /** Lowers one statement. */
  private void translateStatement(KtExpression stmt) {
    if (stmt == null) {
      return;
    }
    if (stmt instanceof KtProperty prop) {
      translateProperty(prop);
      return;
    }
    if (stmt instanceof KtReturnExpression ret) {
      translateReturn(ret);
      return;
    }
    if (stmt instanceof KtIfExpression iff) {
      translateIf(iff);
      return;
    }
    if (stmt instanceof KtCallExpression call) {
      translateCallStatement(call);
      return;
    }
    if (stmt instanceof KtDotQualifiedExpression dot) {
      // e.g. `buf.append("x")` used as a statement.
      //
      // The RECEIVER must be kept. Translating the selector alone drops the
      // receiver, and the receiver is not an optional detail: the TAC's CALL
      // instruction carries it in args[0] (the convention the Java and bytecode
      // frontends both follow), and the null-dereference checker reads it from
      // there. Without it, `buf.append("x")` looked like a call on a null
      // receiver -- a false positive on every such statement -- and the
      // receiver's own taint was lost.
      //
      // So the whole qualified expression is lowered, not just its selector.
      translateExpression(dot);
      return;
    }
    if (stmt instanceof KtBinaryExpression bin) {
      // A comparison or arithmetic used as a statement: lower the operands for their
      // side effects, then discard the result.
      translateExpression(bin.getLeft());
      translateExpression(bin.getRight());
      return;
    }
    if (stmt instanceof KtBlockExpression inner) {
      translateBlock(inner);
      return;
    }
    if (stmt instanceof KtWhenExpression when) {
      translateWhen(when);
      return;
    }
    // Anything else is not lowered. Report it rather than dropping it silently.
    degraded = true;
    report(
        "UNHANDLED_NODE",
        currentFunction,
        stmt.getClass().getSimpleName(),
        "unsupported statement in "
            + currentFunction
            + ": "
            + stmt.getClass().getSimpleName()
            + " at line "
            + lineOf(stmt),
        lineOf(stmt),
        columnOf(stmt));
  }

  /**
   * Lowers a local property declaration.
   *
   * <p>A {@code val} is a fresh binding; a {@code var} may be reassigned later. Both are
   * registered under their source name so later references resolve to the same TAC
   * variable -- which is what makes taint flow through the local.
   *
   * <p>The variable's type is the <b>declared</b> type when there is one, and the
   * <b>inferred</b> type of the initializer when there is not. The distinction matters:
   * {@code val x: String = maybeNull()} declares a non-null {@code String}, while
   * {@code val x = maybeNull()} infers whatever the initializer's type is -- which, for
   * a Java method seen from Kotlin, is a <i>platform</i> type that may hold null.
   */
  private void translateProperty(KtProperty prop) {
    String name = prop.getName();
    if (name == null) {
      return;
    }
    KtExpression init = prop.getInitializer();
    if (init == null) {
      // A declaration with no initializer (`var x: String`) has no value yet. The local
      // still exists, with an unknown type.
      TacOperand.TacVar v = new TacOperand.TacVar(name, TacType.UNKNOWN, false);
      locals.put(name, v);
      builder.addLocal(v);
      return;
    }
    TacOperand value = translateExpression(init);
    TacType declared = declaredTypeOf(prop);
    if (declared == TacType.UNKNOWN) {
      // No declared type: the initializer's own type is the declaration's type.
      declared = expressionTypeOf(init);
    }
    TacOperand.TacVar v = new TacOperand.TacVar(name, declared, false);
    locals.put(name, v);
    builder.addLocal(v);
    if (value != null) {
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.MOVE)
              .dest(v)
              .lhs1(value)
              .line(lineOf(prop))
              .column(columnOf(prop))
              .build());
    }
  }

  /**
   * The TAC type of an expression, as the Analysis API infers it.
   *
   * <p>This is how a call's result or a constructor's result gets a type at all: the
   * Analysis API knows that {@code StringBuilder()} produces a {@code StringBuilder} and
   * that a Java method seen from Kotlin produces a <i>platform</i> type -- a type whose
   * nullability is unknown, and which therefore must be mapped as nullable.
   *
   * @param expression the expression
   * @return the expression's type, or {@link TacType#UNKNOWN} when it cannot be resolved
   */
  private TacType expressionTypeOf(KtExpression expression) {
    if (expression == null) {
      return TacType.UNKNOWN;
    }
    try {
      KaType type = session.getExpressionType(expression);
      return types.map(type);
    } catch (RuntimeException ex) {
      // The expression could not be resolved. "Unknown" is the honest answer: it is not
      // "non-null", and treating it as non-null would suppress findings on every
      // unresolved expression.
      return TacType.UNKNOWN;
    }
  }

  /** Lowers a {@code return} statement. */
  private void translateReturn(KtReturnExpression ret) {
    KtExpression value = ret.getReturnedExpression();
    if (value == null) {
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.RETURN)
              .line(lineOf(ret))
              .column(columnOf(ret))
              .build());
      return;
    }
    TacOperand operand = translateExpression(value);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.RETURN)
            .lhs1(operand)
            .line(lineOf(ret))
            .column(columnOf(ret))
            .build());
  }

  /**
   * Lowers an {@code if} into a branch.
   *
   * <p>The condition is translated into a boolean-valued variable; the branch tests it.
   * Both arms are lowered in sequence with a join label, which is the standard TAC shape
   * for a two-way branch.
   */
  private void translateIf(KtIfExpression iff) {
    KtExpression condition = iff.getCondition();
    if (condition == null) {
      degraded = true;
      return;
    }
    TacOperand cond = translateCondition(condition);

    String thenLabel = builder.newLabel("then");
    String elseLabel = builder.newLabel("else");
    String endLabel = builder.newLabel("endif");

    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(cond)
            .trueLabel(thenLabel)
            .falseLabel(elseLabel)
            .line(lineOf(iff))
            .column(columnOf(iff))
            .build());

    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL)
            .label(thenLabel)
            .line(lineOf(iff))
            .build());
    KtExpression thenBranch = iff.getThen();
    if (thenBranch != null) {
      translateStatement(thenBranch);
    }

    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL).label(elseLabel).line(lineOf(iff)).build());
    KtExpression elseBranch = iff.getElse();
    if (elseBranch != null) {
      translateStatement(elseBranch);
    }

    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL).label(endLabel).line(lineOf(iff)).build());
  }

  /**
   * Lowers a {@code when} into a branch chain.
   *
   * <p>A {@code when} with an explicit subject becomes a chain of equality branches; a
   * {@code when} without one is an if/else chain over its conditions. The chain says
   * everything the {@code when} said -- only the structure differs -- so the precision is
   * not downgraded for it.
   */
  /**
   * Lower one arm of a `when` and move its value into the result.
   *
   * <p>An arm whose body is an EXPRESSION has a value, and that value is the `when`'s value.
   * An arm whose body is a statement does not, and then the result keeps whatever it had.
   *
   * <p>The distinction is the same one the source makes: `1 -> compute()` yields a value,
   * `1 -> doSomething()` does not.
   *
   * @param body the arm's body
   * @param result the temp the value is moved into
   * @param line the source line, for the emitted instruction
   */
  private void assignArmValue(KtExpression body, TacOperand.TacVar result, int line) {
    if (isStatementLike(body)) {
      translateStatement(body);
      return;
    }
    TacOperand value = translateExpression(body);
    if (value == null) {
      translateStatement(body);
      return;
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(result)
            .lhs1(value)
            .line(line)
            .build());
  }

  /**
   * Whether an expression is used for its effect rather than for its value.
   *
   * <p>A call whose result is discarded is a statement in the source's eyes, and lowering it as
   * an expression would put a value on the stack that the source never had.
   */
  private static boolean isStatementLike(KtExpression expr) {
    if (expr == null) return true;
    if (expr instanceof KtCallExpression) return true;
    if (expr instanceof KtDotQualifiedExpression dot) {
      return dot.getSelectorExpression() instanceof KtCallExpression;
    }
    return false;
  }

  private TacOperand translateWhen(KtWhenExpression when) {
    KtExpression subject = when.getSubjectExpression();
    List<KtWhenEntry> entries = when.getEntries();

    String endLabel = builder.newLabel("whenend");
    // The RESULT of the `when`. Every arm assigns it, and the expression yields
    // it. Without it the `when` had no value at all: the arms' bodies were
    // translated as statements, so their values were computed and dropped.
    TacOperand.TacVar result = builder.newTemp(TacType.UNKNOWN);

    if (subject != null) {
      TacOperand subjectValue = translateExpression(subject);
      for (KtWhenEntry entry : entries) {
        if (entry.isElse()) {
          continue;
        }
        // The value the subject is compared against is the entry's *condition*
        // (`when (x) { 1 -> ... }`), not the entry's expression (which is the branch
        // body, after the arrow).
        KtExpression match = firstConditionOf(entry);
        if (match == null) {
          // An `is`-pattern branch ("is String -> ...") has no condition to compare
          // against: it tests the subject's runtime type. The comparison chain cannot
          // express it, so the branch is NOT lowered.
          //
          // It used to be skipped with a bare `continue`, which made the gap SILENT: the
          // function's precision stayed SOURCE_FULL, so a checker trusted a function
          // whose branch was simply gone. The gap is now reported and the function is
          // degraded, which is what "the frontend could not lower this" has to mean.
          degraded = true;
          report(
              "UNSUPPORTED_CONSTRUCT",
              currentFunction,
              "KtWhenEntryWithIsCondition",
              "an 'is' branch of a when expression in "
                  + currentFunction
                  + " was not lowered (line "
                  + lineOf(entry)
                  + ")",
              lineOf(entry),
              -1);
          continue;
        }
        TacOperand matchValue = translateExpression(match);
        TacOperand eq = builder.newTemp(TacType.BOOLEAN);
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.BINOP)
                .dest(eq)
                .lhs1(subjectValue)
                .lhs2(matchValue)
                .binop(BinaryOperator.EQ)
                .line(lineOf(entry))
                .build());

        String matchLabel = builder.newLabel("whenmatch");
        String nextLabel = builder.newLabel("whennext");
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.BRANCH)
                .lhs1(eq)
                .trueLabel(matchLabel)
                .falseLabel(nextLabel)
                .line(lineOf(entry))
                .build());
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.LABEL)
                .label(matchLabel)
                .line(lineOf(entry))
                .build());
        KtExpression body = entry.getExpression();
        if (body != null) {
          assignArmValue(body, result, lineOf(entry));
        }
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.JUMP)
                .label(endLabel)
                .line(lineOf(entry))
                .build());
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.LABEL)
                .label(nextLabel)
                .line(lineOf(entry))
                .build());
      }
      // The `else` branch, if any.
      for (KtWhenEntry entry : entries) {
        if (!entry.isElse()) {
          continue;
        }
        KtExpression body = entry.getExpression();
        if (body != null) {
          assignArmValue(body, result, lineOf(entry));
        }
        break;
      }
    } else {
      // A subject-less `when` is an if/else chain over its conditions.
      for (KtWhenEntry entry : entries) {
        KtExpression condition = firstConditionOf(entry);
        if (condition == null) {
          continue;
        }
        TacOperand cond = translateCondition(condition);
        String thenLabel = builder.newLabel("whenthen");
        String nextLabel = builder.newLabel("whennext");
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.BRANCH)
                .lhs1(cond)
                .trueLabel(thenLabel)
                .falseLabel(nextLabel)
                .line(lineOf(entry))
                .build());
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.LABEL)
                .label(thenLabel)
                .line(lineOf(entry))
                .build());
        KtExpression body = entry.getExpression();
        if (body != null) {
          assignArmValue(body, result, lineOf(entry));
        }
        if (!entry.isElse()) {
          builder.emit(
              TacInstruction.builder(TacInstruction.Kind.JUMP)
                  .label(endLabel)
                  .line(lineOf(entry))
                  .build());
        }
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.LABEL)
                .label(nextLabel)
                .line(lineOf(entry))
                .build());
      }
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL)
            .label(endLabel)
            .line(lineOf(when))
            .build());
    // The `when`'s value. Every arm assigned it, so the merge point holds the value of
    // whichever arm ran.
    return result;
  }

  // ------------------------------------------------------------------
  // Expressions
  // ------------------------------------------------------------------

  /**
   * Lowers an expression into an operand.
   *
   * @return the operand holding the expression's value, or {@code null} when the expression
   *     produced no value (a bare call to a {@code Unit}-returning function).
   */
  TacOperand translateExpression(KtExpression expr) {
    if (expr == null) {
      return null;
    }
    if (expr instanceof KtStringTemplateExpression str) {
      return translateStringTemplate(str);
    }
    if (expr instanceof KtNameReferenceExpression ref) {
      return translateNameReference(ref);
    }
    if (expr instanceof KtCallExpression call) {
      return translateCall(call, null);
    }
    if (expr instanceof KtDotQualifiedExpression dot) {
      return translateDotQualified(dot);
    }
    if (expr instanceof KtBinaryExpression bin) {
      return translateBinary(bin);
    }
    if (expr instanceof KtIfExpression iff) {
      return translateIfExpression(iff);
    }
    if (expr instanceof KtBlockExpression block) {
      // The value of a block is the value of its last expression.
      translateBlock(block);
      List<KtExpression> stmts = block.getStatements();
      if (stmts.isEmpty()) {
        return null;
      }
      return translateExpression(stmts.get(stmts.size() - 1));
    }
    if (expr instanceof KtProperty prop) {
      translateProperty(prop);
      return locals.get(prop.getName());
    }
    if (expr instanceof KtWhenExpression when) {
      // The `when` HAS a value, and the expression form of it is how that value is
      // reached. Returning it is what makes `val x = when (y) { ... }` work.
      return translateWhen(when);
    }
    if (expr instanceof KtConstantExpression constant) {
      return constantOf(constant);
    }
    degraded = true;
    report(
        "UNHANDLED_NODE",
        currentFunction,
        expr.getClass().getSimpleName(),
        "unsupported expression in "
            + currentFunction
            + ": "
            + expr.getClass().getSimpleName()
            + " at line "
            + lineOf(expr),
        lineOf(expr),
        columnOf(expr));
    return null;
  }

  /**
   * Lowers a condition into a boolean-valued operand.
   *
   * <p>A comparison becomes a {@code BINOP} with a comparison operator; a bare boolean
   * variable is used as-is.
   */
  private TacOperand translateCondition(KtExpression condition) {
    if (condition instanceof KtBinaryExpression bin) {
      BinaryOperator op = comparisonOperatorOf(bin);
      if (op != null) {
        TacOperand lhs = translateExpression(bin.getLeft());
        TacOperand rhs = translateExpression(bin.getRight());
        TacOperand dest = builder.newTemp(TacType.BOOLEAN);
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.BINOP)
                .dest(dest)
                .lhs1(lhs)
                .lhs2(rhs)
                .binop(op)
                .line(lineOf(bin))
                .column(columnOf(bin))
                .build());
        return dest;
      }
    }
    if (condition instanceof KtPrefixExpression prefix
        && "!".equals(prefix.getOperationReference().getText())) {      TacOperand inner = translateCondition(prefix.getBaseExpression());
      TacOperand dest = builder.newTemp(TacType.BOOLEAN);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.UNARYOP)
              .dest(dest)
              .lhs1(inner)
              .unop(org.plumb.tac.ir.UnaryOperator.NOT)
              .line(lineOf(condition))
              .build());
      return dest;
    }
    TacOperand operand = translateExpression(condition);
    if (operand == null) {
      // An unresolved condition: a fresh false, so the branch is well-formed.
      TacOperand dest = builder.newTemp(TacType.BOOLEAN);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.MOVE)
              .dest(dest)
              .lhs1(TacOperand.Const.ofBool(false))
              .line(lineOf(condition))
              .build());
      return dest;
    }
    return operand;
  }

  /** Lowers a name reference: a local, a parameter, or a property read. */
  private TacOperand translateNameReference(KtNameReferenceExpression ref) {
    String name = ref.getReferencedName();
    TacOperand.TacVar local = locals.get(name);
    if (local != null) {
      return local;
    }
    // Not a local: it is a property of the receiver or a top-level property. Lower it as
    // a field read off `this`, which is the shape the DFA understands.
    return translatePropertyRead(ref, name);
  }

  /**
   * Lowers a property read.
   *
   * <p>A property of the enclosing class is a {@code FIELDLOAD} off the implicit
   * {@code this}. The receiver is a local named {@code this}, declared by the frontend for
   * every function that has an enclosing class.
   */
  private TacOperand translatePropertyRead(KtExpression receiver, String name) {
    TacOperand.TacVar self = locals.get(THIS);
    if (self == null) {
      // No receiver available (a top-level property): the value is unknown.
      return builder.newTemp(TacType.UNKNOWN);
    }
    TacOperand dest = builder.newTemp(TacType.UNKNOWN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
            .dest(dest)
            .lhs1(self)
            .fieldName(name)
            .line(lineOf(receiver))
            .column(columnOf(receiver))
            .build());
    return dest;
  }

  /**
   * Lowers a qualified expression ({@code a.b}, {@code a.b()}, {@code a?.b}).
   */
  private TacOperand translateDotQualified(KtDotQualifiedExpression dot) {
    KtExpression selector = dot.getSelectorExpression();
    KtExpression receiver = dot.getReceiverExpression();

    if (selector instanceof KtCallExpression call) {
      // `receiver.call(args)`: lower the call with the receiver bound to it.
      return translateCall(call, receiver);
    }
    // `receiver.property`: a field read -- UNLESS the property is really a Java
    // method seen through Kotlin's property syntax.
    if (selector instanceof KtNameReferenceExpression ref) {
      String propertyName = ref.getReferencedName();
      // A Java class seen from Kotlin exposes its methods as PROPERTIES: Kotlin's
      // `s.length` is Java's `String.length()`. Lowering it as a FIELDLOAD
      // would name a field that does not exist, and -- more importantly -- it
      // would never reach a sink family, because the families are anchored to
      // METHOD names (`java.lang.String.length`).
      //
      // The Analysis API knows the difference: it resolves the reference to a
      // symbol, and the symbol says whether it is a property or a function.
      // A resolved FUNCTION means the source's property syntax is a method call,
      // and it is lowered as a CALL so the sink families can see it.
      String javaOwner = javaMethodOwnerOf(ref);
      if (javaOwner != null) {
        TacOperand recv = translateExpression(receiver);
        TacOperand dest = builder.newTemp(TacType.UNKNOWN);
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.CALL)
                .dest(dest)
                .calleeSig(javaOwner + "." + propertyName + "/0")
                .args(List.of(recv == null ? TacOperand.Const.ofNull() : recv))
                .line(lineOf(dot))
                .column(columnOf(dot))
                .build());
        return dest;
      }
      TacOperand recv = translateExpression(receiver);
      TacOperand dest = builder.newTemp(TacType.UNKNOWN);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
              .dest(dest)
              .lhs1(recv)
              .fieldName(propertyName)
              .line(lineOf(dot))
              .column(columnOf(dot))
              .build());
      return dest;
    }
    return translateExpression(selector);
  }

  /**
   * Whether a symbol's origin says it came from a Java class.
   *
   * <p>The two Java origins are {@code JAVA_LIBRARY} (a class from a jar on the classpath) and
   * {@code JAVA_SOURCE} (a Java source file in the analysed project). Both mean "this is a Java
   * declaration", and both are what makes Kotlin's property syntax a method call.
   *
   * <p>{@code JAVA_SYNTHETIC_PROPERTY} is deliberately NOT included: it is a Kotlin-side
   * synthetic property, not a Java method.
   *
   * @param origin the symbol's origin
   * @return true when the symbol is a Java declaration
   */
  private static boolean isJavaOrigin(KaSymbolOrigin origin) {
    return origin == KaSymbolOrigin.JAVA_LIBRARY || origin == KaSymbolOrigin.JAVA_SOURCE;
  }

  /**
   * The owner of the Java method a Kotlin property reference really names, or {@code null}.
   *
   * <p>Kotlin lets a Java method be written without parentheses when it looks like a property:
   * {@code s.length} is {@code String.length()}. Lowering it as a FIELDLOAD would name a field
   * that does not exist, and -- more importantly -- it would never reach a sink family, because
   * the families are anchored to METHOD names.
   *
   * <p><b>WHERE THE DECLARING CLASS ACTUALLY LIVES.</b> This reads the callable id off the
   * PROPERTY, not off the property's getter. That is not a style choice; the getter does not
   * carry it:
   *
   * <pre>
   *   sb.length  where sb: java.lang.StringBuilder
   *     property  -> origin=INTERSECTION_OVERRIDE, callableId=java/lang/StringBuilder.length
   *     getter    -> origin=INTERSECTION_OVERRIDE, callableId=null
   * </pre>
   *
   * <p>The reason is that Kotlin resolves {@code sb.length} to the synthetic INTERSECTION
   * OVERRIDE property it generates for a Java member, and that override's accessor is a
   * synthetic function with no callable id of its own. The Java declaring class survives on
   * the property's callable id. Reading the getter -- the obvious thing to try -- yields
   * {@code null}, and the property silently degrades to a FIELDLOAD that no sink family can
   * ever match.
   *
   * <p>The origin is checked on the PROPERTY too, and for the same reason: the override's
   * origin is {@code INTERSECTION_OVERRIDE}, not {@code JAVA_LIBRARY}. A Java member reached
   * through Kotlin's property syntax is therefore recognised by asking whether the property
   * resolves to a class that came from Java -- not by asking whether the symbol's origin
   * literally says JAVA.
   *
   * @param ref the property reference
   * @return the Java declaring class's FQN, or {@code null} when this is not a Java method
   */
  private String javaMethodOwnerOf(KtNameReferenceExpression ref) {
    try {
      KaSymbol symbol = session.resolveSymbol(ref);
      if (!(symbol instanceof KaPropertySymbol property)) {
        // Not a property at all. Either a plain reference or something the
        // Analysis API cannot classify -- nothing to reinterpret.
        return null;
      }
      org.jetbrains.kotlin.name.ClassId classId = classIdOf(property);
      if (classId == null || classId.isLocal()) {
        return null;
      }
      if (!isJavaClass(classId)) {
        // A KOTLIN property, not a Java method. Turning it into a CALL would
        // invent a method that does not exist, and would make the call site
        // match a sink family that names a method the code never calls.
        return null;
      }
      String rendered = classId.asSingleFqName().asString();
      return rendered == null || rendered.isEmpty() ? null : rendered;
    } catch (RuntimeException | LinkageError ex) {
      // Best effort. A reference that does not resolve stays a property read.
      return null;
    }
  }

  /**
   * Whether a class is declared in Java.
   *
   * <p><b>WHY THE DECLARING CLASS IS ASKED, NOT THE SYMBOL.</b> Kotlin resolves a Java
   * member reached through property syntax to a synthetic INTERSECTION OVERRIDE symbol
   * whose origin is {@code INTERSECTION_OVERRIDE} -- not {@code JAVA_LIBRARY}. So the
   * origin of the symbol in hand never says "this came from Java". What does say so is
   * the class the property belongs to: for {@code sb.length} that is
   * {@code java.lang.StringBuilder}, a Java class, and its origin is JAVA_LIBRARY.
   *
   * <p>For a genuine Kotlin property the declaring class is a Kotlin class, whose origin
   * is not a Java origin -- so the same test separates the two cases without having to
   * special-case the intersection override.
   *
   * @param classId the declaring class
   * @return true when the class comes from Java
   */
  private boolean isJavaClass(org.jetbrains.kotlin.name.ClassId classId) {
    try {
      KaClassSymbol clazz = session.findClass(classId);
      if (clazz == null) {
        return false;
      }
      KaSymbolOrigin origin = clazz.getOrigin();
      return origin == KaSymbolOrigin.JAVA_LIBRARY || origin == KaSymbolOrigin.JAVA_SOURCE;
    } catch (RuntimeException | LinkageError ex) {
      return false;
    }
  }

  /**
   * The declaring class of a property, or {@code null} when it has none.
   *
   * <p>The property's own callable id is the primary source. The getter's is a fallback for
   * the case where the property is a plain Kotlin property with a real accessor -- but a
   * getter that is a synthetic intersection override carries no callable id, so the fallback
   * simply does not apply there.
   *
   * @param property the property symbol
   * @return the declaring class, or {@code null}
   */
  private static org.jetbrains.kotlin.name.ClassId classIdOf(KaPropertySymbol property) {
    org.jetbrains.kotlin.name.CallableId id = property.getCallableId();
    if (id != null && id.getClassId() != null) {
      return id.getClassId();
    }
    KaPropertyGetterSymbol getter = property.getGetter();
    if (getter != null && getter.getCallableId() != null) {
      return getter.getCallableId().getClassId();
    }
    return null;
  }

  /** Lowers an {@code if} used as an expression. */
  private TacOperand translateIfExpression(KtIfExpression iff) {
    // The branches are lowered into a join; the value is a temp the branches assign.
    TacOperand dest = builder.newTemp(TacType.UNKNOWN);
    String thenLabel = builder.newLabel("ifthen");
    String elseLabel = builder.newLabel("ifelse");
    String endLabel = builder.newLabel("ifend");

    TacOperand cond = translateCondition(iff.getCondition());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(cond)
            .trueLabel(thenLabel)
            .falseLabel(elseLabel)
            .line(lineOf(iff))
            .build());

    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL).label(thenLabel).line(lineOf(iff)).build());
    KtExpression thenBranch = iff.getThen();
    if (thenBranch != null) {
      TacOperand v = translateExpression(thenBranch);
      if (v != null) {
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.MOVE).dest(dest).lhs1(v).line(lineOf(iff)).build());
      }
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL).label(elseLabel).line(lineOf(iff)).build());
    KtExpression elseBranch = iff.getElse();
    if (elseBranch != null) {
      TacOperand v = translateExpression(elseBranch);
      if (v != null) {
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.MOVE).dest(dest).lhs1(v).line(lineOf(iff)).build());
      }
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL).label(endLabel).line(lineOf(iff)).build());
    return dest;
  }

  /** Lowers a binary expression: arithmetic, comparison, or a logical operator. */
  private TacOperand translateBinary(KtBinaryExpression bin) {
    BinaryOperator comparison = comparisonOperatorOf(bin);
    if (comparison != null) {
      TacOperand lhs = translateExpression(bin.getLeft());
      TacOperand rhs = translateExpression(bin.getRight());
      TacOperand dest = builder.newTemp(TacType.BOOLEAN);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.BINOP)
              .dest(dest)
              .lhs1(lhs)
              .lhs2(rhs)
              .binop(comparison)
              .line(lineOf(bin))
              .column(columnOf(bin))
              .build());
      return dest;
    }
    BinaryOperator arithmetic = arithmeticOperatorOf(bin);
    if (arithmetic != null) {
      TacOperand lhs = translateExpression(bin.getLeft());
      TacOperand rhs = translateExpression(bin.getRight());
      TacOperand dest = builder.newTemp(TacType.UNKNOWN);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.BINOP)
              .dest(dest)
              .lhs1(lhs)
              .lhs2(rhs)
              .binop(arithmetic)
              .line(lineOf(bin))
              .column(columnOf(bin))
              .build());
      return dest;
    }
    // A logical operator (`&&`, `||`) becomes a short-circuit branch chain.
    String op = operatorTextOf(bin);
    if ("&&".equals(op) || "||".equals(op)) {
      return translateShortCircuit(bin, "&&".equals(op));
    }
    degraded = true;
    report(
        "UNHANDLED_NODE",
        currentFunction,
        "BinaryOperator:" + op,
        "unsupported binary operator in "
            + currentFunction
            + ": "
            + op
            + " at line "
            + lineOf(bin),
        lineOf(bin),
        columnOf(bin));
    return null;
  }

  /**
   * Lowers {@code a && b} / {@code a || b} into a short-circuit branch.
   *
   * <p>The right operand is only evaluated on the branch that reaches it, which is what
   * "short-circuit" means. The result is a temp the branches assign.
   */
  private TacOperand translateShortCircuit(KtBinaryExpression bin, boolean and) {
    TacOperand dest = builder.newTemp(TacType.BOOLEAN);
    String rhsLabel = builder.newLabel("sc_rhs");
    String shortLabel = builder.newLabel("sc_short");
    String endLabel = builder.newLabel("sc_end");

    TacOperand lhs = translateCondition(bin.getLeft());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(lhs)
            .trueLabel(and ? rhsLabel : shortLabel)
            .falseLabel(and ? shortLabel : rhsLabel)
            .line(lineOf(bin))
            .build());

    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL).label(rhsLabel).line(lineOf(bin)).build());
    TacOperand rhs = translateCondition(bin.getRight());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.MOVE).dest(dest).lhs1(rhs).line(lineOf(bin)).build());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.JUMP).label(endLabel).line(lineOf(bin)).build());

    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL).label(shortLabel).line(lineOf(bin)).build());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(dest)
            .lhs1(TacOperand.Const.ofBool(!and))
            .line(lineOf(bin))
            .build());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL).label(endLabel).line(lineOf(bin)).build());
    return dest;
  }

  /**
   * Lowers a string template.
   *
   * <p>A template is a sequence of entries: literal text entries and interpolation
   * entries. A template with no entries is a string constant; one with a single
   * interpolation entry and no literal text around it is that entry's value; anything
   * else is the concatenation of the parts.
   *
   * <p>The Analysis API's Kotlin PSI exposes the entries as an array of {@link
   * KtStringTemplateEntry}, and the entries themselves are <i>not</i> expressions -- an
   * interpolation entry holds its expression, a literal entry holds its text. So the two
   * are distinguished by their type, not by their class name.
   */
  private TacOperand translateStringTemplate(KtStringTemplateExpression str) {
    KtStringTemplateEntry[] entries = str.getEntries();
    if (entries.length == 0) {
      // A plain string literal.
      return TacOperand.Const.ofString(unquote(str.getText()));
    }
    if (entries.length == 1) {
      KtStringTemplateEntry single = entries[0];
      KtExpression hole = interpolationOf(single);
      if (hole != null) {
        // The template is exactly one hole: its value is the hole's value.
        return translateExpression(hole);
      }
      return TacOperand.Const.ofString(unquote(single.getText()));
    }

    TacOperand acc = null;
    for (KtStringTemplateEntry entry : entries) {
      KtExpression hole = interpolationOf(entry);
      TacOperand part = hole != null ? translateExpression(hole) : TacOperand.Const.ofString(unquote(entry.getText()));
      if (acc == null) {
        acc = part;
      } else {
        TacOperand dest = builder.newTemp(TacType.STRING);
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.BINOP)
                .dest(dest)
                .lhs1(acc)
                .lhs2(part)
                .binop(BinaryOperator.ADD)
                .line(lineOf(str))
                .build());
        acc = dest;
      }
    }
    return acc;
  }

  /**
   * The expression a string template entry interpolates, or {@code null} for a literal
   * entry.
   */
  private static KtExpression interpolationOf(KtStringTemplateEntry entry) {
    if (entry instanceof org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression withExpr) {
      return withExpr.getExpression();
    }
    return null;
  }

  /**
   * Strips the quotes from a Kotlin string literal.
   *
   * <p>A raw string ({@code """..."""}) keeps its content verbatim; an ordinary string
   * has its quotes removed. The escape sequences inside an ordinary string are left as
   * they are: the TAC's string constants are compared literally, and unescaping them
   * would change what a sink sees.
   */
  private static String unquote(String text) {
    if (text == null) {
      return "";
    }
    if (text.startsWith("\"\"\"") && text.endsWith("\"\"\"") && text.length() >= 6) {
      return text.substring(3, text.length() - 3);
    }
    if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
      return text.substring(1, text.length() - 1);
    }
    if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
      return text.substring(1, text.length() - 1);
    }
    return text;
  }

  /**
   * Lowers a constant expression.
   *
   * <p>The Analysis API's Kotlin PSI does not expose the constant's <i>kind</i> -- its
   * {@code KtConstantExpression} has no {@code getConstant()} -- so the literal is
   * recognised from its source text. That is exact for the literal forms Kotlin has:
   * {@code null}, {@code true} / {@code false}, a number, a quoted string, and a
   * character literal.
   */
  private TacOperand constantOf(KtConstantExpression constant) {
    String text = constant.getText();
    if (text == null) {
      return null;
    }
    String trimmed = text.trim();
    if ("null".equals(trimmed)) {
      return TacOperand.Const.ofNull();
    }
    if ("true".equals(trimmed)) {
      return TacOperand.Const.ofBool(true);
    }
    if ("false".equals(trimmed)) {
      return TacOperand.Const.ofBool(false);
    }
    if (trimmed.length() >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'")) {
      String inner = trimmed.substring(1, trimmed.length() - 1);
      if (inner.length() == 1) {
        return TacOperand.Const.ofChar(inner.charAt(0));
      }
      return null;
    }
    if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      return TacOperand.Const.ofString(unquote(trimmed));
    }
    TacOperand number = numberConstantOf(trimmed);
    if (number != null) {
      return number;
    }
    return null;
  }

  /**
   * Recognises a numeric literal.
   *
   * <p>The literal's suffix decides the type: {@code L} makes it a {@code Long}, {@code f}
   * a {@code Float}, {@code F} a {@code Float}, and the rest are {@code Int} unless the
   * value does not fit.
   */
  private static TacOperand numberConstantOf(String text) {
    if (text == null || text.isEmpty()) {
      return null;
    }
    String body = text;
    char last = body.charAt(body.length() - 1);
    boolean isLong = false;
    boolean isFloat = false;
    boolean isDouble = false;
    if (last == 'L' || last == 'l') {
      isLong = true;
      body = body.substring(0, body.length() - 1);
    } else if (last == 'f' || last == 'F') {
      isFloat = true;
      body = body.substring(0, body.length() - 1);
    } else if (last == 'd' || last == 'D') {
      isDouble = true;
      body = body.substring(0, body.length() - 1);
    }
    try {
      if (isFloat) {
        return TacOperand.Const.ofFloat(Float.parseFloat(body));
      }
      if (isDouble) {
        return TacOperand.Const.ofDouble(Double.parseDouble(body));
      }
      if (isLong) {
        return TacOperand.Const.ofLong(Long.parseLong(body));
      }
      long value = Long.parseLong(body);
      if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
        return TacOperand.Const.ofInt((int) value);
      }
      return TacOperand.Const.ofLong(value);
    } catch (NumberFormatException ex) {
      // Not a number after all (a hex literal, an underscored literal, ...). The value is
      // still a constant, but the TAC has no exact representation for it, so it is
      // reported as unknown rather than silently mis-typed.
      return null;
    }
  }

  // ------------------------------------------------------------------
  // Calls
  // ------------------------------------------------------------------

  /** Lowers a call used as a statement: the result is discarded. */
  private void translateCallStatement(KtCallExpression call) {
    translateCall(call, null);
  }
  /**
   * Lowers a call.
   *
   * <p>A call to {@code requireNotNull(x)} / {@code checkNotNull(x)} proves {@code x}
   * non-null on the path that follows, so a {@code NULL_CHECK} is emitted for the argument.
   * That is the one place the Kotlin frontend emits a {@code NULL_CHECK} from a call: the
   * standard library's own null-check helpers.
   *
   * <p><b>Not</b> {@code Intrinsics.areEqual} -- that is a null-safe <i>comparison</i>, not
   * a null check, and treating it as one would put "these two values are equal" into the
   * non-null fact set. And <b>not</b> {@code
   * Intrinsics.throwUninitializedPropertyAccessException} -- that is the {@code lateinit}
   * check, not a null check.
   *
   * @param call the call to lower
   * @param receiver the receiver the call is made on, or {@code null} for an unqualified call
   * @return the call's result, or {@code null} for a {@code Unit}-returning call
   */
  private TacOperand translateCall(KtCallExpression call, KtExpression receiver) {
    String calleeName = calledName(call);
    int arity = call.getValueArguments().size();

    // `requireNotNull(x)` / `checkNotNull(x)` / `checkNotNull(x) { "msg" }`: the argument
    // is proven non-null after the call.
    if (isNullCheckHelper(calleeName)) {
      List<KtValueArgument> args = call.getValueArguments();
      if (!args.isEmpty()) {
        KtExpression argExpr = args.get(0).getArgumentExpression();
        if (argExpr != null) {
          TacOperand value = translateExpression(argExpr);
          TacOperand.TacVar checked = asVar(value);
          if (checked != null) {
            builder.emit(
                TacInstruction.builder(TacInstruction.Kind.NULL_CHECK)
                    .dest(checked)
                    .line(lineOf(call))
                    .column(columnOf(call))
                    .build());
          }
          // The call itself returns the value; model it as a call so the trace shows it.
          // The result of `requireNotNull` is non-null by contract, so its type is the
          // argument's type marked non-null.
          TacOperand dest = builder.newTemp(nonNullOf(expressionTypeOf(argExpr)));
          builder.emit(
              TacInstruction.builder(TacInstruction.Kind.CALL)
                  .dest(dest)
                  .calleeSig(calleeSignature(calleeName, receiver, arity, call))
                  .args(List.of(value == null ? TacOperand.Const.ofNull() : value))
                  .line(lineOf(call))
                  .column(columnOf(call))
                  .build());
          return dest;
        }
      }
    }

    // A constructor call: `Foo(...)`.
    if (isConstructorCall(call)) {
      TacType constructed = expressionTypeOf(call);
      TacOperand dest = builder.newTemp(constructed);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.NEW)
              .dest(dest)
              .ctorType(constructed)
              .calleeSig(calleeSignature(calleeName, receiver, arity, call))
              .args(translateArguments(call))
              .line(lineOf(call))
              .column(columnOf(call))
              .build());
      return dest;
    }

    // A general call.
    List<TacOperand> args = translateArguments(call);
    TacType resultType = expressionTypeOf(call);
    TacOperand dest = builder.newTemp(resultType);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(dest)
            .calleeSig(calleeSignature(calleeName, receiver, arity, call))
            .args(withReceiver(args, receiver))
            .line(lineOf(call))
            .column(columnOf(call))
            .build());
    return dest;
  }

  /**
   * The call's arguments, with the receiver in front.
   *
   * <p><b>Why the receiver is an argument.</b> In Kotlin a receiver is not an argument --
   * {@code a.b(x)} has arity 1, not 2 -- but the TAC's {@code CALL} instruction carries the
   * receiver in {@code args[0]}, and that is the convention the Java frontend and the
   * bytecode frontend both follow. The DFA's null-dereference checker reads the receiver
   * from {@code args[0]} for a {@code CALL}: without it, a method call on a possibly-null
   * receiver is not reported at all.
   *
   * <p>The callee signature's arity stays the <i>argument</i> count, so the receiver does
   * not change the call graph's edge.
   */
  private List<TacOperand> withReceiver(List<TacOperand> args, KtExpression receiver) {
    if (receiver == null) {
      return args;
    }
    TacOperand recv = translateExpression(receiver);
    List<TacOperand> out = new ArrayList<>(args.size() + 1);
    out.add(recv == null ? TacOperand.Const.ofNull() : recv);
    out.addAll(args);
    return out;
  }

  /** Lowers a call's arguments. */
  private List<TacOperand> translateArguments(KtCallExpression call) {
    List<TacOperand> args = new ArrayList<>();
    for (KtValueArgument arg : call.getValueArguments()) {
      KtExpression expr = arg.getArgumentExpression();
      if (expr == null) {
        continue;
      }
      TacOperand operand = translateExpression(expr);
      args.add(operand == null ? TacOperand.Const.ofNull() : operand);
    }
    return args;
  }

  /**
   * The name of the function a call refers to.
   *
   * <p>For {@code foo.bar()} the callee is {@code bar}; for {@code bar()} it is
   * {@code bar}. A constructor call ({@code Foo()}) has a {@link
   * org.jetbrains.kotlin.psi.KtConstructorCalleeExpression} as its callee rather than a
   * name reference, and the constructor's name comes from that expression.
   */
  private static String calledName(KtCallExpression call) {
    KtExpression callee = call.getCalleeExpression();
    if (callee instanceof KtNameReferenceExpression ref) {
      return ref.getReferencedName();
    }
    if (callee instanceof org.jetbrains.kotlin.psi.KtConstructorCalleeExpression ctor) {
      KtSimpleNameExpression reference = ctor.getConstructorReferenceExpression();
      return reference == null ? call.getText() : reference.getReferencedName();
    }
    if (callee instanceof KtDotQualifiedExpression dot) {
      KtExpression selector = dot.getSelectorExpression();
    if (selector instanceof KtNameReferenceExpression ref) {
        return ref.getReferencedName();
      }
      if (selector instanceof org.jetbrains.kotlin.psi.KtConstructorCalleeExpression ctor) {
        KtSimpleNameExpression reference = ctor.getConstructorReferenceExpression();
        return reference == null ? selector.getText() : reference.getReferencedName();
      }
    }
    return call.getText();
  }

  /**
   * The callee signature of a call.
   *
   * <p>The shape is the one the DFA's call graph expects: {@code owner.name/arity}. The arity is
   * what the call graph's bare-name map keys on, so it has to be the real argument count -- a call
   * whose arity is wrong resolves to no callee, and the call graph silently loses the edge.
   *
   * <p>A receiver is not an argument in Kotlin's calling convention (it is the dispatch
   * receiver), so {@code a.b(x)} has arity 1, not 2.
   *
   * <p><b>The owner is what makes sink matching work.</b> The sink families in
   * {@code sink-families.yaml} are anchored to a fully qualified name
   * ({@code '^java\.sql\.Statement\.execute'}), and the DFA matches a call against them by its
   * callee signature. A signature without an owner ({@code "execute/1"}) matches NONE of them --
   * not one pattern in the file. So a Kotlin call to {@code stmt.execute(userInput)} was invisible
   * to every taint sink rule: the analysis ran, produced zero findings, and looked like clean
   * code.
   *
   * <p>The owner comes from the RESOLVED symbol, not from the receiver's syntactic type. That
   * distinction is what makes Kotlin and Java share one rule set: a Kotlin call to a Java method
   * resolves to the same {@code java.sql.Statement} owner the Java frontend produces, so both
   * languages reach the same family.
   */
  private String calleeSignature(
      String calleeName, KtExpression receiver, int arity, KtCallExpression call) {
    String owner = ownerOf(call);
    if (owner == null || owner.isEmpty()) {
      return calleeName + "/" + arity;
    }
    return owner + "." + calleeName + "/" + arity;
  }

  /**
   * The fully qualified name of the class that declares the called member, or {@code null}.
   *
   * <p>Resolved through the Analysis API's symbol table: the call's reference expression resolves
   * to the callable symbol, and the symbol's callable id names its containing class.
   *
   * <p><b>Why the symbol and not the receiver's type.</b> The receiver's type is what the call is
   * made ON; the owner is what the method is DECLARED in. They are usually the same, but not
   * always -- and it is the declaration that the sink families name. A call
   * {@code stmt.execute(x)} where {@code stmt} is declared as {@code java.sql.Statement} resolves
   * to a method declared in {@code java.sql.Statement}, so the owner is that class, and the
   * FQN-anchored pattern matches.
   *
   * <p>For a call on a Java class seen from Kotlin, the class id is the Java class's own FQN
   * ({@code java.sql.Statement}), which is exactly what the Java frontend produces for the same
   * call. That is what lets one rule set serve both languages.
   *
   * <p>A call that does not resolve yields {@code null}, and the caller falls back to the bare
   * name. A wrong owner would be worse than no owner: it would match the WRONG sink family.
   */
  private String ownerOf(KtCallExpression call) {
    if (call == null) {
      return null;
    }
    try {
      // A CALL's callee is a KtNameReferenceExpression (or an operation reference
      // for an infix/operator call), NOT a KtCallableReferenceExpression -- the
      // latter is the `::` method-reference form. Resolving the CALL ELEMENT is
      // the API that answers "which function is this call calling?".
      KaFunctionSymbol function = session.resolveSymbol(call);
      if (function == null) {
        return null;
      }
      org.jetbrains.kotlin.name.ClassId classId = function.getCallableId().getClassId();
      if (classId == null || classId.isLocal()) {
        // A local function has no declaring class. There is no owner to name.
        return null;
      }
      org.jetbrains.kotlin.name.FqName fq = classId.asSingleFqName();
      String rendered = fq.asString();
      return rendered == null || rendered.isEmpty() ? null : rendered;
    } catch (RuntimeException | LinkageError ex) {
      // Resolution is best-effort. A call whose owner cannot be determined falls back to
      // the bare name, which at least keeps the call graph's bare-name edge.
      return null;
    }
  }

  /** Whether the call names one of the Kotlin standard library's null-check helpers. */
  private static boolean isNullCheckHelper(String name) {
    return "requireNotNull".equals(name) || "checkNotNull".equals(name);
  }

  /**
   * Whether the call is a constructor invocation.
   *
   * <p>The PSI tells us exactly when it can: a constructor call's callee is a {@link
   * org.jetbrains.kotlin.psi.KtConstructorCalleeExpression}, not a name reference. When
   * the PSI cannot tell -- a name reference whose target did not resolve -- Kotlin's
   * naming convention is the fallback: a capitalised name is a class.
   */
  private static boolean isConstructorCall(KtCallExpression call) {
    KtExpression callee = call.getCalleeExpression();
    if (callee instanceof org.jetbrains.kotlin.psi.KtConstructorCalleeExpression) {
      return true;
    }
    if (callee instanceof KtDotQualifiedExpression dot) {
      return dot.getSelectorExpression()
          instanceof org.jetbrains.kotlin.psi.KtConstructorCalleeExpression;
    }
    if (!(callee instanceof KtNameReferenceExpression ref)) {
      return false;
    }
    String name = ref.getReferencedName();
    if (name == null || name.isEmpty()) {
      return false;
    }
    // A capitalised name is a class in Kotlin's naming convention. The convention is not
    // a guarantee, but the PSI cannot tell a class from a function without the symbol
    // table, and the frontend resolves the name against the symbol table before
    // lowering; this is the fallback when resolution failed.
    return Character.isUpperCase(name.charAt(0));
  }

  // ------------------------------------------------------------------
  // Types
  // ------------------------------------------------------------------

  /** The declared type of a local property, or {@link TacType#UNKNOWN}. */
  private TacType declaredTypeOf(KtProperty prop) {
    org.jetbrains.kotlin.psi.KtTypeReference ref = prop.getTypeReference();
    if (ref == null) {
      return TacType.UNKNOWN;
    }
    try {
      KaType kt = session.getType(ref);
      return types.map(kt);
    } catch (RuntimeException ex) {
      return TacType.UNKNOWN;
    }
  }

  /**
   * The comparison operator of a binary expression, or {@code null}.
   *
   * <p>The operator is read from the operation reference's source text rather than from
   * the token: the Analysis API's Kotlin PSI exposes {@code getOperationToken()} as an
   * {@code IElementType}, and mapping every operator token back to its spelling would
   * duplicate the lexer's table. The text is the operator's own source, so it is exact.
   */
  private static BinaryOperator comparisonOperatorOf(KtBinaryExpression bin) {
    String op = operatorTextOf(bin);
    if (op == null) {
      return null;
    }
    switch (op) {
      case "==":
        return BinaryOperator.EQ;
      case "!=":
        return BinaryOperator.NE;
      case "<":
        return BinaryOperator.LT;
      case "<=":
        return BinaryOperator.LE;
      case ">":
        return BinaryOperator.GT;
      case ">=":
        return BinaryOperator.GE;
      default:
        return null;
    }
  }

  /**
   * The arithmetic operator of a binary expression, or {@code null}.
   *
   * @see #comparisonOperatorOf(KtBinaryExpression)
   */
  private static BinaryOperator arithmeticOperatorOf(KtBinaryExpression bin) {
    String op = operatorTextOf(bin);
    if (op == null) {
      return null;
    }
    switch (op) {
      case "+":
        return BinaryOperator.ADD;
      case "-":
        return BinaryOperator.SUB;
      case "*":
        return BinaryOperator.MUL;
      case "/":
        return BinaryOperator.DIV;
      case "%":
        return BinaryOperator.MOD;
      default:
        return null;
    }
  }

  /** The source text of a binary expression's operator, or {@code null}. */
  private static String operatorTextOf(KtBinaryExpression bin) {
    if (bin == null) {
      return null;
    }
    org.jetbrains.kotlin.psi.KtSimpleNameExpression ref = bin.getOperationReference();
    if (ref == null) {
      return null;
    }
    String text = ref.getText();
    return text == null || text.isEmpty() ? null : text;
  }

  // ------------------------------------------------------------------
  // Small helpers
  // ------------------------------------------------------------------

  /** The TAC variable an operand denotes, or {@code null} when it is a constant. */
  private static TacOperand.TacVar asVar(TacOperand operand) {
    return operand instanceof TacOperand.TacVar v ? v : null;
  }

  /**
   * A type marked statically non-null.
   *
   * <p>A primitive or an already-non-null type is returned unchanged. This is the
   * <i>suppression</i> direction only: it says "this declaration cannot hold null", and
   * never "this value cannot be null at runtime".
   */
  private static TacType nonNullOf(TacType type) {
    if (type == null || !type.isReference()) {
      return type == null ? TacType.UNKNOWN : type;
    }
    return type.nonNull();
  }

  /**
   * The condition expression of a {@code when} entry, or {@code null}.
   *
   * <p>A {@code when} entry carries a list of conditions; a subject-less {@code when} uses
   * the first one. An {@code is} pattern has no expression -- it is a type test, which the
   * TAC has no opcode for -- so it is reported as absent rather than guessed at.
   */
  private static KtExpression firstConditionOf(KtWhenEntry entry) {
    KtWhenCondition[] conditions = entry.getConditions();
    for (KtWhenCondition condition : conditions) {
      if (condition instanceof KtWhenConditionWithExpression withExpr) {
        return withExpr.getExpression();
      }
    }
    return null;
  }

  /**
   * The 1-based line of a PSI element, or -1.
   *
   * <p>The line is read from the file's {@link com.intellij.openapi.editor.Document}, which
   * is the authoritative line map. The Analysis API's bundled IntelliJ surface does not
   * expose {@code PsiFile.getLineNumber(int)}, so the document is the way in.
   */
  private static int lineOf(com.intellij.psi.PsiElement element) {
    if (element == null) {
      return -1;
    }
    com.intellij.psi.PsiFile file = element.getContainingFile();
    if (file == null) {
      return -1;
    }
    int offset = element.getTextOffset();
    if (offset < 0 || offset > file.getTextLength()) {
      return -1;
    }
    com.intellij.openapi.editor.Document document = documentOf(file);
    if (document != null) {
      try {
        return document.getLineNumber(offset) + 1;
      } catch (RuntimeException ex) {
        // Fall through to the text-based computation.
      }
    }
    return lineOfText(file.getText(), offset);
  }

  /** The 0-based column of a PSI element, or -1. */
  private static int columnOf(com.intellij.psi.PsiElement element) {
    if (element == null) {
      return -1;
    }
    com.intellij.psi.PsiFile file = element.getContainingFile();
    if (file == null) {
      return -1;
    }
    int offset = element.getTextOffset();
    if (offset < 0 || offset > file.getTextLength()) {
      return -1;
    }
    com.intellij.openapi.editor.Document document = documentOf(file);
    if (document != null) {
      try {
        int line = document.getLineNumber(offset);
        return offset - document.getLineStartOffset(line);
      } catch (RuntimeException ex) {
        // Fall through to the text-based computation.
      }
    }
    int lineStart = lastLineStartBefore(file.getText(), offset);
    return offset - lineStart;
  }

  /**
   * The document of a file, or {@code null}.
   *
   * <p>{@code getFileDocument()} is a default method that may return {@code null} when
   * the file has no document (a stub-only file, for instance).
   */
  private static com.intellij.openapi.editor.Document documentOf(com.intellij.psi.PsiFile file) {
    try {
      return file.getFileDocument();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /** The 1-based line number of an offset, computed from the text. */
  private static int lineOfText(String text, int offset) {
    if (text == null) {
      return -1;
    }
    int line = 1;
    int limit = Math.min(offset, text.length());
    for (int i = 0; i < limit; i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  /** The offset of the start of the line containing {@code offset}. */
  private static int lastLineStartBefore(String text, int offset) {
    if (text == null) {
      return 0;
    }
    int limit = Math.min(offset, text.length());
    for (int i = limit - 1; i >= 0; i--) {
      if (text.charAt(i) == '\n') {
        return i + 1;
      }
    }
    return 0;
  }

  /** The name of the implicit receiver local. */
  static final String THIS = "this";

  /**
   * Whether a symbol is compiler-generated.
   *
   * <p>A data class's {@code componentN} and {@code copy} come back from the Analysis
   * API with the origin {@link KaSymbolOrigin#SOURCE_MEMBER_GENERATED}. That is exact: it
   * is the compiler telling us, not a name-pattern guess.
   */
  static boolean isSynthetic(KaSymbolOrigin origin) {
    return origin == KaSymbolOrigin.SOURCE_MEMBER_GENERATED;
  }

  /**
   * Whether a callable is a data class's generated member.
   *
   * <p>Used as a second line of defence: a callable whose origin is
   * {@code SOURCE_MEMBER_GENERATED} is synthetic, and so is one named {@code copy} or
   * {@code componentN} on a data class, even if the origin says otherwise.
   */
  static boolean isDataClassGenerated(KaCallableSymbol callable, boolean ownerIsData) {
    if (!ownerIsData) {
      return false;
    }
    if (!(callable instanceof KaNamedSymbol named)) {
      return false;
    }
    String name = named.getName().asString();
    if ("copy".equals(name)) {
      return true;
    }
    return name.startsWith("component") && name.length() > "component".length();
  }

  /**
   * Whether the callable is a data class's generated member, by name.
   *
   * <p>Kept separate from {@link #isDataClassGenerated} so the frontend can use it when
   * the owning class's symbol is not available.
   */
  static boolean isDataClassGeneratedName(String name) {
    if (name == null) {
      return false;
    }
    if ("copy".equals(name)) {
      return true;
    }
    return name.startsWith("component")
        && name.length() > "component".length()
        && name.substring("component".length()).chars().allMatch(Character::isDigit);
  }

  /**
   * Whether the callable is a data class's generated member.
   *
   * <p>Unchecked variant, for use where the owning class's {@code isData} flag is known.
   */
  static boolean isGeneratedDataMember(KaFunctionSymbol symbol) {
    return isSynthetic(symbol.getOrigin());
  }
}
