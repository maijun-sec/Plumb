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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.kotlin.analysis.api.KaSession;
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertyGetterSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin;
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol;
import org.jetbrains.kotlin.analysis.api.types.KaType;
import org.jetbrains.kotlin.psi.KtAnnotatedExpression;
import org.jetbrains.kotlin.psi.KtArrayAccessExpression;
import org.jetbrains.kotlin.psi.KtBinaryExpression;
import org.jetbrains.kotlin.psi.KtBlockExpression;
import org.jetbrains.kotlin.psi.KtBreakExpression;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression;
import org.jetbrains.kotlin.psi.KtConstantExpression;
import org.jetbrains.kotlin.psi.KtContinueExpression;
import org.jetbrains.kotlin.psi.KtDoWhileExpression;
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtForExpression;
import org.jetbrains.kotlin.psi.KtIfExpression;
import org.jetbrains.kotlin.psi.KtIsExpression;
import org.jetbrains.kotlin.psi.KtLabeledExpression;
import org.jetbrains.kotlin.psi.KtLambdaExpression;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression;
import org.jetbrains.kotlin.psi.KtPostfixExpression;
import org.jetbrains.kotlin.psi.KtPrefixExpression;
import org.jetbrains.kotlin.psi.KtProperty;
import org.jetbrains.kotlin.psi.KtReturnExpression;
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression;
import org.jetbrains.kotlin.psi.KtSimpleNameExpression;
import org.jetbrains.kotlin.psi.KtStringTemplateEntry;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;
import org.jetbrains.kotlin.psi.KtSuperExpression;
import org.jetbrains.kotlin.psi.KtThisExpression;
import org.jetbrains.kotlin.psi.KtThrowExpression;
import org.jetbrains.kotlin.psi.KtTryExpression;
import org.jetbrains.kotlin.psi.KtValueArgument;
import org.jetbrains.kotlin.psi.KtWhenCondition;
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern;
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression;
import org.jetbrains.kotlin.psi.KtWhenEntry;
import org.jetbrains.kotlin.psi.KtWhenExpression;
import org.jetbrains.kotlin.psi.KtWhileExpression;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacGuard;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacLabelRef;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;
import org.plumb.tac.translate.TacBuilder;

/**
 * Lowers one Kotlin function body to TAC.
 *
 * <p><b>Why PSI and not the Analysis API for the body.</b> The Analysis API gives types and
 * symbols; it does not give a structured, walkable statement tree that a three-address lowering can
 * consume. The Kotlin PSI ({@code KtBlockExpression}, {@code KtIfExpression}, {@code
 * KtCallExpression}, ...) is exactly that tree, and it is what the Analysis API's own {@code
 * getPsi()} hands back. So the hybrid is: PSI for the <i>shape</i> of the body, the Analysis API
 * for the <i>meaning</i> of the types.
 *
 * <p><b>What this translator lowers.</b> The subset that the dataflow engine can act on:
 * assignments, calls, returns, branches, string templates, property reads and writes, and the
 * standard library calls that appear in security-relevant Kotlin (the {@code requireNotNull}
 * family, the string builders, the collection factories).
 *
 * <p><b>What it does not lower.</b> Coroutines, lambdas with captured state, inline classes and the
 * compiler's own lowering of {@code when} into a {@code tableswitch}. A construct that is not
 * lowered is reported as a problem rather than silently dropped -- and as a {@link
 * org.plumb.tac.ir.TacDrop}, which is what says how far the function may be trusted.
 *
 * <p><b>No new opcodes.</b> Everything here lowers into the existing instruction kinds: {@code
 * MOVE}, {@code BINOP}, {@code CALL}, {@code FIELDLOAD}, {@code FIELDSTORE}, {@code BRANCH}, {@code
 * RETURN}, {@code THROW}, {@code NULL_CHECK}. A Kotlin {@code when} becomes a branch chain; a
 * Kotlin {@code if} becomes a branch; that is structure, not information loss.
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
   * <p>Keyed by the Kotlin source name, not by a {@link TacOperand.TacVar}: the TAC's variable
   * identity is name + temp flag, and a Kotlin local's name <i>is</i> its TAC name. The value
   * carries the resolved {@link TacType} so a later refinement can be applied without changing the
   * identity.
   */
  private Map<String, TacOperand.TacVar> locals = new HashMap<>();

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
   * The labels a loop's {@code break} and {@code continue} jump to.
   *
   * <p>A loop pushes its own targets when it starts and pops them when it ends, so a {@code break}
   * inside a nested loop leaves the INNERMOST loop, and a {@code break} inside a lambda inside a
   * loop leaves the loop -- a lambda is not a loop, so it does not push anything.
   *
   * @param continueLabel the label the loop's back-edge jumps to
   * @param breakLabel the label a {@code break} jumps to
   * @param scopeId an id that is unique per loop, so two loops' labels cannot be confused
   * @param labelName the source label the loop carries, or {@code null} when it has none
   */
  private record LoopTargets(
      String continueLabel, String breakLabel, String scopeId, String labelName) {}

  /** The loops the function being lowered is inside, innermost first. */
  private Deque<LoopTargets> loops = new ArrayDeque<>();

  /**
   * The label of the statement being lowered, when it carries one.
   *
   * <p>A label is read by the statement it labels -- in Kotlin, a loop -- so the label has to
   * travel from the labelled statement to the loop below it.
   */
  private String pendingLabel;

  /**
   * The fully qualified name of the class the function being lowered belongs to.
   *
   * <p>This is the {@code ownerType} of a field access. Without it a field read says "some object's
   * {@code name} field" and nothing more, so the engine cannot tell a field of the audited class
   * from a field of a local helper object, and the cross-function field tracker buckets every field
   * under the same name.
   *
   * <p>It is the FQN rather than the short name so that a Kotlin class and a Java class with the
   * same simple name stay apart -- and so that a field written in Kotlin and a field written in
   * Java are the SAME field when they really are.
   */
  private String enclosingClassFqn;

  /** A counter that gives every loop its own scope id. */
  private int loopCounter;

  /**
   * The functions the lambdas in the function being lowered produced.
   *
   * <p>A lambda is a FUNCTION, not an expression: its body is a scope of its own, and a sink inside
   * it is a finding in that function. The front end adds these to the program next to the function
   * they were found in.
   */
  private final List<TacFunction> nestedFunctions = new ArrayList<>();

  /** The functions the lambdas in the function being lowered produced. */
  List<TacFunction> nestedFunctions() {
    return List.copyOf(nestedFunctions);
  }

  /** A counter that gives every lambda in the function being lowered a distinct name. */
  private int lambdaCounter;

  /**
   * A counter that gives every expanded scope its own suffix.
   *
   * <p>The suffix is what keeps two locals with the same source name apart. The TAC's variable
   * identity is the NAME, so an expanded inline body's {@code x} and the caller's {@code x} would
   * be one variable without it -- and the caller's value would be overwritten by the callee's.
   */
  private int expansionCounter;

  /**
   * The suffix the CURRENT scope gives to the locals it declares.
   *
   * <p>Empty in the function's own scope. An expanded inline body and an inlined lambda each push
   * their own suffix, so a name declared in one of them can never collide with a name declared in
   * another scope of the same TAC function.
   */
  private String scopeSuffix = "";

  /**
   * The inline expansions being lowered, innermost last.
   *
   * <p>An expansion is what makes a call to an inline function a copy of its body rather than a
   * call. While one is in progress, a call to one of its lambda parameters is not a call at all:
   * the lambda's body is inlined where it is called.
   */
  private final Deque<InlineExpansion> expansions = new ArrayDeque<>();

  /**
   * Where a {@code return} in the scope being lowered goes, innermost last.
   *
   * <p>The bottom entry is the function's own scope: a {@code return} there leaves the TAC
   * function. An entry above it belongs to an expanded inline body, and a {@code return} there ends
   * the expansion: it assigns the expansion's result and jumps past the body.
   */
  private final Deque<ReturnTarget> returnTargets = new ArrayDeque<>();

  /**
   * Where a {@code return} in the scope being lowered goes.
   *
   * @param resultVar the variable that receives the returned value, or {@code null} when the return
   *     leaves the TAC function
   * @param endLabel the label the return jumps to, or {@code null} when it leaves the function
   */
  private record ReturnTarget(TacOperand.TacVar resultVar, String endLabel) {}

  /**
   * One inline expansion in progress.
   *
   * @param callee the expanded function's name
   * @param lambdas the lambda arguments, by the callee's parameter name
   * @param callerLocals the caller's locals, for the lambdas' free variables
   * @param callerSuffix the caller's scope suffix
   * @param callerLoops the caller's loops, for a {@code break} inside an inlined lambda
   * @param callerReturnTarget where a {@code return} inside an inlined lambda goes
   */
  private record InlineExpansion(
      String callee,
      Map<String, KtLambdaExpression> lambdas,
      Map<String, TacOperand.TacVar> callerLocals,
      String callerSuffix,
      Deque<LoopTargets> callerLoops,
      ReturnTarget callerReturnTarget) {}

  /**
   * The functions the file being translated declares, by name.
   *
   * <p><b>WHY THE DECLARATIONS ARE HANDED IN RATHER THAN RESOLVED.</b> A call to a function
   * declared in the file being analysed does not resolve through the Analysis API session this
   * frontend runs in: the resolver sees the library, not the file. So the callee of a call cannot
   * be found by resolving it -- it has to be found by looking at the file's own declarations, which
   * the frontend already walks.
   *
   * <p>A top-level function is keyed by its name; a class member by {@code Owner.name}, so a member
   * of the class being lowered is told apart from a top-level function of the same name.
   */
  private Map<String, List<KtNamedFunction>> sourceDeclarations = Map.of();

  /**
   * Sets the functions the file declares.
   *
   * @param declarations the declarations, by name (see {@link #sourceDeclarations})
   */
  void setSourceDeclarations(Map<String, List<KtNamedFunction>> declarations) {
    this.sourceDeclarations = declarations == null ? Map.of() : declarations;
  }

  /** The class the function being lowered belongs to, by short name. */
  private String currentEnclosingClass;

  /** The module the function being lowered belongs to. */
  private String currentModule;

  /** The annotations of the class the function being lowered belongs to. */
  private List<String> currentClassAnnotations = List.of();

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
      String kind, String functionName, String nodeClass, String message, int line, int column) {
    problems.add(message);
    structuredProblems.add(
        new org.plumb.tac.ir.TacProgram.FunctionProblem(
            functionName, kind, message, nodeClass, line, column));
  }

  /**
   * Records a construct that could not be lowered, in the machine-readable channel.
   *
   * <p>A problem is a sentence for a human; a DROP is the receipt the engine reads. A front end
   * that degrades a construct and leaves only the sentence produces a function whose findings look
   * exactly as complete as a fully lowered one's -- which is the failure mode the drop channel
   * exists to prevent.
   *
   * <p><b>The impact says WHAT was lost, and it decides the function's precision.</b> The engine
   * derives the precision from the drops ({@link org.plumb.tac.ir.TacFunction#derivePrecision}), so
   * a drop that claims the wrong grade makes the precision a lie in the same direction the loss is.
   * A construct whose effect is gone is {@link org.plumb.tac.ir.TacDrop.Impact#SEMANTICS}; one that
   * only lost its shape is {@link org.plumb.tac.ir.TacDrop.Impact#STRUCTURE}; one that only lost a
   * single value is {@link org.plumb.tac.ir.TacDrop.Impact#VALUE}.
   */
  private void reportDrop(
      org.plumb.tac.ir.TacDrop.Impact impact,
      String construct,
      String detail,
      int line,
      int column) {
    if (builder == null) {
      return;
    }
    builder.reportDrop(construct, detail, impact, line, column);
  }

  /** The type mapper, exposed so the frontend can reuse it for symbol-level mapping. */
  KotlinTypeMapper types() {
    return types;
  }

  /**
   * Sets the fully qualified name of the class the function being lowered belongs to.
   *
   * <p>It becomes the {@code ownerType} of every field access in that function. The front end knows
   * it from the class declaration's symbol; the translator does not, because the class declaration
   * is not part of the function's body.
   *
   * @param fqn the class's fully qualified name, or {@code null} when there is none
   */
  void setEnclosingClassFqn(String fqn) {
    this.enclosingClassFqn = fqn;
  }

  /**
   * The {@code ownerType} a field access in the function being lowered carries.
   *
   * @return the owning class's FQN, or {@code null} when the front end did not resolve one
   */
  private String ownerType() {
    return enclosingClassFqn;
  }

  /**
   * The TAC name a local declared in the CURRENT scope gets.
   *
   * <p>The name is the source name with the current scope's suffix. The suffix is what keeps two
   * locals with the same source name apart: the TAC's variable identity is the NAME, so an expanded
   * inline body's {@code x} and the caller's {@code x} would be one storage location without it --
   * and the caller's value would be overwritten by the callee's.
   *
   * <p>In the function's own scope the suffix is empty, so the name is the source name and nothing
   * changes for ordinary code.
   *
   * @param sourceName the name the source gives the local
   * @return the name the local gets in the TAC
   */
  private String tacName(String sourceName) {
    return scopeSuffix + sourceName;
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
    return translate(
        name,
        signature,
        returnType,
        params,
        body,
        enclosingClass,
        synthetic,
        module,
        List.of(),
        List.of(),
        Map.of(),
        null);
  }

  /**
   * Translate one function, with the annotations it carries and the kind of member it is.
   *
   * <p>The annotations are not decoration. A rule that keys on {@code @RequestParam} or
   * {@code @Transactional} needs the name, and the name is on the declaration -- not on the
   * function's body, and not on the symbol the analysis API resolves.
   *
   * @param classAnnotations the annotations of the declaring class
   * @param methodAnnotations the annotations of the function itself
   * @param paramAnnotations the annotations of the parameters, by name
   * @param memberKind what kind of member this function is
   */
  TacFunction translate(
      String name,
      String signature,
      TacType returnType,
      List<TacOperand.TacVar> params,
      KtExpression body,
      String enclosingClass,
      boolean synthetic,
      String module,
      List<String> classAnnotations,
      List<String> methodAnnotations,
      Map<String, List<String>> paramAnnotations,
      org.plumb.tac.ir.TacMemberKind memberKind) {

    this.currentFunction = name;
    this.locals.clear();
    this.problems.clear();
    this.structuredProblems.clear();
    // The scopes of the PREVIOUS function do not belong to this one: an expansion in one
    // function must not leak its suffix, its lambdas or its return target into the next.
    this.scopeSuffix = "";
    this.expansions.clear();
    this.returnTargets.clear();
    this.returnTargets.push(new ReturnTarget(null, null));
    this.currentEnclosingClass = enclosingClass;
    this.currentModule = module;
    this.currentClassAnnotations =
        classAnnotations == null ? List.of() : List.copyOf(classAnnotations);

    TacBuilder.FunctionSpec spec = new TacBuilder.FunctionSpec(name, signature, returnType);
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
    if (classAnnotations != null && !classAnnotations.isEmpty()) {
      b.addClassAnnotations(classAnnotations);
    }
    if (methodAnnotations != null && !methodAnnotations.isEmpty()) {
      b.addMethodAnnotations(methodAnnotations);
    }
    if (paramAnnotations != null) {
      for (Map.Entry<String, List<String>> entry : paramAnnotations.entrySet()) {
        b.addParamAnnotations(entry.getKey(), entry.getValue());
      }
    }
    if (memberKind != null) {
      b.setMemberKind(memberKind);
    }
    this.builder = b;

    // Register the parameters as locals so a body reference to them resolves to the
    // parameter's TAC variable rather than to a fresh temp.
    for (TacOperand.TacVar p : params) {
      this.locals.put(p.name(), p);
      b.addLocal(p);
    }

    // Register the implicit receiver.
    //
    // WHY THIS MATTERS. A member function's body reads and writes the class's own
    // properties. Those reads are FIELDLOADs off `this` -- and the receiver is
    // `this`. The local named "this" was NEVER registered, so every property
    // read fell into the "no receiver available" branch and returned a fresh
    // unknown temp: the read produced NO instruction at all. A value that came
    // from a field therefore never reached the field, and the field's taint
    // stopped at the property access.
    //
    // A top-level function has no receiver, so it gets none: a property of the
    // enclosing file is a different thing, and pretending it has a `this` would
    // invent a receiver that does not exist.
    if (enclosingClass != null && !enclosingClass.isEmpty()) {
      TacOperand.TacVar self = new TacOperand.TacVar(THIS, TacType.UNKNOWN, false);
      this.locals.put(THIS, self);
      b.addLocal(self);
    }

    if (body == null) {
      // An abstract function, or a function whose body the compiler generated. There is
      // nothing to lower.
      //
      // The loss is RECORDED: a function with no body at all is worse than one whose
      // body was lowered with a gap in it, and a gap that leaves no receipt is a gap
      // the engine cannot see.
      reportDrop(
          org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
          "kotlin.body.absent",
          "the function has no body to lower: it is abstract, or the compiler generated it",
          -1,
          -1);
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
      reportDrop(
          org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
          "kotlin.translation.failed",
          "the lowering failed: " + ex.getClass().getSimpleName(),
          -1,
          -1);
    }

    // The precision is NOT stated here: it is derived from the drops (the builder
    // combines the two), and a front end that states it as well can only talk the
    // loss up.
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
    if (stmt instanceof KtWhileExpression whileExpr) {
      translateWhile(whileExpr);
      return;
    }
    if (stmt instanceof KtDoWhileExpression doWhile) {
      translateDoWhile(doWhile);
      return;
    }
    if (stmt instanceof KtForExpression forExpr) {
      translateFor(forExpr);
      return;
    }
    if (stmt instanceof KtBreakExpression brk) {
      translateBreak(brk);
      return;
    }
    if (stmt instanceof KtContinueExpression cont) {
      translateContinue(cont);
      return;
    }
    if (stmt instanceof KtThrowExpression thr) {
      translateThrow(thr);
      return;
    }
    if (stmt instanceof KtTryExpression tryExpr) {
      translateTry(tryExpr);
      return;
    }
    if (stmt instanceof KtLabeledExpression labeled) {
      translateLabeled(labeled);
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
      // An assignment used as a statement WRITES; a comparison or an arithmetic
      // expression does not. The assignment has to go through the expression
      // lowering, because that is where the store is emitted -- lowering only
      // the operands (which is what this branch used to do) computes the
      // right-hand side and throws the result away, so the write never happened.
      if (isAssignmentOperator(operatorTextOf(bin))) {
        translateExpression(bin);
        return;
      }
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
    if (stmt instanceof KtPostfixExpression postfix) {
      // "n++" as a statement. The statement's VALUE is discarded, but the variable
      // still changes -- so the expression lowering is the right one: it emits the
      // UNARYOP and the write-back.
      translateExpression(postfix);
      return;
    }
    if (stmt instanceof KtPrefixExpression prefix) {
      // "++n" as a statement. Same reasoning as the postfix form.
      translateExpression(prefix);
      return;
    }
    if (stmt instanceof KtWhenExpression when) {
      translateWhen(when);
      return;
    }
    // Anything else is not lowered. Report it rather than dropping it silently.
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
    reportDrop(
        org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
        "kotlin.statement.unmodelled",
        "the statement was not lowered: " + stmt.getClass().getSimpleName(),
        lineOf(stmt),
        columnOf(stmt));
  }

  /**
   * Lowers a local property declaration.
   *
   * <p>A {@code val} is a fresh binding; a {@code var} may be reassigned later. Both are registered
   * under their source name so later references resolve to the same TAC variable -- which is what
   * makes taint flow through the local.
   *
   * <p>The variable's type is the <b>declared</b> type when there is one, and the <b>inferred</b>
   * type of the initializer when there is not. The distinction matters: {@code val x: String =
   * maybeNull()} declares a non-null {@code String}, while {@code val x = maybeNull()} infers
   * whatever the initializer's type is -- which, for a Java method seen from Kotlin, is a
   * <i>platform</i> type that may hold null.
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
      TacOperand.TacVar v = new TacOperand.TacVar(tacName(name), TacType.UNKNOWN, false);
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
    TacOperand.TacVar v = new TacOperand.TacVar(tacName(name), declared, false);
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

  // ------------------------------------------------------------------
  // Loops, jumps and exceptions
  // ------------------------------------------------------------------

  /**
   * Lowers a {@code while} loop.
   *
   * <p>The shape is the standard one: a condition label, a branch to the body or past it, the body,
   * and a jump back to the condition.
   *
   * <p><b>WHY THE LOOP MATTERS.</b> A loop body is where a tainted value is usually read, sanitised
   * and written. Before this, a {@code while} statement fell into the catch-all branch of the
   * statement lowering: the body produced NO instructions at all, so every taint inside it was
   * invisible to the analysis -- a silent false negative on one of the most common shapes a bug
   * takes in Kotlin.
   */
  private void translateWhile(KtWhileExpression loop) {
    String condLabel = builder.newLabel("whileCond");
    String bodyLabel = builder.newLabel("whileBody");
    String endLabel = builder.newLabel("whileEnd");
    String scopeId = nextLoopScope();
    String label = pendingLabel;

    emitLabel(condLabel, TacLabelRef.Kind.LOOP_HEAD, scopeId);
    TacOperand cond = translateCondition(loop.getCondition());
    if (cond == null) {
      cond = unknownCondition(loop);
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(cond)
            .trueLabel(bodyLabel)
            .falseLabel(endLabel)
            .guard(TacGuard.truthiness(cond))
            .labelRef(new TacLabelRef(bodyLabel, TacLabelRef.Kind.LOOP_HEAD, scopeId))
            .line(lineOf(loop))
            .column(columnOf(loop))
            .build());
    emitLabel(bodyLabel, TacLabelRef.Kind.LOOP_HEAD, scopeId);

    loops.push(new LoopTargets(condLabel, endLabel, scopeId, label));
    try {
      translateStatement(loop.getBody());
    } finally {
      loops.pop();
    }

    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.JUMP)
            .label(condLabel)
            .labelRef(new TacLabelRef(condLabel, TacLabelRef.Kind.LOOP_HEAD, scopeId))
            .line(lineOf(loop))
            .build());
    emitLabel(endLabel, TacLabelRef.Kind.LOOP_END, scopeId);
  }

  /**
   * Lowers a {@code do-while} loop.
   *
   * <p>The body runs BEFORE the first test, which is what "do-while" means. The {@code continue}
   * target is therefore the CONDITION, not the body: a {@code continue} in a do-while goes back to
   * the test, not to the top of the body.
   */
  private void translateDoWhile(KtDoWhileExpression loop) {
    String bodyLabel = builder.newLabel("doBody");
    String condLabel = builder.newLabel("doCond");
    String endLabel = builder.newLabel("doEnd");
    String scopeId = nextLoopScope();
    String label = pendingLabel;

    emitLabel(bodyLabel, TacLabelRef.Kind.LOOP_HEAD, scopeId);
    loops.push(new LoopTargets(condLabel, endLabel, scopeId, label));
    try {
      translateStatement(loop.getBody());
    } finally {
      loops.pop();
    }

    emitLabel(condLabel, TacLabelRef.Kind.LOOP_HEAD, scopeId);
    TacOperand cond = translateCondition(loop.getCondition());
    if (cond == null) {
      cond = unknownCondition(loop);
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(cond)
            .trueLabel(bodyLabel)
            .falseLabel(endLabel)
            .guard(TacGuard.truthiness(cond))
            .labelRef(new TacLabelRef(bodyLabel, TacLabelRef.Kind.LOOP_HEAD, scopeId))
            .line(lineOf(loop))
            .column(columnOf(loop))
            .build());
    emitLabel(endLabel, TacLabelRef.Kind.LOOP_END, scopeId);
  }

  /**
   * Lowers a {@code for} loop.
   *
   * <p>The loop is lowered through the ITERATION PROTOCOL: the collection is asked for an iterator,
   * the loop asks the iterator whether it has another element, and the element comes from the
   * iterator. The protocol method names are the REAL ones -- {@code java.lang.Iterable.iterator},
   * {@code java.util.Iterator.hasNext}, {@code java.util.Iterator.next} -- because the shared
   * semantics table is keyed on them, and a synthetic short name would reach no summary at all.
   *
   * <p>Lowering the {@code for} as a call to the collection's own {@code forEach} would have been
   * shorter, but the body would then live inside a call the engine cannot see into: the taint
   * inside the body would not flow.
   */
  private void translateFor(KtForExpression loop) {
    KtExpression collectionExpr = loop.getLoopRange();
    if (collectionExpr == null) {
      report(
          "UNSUPPORTED_CONSTRUCT",
          currentFunction,
          "KtForExpression",
          "a for loop without a collection in " + currentFunction + " (line " + lineOf(loop) + ")",
          lineOf(loop),
          columnOf(loop));
      reportDrop(
          org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
          "kotlin.for.unmodelled",
          "the for loop was not lowered: it has no collection expression",
          lineOf(loop),
          columnOf(loop));
      return;
    }

    String condLabel = builder.newLabel("forCond");
    String bodyLabel = builder.newLabel("forBody");
    String endLabel = builder.newLabel("forEnd");
    String scopeId = nextLoopScope();
    String label = pendingLabel;

    TacOperand collection = translateExpression(collectionExpr);
    if (collection == null) {
      collection = TacOperand.Const.ofNull();
    }
    TacOperand iterator = builder.newTemp(TacType.UNKNOWN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(iterator)
            .calleeSig("java.lang.Iterable.iterator/0")
            .args(List.of(collection))
            .line(lineOf(loop))
            .build());

    // The loop variable. It is registered BEFORE the body is lowered, so a reference to it
    // inside the body resolves to the same TAC variable the iterator's element is moved into.
    org.jetbrains.kotlin.psi.KtParameter parameter = loop.getLoopParameter();
    TacOperand.TacVar variable = null;
    if (parameter != null && parameter.getName() != null && !parameter.getName().isEmpty()) {
      TacType elementType = declaredTypeOf(parameter.getTypeReference());
      variable = new TacOperand.TacVar(tacName(parameter.getName()), elementType, false);
      locals.put(parameter.getName(), variable);
      builder.addLocal(variable);
    }

    emitLabel(condLabel, TacLabelRef.Kind.LOOP_HEAD, scopeId);
    TacOperand hasNext = builder.newTemp(TacType.BOOLEAN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(hasNext)
            .calleeSig("java.util.Iterator.hasNext/0")
            .args(List.of(iterator))
            .line(lineOf(loop))
            .build());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(hasNext)
            .trueLabel(bodyLabel)
            .falseLabel(endLabel)
            .guard(TacGuard.truthiness(hasNext))
            .labelRef(new TacLabelRef(bodyLabel, TacLabelRef.Kind.LOOP_HEAD, scopeId))
            .line(lineOf(loop))
            .column(columnOf(loop))
            .build());
    emitLabel(bodyLabel, TacLabelRef.Kind.LOOP_HEAD, scopeId);

    TacOperand next = builder.newTemp(TacType.UNKNOWN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(next)
            .calleeSig("java.util.Iterator.next/0")
            .args(List.of(iterator))
            .line(lineOf(loop))
            .build());
    if (variable != null) {
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.MOVE)
              .dest(variable)
              .lhs1(next)
              .line(lineOf(loop))
              .build());
    }

    loops.push(new LoopTargets(condLabel, endLabel, scopeId, label));
    try {
      translateStatement(loop.getBody());
    } finally {
      loops.pop();
    }

    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.JUMP)
            .label(condLabel)
            .labelRef(new TacLabelRef(condLabel, TacLabelRef.Kind.LOOP_HEAD, scopeId))
            .line(lineOf(loop))
            .build());
    emitLabel(endLabel, TacLabelRef.Kind.LOOP_END, scopeId);
  }

  /** Lowers a {@code break}. */
  private void translateBreak(KtBreakExpression brk) {
    LoopTargets targets = loopTargetsFor(brk.getLabelName());
    if (targets == null) {
      // A `break` with no loop around it does not compile in Kotlin, so this is source the
      // compiler would reject. There is no loop to leave, and inventing one would put a
      // jump into the TAC that the source does not have.
      report(
          "UNSUPPORTED_CONSTRUCT",
          currentFunction,
          "KtBreakExpression",
          "a break outside a loop in " + currentFunction + " (line " + lineOf(brk) + ")",
          lineOf(brk),
          columnOf(brk));
      reportDrop(
          org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
          "kotlin.break.unmodelled",
          "the break was not lowered: there is no loop to leave",
          lineOf(brk),
          columnOf(brk));
      return;
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.JUMP)
            .label(targets.breakLabel())
            .labelRef(
                new TacLabelRef(targets.breakLabel(), TacLabelRef.Kind.LOOP_END, targets.scopeId()))
            .line(lineOf(brk))
            .column(columnOf(brk))
            .build());
  }

  /** Lowers a {@code continue}. */
  private void translateContinue(KtContinueExpression cont) {
    LoopTargets targets = loopTargetsFor(cont.getLabelName());
    if (targets == null) {
      report(
          "UNSUPPORTED_CONSTRUCT",
          currentFunction,
          "KtContinueExpression",
          "a continue outside a loop in " + currentFunction + " (line " + lineOf(cont) + ")",
          lineOf(cont),
          columnOf(cont));
      reportDrop(
          org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
          "kotlin.continue.unmodelled",
          "the continue was not lowered: there is no loop to continue",
          lineOf(cont),
          columnOf(cont));
      return;
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.JUMP)
            .label(targets.continueLabel())
            .labelRef(
                new TacLabelRef(
                    targets.continueLabel(), TacLabelRef.Kind.LOOP_HEAD, targets.scopeId()))
            .line(lineOf(cont))
            .column(columnOf(cont))
            .build());
  }

  /**
   * Lowers a {@code throw}.
   *
   * <p>The thrown value is the instruction's operand; the THROW itself terminates the path, which
   * is what the CFG builder reads.
   */
  private void translateThrow(KtThrowExpression thr) {
    TacOperand value = translateExpression(thr.getThrownExpression());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.THROW)
            .lhs1(value)
            .line(lineOf(thr))
            .column(columnOf(thr))
            .build());
  }

  /**
   * Lowers a {@code try} / {@code catch} / {@code finally}.
   *
   * <p>The shape is the one the Java front end produces and the CFG builder understands: the try
   * body, then one {@code CATCH_HANDLER} per catch clause (each preceded by a {@code
   * L_CATCH_<Type>} label), then the finally block behind a {@code FINALLY_ENTER} marker. The
   * region is registered on the function, and the CFG builder wires the exception edges from the
   * try body to the handlers.
   */
  private void translateTry(KtTryExpression tryExpr) {
    int tryStart = builder.instructions().size();
    KtBlockExpression tryBlock = tryExpr.getTryBlock();
    if (tryBlock != null) {
      translateBlock(tryBlock);
    }
    int tryEnd = builder.instructions().size() - 1;

    List<String> catchLabels = new ArrayList<>();
    int firstCatchStart = -1;
    int lastCatchEnd = -1;
    for (org.jetbrains.kotlin.psi.KtCatchClause clause : tryExpr.getCatchClauses()) {
      org.jetbrains.kotlin.psi.KtParameter parameter = clause.getCatchParameter();
      TacType catchType = TacType.UNKNOWN;
      String catchTypeName = "";
      if (parameter != null) {
        catchType = declaredTypeOf(parameter.getTypeReference());
        catchTypeName = simpleNameOf(catchType);
      }
      String labelName =
          catchTypeName.isEmpty()
              ? builder.newLabel("L_CATCH")
              : builder.newLabel("L_CATCH_" + catchTypeName);
      catchLabels.add(labelName);
      if (firstCatchStart < 0) {
        firstCatchStart = builder.instructions().size();
      }
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.LABEL)
              .label(labelName)
              .labelRef(new TacLabelRef(labelName, TacLabelRef.Kind.CATCH, null))
              .line(lineOf(clause))
              .build());
      if (parameter != null && parameter.getName() != null && !parameter.getName().isEmpty()) {
        TacOperand.TacVar caught =
            new TacOperand.TacVar(tacName(parameter.getName()), catchType, false);
        locals.put(parameter.getName(), caught);
        builder.addLocal(caught);
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.CATCH_HANDLER)
                .dest(caught)
                .ctorType(catchType)
                .line(lineOf(clause))
                .build());
      } else {
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.CATCH_HANDLER)
                .ctorType(catchType)
                .line(lineOf(clause))
                .build());
      }
      KtExpression catchBody = clause.getCatchBody();
      if (catchBody instanceof KtBlockExpression catchBlock) {
        translateBlock(catchBlock);
      } else if (catchBody != null) {
        translateStatement(catchBody);
      }
      lastCatchEnd = builder.instructions().size() - 1;
    }

    String finallyLabel = null;
    int finallyStart = -1;
    int finallyEnd = -1;
    org.jetbrains.kotlin.psi.KtFinallySection finallySection = tryExpr.getFinallyBlock();
    if (finallySection != null) {
      finallyLabel = builder.newLabel("L_FINALLY");
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.LABEL)
              .label(finallyLabel)
              .labelRef(new TacLabelRef(finallyLabel, TacLabelRef.Kind.FINALLY_ENTRY, null))
              .build());
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.FINALLY_ENTER).label(finallyLabel).build());
      finallyStart = builder.instructions().size();
      KtExpression finalExpression = finallySection.getFinalExpression();
      if (finalExpression instanceof KtBlockExpression finalBlock) {
        translateBlock(finalBlock);
      } else if (finalExpression != null) {
        translateStatement(finalExpression);
      }
      finallyEnd = builder.instructions().size() - 1;
    }

    if (tryStart <= tryEnd) {
      builder.recordTryRegion(
          new TacFunction.TryRegion(
              tryStart,
              tryEnd,
              firstCatchStart,
              lastCatchEnd,
              catchLabels,
              finallyLabel,
              finallyStart,
              finallyEnd));
    }
  }

  /**
   * Lowers a labelled statement.
   *
   * <p>The label itself does not change the shape of the control flow; it NAMES a place a {@code
   * break@label} or {@code continue@label} can reach. The label is therefore handed to the
   * statement it labels -- which, in Kotlin, is a loop -- and the body is lowered normally.
   */
  private void translateLabeled(KtLabeledExpression labeled) {
    String previous = pendingLabel;
    pendingLabel = labeled.getLabelName();
    try {
      translateStatement(labeled.getBaseExpression());
    } finally {
      pendingLabel = previous;
    }
  }

  /**
   * The loop a {@code break} or {@code continue} leaves.
   *
   * <p>Without a label, that is the innermost loop. With one, it is the loop that carries that
   * label -- which may be further out, and is the whole point of a labelled jump.
   *
   * @param label the label's name, or {@code null}
   * @return the loop's targets, or {@code null} when there is no such loop
   */
  private LoopTargets loopTargetsFor(String label) {
    if (label == null || label.isEmpty()) {
      return loops.peek();
    }
    for (LoopTargets targets : loops) {
      if (label.equals(targets.labelName())) {
        return targets;
      }
    }
    return null;
  }

  /** A scope id that is unique to one loop. */
  private String nextLoopScope() {
    return "loop" + (++loopCounter);
  }

  /** Emits a label that says what KIND of place it names. */
  private void emitLabel(String label, TacLabelRef.Kind kind, String scopeId) {
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL)
            .label(label)
            .labelRef(new TacLabelRef(label, kind, scopeId))
            .build());
  }

  /**
   * A condition the front end could not resolve.
   *
   * <p>The branch has to be well-formed, and it must not be a claim the source does not make: "the
   * condition is false" would say the arm never runs, and "the condition is true" would say it
   * always does. An unknown value says neither -- the engine knows nothing about it, and both arms
   * stay reachable.
   */
  private TacOperand unknownCondition(KtExpression condition) {
    TacOperand dest = builder.newTemp(TacType.UNKNOWN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.HAVOC)
            .dest(dest)
            .line(lineOf(condition))
            .build());
    return dest;
  }

  /** The simple name of a type, for a label. */
  private static String simpleNameOf(TacType type) {
    if (type == null) {
      return "";
    }
    String s = type.displayName();
    if (s == null) {
      return "";
    }
    int lt = s.indexOf('<');
    if (lt >= 0) {
      s = s.substring(0, lt);
    }
    int dot = s.lastIndexOf('.');
    if (dot >= 0) {
      s = s.substring(dot + 1);
    }
    return s;
  }

  /**
   * Lowers a lambda into a synthetic function and a call to it.
   *
   * <p>A lambda is a FUNCTION, not an expression: its body is a scope of its own, and a sink inside
   * it is a finding in that function. Lowering the body inline would lose the boundary, and
   * dropping the lambda would lose the body -- and with it every taint that reaches a sink inside
   * the lambda.
   *
   * <p><b>WHERE THE CAPTURED VALUES GO.</b> The engine binds a call's arguments to the callee's
   * parameters from {@code args[1]} onwards; {@code args[0]} is the receiver slot and the engine
   * never binds it to the callee. A value the lambda reads from the enclosing scope therefore has
   * to travel as a LEADING PARAMETER: the call site passes it as the first real argument, and the
   * engine binds it to the lambda's first parameter. Without that, the body reads a variable that
   * no parameter holds, and the taint stops at the closure's boundary.
   *
   * <p>The captured values come first in the parameter list, and the closure's own parameters come
   * after them, because the call site passes them in that order.
   */
  private TacOperand translateLambda(KtLambdaExpression lambda) {
    List<org.jetbrains.kotlin.psi.KtParameter> declared = lambda.getValueParameters();
    List<String> captures = discoverCaptures(lambda, declared);

    List<TacOperand.TacVar> params = new ArrayList<>();
    List<String> paramTypes = new ArrayList<>();
    for (String capture : captures) {
      TacOperand.TacVar enclosing = locals.get(capture);
      TacType type = enclosing == null ? TacType.UNKNOWN : enclosing.type();
      TacOperand.TacVar param = new TacOperand.TacVar(capture, type, false);
      params.add(param);
      paramTypes.add(type.displayName());
    }
    for (org.jetbrains.kotlin.psi.KtParameter parameter : declared) {
      String name = parameter.getName();
      if (name == null || name.isEmpty()) {
        name = "arg" + params.size();
      }
      TacType type = declaredTypeOf(parameter.getTypeReference());
      params.add(new TacOperand.TacVar(name, type, false));
      paramTypes.add(type.displayName());
    }

    String name = currentFunction + "$L$" + lambdaCounter++;
    TacType returnType = expressionTypeOf(lambda);
    String signature = name + "(" + String.join(",", paramTypes) + "):" + returnType.displayName();

    // The body is lowered into a builder of its own.
    TacBuilder outerBuilder = builder;
    Map<String, TacOperand.TacVar> outerLocals = locals;
    List<String> outerProblems = new ArrayList<>(problems);
    List<org.plumb.tac.ir.TacProgram.FunctionProblem> outerStructured =
        new ArrayList<>(structuredProblems);
    // A lambda is a function of its OWN, so its locals live in that function and cannot
    // collide with the enclosing scope's. The scope suffix is therefore dropped here: it
    // belongs to the scope the lambda was written in, not to the lambda.
    String outerSuffix = scopeSuffix;

    TacBuilder nested =
        new TacBuilder(new TacBuilder.FunctionSpec(name, signature, returnType), params);
    nested.setSourceLanguage(SourceLanguage.KOTLIN);
    nested.setIrPrecision(org.plumb.tac.ir.IrPrecision.SOURCE_FULL);
    if (currentEnclosingClass != null) {
      nested.setEnclosingClass(currentEnclosingClass);
    }
    if (currentModule != null) {
      nested.setModule(currentModule);
    }
    if (!currentClassAnnotations.isEmpty()) {
      nested.addClassAnnotations(currentClassAnnotations);
    }
    builder = nested;
    locals = new HashMap<>();
    scopeSuffix = "";
    for (TacOperand.TacVar p : params) {
      locals.put(p.name(), p);
      nested.addLocal(p);
    }
    try {
      KtExpression lambdaBody = lambda.getBodyExpression();
      if (lambdaBody instanceof KtBlockExpression lambdaBlock) {
        // The last statement of the body is the lambda's VALUE. It is lowered as an
        // EXPRESSION, so the value is computed rather than discarded: a lambda whose
        // body is "{ prefix -> prefix + input }" used to lower to an empty function,
        // because a statement's operands are lowered for their side effects and the
        // arithmetic between them was thrown away.
        List<KtExpression> stmts = lambdaBlock.getStatements();
        for (int i = 0; i < stmts.size(); i++) {
          boolean last = i == stmts.size() - 1;
          if (last && !(stmts.get(i) instanceof KtReturnExpression)) {
            translateExpression(stmts.get(i));
          } else {
            translateStatement(stmts.get(i));
          }
        }
      } else if (lambdaBody != null) {
        translateExpression(lambdaBody);
      }
    } finally {
      TacFunction function = nested.build();
      builder = outerBuilder;
      locals = outerLocals;
      scopeSuffix = outerSuffix;
      problems.clear();
      problems.addAll(outerProblems);
      structuredProblems.clear();
      structuredProblems.addAll(outerStructured);
      nestedFunctions.add(function);
    }

    // The call site. The receiver slot holds nothing -- a lambda has no receiver -- and the
    // closure's own parameters are not bound at the point the closure is CREATED: they are
    // bound where it is CALLED, and that call is not modelled here.
    List<TacOperand> args = new ArrayList<>();
    args.add(TacOperand.Const.ofNull());
    for (String capture : captures) {
      TacOperand value = locals.get(capture);
      args.add(value == null ? TacOperand.Const.ofNull() : value);
    }
    for (int i = 0; i < declared.size(); i++) {
      args.add(TacOperand.Const.ofNull());
    }
    TacOperand dest = builder.newTemp(TacType.UNKNOWN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(dest)
            .calleeSig(name + "/" + params.size())
            .args(args)
            .line(lineOf(lambda))
            .column(columnOf(lambda))
            .build());
    return dest;
  }

  /**
   * The names the lambda's body reads from the enclosing scope.
   *
   * <p>The names come from the body's own text: a name that the enclosing scope declares and that
   * the body mentions is a capture. The test is deliberately generous -- it can name a capture that
   * the body does not really use, and a capture it names twice is named once. Both errors are in
   * the safe direction: an extra capture passes a value that may not be needed, and a missing one
   * would lose taint.
   *
   * @param lambda the lambda
   * @param declared the lambda's own parameters, which are not captures
   * @return the captured names, in the order they were found
   */
  private List<String> discoverCaptures(
      KtLambdaExpression lambda, List<org.jetbrains.kotlin.psi.KtParameter> declared) {
    List<String> captures = new ArrayList<>();
    KtBlockExpression body = lambda.getBodyExpression();
    if (body == null) {
      return captures;
    }
    String text = body.getText();
    if (text == null || text.isEmpty()) {
      return captures;
    }
    for (Map.Entry<String, TacOperand.TacVar> entry : locals.entrySet()) {
      String name = entry.getKey();
      if (name == null || name.isEmpty() || THIS.equals(name)) {
        continue;
      }
      boolean declaredHere = false;
      for (org.jetbrains.kotlin.psi.KtParameter parameter : declared) {
        if (name.equals(parameter.getName())) {
          declaredHere = true;
          break;
        }
      }
      if (declaredHere) {
        continue;
      }
      if (mentions(text, name) && !captures.contains(name)) {
        captures.add(name);
      }
    }
    return captures;
  }

  /**
   * Whether a text mentions a name as a whole word.
   *
   * <p>The word boundary matters: a local named {@code id} must not match inside {@code width}.
   */
  private static boolean mentions(String text, String name) {
    int from = 0;
    while (true) {
      int at = text.indexOf(name, from);
      if (at < 0) {
        return false;
      }
      boolean startOk = at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1));
      int end = at + name.length();
      boolean endOk = end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
      if (startOk && endOk) {
        return true;
      }
      from = at + 1;
    }
  }

  // ------------------------------------------------------------------
  // Inline expansion
  // ------------------------------------------------------------------

  /**
   * Where a {@code return} in the scope being lowered goes.
   *
   * @return the innermost return target, or the function's own scope when there is none
   */
  private ReturnTarget currentReturnTarget() {
    ReturnTarget target = returnTargets.peek();
    return target == null ? new ReturnTarget(null, null) : target;
  }

  /**
   * Lowers a call to an inline function by expanding its body at the call site.
   *
   * <p><b>WHY THE BODY HAS TO BE EXPANDED.</b> An {@code inline} function has no run-time
   * counterpart: the compiler copies its body into every call site, and what the call site really
   * runs is the COPY. Lowering the call as an ordinary {@code CALL} therefore loses the copy -- and
   * with it the control flow that exists only in the copy. The sharpest case is a {@code return}
   * inside a lambda passed to an inline function: after the copy, that {@code return} leaves the
   * function that CALLED the inline one, and lowered as an ordinary call it leaves the lambda
   * instead -- a different function, with a different result.
   *
   * <p><b>WHY THE ENGINE CANNOT BE ASKED TO DO IT.</b> A non-local return is a jump that leaves
   * another function. The IR has no way to say that: its label references have no such kind, and
   * the engine does not read label references at all. So the only correct expression of a non-local
   * return is to have the code in ONE function -- which is exactly what the expansion does.
   *
   * <p>A call that cannot be expanded is not silently left alone: it records a {@code
   * inline.notExpanded} drop, so the function that carries it says how far it may be trusted.
   *
   * @param call the call being lowered
   * @param receiver the receiver the call is made on, or {@code null} for an unqualified call
   * @return the call's value, or {@code null} when the call is not an expandable inline call
   */
  private TacOperand expandInlineCall(KtCallExpression call, KtExpression receiver) {
    String calleeName = calledName(call);
    // A QUALIFIED call names a member of the receiver's type or an extension function. Those bind a
    // receiver that the expansion would have to reproduce exactly, so the file's own declarations
    // are looked for under the receiver's type -- and, for an extension function, under the bare
    // name.
    KtNamedFunction declaration =
        receiver == null
            ? findSourceDeclaration(calleeName, call.getValueArguments().size())
            : findQualifiedDeclaration(calleeName, call.getValueArguments().size(), receiver);
    if (declaration != null) {
      KaNamedFunctionSymbol symbol = symbolOf(declaration);
      if (symbol == null || !symbol.isInline()) {
        // Not an inline function: there is no copy to make, so nothing is lost.
        return null;
      }
      if (hasReifiedTypeParameter(symbol)) {
        // The type argument is lost: the copied body sees the erased type, not the one the
        // caller asked for. That is a lost VALUE, not a lost control flow -- the function
        // stays at full precision.
        reportDrop(
            org.plumb.tac.ir.TacDrop.Impact.VALUE,
            "inline.reifiedTypeArgument",
            "the reified type argument of the inline function is erased: the expanded body sees "
                + "the erased type, not the type the caller asked for",
            lineOf(call),
            columnOf(call));
      }
      String reason = whyNotExpandable(declaration, call, symbol);
      if (reason != null) {
        // The impact says WHAT the lost copy cost. The copy is the callee's body with the
        // call's lambda arguments inlined at the parameter call sites, so the copy could change
        // the call site when the body -- or one of those lambdas -- could return, throw or
        // loop. A copy that only computes a value costs one value.
        boolean changes =
            copyChangesTheCallSite(declaration.getBodyExpression())
                || argumentsCarryALostCopy(call);
        reportDrop(
            changes
                ? org.plumb.tac.ir.TacDrop.Impact.SEMANTICS
                : org.plumb.tac.ir.TacDrop.Impact.VALUE,
            "inline.notExpanded",
            reason,
            lineOf(call),
            columnOf(call));
        return null;
      }
      return expandInlineBody(call, declaration, symbol);
    }
    // The file does not declare the callee: it may be an inline function from the library,
    // whose body is not in the analysed sources.
    if (libraryInlineSymbolOf(call) == null) {
      return null;
    }
    // A library inline call's body is not in the analysis -- and a library body is not in the
    // analysis for ANY call, inline or not, so what is missing there is not measured. What
    // IS measurable is the call's own arguments: they are in the analysed sources, and the
    // copy would have put them at the call site. So the loss is the control flow when one of
    // them could return, throw or loop, and one value when none of them could.
    boolean changes = argumentsCarryALostCopy(call);
    reportDrop(
        changes ? org.plumb.tac.ir.TacDrop.Impact.SEMANTICS : org.plumb.tac.ir.TacDrop.Impact.VALUE,
        "inline.notExpanded",
        changes
            ? "the inline function has no source in this analysis, so the copy that would carry "
                + "the argument's control flow to the call site is missing"
            : "the inline function has no source in this analysis, so the value its copy would "
                + "produce is not in the TAC",
        lineOf(call),
        columnOf(call));
    return null;
  }

  /**
   * The declaration of the function a call names, or {@code null} when the file does not declare
   * it.
   *
   * <p>A member of the class being lowered shadows a top-level function of the same name, so the
   * member is looked for first. A local of that name shadows both.
   *
   * @param name the callee's name
   * @param arity the call's argument count
   * @return the declaration, or {@code null}
   */
  private KtNamedFunction findSourceDeclaration(String name, int arity) {
    if (name == null || name.isEmpty() || locals.containsKey(name)) {
      return null;
    }
    if (currentEnclosingClass != null) {
      KtNamedFunction member =
          pickByArity(sourceDeclarations.get(currentEnclosingClass + "." + name), arity);
      if (member != null) {
        return member;
      }
    }
    return pickByArity(sourceDeclarations.get(name), arity);
  }

  /**
   * The declaration a QUALIFIED call names, or {@code null} when the file does not declare it.
   *
   * <p>A qualified call names a member of the receiver's type, or an extension function -- a
   * top-level function the receiver is passed to. The member is looked for under the receiver's
   * type name, the extension function under the bare name.
   *
   * <p><b>WHY THE MEMBER IS FOUND AT ALL.</b> A member that is inline is copied into every call
   * site, and the copy is not made here. If the member were not looked for, the call site would
   * look exactly like an ordinary call -- and the loss would be SILENT, which is the one thing a
   * receipt exists to prevent.
   *
   * @param name the callee's name
   * @param arity the call's argument count
   * @param receiver the receiver the call is made on
   * @return the declaration, or {@code null}
   */
  private KtNamedFunction findQualifiedDeclaration(String name, int arity, KtExpression receiver) {
    if (name == null || name.isEmpty()) {
      return null;
    }
    String typeName = shortTypeNameOf(receiver);
    if (typeName != null) {
      KtNamedFunction member = pickByArity(sourceDeclarations.get(typeName + "." + name), arity);
      if (member != null) {
        return member;
      }
    }
    // An extension function: a top-level declaration the receiver is passed to.
    KtNamedFunction extension = pickByArity(sourceDeclarations.get(name), arity);
    if (extension != null) {
      return extension;
    }
    // The receiver's type could not be read. That is what happens when the receiver is an
    // object of a class in the file being analysed: this session does not resolve the file's
    // own classes. The call may still name a member of one of them, so the file's members
    // are searched by name -- and the loss is recorded rather than left silent.
    //
    // The name search runs ONLY when the type is unreadable. A readable type that has no
    // member of that name means the call names a member of a LIBRARY type -- and matching
    // that against this file's members would be a guess, not a reading.
    if (typeName == null) {
      return pickMemberByName(name, arity);
    }
    return null;
  }

  /**
   * The member of one of the file's classes that has the given name, or {@code null}.
   *
   * <p>When several classes declare a member with that name, the first one does: the call's owner
   * cannot be read, and the receipt's grade is the heavier of the two anyway.
   *
   * @param name the member's name
   * @param arity the call's argument count
   * @return the member, or {@code null}
   */
  private KtNamedFunction pickMemberByName(String name, int arity) {
    String suffix = "." + name;
    for (Map.Entry<String, List<KtNamedFunction>> entry : sourceDeclarations.entrySet()) {
      if (!entry.getKey().endsWith(suffix)) {
        continue;
      }
      KtNamedFunction member = pickByArity(entry.getValue(), arity);
      if (member != null) {
        return member;
      }
    }
    return null;
  }

  /**
   * The short name of the receiver's type, or {@code null} when it cannot be read.
   *
   * <p>The short name is the one the file's declarations are keyed by: a member is keyed by {@code
   * Owner.name}, and the owner is the class's short name.
   */
  private String shortTypeNameOf(KtExpression receiver) {
    try {
      TacType type = expressionTypeOf(receiver);
      if (type == null || type.kind() != TacType.Kind.CLASS) {
        return null;
      }
      String name = type.className();
      if (name == null || name.isEmpty()) {
        return null;
      }
      int dot = name.lastIndexOf('.');
      return dot < 0 ? name : name.substring(dot + 1);
    } catch (RuntimeException | LinkageError ex) {
      // The receiver's type could not be read.
      return null;
    }
  }

  /** The declaration whose parameter count matches the call, or the first one. */
  private static KtNamedFunction pickByArity(List<KtNamedFunction> candidates, int arity) {
    if (candidates == null || candidates.isEmpty()) {
      return null;
    }
    for (KtNamedFunction candidate : candidates) {
      if (candidate.getValueParameters().size() == arity) {
        return candidate;
      }
    }
    return candidates.get(0);
  }

  /** The symbol of a declaration, or {@code null} when it cannot be read. */
  private KaNamedFunctionSymbol symbolOf(KtNamedFunction declaration) {
    try {
      KaFunctionSymbol symbol = session.getSymbol(declaration);
      return symbol instanceof KaNamedFunctionSymbol named ? named : null;
    } catch (RuntimeException | LinkageError ex) {
      return null;
    }
  }

  /**
   * The inline function a call names in the LIBRARY, or {@code null} when it is not one.
   *
   * <p>Only library callees are found this way: a call to a function declared in the file being
   * analysed does not resolve through this session, so the file's own declarations are consulted
   * instead.
   */
  private KaNamedFunctionSymbol libraryInlineSymbolOf(KtCallExpression call) {
    if (call == null) {
      return null;
    }
    try {
      KaFunctionSymbol function = session.resolveSymbol(call);
      if (!(function instanceof KaNamedFunctionSymbol named)) {
        return null;
      }
      return named.isInline() ? named : null;
    } catch (RuntimeException | LinkageError ex) {
      // Resolution is best-effort: a call whose callee cannot be resolved is not known to be
      // inline, and saying so would be a guess.
      return null;
    }
  }

  /**
   * Whether a function has a reified type parameter.
   *
   * <p>A reified type parameter is the one thing an {@code inline} function gets from the type
   * system, and the copy of its body cannot have it: the copy is not generic.
   */
  private static boolean hasReifiedTypeParameter(KaNamedFunctionSymbol symbol) {
    try {
      for (org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol parameter :
          symbol.getTypeParameters()) {
        if (parameter.isReified()) {
          return true;
        }
      }
    } catch (RuntimeException | LinkageError ex) {
      // The type parameters could not be read.
    }
    return false;
  }

  /**
   * Why an inline function cannot be expanded at this call site, or {@code null} when it can.
   *
   * <p>Every reason here is a reason the expansion would produce a WRONG graph, not a reason it
   * would be inconvenient: the drop says what was lost, and the loss is real.
   */
  private String whyNotExpandable(
      KtNamedFunction declaration, KtCallExpression call, KaNamedFunctionSymbol symbol) {
    if (declaration.getContainingFile() != call.getContainingFile()) {
      return "the inline function is declared in another file, so its body is not expanded here";
    }
    KtExpression body;
    try {
      body = declaration.getBodyExpression();
    } catch (RuntimeException | LinkageError ex) {
      body = null;
    }
    if (body == null) {
      return "the inline function has no body to expand";
    }
    if (!declaration.isTopLevel()) {
      return "the inline function has a receiver, so expanding it here would bind the wrong one";
    }
    if (containsThis(body)) {
      return "the inline function's body reads `this`, which the expansion cannot bind";
    }
    if (declaration.getValueParameters().size() != call.getValueArguments().size()) {
      return "the call's argument count does not match the inline function's parameters, so the "
          + "arguments cannot be bound one to one";
    }

    List<KtValueArgument> arguments = call.getValueArguments();
    List<org.jetbrains.kotlin.psi.KtParameter> parameters = declaration.getValueParameters();
    for (int i = 0; i < parameters.size(); i++) {
      org.jetbrains.kotlin.psi.KtParameter parameter = parameters.get(i);
      String name = parameter.getName();
      if (name == null || name.isEmpty()) {
        continue;
      }
      if (isVararg(symbol, i)) {
        return "the inline function takes a variadic parameter, so its arguments cannot be bound "
            + "one to one";
      }
      KtExpression argument =
          i < arguments.size() ? arguments.get(i).getArgumentExpression() : null;
      if (argument == null && i < arguments.size()) {
        return "the argument for the inline function's parameter `"
            + name
            + "` is spread, so it cannot be bound";
      }
      if (!(argument instanceof KtLambdaExpression lambda)) {
        if (isFunctionTyped(parameter)) {
          return "the argument for the inline function's parameter `"
              + name
              + "` is not a lambda, so it cannot be inlined";
        }
        continue;
      }
      if (isPassedAsValue(symbol, i)) {
        // A `noinline` or `crossinline` parameter is passed on as a VALUE: the lambda stays a
        // function of its own, and its `return` leaves the lambda -- which is exactly what
        // those modifiers mean.
        continue;
      }
      if (!isOnlyCalled(body, name)) {
        return "the inline function passes its parameter `"
            + name
            + "` on as a value, so the argument cannot be inlined";
      }
      if (lambda.getValueParameters().size() > 1) {
        return "the inline function's parameter `"
            + name
            + "` takes a lambda with several parameters, whose types cannot be bound one to one";
      }
    }
    return null;
  }

  /**
   * Whether a parameter is passed as a value rather than inlined.
   *
   * <p>{@code noinline} and {@code crossinline} are exactly the modifiers that say "this lambda is
   * an ordinary function value".
   */
  private static boolean isPassedAsValue(KaNamedFunctionSymbol symbol, int index) {
    try {
      List<org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol> parameters =
          symbol.getValueParameters();
      if (index >= parameters.size()) {
        return false;
      }
      org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol parameter =
          parameters.get(index);
      return parameter.isNoinline() || parameter.isCrossinline();
    } catch (RuntimeException | LinkageError ex) {
      return false;
    }
  }

  /** Whether a parameter is variadic. */
  private static boolean isVararg(KaNamedFunctionSymbol symbol, int index) {
    try {
      List<org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol> parameters =
          symbol.getValueParameters();
      if (index >= parameters.size()) {
        return false;
      }
      return parameters.get(index).isVararg();
    } catch (RuntimeException | LinkageError ex) {
      return false;
    }
  }

  /** Whether a parameter's declared type is a function type. */
  private static boolean isFunctionTyped(org.jetbrains.kotlin.psi.KtParameter parameter) {
    org.jetbrains.kotlin.psi.KtTypeReference reference = parameter.getTypeReference();
    if (reference == null) {
      return false;
    }
    return reference.getTypeElement() instanceof org.jetbrains.kotlin.psi.KtFunctionType;
  }

  /**
   * Whether every mention of a name in a subtree is the callee of a call.
   *
   * <p>A parameter that is only ever CALLED can be replaced by the caller's argument: the
   * argument's body is inlined where the parameter is called. A parameter that is also used as a
   * VALUE cannot: the value has to exist, and the argument's body has no value to give.
   */
  private static boolean isOnlyCalled(com.intellij.psi.PsiElement scope, String name) {
    for (KtNameReferenceExpression reference : referencesNamed(scope, name)) {
      if (!isCalleeOf(reference)) {
        return false;
      }
    }
    return true;
  }

  /** Whether a name reference is the callee of a call. */
  private static boolean isCalleeOf(KtNameReferenceExpression reference) {
    com.intellij.psi.PsiElement parent = reference.getParent();
    if (parent instanceof KtCallExpression call) {
      return call.getCalleeExpression() == reference;
    }
    if (parent instanceof KtDotQualifiedExpression dot
        && dot.getSelectorExpression() == reference
        && dot.getParent() instanceof KtCallExpression qualified
        && qualified.getCalleeExpression() == dot) {
      return true;
    }
    return false;
  }

  /** Every name reference in a subtree that names {@code name}. */
  private static List<KtNameReferenceExpression> referencesNamed(
      com.intellij.psi.PsiElement scope, String name) {
    List<KtNameReferenceExpression> out = new ArrayList<>();
    collectReferences(scope, name, out);
    return out;
  }

  private static void collectReferences(
      com.intellij.psi.PsiElement element, String name, List<KtNameReferenceExpression> out) {
    for (com.intellij.psi.PsiElement child : element.getChildren()) {
      if (child instanceof KtNameReferenceExpression reference
          && name.equals(reference.getReferencedName())) {
        out.add(reference);
      }
      collectReferences(child, name, out);
    }
  }

  /**
   * Whether a subtree reads {@code this}.
   *
   * <p>An expanded body lives in the CALLER's function, where {@code this} means the caller's
   * receiver. A body that reads {@code this} would therefore read the wrong object, so it is not
   * expanded.
   */
  private static boolean containsThis(com.intellij.psi.PsiElement scope) {
    for (com.intellij.psi.PsiElement child : scope.getChildren()) {
      if (child instanceof KtThisExpression) {
        return true;
      }
      if (containsThis(child)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the copy of a subtree could change what the CALL SITE does.
   *
   * <p><b>WHY THESE THREE AND NOT A BRANCH.</b> The copy of an inline body runs at the call site,
   * so a {@code return} in it leaves the CALLER (that is what an inline function's {@code return}
   * means once the body is copied), a {@code throw} in it can stop the call site from continuing,
   * and a loop in it can stop the call site from continuing. A branch, on the other hand, does
   * neither: the copy still produces one value and still returns, so all that is missing is the
   * VALUE the call produces.
   *
   * <p>So the receipt's grade follows this: a copy that could change the call site costs the
   * function its MEANING, and a copy that only computes a value costs it one value.
   *
   * @param scope the body the copy would carry
   * @return whether the copy could change what the call site does
   */
  private static boolean copyChangesTheCallSite(com.intellij.psi.PsiElement scope) {
    if (scope == null) {
      return false;
    }
    for (com.intellij.psi.PsiElement child : scope.getChildren()) {
      if (child instanceof KtReturnExpression
          || child instanceof KtThrowExpression
          || child instanceof KtForExpression
          || child instanceof KtWhileExpression
          || child instanceof KtDoWhileExpression) {
        return true;
      }
      if (copyChangesTheCallSite(child)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether one of the call's LAMBDA arguments carries something the copy would have put at the
   * call site.
   *
   * <p>After the expansion the lambda's body is code at the call site, so a {@code return} in it is
   * the non-local return the expansion exists for, and a {@code throw} or a loop in it can stop the
   * call site from continuing. Without the expansion the lambda is a function of its own: its
   * {@code return} leaves the LAMBDA -- a different function -- and its {@code throw} stays inside
   * it.
   *
   * @param call the call being lowered
   * @return whether the call passes a lambda whose copy would change the call site
   */
  private static boolean argumentsCarryALostCopy(com.intellij.psi.PsiElement call) {
    if (!(call instanceof KtCallExpression expression)) {
      return false;
    }
    for (KtValueArgument argument : expression.getValueArguments()) {
      KtExpression value = argument.getArgumentExpression();
      if (value instanceof KtLambdaExpression lambda
          && copyChangesTheCallSite(lambda.getBodyExpression())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Expands an inline function's body at the call site.
   *
   * <p>The body is lowered into the CALLER's function, in a scope of its own: the parameters are
   * bound to the caller's arguments, the body's locals are renamed so they cannot collide with the
   * caller's, and a {@code return} in the body ends the expansion rather than the function.
   *
   * @param call the call site
   * @param declaration the expanded function's declaration
   * @param symbol the expanded function's symbol
   * @return the value the call produces
   */
  private TacOperand expandInlineBody(
      KtCallExpression call, KtNamedFunction declaration, KaNamedFunctionSymbol symbol) {
    List<org.jetbrains.kotlin.psi.KtParameter> parameters = declaration.getValueParameters();
    List<KtValueArgument> arguments = call.getValueArguments();

    // The lambdas that are INLINED rather than passed as values.
    Map<String, KtLambdaExpression> inlineLambdas = new LinkedHashMap<>();
    for (int i = 0; i < parameters.size(); i++) {
      org.jetbrains.kotlin.psi.KtParameter parameter = parameters.get(i);
      String name = parameter.getName();
      if (name == null || name.isEmpty()) {
        continue;
      }
      KtExpression argument =
          i < arguments.size() ? arguments.get(i).getArgumentExpression() : null;
      if (argument instanceof KtLambdaExpression lambda && !isPassedAsValue(symbol, i)) {
        inlineLambdas.put(name, lambda);
      }
    }

    // The arguments that ARE values, evaluated in the CALLER's scope.
    List<TacOperand> argumentValues = new ArrayList<>();
    for (int i = 0; i < arguments.size(); i++) {
      KtExpression argument = arguments.get(i).getArgumentExpression();
      TacOperand value = null;
      if (argument != null && !inlineLambdas.containsValue(argument)) {
        value = translateExpression(argument);
      }
      argumentValues.add(value);
    }

    // The scope the expansion introduces.
    Map<String, TacOperand.TacVar> callerLocals = locals;
    String callerSuffix = scopeSuffix;
    Deque<LoopTargets> callerLoops = loops;
    String callerPendingLabel = pendingLabel;
    ReturnTarget callerReturn = currentReturnTarget();

    String expansionSuffix = callerSuffix + "$inl" + (++expansionCounter);
    Map<String, TacOperand.TacVar> expansionLocals = new HashMap<>();

    TacType returnType = declaredTypeOf(declaration.getTypeReference());
    TacOperand.TacVar result = builder.newTemp(returnType);
    String endLabel = builder.newLabel("inlineEnd");

    // Bind the parameters. A parameter that takes an inlined lambda has no run-time value:
    // its body is inlined where it is called.
    for (int i = 0; i < parameters.size(); i++) {
      org.jetbrains.kotlin.psi.KtParameter parameter = parameters.get(i);
      String name = parameter.getName();
      if (name == null || name.isEmpty() || inlineLambdas.containsKey(name)) {
        continue;
      }
      TacType type = declaredTypeOf(parameter.getTypeReference());
      TacOperand.TacVar variable = new TacOperand.TacVar(expansionSuffix + name, type, true);
      expansionLocals.put(name, variable);
      builder.addLocal(variable);
      TacOperand value = i < argumentValues.size() ? argumentValues.get(i) : null;
      if (value != null) {
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.MOVE)
                .dest(variable)
                .lhs1(value)
                .line(lineOf(call))
                .column(columnOf(call))
                .build());
      }
    }

    // Lower the body in the expansion's scope.
    locals = expansionLocals;
    scopeSuffix = expansionSuffix;
    loops = new ArrayDeque<>();
    pendingLabel = null;
    returnTargets.push(new ReturnTarget(result, endLabel));
    expansions.push(
        new InlineExpansion(
            declaration.getName(),
            inlineLambdas,
            callerLocals,
            callerSuffix,
            callerLoops,
            callerReturn));
    TacOperand bodyValue = null;
    try {
      KtExpression body = declaration.getBodyExpression();
      if (body instanceof KtBlockExpression block) {
        List<KtExpression> statements = block.getStatements();
        for (int i = 0; i < statements.size(); i++) {
          boolean last = i == statements.size() - 1;
          if (last && !(statements.get(i) instanceof KtReturnExpression)) {
            bodyValue = translateExpression(statements.get(i));
          } else {
            translateStatement(statements.get(i));
          }
        }
      } else if (body != null) {
        bodyValue = translateExpression(body);
      }
    } finally {
      expansions.pop();
      returnTargets.pop();
      locals = callerLocals;
      scopeSuffix = callerSuffix;
      loops = callerLoops;
      pendingLabel = callerPendingLabel;
    }

    // The body's value is the call's value. A body that returned early has already written the
    // result and jumped past this assignment.
    if (bodyValue != null) {
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.MOVE)
              .dest(result)
              .lhs1(bodyValue)
              .line(lineOf(call))
              .column(columnOf(call))
              .build());
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL)
            .label(endLabel)
            .line(lineOf(call))
            .build());
    return result;
  }

  /**
   * Lowers a call to an inline function's lambda parameter by inlining the lambda's body.
   *
   * <p>After the expansion the lambda is not a function: it is code, and the code runs where the
   * parameter is called. A {@code return} inside it leaves the function the lambda was WRITTEN in
   * -- the non-local return that the expansion exists for.
   *
   * @param call the call to the lambda parameter
   * @param receiver the receiver, or {@code null} for an unqualified call
   * @return the call's value, or {@code null} when the callee is not a lambda parameter
   */
  private TacOperand expandInlineLambdaCall(KtCallExpression call, KtExpression receiver) {
    if (receiver != null) {
      return null;
    }
    InlineExpansion expansion = expansions.peek();
    if (expansion == null) {
      return null;
    }
    KtLambdaExpression lambda = expansion.lambdas().get(calledName(call));
    if (lambda == null) {
      return null;
    }

    // The arguments are evaluated where the lambda is CALLED -- in the expanded function's
    // scope, not in the lambda's own.
    List<TacOperand> argumentValues = new ArrayList<>();
    for (KtValueArgument argument : call.getValueArguments()) {
      KtExpression expression = argument.getArgumentExpression();
      argumentValues.add(expression == null ? null : translateExpression(expression));
    }

    Map<String, TacOperand.TacVar> savedLocals = locals;
    String savedSuffix = scopeSuffix;
    Deque<LoopTargets> savedLoops = loops;
    String savedPendingLabel = pendingLabel;

    // The lambda's scope: the CALLER's locals (the lambda was written there, so the names it
    // reads are the caller's) plus the lambda's own parameters and locals, renamed.
    String lambdaSuffix = expansion.callerSuffix() + "$lam" + (++expansionCounter);
    Map<String, TacOperand.TacVar> lambdaLocals = new HashMap<>(expansion.callerLocals());
    locals = lambdaLocals;
    scopeSuffix = lambdaSuffix;
    loops = expansion.callerLoops();
    pendingLabel = null;
    returnTargets.push(expansion.callerReturnTarget());

    try {
      List<org.jetbrains.kotlin.psi.KtParameter> lambdaParameters = lambda.getValueParameters();
      for (int i = 0; i < lambdaParameters.size(); i++) {
        String name = lambdaParameters.get(i).getName();
        if (name == null || name.isEmpty()) {
          continue;
        }
        TacType type = declaredTypeOf(lambdaParameters.get(i).getTypeReference());
        TacOperand.TacVar variable = new TacOperand.TacVar(lambdaSuffix + name, type, true);
        lambdaLocals.put(name, variable);
        builder.addLocal(variable);
        TacOperand value = i < argumentValues.size() ? argumentValues.get(i) : null;
        if (value != null) {
          builder.emit(
              TacInstruction.builder(TacInstruction.Kind.MOVE)
                  .dest(variable)
                  .lhs1(value)
                  .line(lineOf(call))
                  .column(columnOf(call))
                  .build());
        }
      }
      TacOperand value = null;
      KtExpression lambdaBody = lambda.getBodyExpression();
      if (lambdaBody instanceof KtBlockExpression block) {
        List<KtExpression> statements = block.getStatements();
        for (int i = 0; i < statements.size(); i++) {
          boolean last = i == statements.size() - 1;
          if (last && !(statements.get(i) instanceof KtReturnExpression)) {
            value = translateExpression(statements.get(i));
          } else {
            translateStatement(statements.get(i));
          }
        }
      } else if (lambdaBody != null) {
        value = translateExpression(lambdaBody);
      }
      return value;
    } finally {
      returnTargets.pop();
      locals = savedLocals;
      scopeSuffix = savedSuffix;
      loops = savedLoops;
      pendingLabel = savedPendingLabel;
    }
  }

  /**
   * Lowers a class's primary constructor.
   *
   * <p><b>WHY A CONSTRUCTOR IS THE MOST IMPORTANT FUNCTION IN A KOTLIN CLASS.</b> The TAC had no
   * constructor function at all: a class's members were translated, and the class's CONSTRUCTOR --
   * the function every {@code new} call actually enters -- was not. A call to {@code
   * Foo(userInput)} therefore named a callee that did not exist in the program, the call graph's
   * edge went nowhere, and the value that entered the object never reached the fields it
   * initialised. Every finding that depended on a value passing through a constructor was therefore
   * invisible.
   *
   * <p>The body has three parts, in the order Kotlin runs them: the constructor's own parameters
   * that declare properties become the backing fields of those properties; the property
   * initialisers run; the {@code init} blocks run.
   *
   * @param name the constructor's qualified name
   * @param signature the canonical signature
   * @param returnType the constructed type
   * @param params the constructor's parameters
   * @param valueParameters the constructor's parameter declarations
   * @param properties the class's member properties
   * @param initBlocks the class's {@code init} blocks
   * @param enclosingClass the declaring class
   * @param module the module
   * @param classAnnotations the class's annotations
   * @param methodAnnotations the constructor's annotations
   * @param paramAnnotations the parameters' annotations, by name
   * @return the lowered constructor
   */
  TacFunction translateConstructor(
      String name,
      String signature,
      TacType returnType,
      List<TacOperand.TacVar> params,
      List<org.jetbrains.kotlin.psi.KtParameter> valueParameters,
      List<org.jetbrains.kotlin.psi.KtProperty> properties,
      List<KtExpression> initBlocks,
      String enclosingClass,
      String module,
      List<String> classAnnotations,
      List<String> methodAnnotations,
      Map<String, List<String>> paramAnnotations) {

    this.currentFunction = name;
    this.locals.clear();
    this.problems.clear();
    this.structuredProblems.clear();
    // The scopes of the PREVIOUS function do not belong to this one: an expansion in one
    // function must not leak its suffix, its lambdas or its return target into the next.
    this.scopeSuffix = "";
    this.expansions.clear();
    this.returnTargets.clear();
    this.returnTargets.push(new ReturnTarget(null, null));
    this.currentEnclosingClass = enclosingClass;
    this.currentModule = module;
    this.currentClassAnnotations =
        classAnnotations == null ? List.of() : List.copyOf(classAnnotations);

    TacBuilder.FunctionSpec spec = new TacBuilder.FunctionSpec(name, signature, returnType);
    TacBuilder b = new TacBuilder(spec, params);
    b.setModule(module);
    b.setSourceLanguage(SourceLanguage.KOTLIN);
    b.setIrPrecision(org.plumb.tac.ir.IrPrecision.SOURCE_FULL);
    if (enclosingClass != null && !enclosingClass.isEmpty()) {
      b.setEnclosingClass(enclosingClass);
    }
    if (classAnnotations != null && !classAnnotations.isEmpty()) {
      b.addClassAnnotations(classAnnotations);
    }
    if (methodAnnotations != null && !methodAnnotations.isEmpty()) {
      b.addMethodAnnotations(methodAnnotations);
    }
    if (paramAnnotations != null) {
      for (Map.Entry<String, List<String>> entry : paramAnnotations.entrySet()) {
        b.addParamAnnotations(entry.getKey(), entry.getValue());
      }
    }
    b.setMemberKind(org.plumb.tac.ir.TacMemberKind.CONSTRUCTOR);
    this.builder = b;

    for (TacOperand.TacVar p : params) {
      this.locals.put(p.name(), p);
      b.addLocal(p);
    }
    TacOperand.TacVar self = new TacOperand.TacVar(THIS, TacType.UNKNOWN, false);
    this.locals.put(THIS, self);
    b.addLocal(self);

    // The constructor's parameters that declare a property become the property's
    // backing field.
    for (org.jetbrains.kotlin.psi.KtParameter parameter : valueParameters) {
      String parameterName = parameter.getName();
      if (parameterName == null || parameterName.isEmpty() || !parameter.hasValOrVar()) {
        continue;
      }
      TacOperand.TacVar value = locals.get(parameterName);
      if (value == null) {
        continue;
      }
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
              .dest(value)
              .lhs1(self)
              .fieldName(parameterName)
              .ownerType(ownerType())
              .line(lineOf(parameter))
              .build());
    }

    // The property initialisers.
    for (KtProperty property : properties) {
      String propertyName = property.getName();
      KtExpression initializer = property.getInitializer();
      if (propertyName == null || initializer == null || !property.isMember()) {
        continue;
      }
      if (isConstructorProperty(propertyName, valueParameters)) {
        // Already stored from the constructor's parameter.
        continue;
      }
      TacOperand value = translateExpression(initializer);
      if (value == null) {
        continue;
      }
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
              .dest(value)
              .lhs1(self)
              .fieldName(propertyName)
              .ownerType(ownerType())
              .line(lineOf(property))
              .build());
    }

    // The init blocks.
    for (KtExpression initBlock : initBlocks) {
      if (initBlock instanceof KtBlockExpression block) {
        translateBlock(block);
      } else {
        translateStatement(initBlock);
      }
    }

    // The precision is NOT stated here: it is derived from the drops (the builder
    // combines the two), and a front end that states it as well can only talk the
    // loss up.
    return b.build();
  }

  /**
   * Whether a property is one the constructor's parameters declare.
   *
   * <p>Such a property's value comes from the parameter, not from an initialiser, so the
   * constructor must not store it twice.
   */
  private static boolean isConstructorProperty(
      String propertyName, List<org.jetbrains.kotlin.psi.KtParameter> valueParameters) {
    for (org.jetbrains.kotlin.psi.KtParameter parameter : valueParameters) {
      if (propertyName.equals(parameter.getName())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Lowers a property's accessor.
   *
   * <p>A property with no accessor of its own has a DEFAULT one: the getter reads the backing
   * field, the setter writes it. A property with an accessor of its own has a body, and that body
   * is user code -- it can do anything, and the analysis has to see what it does.
   *
   * @param name the accessor's qualified name
   * @param signature the canonical signature
   * @param returnType the property's type
   * @param params the accessor's parameters (the setter's value)
   * @param body the accessor's own body, or {@code null} for the default accessor
   * @param fieldName the backing field's name
   * @param setter whether this is the setter
   * @param enclosingClass the declaring class
   * @param module the module
   * @param classAnnotations the class's annotations
   * @param methodAnnotations the accessor's annotations
   * @param paramAnnotations the parameters' annotations, by name
   * @return the lowered accessor
   */
  TacFunction translateAccessor(
      String name,
      String signature,
      TacType returnType,
      List<TacOperand.TacVar> params,
      KtExpression body,
      String fieldName,
      boolean setter,
      String enclosingClass,
      String module,
      List<String> classAnnotations,
      List<String> methodAnnotations,
      Map<String, List<String>> paramAnnotations) {

    this.currentFunction = name;
    this.locals.clear();
    this.problems.clear();
    this.structuredProblems.clear();
    // The scopes of the PREVIOUS function do not belong to this one: an expansion in one
    // function must not leak its suffix, its lambdas or its return target into the next.
    this.scopeSuffix = "";
    this.expansions.clear();
    this.returnTargets.clear();
    this.returnTargets.push(new ReturnTarget(null, null));
    this.currentEnclosingClass = enclosingClass;
    this.currentModule = module;
    this.currentClassAnnotations =
        classAnnotations == null ? List.of() : List.copyOf(classAnnotations);

    TacBuilder.FunctionSpec spec = new TacBuilder.FunctionSpec(name, signature, returnType);
    TacBuilder b = new TacBuilder(spec, params);
    b.setModule(module);
    b.setSourceLanguage(SourceLanguage.KOTLIN);
    b.setIrPrecision(org.plumb.tac.ir.IrPrecision.SOURCE_FULL);
    if (enclosingClass != null && !enclosingClass.isEmpty()) {
      b.setEnclosingClass(enclosingClass);
    }
    if (classAnnotations != null && !classAnnotations.isEmpty()) {
      b.addClassAnnotations(classAnnotations);
    }
    if (methodAnnotations != null && !methodAnnotations.isEmpty()) {
      b.addMethodAnnotations(methodAnnotations);
    }
    if (paramAnnotations != null) {
      for (Map.Entry<String, List<String>> entry : paramAnnotations.entrySet()) {
        b.addParamAnnotations(entry.getKey(), entry.getValue());
      }
    }
    b.setMemberKind(
        setter ? org.plumb.tac.ir.TacMemberKind.SETTER : org.plumb.tac.ir.TacMemberKind.GETTER);
    this.builder = b;

    for (TacOperand.TacVar p : params) {
      this.locals.put(p.name(), p);
      b.addLocal(p);
    }
    TacOperand.TacVar self = new TacOperand.TacVar(THIS, TacType.UNKNOWN, false);
    this.locals.put(THIS, self);
    b.addLocal(self);

    if (setter) {
      TacOperand value = params.isEmpty() ? null : params.get(0);
      if (value != null) {
        b.emit(
            TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
                .dest(value)
                .lhs1(self)
                .fieldName(fieldName)
                .ownerType(ownerType())
                .line(lineOf(body))
                .build());
      }
    } else if (body != null) {
      // The accessor has a body of its own: it is user code, and its value is the
      // accessor's value.
      TacOperand value = translateExpression(body);
      if (value != null) {
        b.emit(
            TacInstruction.builder(TacInstruction.Kind.RETURN)
                .lhs1(value)
                .line(lineOf(body))
                .build());
      }
    } else {
      // The default getter: it reads the backing field.
      TacOperand value = builder.newTemp(returnType);
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
              .dest(value)
              .lhs1(self)
              .fieldName(fieldName)
              .ownerType(ownerType())
              .build());
      b.emit(TacInstruction.builder(TacInstruction.Kind.RETURN).lhs1(value).build());
    }

    // The precision is NOT stated here: it is derived from the drops (the builder
    // combines the two), and a front end that states it as well can only talk the
    // loss up.
    return b.build();
  }

  /**
   * Lowers one expression into a builder the caller owns.
   *
   * <p>It is how a top-level property's initialiser is computed: the value belongs to the file's
   * field initializer, and the field initializer's builder is the caller's.
   *
   * @param expression the expression to lower
   * @param target the builder to lower into
   * @return the expression's value, or {@code null}
   */
  TacOperand lowerInitializer(KtExpression expression, TacBuilder target) {
    TacBuilder outerBuilder = builder;
    builder = target;
    try {
      return translateExpression(expression);
    } finally {
      builder = outerBuilder;
    }
  }

  /**
   * The TAC type of an expression, as the Analysis API infers it.
   *
   * <p>This is how a call's result or a constructor's result gets a type at all: the Analysis API
   * knows that {@code StringBuilder()} produces a {@code StringBuilder} and that a Java method seen
   * from Kotlin produces a <i>platform</i> type -- a type whose nullability is unknown, and which
   * therefore must be mapped as nullable.
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

  /**
   * Lowers a {@code return} statement.
   *
   * <p><b>WHERE THE RETURN GOES.</b> In the function's own scope a {@code return} leaves the TAC
   * function. Inside an expanded inline body it ends the EXPANSION: it hands the value to the
   * expansion's result variable and jumps past the body, because that is what the copy of the body
   * does -- the copy returns to the call site, not out of the function that was copied.
   *
   * <p>And inside an INLINED LAMBDA it leaves the function the lambda was WRITTEN in. That is the
   * non-local return: the lambda is not a function at all after the expansion, so its {@code
   * return} has to leave whatever function contains the code the lambda was written in.
   */
  private void translateReturn(KtReturnExpression ret) {
    KtExpression value = ret.getReturnedExpression();
    ReturnTarget target = currentReturnTarget();
    if (value == null) {
      if (target.resultVar() != null) {
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.JUMP)
                .label(target.endLabel())
                .line(lineOf(ret))
                .column(columnOf(ret))
                .build());
        return;
      }
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.RETURN)
              .line(lineOf(ret))
              .column(columnOf(ret))
              .build());
      return;
    }
    TacOperand operand = translateExpression(value);
    if (target.resultVar() != null) {
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.MOVE)
              .dest(target.resultVar())
              .lhs1(operand)
              .line(lineOf(ret))
              .column(columnOf(ret))
              .build());
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.JUMP)
              .label(target.endLabel())
              .line(lineOf(ret))
              .column(columnOf(ret))
              .build());
      return;
    }
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
   * <p>The condition is translated into a boolean-valued variable; the branch tests it. Both arms
   * are lowered in sequence with a join label, which is the standard TAC shape for a two-way
   * branch.
   */
  private void translateIf(KtIfExpression iff) {
    KtExpression condition = iff.getCondition();
    if (condition == null) {
      // The condition could not be read, so the `if` is not lowered at all: neither
      // arm runs in the TAC, and the value the `if` produces is gone. The loss is
      // recorded -- a silently missing branch is a function whose precision lies.
      reportDrop(
          org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
          "kotlin.if.condition",
          "the if-condition could not be read, so the if was not lowered",
          lineOf(iff),
          columnOf(iff));
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
        TacInstruction.builder(TacInstruction.Kind.LABEL)
            .label(elseLabel)
            .line(lineOf(iff))
            .build());
    KtExpression elseBranch = iff.getElse();
    if (elseBranch != null) {
      translateStatement(elseBranch);
    }

    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL)
            .label(endLabel)
            .line(lineOf(iff))
            .build());
  }

  /**
   * Lowers a {@code when} into a branch chain.
   *
   * <p>A {@code when} with an explicit subject becomes a chain of equality branches; a {@code when}
   * without one is an if/else chain over its conditions. The chain says everything the {@code when}
   * said -- only the structure differs -- so the precision is not downgraded for it.
   */
  /**
   * Lower one arm of a `when` and move its value into the result.
   *
   * <p>An arm whose body is an EXPRESSION has a value, and that value is the `when`'s value. An arm
   * whose body is a statement does not, and then the result keeps whatever it had.
   *
   * <p>The distinction is the same one the source makes: `1 -> compute()` yields a value, `1 ->
   * doSomething()` does not.
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
   * <p>A call whose result is discarded is a statement in the source's eyes, and lowering it as an
   * expression would put a value on the stack that the source never had.
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
        // The entry's CONDITIONS, not its body. An entry can name several of them
        // ("1, 2 -> ..."), and every one of them has to be tested: testing only the
        // first made the entry match less than the source says.
        TacOperand eq = whenTest(subjectValue, entry);
        if (eq == null) {
          // A condition the front end could not read. The branch is not lowered, and
          // the gap is reported -- a silently skipped branch is a function whose
          // precision lies.
          report(
              "UNSUPPORTED_CONSTRUCT",
              currentFunction,
              "KtWhenEntry",
              "a branch of a when expression in "
                  + currentFunction
                  + " was not lowered (line "
                  + lineOf(entry)
                  + ")",
              lineOf(entry),
              -1);
          reportDrop(
              org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
              "kotlin.when.isBranch",
              "a branch of a when expression was not lowered",
              lineOf(entry),
              -1);
          continue;
        }

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
    if (expr instanceof org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS cast) {
      // A CAST: "x as String" and "x as? String".
      //
      // <b>Why this is a class of its own.</b> A cast with a type on the right is NOT a
      // KtBinaryExpression -- it is a KtBinaryExpressionWithTypeRHS, which extends
      // KtExpression directly. A check for KtBinaryExpression therefore never matched it,
      // and every cast in a Kotlin program fell through to the catch-all: the cast was
      // reported as unlowerable and produced NO instruction at all.
      return translateCastWithTypeRhs(cast);
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
    if (expr instanceof KtSafeQualifiedExpression safe) {
      return translateSafeQualified(safe);
    }
    if (expr instanceof KtArrayAccessExpression access) {
      return translateArrayAccess(access);
    }
    if (expr instanceof KtLambdaExpression lambda) {
      return translateLambda(lambda);
    }
    if (expr instanceof KtCallableReferenceExpression reference) {
      return translateCallableReference(reference);
    }
    if (expr instanceof KtObjectLiteralExpression object) {
      return translateObjectLiteral(object);
    }
    if (expr instanceof KtIsExpression is) {
      return translateIs(is);
    }
    if (expr instanceof KtPostfixExpression postfix) {
      return translatePostfix(postfix);
    }
    if (expr instanceof KtPrefixExpression prefix) {
      return translatePrefix(prefix);
    }
    if (expr instanceof KtAnnotatedExpression annotated) {
      // An annotation on an expression carries no runtime behaviour, so the
      // expression is lowered as-is.
      return translateExpression(annotated.getBaseExpression());
    }
    if (expr instanceof KtSuperExpression superExpression) {
      return translateSuper(superExpression);
    }
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
    reportDrop(
        org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
        "kotlin.expression.unmodelled",
        "the expression was not lowered: " + expr.getClass().getSimpleName(),
        lineOf(expr),
        columnOf(expr));
    return null;
  }

  /**
   * Lowers a condition into a boolean-valued operand.
   *
   * <p>A comparison becomes a {@code BINOP} with a comparison operator; a bare boolean variable is
   * used as-is.
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
        && "!".equals(prefix.getOperationReference().getText())) {
      TacOperand inner = translateCondition(prefix.getBaseExpression());
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
      // An unresolved condition.
      //
      // WHY NOT A CONSTANT false. The branch has to be well-formed, and a constant false is
      // well-formed -- but it is also a CLAIM: it says the guarded arm never runs, so every
      // taint inside it becomes unreachable and the engine stops reporting it. The source did
      // not say that; the front end merely could not read the condition. An unknown value
      // says "the analysis does not know", and both arms stay reachable.
      return unknownCondition(condition);
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
   * <p>A property of the enclosing class is a {@code FIELDLOAD} off the implicit {@code this}. The
   * receiver is a local named {@code this}, declared by the frontend for every function that has an
   * enclosing class.
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
            .ownerType(ownerType())
            .line(lineOf(receiver))
            .column(columnOf(receiver))
            .build());
    return dest;
  }

  /**
   * Lowers a field read off an arbitrary receiver.
   *
   * <p>The implicit-receiver read ({@code this.field}) has its own helper, because it reads off
   * {@code this}. A read off an arbitrary receiver ({@code a?.b}) must read off THAT receiver --
   * reading it off {@code this} would answer a question about a different object.
   *
   * @param receiver the receiver's value
   * @param name the field's name
   * @param tree the tree being lowered, for the source position
   * @return the field's value
   */
  private TacOperand translateFieldRead(
      TacOperand receiver, String name, org.jetbrains.kotlin.psi.KtExpression tree) {
    if (receiver == null) {
      return builder.newTemp(TacType.UNKNOWN);
    }
    TacOperand dest = builder.newTemp(TacType.UNKNOWN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
            .dest(dest)
            .lhs1(receiver)
            .fieldName(name)
            .ownerType(ownerType())
            .line(lineOf(tree))
            .column(columnOf(tree))
            .build());
    return dest;
  }

  /** Lowers a qualified expression ({@code a.b}, {@code a.b()}, {@code a?.b}). */
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
   * <p>{@code JAVA_SYNTHETIC_PROPERTY} is deliberately NOT included: it is a Kotlin-side synthetic
   * property, not a Java method.
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
   * {@code s.length} is {@code String.length()}. Lowering it as a FIELDLOAD would name a field that
   * does not exist, and -- more importantly -- it would never reach a sink family, because the
   * families are anchored to METHOD names.
   *
   * <p><b>WHERE THE DECLARING CLASS ACTUALLY LIVES.</b> This reads the callable id off the
   * PROPERTY, not off the property's getter. That is not a style choice; the getter does not carry
   * it:
   *
   * <pre>
   *   sb.length  where sb: java.lang.StringBuilder
   *     property  -> origin=INTERSECTION_OVERRIDE, callableId=java/lang/StringBuilder.length
   *     getter    -> origin=INTERSECTION_OVERRIDE, callableId=null
   * </pre>
   *
   * <p>The reason is that Kotlin resolves {@code sb.length} to the synthetic INTERSECTION OVERRIDE
   * property it generates for a Java member, and that override's accessor is a synthetic function
   * with no callable id of its own. The Java declaring class survives on the property's callable
   * id. Reading the getter -- the obvious thing to try -- yields {@code null}, and the property
   * silently degrades to a FIELDLOAD that no sink family can ever match.
   *
   * <p>The origin is checked on the PROPERTY too, and for the same reason: the override's origin is
   * {@code INTERSECTION_OVERRIDE}, not {@code JAVA_LIBRARY}. A Java member reached through Kotlin's
   * property syntax is therefore recognised by asking whether the property resolves to a class that
   * came from Java -- not by asking whether the symbol's origin literally says JAVA.
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
   * <p><b>WHY THE DECLARING CLASS IS ASKED, NOT THE SYMBOL.</b> Kotlin resolves a Java member
   * reached through property syntax to a synthetic INTERSECTION OVERRIDE symbol whose origin is
   * {@code INTERSECTION_OVERRIDE} -- not {@code JAVA_LIBRARY}. So the origin of the symbol in hand
   * never says "this came from Java". What does say so is the class the property belongs to: for
   * {@code sb.length} that is {@code java.lang.StringBuilder}, a Java class, and its origin is
   * JAVA_LIBRARY.
   *
   * <p>For a genuine Kotlin property the declaring class is a Kotlin class, whose origin is not a
   * Java origin -- so the same test separates the two cases without having to special-case the
   * intersection override.
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
   * <p>The property's own callable id is the primary source. The getter's is a fallback for the
   * case where the property is a plain Kotlin property with a real accessor -- but a getter that is
   * a synthetic intersection override carries no callable id, so the fallback simply does not apply
   * there.
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
        TacInstruction.builder(TacInstruction.Kind.LABEL)
            .label(thenLabel)
            .line(lineOf(iff))
            .build());
    KtExpression thenBranch = iff.getThen();
    if (thenBranch != null) {
      TacOperand v = translateExpression(thenBranch);
      if (v != null) {
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.MOVE)
                .dest(dest)
                .lhs1(v)
                .line(lineOf(iff))
                .build());
      }
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL)
            .label(elseLabel)
            .line(lineOf(iff))
            .build());
    KtExpression elseBranch = iff.getElse();
    if (elseBranch != null) {
      TacOperand v = translateExpression(elseBranch);
      if (v != null) {
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.MOVE)
                .dest(dest)
                .lhs1(v)
                .line(lineOf(iff))
                .build());
      }
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL)
            .label(endLabel)
            .line(lineOf(iff))
            .build());
    return dest;
  }

  /** Lowers a binary expression: arithmetic, comparison, or a logical operator. */
  private TacOperand translateBinary(KtBinaryExpression bin) {
    String op = operatorTextOf(bin);

    // An assignment is a STORE, not a value. It has to be recognised before the
    // comparison and arithmetic branches: neither of them knows what to do with
    // "=", and the arithmetic branch would turn "x = y" into a comparison.
    if (isAssignmentOperator(op)) {
      return translateAssignment(bin, op);
    }
    // A cast: "x as String" and "x as? String".
    if ("as".equals(op) || "as?".equals(op)) {
      return translateCast(bin, "as?".equals(op));
    }
    // A membership test: "x in collection" and "x !in collection".
    if ("in".equals(op) || "!in".equals(op)) {
      return translateMembership(bin, "!in".equals(op));
    }
    // The Elvis operator: "a ?: b".
    if ("?:".equals(op)) {
      return translateElvis(bin);
    }

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
    if ("&&".equals(op) || "||".equals(op)) {
      return translateShortCircuit(bin, "&&".equals(op));
    }
    report(
        "UNHANDLED_NODE",
        currentFunction,
        "BinaryOperator:" + op,
        "unsupported binary operator in " + currentFunction + ": " + op + " at line " + lineOf(bin),
        lineOf(bin),
        columnOf(bin));
    reportDrop(
        org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
        "kotlin.binaryOperator.unmodelled",
        "the binary operator was not lowered: " + op,
        lineOf(bin),
        columnOf(bin));
    return null;
  }

  /**
   * Lowers an assignment.
   *
   * <p>A plain assignment stores the right-hand side. A COMPOUND assignment stores the result of
   * the operation: {@code x += v} is {@code x = x + v}. Lowering the compound form as a plain store
   * would throw the old value away, and lowering it as a bare arithmetic expression would compute
   * the new value and never store it.
   *
   * @param bin the assignment
   * @param op the operator's source text
   * @return the stored value
   */
  private TacOperand translateAssignment(KtBinaryExpression bin, String op) {
    TacOperand value = translateExpression(bin.getRight());
    if (value == null) {
      return null;
    }
    if (!"=".equals(op)) {
      BinaryOperator arithmetic = compoundOperatorOf(op);
      if (arithmetic == null) {
        report(
            "UNHANDLED_NODE",
            currentFunction,
            "BinaryOperator:" + op,
            "unsupported compound assignment in " + currentFunction + ": " + op,
            lineOf(bin),
            columnOf(bin));
        reportDrop(
            org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
            "kotlin.assignment.unmodelled",
            "the compound assignment was not lowered: " + op,
            lineOf(bin),
            columnOf(bin));
        return null;
      }
      TacOperand current = translateExpression(bin.getLeft());
      if (current == null) {
        return null;
      }
      TacOperand computed = builder.newTemp(TacType.UNKNOWN);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.BINOP)
              .dest(computed)
              .lhs1(current)
              .lhs2(value)
              .binop(arithmetic)
              .line(lineOf(bin))
              .column(columnOf(bin))
              .build());
      value = computed;
    }
    return store(bin.getLeft(), value, bin);
  }

  /**
   * Stores a value into the place a left-hand side names.
   *
   * <p>Three shapes, in the order the source decides them: a local, a property of an object, and an
   * element of an array.
   */
  private TacOperand store(KtExpression lhs, TacOperand value, KtExpression tree) {
    if (lhs instanceof KtNameReferenceExpression ref) {
      String name = ref.getReferencedName();
      TacOperand.TacVar local = locals.get(name);
      if (local != null) {
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.MOVE)
                .dest(local)
                .lhs1(value)
                .line(lineOf(tree))
                .column(columnOf(tree))
                .build());
        return local;
      }
      return storeProperty(null, name, value, tree);
    }
    if (lhs instanceof KtDotQualifiedExpression dot) {
      KtExpression selector = dot.getSelectorExpression();
      if (selector instanceof KtNameReferenceExpression ref) {
        return storeProperty(dot.getReceiverExpression(), ref.getReferencedName(), value, tree);
      }
    }
    if (lhs instanceof KtArrayAccessExpression access) {
      return storeArrayElement(access, value, tree);
    }
    report(
        "UNHANDLED_NODE",
        currentFunction,
        lhs.getClass().getSimpleName(),
        "unsupported assignment target in " + currentFunction + " at line " + lineOf(tree),
        lineOf(tree),
        columnOf(tree));
    reportDrop(
        org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
        "kotlin.assignment.unmodelled",
        "the assignment target was not lowered: " + lhs.getClass().getSimpleName(),
        lineOf(tree),
        columnOf(tree));
    return value;
  }

  /**
   * Stores a value into a property.
   *
   * @param receiverExpr the receiver expression, or {@code null} for the implicit {@code this}
   * @param name the property's name
   * @param value the value being stored
   * @param tree the tree being lowered, for the source position
   * @return the stored value
   */
  private TacOperand storeProperty(
      KtExpression receiverExpr, String name, TacOperand value, KtExpression tree) {
    TacOperand receiver;
    String owner;
    if (receiverExpr == null) {
      receiver = locals.get(THIS);
      owner = ownerType();
    } else {
      receiver = translateExpression(receiverExpr);
      owner = ownerTypeOf(expressionTypeOf(receiverExpr));
    }
    if (receiver == null) {
      // A top-level property: there is no receiver to write to, and inventing one
      // would put a field store into the TAC that the program does not perform.
      report(
          "UNHANDLED_NODE",
          currentFunction,
          "KtPropertyAssignment",
          "a write to a top-level property in " + currentFunction + " at line " + lineOf(tree),
          lineOf(tree),
          columnOf(tree));
      reportDrop(
          org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
          "kotlin.assignment.unmodelled",
          "the write to a top-level property was not lowered: there is no receiver",
          lineOf(tree),
          columnOf(tree));
      return value;
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
            .dest(value)
            .lhs1(receiver)
            .fieldName(name)
            .ownerType(owner)
            .line(lineOf(tree))
            .column(columnOf(tree))
            .build());
    return value;
  }

  /**
   * Stores a value into an array element.
   *
   * <p>The element's type is the ARRAY's element type, not the array's own type: the value that
   * goes in is the element, so a checker that looks at the store's type has to see the element's.
   */
  private TacOperand storeArrayElement(
      KtArrayAccessExpression access, TacOperand value, KtExpression tree) {
    TacOperand array = translateExpression(access.getArrayExpression());
    TacOperand index = translateIndex(access);
    if (array == null || index == null) {
      return value;
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.ARRAYSTORE)
            .dest(value)
            .lhs1(array)
            .lhs2(index)
            .line(lineOf(tree))
            .column(columnOf(tree))
            .build());
    return value;
  }

  /**
   * Lowers a cast.
   *
   * <p>A plain cast ({@code x as String}) throws when it fails, so the value afterwards is the
   * target type. A SAFE cast ({@code x as? String}) yields null instead of throwing, so the value
   * afterwards is the target type MARKED NULLABLE -- and the path continues, which is the
   * difference that matters to the analysis.
   *
   * <p>The target type is read from the expression's own type, because the Analysis API already
   * resolved the cast: asking the PSI for the type reference and mapping it again would do the same
   * work with a second chance to disagree.
   */
  private TacOperand translateCast(KtBinaryExpression bin, boolean safe) {
    TacType target = expressionTypeOf(bin);
    if (safe && target.isReference()) {
      target = target.nullable();
    }
    TacOperand value = translateExpression(bin.getLeft());
    if (value == null) {
      return null;
    }
    TacOperand dest = builder.newTemp(target);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.UNARYOP)
            .dest(dest)
            .lhs1(value)
            .unop(org.plumb.tac.ir.UnaryOperator.CAST)
            .castType(target)
            .line(lineOf(bin))
            .column(columnOf(bin))
            .build());
    return dest;
  }

  /**
   * Lowers a cast whose right side is a TYPE: {@code x as String}, {@code x as? String}.
   *
   * <p>A plain cast throws when it fails, so the value afterwards is the target type. A SAFE cast
   * yields null instead of throwing, so the value afterwards is the target type MARKED NULLABLE --
   * and the path continues, which is the difference that matters to the analysis.
   *
   * <p>The target type is read from the type reference the source wrote down. The Analysis API has
   * already resolved the cast, so the expression's own type would say the same thing; the reference
   * is used because it says it even when the resolution failed.
   */
  private TacOperand translateCastWithTypeRhs(
      org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS cast) {
    String op = cast.getOperationReference().getText();
    boolean safe = "as?".equals(op);
    TacOperand value = translateExpression(cast.getLeft());
    if (value == null) {
      return null;
    }
    TacType target = declaredTypeOf(cast.getRight());
    if (target == null || target == TacType.UNKNOWN) {
      target = expressionTypeOf(cast);
    }
    if (safe && target.isReference()) {
      target = target.nullable();
    }
    TacOperand dest = builder.newTemp(target);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.UNARYOP)
            .dest(dest)
            .lhs1(value)
            .unop(org.plumb.tac.ir.UnaryOperator.CAST)
            .castType(target)
            .line(lineOf(cast))
            .column(columnOf(cast))
            .build());
    return dest;
  }

  /**
   * Lowers a membership test.
   *
   * <p>{@code x in collection} asks the COLLECTION whether it holds {@code x}, so the collection is
   * the receiver and {@code x} is the argument. The owner comes from the resolved symbol, so the
   * call names the real function rather than a guessed one.
   */
  private TacOperand translateMembership(KtBinaryExpression bin, boolean negated) {
    TacOperand element = translateExpression(bin.getLeft());
    TacOperand collection = translateExpression(bin.getRight());
    if (element == null || collection == null) {
      return null;
    }
    String owner = ownerOfMembership(bin);
    String signature = (owner == null || owner.isEmpty() ? "contains" : owner + ".contains") + "/2";
    TacOperand dest = builder.newTemp(TacType.BOOLEAN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(dest)
            .calleeSig(signature)
            .args(List.of(collection, element))
            .line(lineOf(bin))
            .column(columnOf(bin))
            .build());
    if (!negated) {
      return dest;
    }
    TacOperand negatedDest = builder.newTemp(TacType.BOOLEAN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.UNARYOP)
            .dest(negatedDest)
            .lhs1(dest)
            .unop(org.plumb.tac.ir.UnaryOperator.NOT)
            .line(lineOf(bin))
            .column(columnOf(bin))
            .build());
    return negatedDest;
  }

  /**
   * The owner of the function an {@code in} test calls.
   *
   * <p>Resolved from the operator's own symbol: {@code in} is an extension function, and the
   * extension's receiver is the collection. The owner is whatever the symbol says; when the symbol
   * does not resolve, the bare name is the honest fallback.
   */
  private String ownerOfMembership(KtBinaryExpression bin) {
    try {
      KaSymbol symbol = session.resolveSymbol(bin.getOperationReference());
      if (symbol instanceof KaFunctionSymbol function) {
        org.jetbrains.kotlin.name.ClassId classId = function.getCallableId().getClassId();
        if (classId != null && !classId.isLocal()) {
          String rendered = classId.asSingleFqName().asString();
          if (rendered != null && !rendered.isEmpty()) {
            return rendered;
          }
        }
      }
    } catch (RuntimeException | LinkageError ex) {
      // Best effort: the bare name still carries the call graph's bare-name edge.
    }
    return null;
  }

  /**
   * Lowers the Elvis operator: {@code a ?: b}.
   *
   * <p>The value is {@code a} when {@code a} is not null, and {@code b} otherwise. The TAC has no
   * "is null" test, so the branch is made on a comparison against the null constant: the shape says
   * exactly what the source says, and a checker that understands the comparison gets the narrowing
   * for free.
   *
   * <p>Lowering this as "the value is {@code a}, and {@code b} is computed too" would lose the
   * difference between the two arms, and lowering it as "the value is {@code b}" would lose {@code
   * a}'s value entirely -- which is how a tainted {@code a} used to lose its taint.
   */
  private TacOperand translateElvis(KtBinaryExpression bin) {
    TacOperand left = translateExpression(bin.getLeft());
    TacOperand right = translateExpression(bin.getRight());
    if (left == null || right == null) {
      return null;
    }
    TacOperand dest = builder.newTemp(TacType.UNKNOWN);
    TacOperand isNull = builder.newTemp(TacType.BOOLEAN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BINOP)
            .dest(isNull)
            .lhs1(left)
            .lhs2(TacOperand.Const.ofNull())
            .binop(BinaryOperator.EQ)
            .line(lineOf(bin))
            .column(columnOf(bin))
            .build());

    String nullLabel = builder.newLabel("elvisNull");
    String notNullLabel = builder.newLabel("elvisValue");
    String endLabel = builder.newLabel("elvisEnd");
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(isNull)
            .trueLabel(nullLabel)
            .falseLabel(notNullLabel)
            .guard(TacGuard.compare(left, BinaryOperator.EQ, TacOperand.Const.ofNull()))
            .line(lineOf(bin))
            .column(columnOf(bin))
            .build());

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(notNullLabel).build());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(dest)
            .lhs1(left)
            .line(lineOf(bin))
            .build());
    builder.emit(TacInstruction.builder(TacInstruction.Kind.JUMP).label(endLabel).build());

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(nullLabel).build());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(dest)
            .lhs1(right)
            .line(lineOf(bin))
            .build());

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(endLabel).build());
    return dest;
  }

  /**
   * Lowers a safe call: {@code a?.b} and {@code a?.b()}.
   *
   * <p>The receiver is checked FIRST: when it is null, the read or the call does not happen at all
   * and the value of the whole expression is null. Lowering it as an ordinary call would make the
   * engine's null-dereference checker fire on every such call -- a false positive on exactly the
   * construct that exists to PREVENT the dereference.
   */
  private TacOperand translateSafeQualified(KtSafeQualifiedExpression safe) {
    KtExpression receiverExpr = safe.getReceiverExpression();
    KtExpression selector = safe.getSelectorExpression();
    TacOperand receiver = translateExpression(receiverExpr);
    if (receiver == null) {
      return null;
    }
    TacOperand dest = builder.newTemp(TacType.UNKNOWN);
    TacOperand isNull = builder.newTemp(TacType.BOOLEAN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BINOP)
            .dest(isNull)
            .lhs1(receiver)
            .lhs2(TacOperand.Const.ofNull())
            .binop(BinaryOperator.EQ)
            .line(lineOf(safe))
            .column(columnOf(safe))
            .build());

    String useLabel = builder.newLabel("safeUse");
    String nullLabel = builder.newLabel("safeNull");
    String endLabel = builder.newLabel("safeEnd");
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(isNull)
            .trueLabel(nullLabel)
            .falseLabel(useLabel)
            .guard(TacGuard.compare(receiver, BinaryOperator.EQ, TacOperand.Const.ofNull()))
            .line(lineOf(safe))
            .column(columnOf(safe))
            .build());

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(useLabel).build());
    TacOperand value;
    if (selector instanceof KtCallExpression call) {
      value = translateCall(call, receiverExpr, receiver);
    } else if (selector instanceof KtNameReferenceExpression ref) {
      value = translateFieldRead(receiver, ref.getReferencedName(), safe);
    } else {
      value = translateExpression(selector);
    }
    if (value != null) {
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.MOVE)
              .dest(dest)
              .lhs1(value)
              .line(lineOf(safe))
              .build());
    }
    builder.emit(TacInstruction.builder(TacInstruction.Kind.JUMP).label(endLabel).build());

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(nullLabel).build());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(dest)
            .lhs1(TacOperand.Const.ofNull())
            .line(lineOf(safe))
            .build());

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(endLabel).build());
    return dest;
  }

  /**
   * Lowers an array access: {@code a[i]}.
   *
   * <p>The element's type is the expression's own type, which is the element's type -- not the
   * array's.
   */
  private TacOperand translateArrayAccess(KtArrayAccessExpression access) {
    TacOperand array = translateExpression(access.getArrayExpression());
    TacOperand index = translateIndex(access);
    if (array == null || index == null) {
      return null;
    }
    TacOperand dest = builder.newTemp(expressionTypeOf(access));
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.ARRAYLOAD)
            .dest(dest)
            .lhs1(array)
            .lhs2(index)
            .line(lineOf(access))
            .column(columnOf(access))
            .build());
    return dest;
  }

  /**
   * Lowers a type test: {@code x is String} and {@code x !is String}.
   *
   * <p>The test is an {@code INSTANCEOF} against the tested type, which is what the engine's type
   * narrowing reads.
   */
  private TacOperand translateIs(KtIsExpression is) {
    TacOperand value = translateExpression(is.getLeftHandSide());
    if (value == null) {
      return null;
    }
    TacType tested = declaredTypeOf(is.getTypeReference());
    TacOperand dest = builder.newTemp(TacType.BOOLEAN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.INSTANCEOF)
            .dest(dest)
            .lhs1(value)
            .ctorType(tested)
            .line(lineOf(is))
            .column(columnOf(is))
            .build());
    return dest;
  }

  /**
   * Lowers a postfix operator: {@code x++} and {@code x--}.
   *
   * <p>The value of the expression is the value BEFORE the change, and the variable is changed. A
   * postfix increment whose value is discarded still changes the variable, so the store happens
   * either way.
   */
  private TacOperand translatePostfix(KtPostfixExpression postfix) {
    String op = postfix.getOperationReference().getText();

    // The NOT-NULL ASSERTION: "x!!".
    //
    // <b>Why it is here and not in the PREFIX lowering.</b> "!!" is a POSTFIX operator in
    // Kotlin -- the value is read and THEN asserted -- so the prefix branch never saw it,
    // and the assertion was lowered as the plain value: every null check the programmer
    // wrote was invisible to the engine's null-dereference checker.
    if ("!!".equals(op)) {
      TacOperand value = translateExpression(postfix.getBaseExpression());
      if (value == null) {
        return null;
      }
      TacOperand.TacVar checked = asVar(value);
      if (checked != null) {
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.NULL_CHECK)
                .dest(checked)
                .line(lineOf(postfix))
                .column(columnOf(postfix))
                .build());
      }
      return value;
    }

    org.plumb.tac.ir.UnaryOperator unop = null;
    if ("++".equals(op)) {
      unop = org.plumb.tac.ir.UnaryOperator.POST_INC;
    } else if ("--".equals(op)) {
      unop = org.plumb.tac.ir.UnaryOperator.POST_DEC;
    }
    if (unop == null) {
      return translateExpression(postfix.getBaseExpression());
    }
    TacOperand base = translateExpression(postfix.getBaseExpression());
    if (base == null) {
      return null;
    }
    // The postfix form evaluates to the OLD value, so the value is snapshotted BEFORE the
    // write-back: after the write-back the variable holds the new one.
    TacOperand.TacVar oldValue = builder.newTemp(TacType.UNKNOWN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(oldValue)
            .lhs1(base)
            .line(lineOf(postfix))
            .column(columnOf(postfix))
            .build());
    TacOperand dest = builder.newTemp(TacType.UNKNOWN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.UNARYOP)
            .dest(dest)
            .lhs1(base)
            .unop(unop)
            .line(lineOf(postfix))
            .column(columnOf(postfix))
            .build());
    // The engine's UNARYOP writes the new value into `dest` and leaves the operand alone,
    // so storing it back is the TRANSLATOR's job. Without it the variable never changed,
    // so every later read saw the pre-increment value and every loop counter ran one
    // step short.
    store(postfix.getBaseExpression(), dest, postfix);
    return oldValue;
  }

  /**
   * Lowers a prefix operator.
   *
   * <p>{@code !x} negates, {@code -x} negates a number, and {@code ++x} and {@code --x} change the
   * variable and yield the NEW value.
   *
   * <p><b>Why the not-null assertion is NOT here.</b> {@code !!x} is Kotlin's "I assert this is not
   * null", and the TAC's {@code NULL_CHECK} is exactly that claim -- but {@code !!} is a POSTFIX
   * operator, so the expression reaches {@link #translatePostfix}. A branch that can never be taken
   * is worse than no branch at all: it says the frontend handles something it does not.
   */
  private TacOperand translatePrefix(KtPrefixExpression prefix) {
    String op = prefix.getOperationReference().getText();
    KtExpression base = prefix.getBaseExpression();
    if ("++".equals(op) || "--".equals(op)) {
      TacOperand baseValue = translateExpression(base);
      if (baseValue == null) {
        return null;
      }
      TacOperand dest = builder.newTemp(TacType.UNKNOWN);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.UNARYOP)
              .dest(dest)
              .lhs1(baseValue)
              .unop(
                  "++".equals(op)
                      ? org.plumb.tac.ir.UnaryOperator.PRE_INC
                      : org.plumb.tac.ir.UnaryOperator.PRE_DEC)
              .line(lineOf(prefix))
              .column(columnOf(prefix))
              .build());
      // The engine's UNARYOP writes the new value into `dest` and leaves the operand
      // alone, so the write-back is the TRANSLATOR's job.
      store(base, dest, prefix);
      // The prefix form evaluates to the NEW value.
      return dest;
    }
    if ("!".equals(op)) {
      TacOperand value = translateCondition(base);
      TacOperand dest = builder.newTemp(TacType.BOOLEAN);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.UNARYOP)
              .dest(dest)
              .lhs1(value)
              .unop(org.plumb.tac.ir.UnaryOperator.NOT)
              .line(lineOf(prefix))
              .column(columnOf(prefix))
              .build());
      return dest;
    }
    if ("-".equals(op)) {
      TacOperand value = translateExpression(base);
      if (value == null) {
        return null;
      }
      TacOperand dest = builder.newTemp(TacType.UNKNOWN);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.UNARYOP)
              .dest(dest)
              .lhs1(value)
              .unop(org.plumb.tac.ir.UnaryOperator.NEG)
              .line(lineOf(prefix))
              .column(columnOf(prefix))
              .build());
      return dest;
    }
    // A unary plus, or an operator this front end does not know: the operand is
    // lowered for its value, and the operator itself carries nothing.
    return translateExpression(base);
  }

  /**
   * Lowers {@code super}.
   *
   * <p>The super receiver is a value the TAC carries: a call on it puts the receiver in {@code
   * args[0]}, exactly as any other call does. Lowering {@code super} as nothing made {@code
   * super.f()} a call with no receiver at all, and the engine's null-dereference checker read the
   * missing receiver as a null one.
   */
  private TacOperand translateSuper(KtSuperExpression superExpression) {
    TacOperand.TacVar self = locals.get(THIS);
    if (self == null) {
      return builder.newTemp(TacType.UNKNOWN);
    }
    return self;
  }

  /**
   * Lowers an object literal: {@code object : Base { ... }}.
   *
   * <p>The literal is a construction of an anonymous type. The type's name carries the outer
   * class's name, so two literals in one file do not collide.
   */
  private TacOperand translateObjectLiteral(KtObjectLiteralExpression object) {
    TacType constructed = expressionTypeOf(object);
    TacOperand dest = builder.newTemp(constructed);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.NEW)
            .dest(dest)
            .ctorType(constructed)
            .calleeSig(constructed == null ? null : constructed.className() + ".<init>/0")
            .args(List.of())
            .line(lineOf(object))
            .column(columnOf(object))
            .build());
    return dest;
  }

  /**
   * Lowers a callable reference: {@code ::f} and {@code receiver::f}.
   *
   * <p>A callable reference is a FUNCTION VALUE. The TAC has no representation for one: there is no
   * operand that holds "a function". Lowering it as an ordinary call would claim the function is
   * invoked at the point the reference is written, which it is not.
   *
   * <p>So the reference is lowered as a CALL -- the shape every other front end uses for a function
   * value -- and the gap is recorded. The alternative, dropping it, loses the taint that flows into
   * the reference; the CALL keeps the data flow visible and lets the checker see that the function
   * is used as a value.
   */
  private TacOperand translateCallableReference(KtCallableReferenceExpression reference) {
    String name =
        reference.getCallableReference() == null
            ? null
            : reference.getCallableReference().getReferencedName();
    if (name == null || name.isEmpty()) {
      return null;
    }
    String owner = ownerOfCallableReference(reference);
    String signature = (owner == null || owner.isEmpty() ? name : owner + "." + name) + "/0";
    TacOperand dest = builder.newTemp(TacType.UNKNOWN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(dest)
            .calleeSig(signature)
            .args(List.of())
            .line(lineOf(reference))
            .column(columnOf(reference))
            .build());
    reportDrop(
        org.plumb.tac.ir.TacDrop.Impact.VALUE,
        "kotlin.callableReference.asValue",
        "the callable reference was lowered as a call; the function value itself is not modelled",
        lineOf(reference),
        columnOf(reference));
    return dest;
  }

  /** The owner of the member a callable reference names, or {@code null}. */
  private String ownerOfCallableReference(KtCallableReferenceExpression reference) {
    try {
      KaSymbol symbol = session.resolveSymbol(reference);
      if (symbol instanceof KaCallableSymbol callable) {
        org.jetbrains.kotlin.name.ClassId classId = callable.getCallableId().getClassId();
        if (classId != null && !classId.isLocal()) {
          String rendered = classId.asSingleFqName().asString();
          if (rendered != null && !rendered.isEmpty()) {
            return rendered;
          }
        }
      }
    } catch (RuntimeException | LinkageError ex) {
      // Best effort.
    }
    return null;
  }

  /**
   * The index of an array access.
   *
   * <p>An array access carries a LIST of index expressions, because {@code a[i, j]} is one access
   * with two indices. The first one is the index the TAC models; the rest are lowered for their
   * side effects, so a call inside them is not lost.
   */
  private TacOperand translateIndex(KtArrayAccessExpression access) {
    List<KtExpression> indices = access.getIndexExpressions();
    if (indices == null || indices.isEmpty()) {
      return null;
    }
    TacOperand index = translateExpression(indices.get(0));
    for (int i = 1; i < indices.size(); i++) {
      translateExpression(indices.get(i));
    }
    return index;
  }

  /**
   * The owner of a type, for a field access.
   *
   * @param type the type
   * @return the class's name, or {@code null} when the type does not name a class
   */
  private static String ownerTypeOf(TacType type) {
    if (type == null || type.kind() != TacType.Kind.CLASS) {
      return null;
    }
    return type.className();
  }

  /**
   * The binary operator of a compound assignment.
   *
   * @param op the operator's source text, e.g. {@code "+="}
   * @return the operator, or {@code null} when the text is not a compound assignment
   */
  private static BinaryOperator compoundOperatorOf(String op) {
    if (op == null || op.length() < 2) {
      return null;
    }
    switch (op.charAt(0)) {
      case '+':
        return BinaryOperator.ADD;
      case '-':
        return BinaryOperator.SUB;
      case '*':
        return BinaryOperator.MUL;
      case '/':
        return BinaryOperator.DIV;
      case '%':
        return BinaryOperator.MOD;
      default:
        return null;
    }
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
        TacInstruction.builder(TacInstruction.Kind.LABEL)
            .label(rhsLabel)
            .line(lineOf(bin))
            .build());
    TacOperand rhs = translateCondition(bin.getRight());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(dest)
            .lhs1(rhs)
            .line(lineOf(bin))
            .build());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.JUMP).label(endLabel).line(lineOf(bin)).build());

    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL)
            .label(shortLabel)
            .line(lineOf(bin))
            .build());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(dest)
            .lhs1(TacOperand.Const.ofBool(!and))
            .line(lineOf(bin))
            .build());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL)
            .label(endLabel)
            .line(lineOf(bin))
            .build());
    return dest;
  }

  /**
   * Lowers a string template.
   *
   * <p>A template is a sequence of entries: literal text entries and interpolation entries. A
   * template with no entries is a string constant; one with a single interpolation entry and no
   * literal text around it is that entry's value; anything else is the concatenation of the parts.
   *
   * <p>The Analysis API's Kotlin PSI exposes the entries as an array of {@link
   * KtStringTemplateEntry}, and the entries themselves are <i>not</i> expressions -- an
   * interpolation entry holds its expression, a literal entry holds its text. So the two are
   * distinguished by their type, not by their class name.
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
      TacOperand part =
          hole != null
              ? translateExpression(hole)
              : TacOperand.Const.ofString(unquote(entry.getText()));
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

  /** The expression a string template entry interpolates, or {@code null} for a literal entry. */
  private static KtExpression interpolationOf(KtStringTemplateEntry entry) {
    if (entry instanceof org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression withExpr) {
      return withExpr.getExpression();
    }
    return null;
  }

  /**
   * Strips the quotes from a Kotlin string literal.
   *
   * <p>A raw string ({@code """..."""}) keeps its content verbatim; an ordinary string has its
   * quotes removed. The escape sequences inside an ordinary string are left as they are: the TAC's
   * string constants are compared literally, and unescaping them would change what a sink sees.
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
   * <p>The Analysis API's Kotlin PSI does not expose the constant's <i>kind</i> -- its {@code
   * KtConstantExpression} has no {@code getConstant()} -- so the literal is recognised from its
   * source text. That is exact for the literal forms Kotlin has: {@code null}, {@code true} /
   * {@code false}, a number, a quoted string, and a character literal.
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
   * <p>The literal's suffix decides the type: {@code L} makes it a {@code Long}, {@code f} a {@code
   * Float}, {@code F} a {@code Float}, and the rest are {@code Int} unless the value does not fit.
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
   * <p>A call to {@code requireNotNull(x)} / {@code checkNotNull(x)} proves {@code x} non-null on
   * the path that follows, so a {@code NULL_CHECK} is emitted for the argument. That is the one
   * place the Kotlin frontend emits a {@code NULL_CHECK} from a call: the standard library's own
   * null-check helpers.
   *
   * <p><b>Not</b> {@code Intrinsics.areEqual} -- that is a null-safe <i>comparison</i>, not a null
   * check, and treating it as one would put "these two values are equal" into the non-null fact
   * set. And <b>not</b> {@code Intrinsics.throwUninitializedPropertyAccessException} -- that is the
   * {@code lateinit} check, not a null check.
   *
   * @param call the call to lower
   * @param receiver the receiver the call is made on, or {@code null} for an unqualified call
   * @return the call's result, or {@code null} for a {@code Unit}-returning call
   */
  private TacOperand translateCall(KtCallExpression call, KtExpression receiver) {
    return translateCall(call, receiver, null);
  }

  /**
   * Lowers a call whose receiver has already been lowered.
   *
   * <p>The safe-call lowering needs this: it checks the receiver FIRST and then calls on it, so the
   * receiver is lowered once -- before the check -- and the call must use that same value. Lowering
   * the receiver a second time inside the call would emit the receiver's instructions twice, and a
   * call inside the receiver would then happen twice.
   *
   * @param call the call to lower
   * @param receiver the receiver expression, or {@code null} for an unqualified call
   * @param receiverOperand the already-lowered receiver, or {@code null}
   * @return the call's result
   */
  private TacOperand translateCall(
      KtCallExpression call, KtExpression receiver, TacOperand receiverOperand) {
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

    // An INLINE FUNCTION: its body is a copy that the compiler puts at the call site, so
    // the copy is what the call site really runs. Lowering the call as an ordinary CALL
    // loses that copy -- and with it the control flow that exists only in the copy.
    TacOperand expanded = expandInlineCall(call, receiver);
    if (expanded != null) {
      return expanded;
    }

    // A call to an inline function's LAMBDA PARAMETER: after the expansion the lambda is
    // not a function any more, so its body is inlined where it is called.
    TacOperand inlined = expandInlineLambdaCall(call, receiver);
    if (inlined != null) {
      return inlined;
    }

    // A general call.
    List<TacOperand> args = translateArguments(call);
    TacType resultType = expressionTypeOf(call);
    TacOperand dest = builder.newTemp(resultType);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(dest)
            .calleeSig(calleeSignature(calleeName, receiver, arity, call))
            .args(withReceiver(args, receiver, receiverOperand))
            .line(lineOf(call))
            .column(columnOf(call))
            .build());
    return dest;
  }

  /**
   * The call's arguments, with the receiver in front.
   *
   * <p><b>Why the receiver is an argument.</b> In Kotlin a receiver is not an argument -- {@code
   * a.b(x)} has arity 1, not 2 -- but the TAC's {@code CALL} instruction carries the receiver in
   * {@code args[0]}, and that is the convention the Java frontend and the bytecode frontend both
   * follow. The DFA's null-dereference checker reads the receiver from {@code args[0]} for a {@code
   * CALL}: without it, a method call on a possibly-null receiver is not reported at all.
   *
   * <p>The callee signature's arity stays the <i>argument</i> count, so the receiver does not
   * change the call graph's edge.
   */
  private List<TacOperand> withReceiver(
      List<TacOperand> args, KtExpression receiver, TacOperand receiverOperand) {
    if (receiverOperand != null) {
      List<TacOperand> withSlot = new ArrayList<>(args.size() + 1);
      withSlot.add(receiverOperand);
      withSlot.addAll(args);
      return withSlot;
    }
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
   * <p>For {@code foo.bar()} the callee is {@code bar}; for {@code bar()} it is {@code bar}. A
   * constructor call ({@code Foo()}) has a {@link
   * org.jetbrains.kotlin.psi.KtConstructorCalleeExpression} as its callee rather than a name
   * reference, and the constructor's name comes from that expression.
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
   * <p>A receiver is not an argument in Kotlin's calling convention (it is the dispatch receiver),
   * so {@code a.b(x)} has arity 1, not 2.
   *
   * <p><b>The owner is what makes sink matching work.</b> The sink families in {@code
   * sink-families.yaml} are anchored to a fully qualified name ({@code
   * '^java\.sql\.Statement\.execute'}), and the DFA matches a call against them by its callee
   * signature. A signature without an owner ({@code "execute/1"}) matches NONE of them -- not one
   * pattern in the file. So a Kotlin call to {@code stmt.execute(userInput)} was invisible to every
   * taint sink rule: the analysis ran, produced zero findings, and looked like clean code.
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
   * made ON; the owner is what the method is DECLARED in. They are usually the same, but not always
   * -- and it is the declaration that the sink families name. A call {@code stmt.execute(x)} where
   * {@code stmt} is declared as {@code java.sql.Statement} resolves to a method declared in {@code
   * java.sql.Statement}, so the owner is that class, and the FQN-anchored pattern matches.
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
   * org.jetbrains.kotlin.psi.KtConstructorCalleeExpression}, not a name reference. When the PSI
   * cannot tell -- a name reference whose target did not resolve -- Kotlin's naming convention is
   * the fallback: a capitalised name is a class.
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
    return declaredTypeOf(prop == null ? null : prop.getTypeReference());
  }

  /**
   * The declared type of a type reference, or {@link TacType#UNKNOWN}.
   *
   * @param ref the type reference, or {@code null}
   * @return the declared type
   */
  private TacType declaredTypeOf(org.jetbrains.kotlin.psi.KtTypeReference ref) {
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
   * Whether an operator writes to its left-hand side.
   *
   * <p>An assignment is not an expression whose value is discarded: it is a store. The two are
   * lowered differently, and treating an assignment as a discarded value means the store never
   * happens.
   *
   * @param op the operator's source text, or {@code null}
   * @return true for {@code =} and for every compound assignment
   */
  private static boolean isAssignmentOperator(String op) {
    if (op == null || op.isEmpty()) {
      return false;
    }
    if ("=".equals(op)) {
      return true;
    }
    // A compound assignment: "+=", "-=", "*=", "/=", "%=".
    return op.length() >= 2 && op.endsWith("=") && "+-*/%".indexOf(op.charAt(0)) >= 0;
  }

  /**
   * The comparison operator of a binary expression, or {@code null}.
   *
   * <p>The operator is read from the operation reference's source text rather than from the token:
   * the Analysis API's Kotlin PSI exposes {@code getOperationToken()} as an {@code IElementType},
   * and mapping every operator token back to its spelling would duplicate the lexer's table. The
   * text is the operator's own source, so it is exact.
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
   * <i>suppression</i> direction only: it says "this declaration cannot hold null", and never "this
   * value cannot be null at runtime".
   */
  private static TacType nonNullOf(TacType type) {
    if (type == null || !type.isReference()) {
      return type == null ? TacType.UNKNOWN : type;
    }
    return type.nonNull();
  }

  /**
   * The test of a {@code when} entry.
   *
   * <p>An entry can name several conditions, and it matches when ANY of them matches. An {@code is}
   * pattern tests the subject's runtime type. The tests are combined with a bitwise-or over
   * booleans, which is the same thing the source says.
   *
   * @param subject the subject's value
   * @param entry the entry
   * @return the test, or {@code null} when the entry's conditions could not be read
   */
  private TacOperand whenTest(TacOperand subject, KtWhenEntry entry) {
    TacOperand combined = null;
    for (KtWhenCondition condition : entry.getConditions()) {
      TacOperand one = whenConditionTest(subject, condition, entry);
      if (one == null) {
        continue;
      }
      if (combined == null) {
        combined = one;
        continue;
      }
      TacOperand merged = builder.newTemp(TacType.BOOLEAN);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.BINOP)
              .dest(merged)
              .lhs1(combined)
              .lhs2(one)
              .binop(BinaryOperator.OR)
              .line(lineOf(entry))
              .build());
      combined = merged;
    }
    return combined;
  }

  /**
   * The test of ONE condition of a {@code when} entry.
   *
   * @param subject the subject's value
   * @param condition the condition
   * @param entry the entry the condition belongs to
   * @return the test, or {@code null} when the condition could not be read
   */
  private TacOperand whenConditionTest(
      TacOperand subject, KtWhenCondition condition, KtWhenEntry entry) {
    if (condition instanceof KtWhenConditionIsPattern isPattern) {
      // An `is` pattern: the subject's runtime type is the test.
      TacType tested = declaredTypeOf(isPattern.getTypeReference());
      TacOperand test = builder.newTemp(TacType.BOOLEAN);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.INSTANCEOF)
              .dest(test)
              .lhs1(subject)
              .ctorType(tested)
              .line(lineOf(entry))
              .build());
      if (isPattern.isNegated()) {
        TacOperand negated = builder.newTemp(TacType.BOOLEAN);
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.UNARYOP)
                .dest(negated)
                .lhs1(test)
                .unop(org.plumb.tac.ir.UnaryOperator.NOT)
                .line(lineOf(entry))
                .build());
        return negated;
      }
      return test;
    }
    if (condition instanceof KtWhenConditionWithExpression withExpression) {
      TacOperand expected = translateExpression(withExpression.getExpression());
      if (expected == null) {
        return null;
      }
      TacOperand test = builder.newTemp(TacType.BOOLEAN);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.BINOP)
              .dest(test)
              .lhs1(subject)
              .lhs2(expected)
              .binop(BinaryOperator.EQ)
              .line(lineOf(entry))
              .build());
      return test;
    }
    return null;
  }

  /**
   * The condition expression of a {@code when} entry, or {@code null}.
   *
   * <p>A {@code when} entry carries a list of conditions; a subject-less {@code when} uses the
   * first one. An {@code is} pattern has no expression -- it is a type test, which the TAC has no
   * opcode for -- so it is reported as absent rather than guessed at.
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
   * <p>The line is read from the file's {@link com.intellij.openapi.editor.Document}, which is the
   * authoritative line map. The Analysis API's bundled IntelliJ surface does not expose {@code
   * PsiFile.getLineNumber(int)}, so the document is the way in.
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
   * <p>{@code getFileDocument()} is a default method that may return {@code null} when the file has
   * no document (a stub-only file, for instance).
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
   * <p>A data class's {@code componentN} and {@code copy} come back from the Analysis API with the
   * origin {@link KaSymbolOrigin#SOURCE_MEMBER_GENERATED}. That is exact: it is the compiler
   * telling us, not a name-pattern guess.
   */
  static boolean isSynthetic(KaSymbolOrigin origin) {
    return origin == KaSymbolOrigin.SOURCE_MEMBER_GENERATED;
  }

  /**
   * Whether a callable is a data class's generated member.
   *
   * <p>Used as a second line of defence: a callable whose origin is {@code SOURCE_MEMBER_GENERATED}
   * is synthetic, and so is one named {@code copy} or {@code componentN} on a data class, even if
   * the origin says otherwise.
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
   * <p>Kept separate from {@link #isDataClassGenerated} so the frontend can use it when the owning
   * class's symbol is not available.
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
