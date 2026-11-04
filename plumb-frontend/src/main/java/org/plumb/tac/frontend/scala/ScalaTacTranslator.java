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
package org.plumb.tac.frontend.scala;

import dotty.tools.dotc.ast.Trees;
import dotty.tools.dotc.core.Contexts.Context;
import dotty.tools.dotc.core.Flags;
import dotty.tools.dotc.core.Names.TermName;
import dotty.tools.dotc.core.Symbols.Symbol;
import dotty.tools.dotc.core.Types.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;
import org.plumb.tac.translate.TacBuilder;

/**
 * Lowers a typed Scala 3 tree into Plumb's TAC.
 *
 * <p><b>What this lowers.</b> The subset the dataflow engine can act on: literals,
 * local reads and writes, field reads and writes, method calls, object creation,
 * arithmetic and comparison, branches, loops, blocks, and {@code match}. A {@code match}
 * becomes a branch chain -- one {@code INSTANCEOF} plus one {@code BRANCH} per typed
 * case, with a {@code JUMP} to a merge label -- which is exactly the shape a Java {@code
 * instanceof} chain has, so the engine needs no change to read it.
 *
 * <p><b>What this does not lower.</b> Pattern matching on non-reference types, by-name
 * arguments, implicit conversions, varargs (beyond their first argument), and closures.
 * A construct that is not lowered is reported as a problem rather than silently
 * dropped, and it downgrades the enclosing function's precision to {@link
 * IrPrecision#SOURCE_LOWERED} -- so a checker knows not to trust a precision-dependent
 * fact about it.
 *
 * <p><b>No new opcodes.</b> Everything here lowers into the existing instruction kinds:
 * {@code MOVE}, {@code BINOP}, {@code CALL}, {@code NEW}, {@code FIELDLOAD},
 * {@code FIELDSTORE}, {@code INSTANCEOF}, {@code BRANCH}, {@code JUMP}, {@code LABEL},
 * {@code RETURN}, {@code THROW}, {@code NOP}.
 *
 * <p><b>Variables.</b> The TAC's variable identity is name + temp flag. A Scala local's
 * source name <i>is</i> its TAC name, so a body reference to a parameter or a local
 * resolves to the same storage location rather than to a fresh temp. Scala's
 * name-mangled locals ({@code x$1}) keep their mangled names, which is what the
 * compiler itself uses and what keeps two distinct locals apart.
 */
final class ScalaTacTranslator {

  /** The compiler context the trees are read in. */
  private final Context ctx;

  /** Maps Scala types onto TAC types. */
  private final ScalaTypeMapper types;

  /** The builder the function being lowered is built into. */
  private TacBuilder builder;


  /**
   * The class the function being lowered belongs to.
   *
   * <p>It is what qualifies a lambda's target: the closure names the SHORT name of the function
   * that carries the body, while the definition the translator produced is named with the
   * qualified form. The call has to name the same function the definition produced, or the call
   * graph's edge points at nothing.
   */
  private String currentEnclosingClass;

  /** The name of the function being lowered; used for problem messages. */
  private String currentFunction = "<unknown>";

  /**
   * The locals of the function being lowered, by source name.
   *
   * <p>Keyed by the Scala source name, not by a {@link TacOperand.TacVar}: the TAC's
   * variable identity is name + temp flag, and a Scala local's name <i>is</i> its TAC
   * name.
   */
  private final Map<String, TacOperand.TacVar> locals = new HashMap<>();


  /**
   * Whether the function being lowered used a construct that could not be lowered.
   *
   * <p>When true the function's precision is downgraded to {@link
   * IrPrecision#SOURCE_LOWERED}.
   */
  private boolean degraded;

  /** The problems found while lowering, in the order they were found. */
  private final ProblemCollector problems = new ProblemCollector();

  /**
   * The structured problems of the UNIT-level failures -- the ones raised outside a
   * function's own lowering.
   *
   * <p>They are kept apart from the collector's own, because a unit-level failure has no
   * enclosing function to name: the {@code def} it happened in is the subject.
   */
  private final List<org.plumb.tac.ir.TacProgram.FunctionProblem> unitStructuredProblems =
      new ArrayList<>();

  /**
   * The module the unit being lowered belongs to, or {@code null} when there is no module
   * boundary.
   *
   * <p>It is recorded on every produced function, so a single-module analysis can tell this
   * module's functions from the ones it merely reads for symbol resolution.
   */
  private String module;

  /**
   * The SYNTHETIC functions this lowering produced, in the order they were produced.
   *
   * <p>A lambda becomes a synthetic function, exactly as the Java frontend does it: the body is
   * lowered into its own function and the lambda's use site becomes a CALL to it. That is what a
   * compiler does, and it is the only way the body's taint survives the function boundary.
   *
   * <p><b>Why it matters most for {@code for} comprehensions.</b> The Scala typer desugars
   * {@code for (x <- xs) body} into {@code xs.foreach(x => body)}. Before this, the lambda was
   * dropped, so the loop body lived in a function that was never called: the accumulator stayed at
   * its initial value, every taint that flowed through the loop was destroyed, and the analysis
   * reported the function as CLEAN. A silent wrong answer, not a visible gap.
   */

  /**
   * The names of the synthetic functions already created, so a name is never reused.
   *
   * <p>Two lambdas with the same name would be two functions the call graph cannot tell apart, and
   * the summary of one would be read for the other.
   */

  /** The counter the synthetic function names come from. */

  ScalaTacTranslator(Context ctx) {
    this.ctx = ctx;
    this.types = new ScalaTypeMapper(ctx);
  }

  /** Sets the module the unit being lowered belongs to. */
  void setModule(String module) {
    this.module = module == null || module.isBlank() ? null : module;
  }

  /**
   * Lowers a whole compilation unit.
   *
   * @param tree the typed compilation unit
   * @param functions the list the produced functions are added to
   * @param problems the list the found problems are added to
   */
  void translateUnit(
      Trees.Tree<Type> tree, List<TacFunction> functions, List<String> unitProblems) {
    if (tree == null) {
      return;
    }
    for (Trees.DefDef<Type> def : collectDefs(tree)) {
      try {
        TacFunction fn = translateDef(def);
        if (fn != null) {
          functions.add(fn);
        }
      } catch (RuntimeException ex) {
        // One malformed definition must not lose the rest of the unit.
        unitProblems.add(
            "lowering failure in "
                + defName(def)
                + ": "
                + ex.getClass().getSimpleName()
                + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
        // The structured form, so the gap travels with the IR. Without it a Scala
        // function that was only partly lowered produced findings that looked exactly
        // as complete as a fully lowered one.
        unitStructuredProblems.add(
            new org.plumb.tac.ir.TacProgram.FunctionProblem(
                defName(def),
                "TRANSLATION_ERROR",
                "lowering failure in " + defName(def),
                ex.getClass().getSimpleName(),
                -1,
                -1));
      }
    }
  }

  /**
   * The structured problems, in the order they were found.
   *
   * <p>These travel with the IR, so a source-less consumer can see WHICH construct was not
   * lowered, in WHICH function -- instead of only learning that "something went wrong
   * somewhere".
   */
  List<org.plumb.tac.ir.TacProgram.FunctionProblem> structuredProblems() {
    List<org.plumb.tac.ir.TacProgram.FunctionProblem> out = new ArrayList<>(problems.structured());
    out.addAll(unitStructuredProblems);
    return List.copyOf(out);
  }

  /** The problems in their string form, for the log. */
  List<String> problemMessages() {
    return problems.messages();
  }

  /** Collects every {@code DefDef} in the unit, in source order. */
  private List<Trees.DefDef<Type>> collectDefs(Trees.Tree<Type> tree) {
    List<Trees.DefDef<Type>> defs = new ArrayList<>();
    dotty.tools.dotc.ast.tpd.TreeOps$.MODULE$
        .foreachSubTree$extension(
            tree,
            new scala.Function1<Trees.Tree<Type>, scala.runtime.BoxedUnit>() {
              @Override
              public scala.runtime.BoxedUnit apply(Trees.Tree<Type> t) {
                if (t instanceof Trees.DefDef) {
                  defs.add((Trees.DefDef<Type>) t);
                }
                return scala.runtime.BoxedUnit.UNIT;
              }
            },
            ctx);
    return defs;
  }

  /**
   * Lowers one definition.
   *
   * @param def the definition
   * @return the lowered function, or {@code null} when the definition has no body
   */
  private TacFunction translateDef(Trees.DefDef<Type> def) {
    String name = defName(def);
    Symbol symbol = def.symbol(ctx);
    if (symbol == null) {
      return null;
    }

    // A constructor of a case class is generated by the compiler, but it is also
    // the class's public API -- a call site can reach it. It is translated, not
    // skipped. The synthetic flag only says "nobody wrote this".
    String qualified = qualifiedName(symbol, name);
    TacType returnType = types.map(def.tpt().tpe());
    List<TacOperand.TacVar> params = buildParams(def);

    this.currentFunction = qualified;
    this.currentEnclosingClass = enclosingClassName(symbol);
    this.locals.clear();
    this.degraded = false;

    TacBuilder b =
        new TacBuilder(
            new TacBuilder.FunctionSpec(qualified, signature(symbol, name, params), returnType),
            params);
    b.setSourceLanguage(SourceLanguage.SCALA);
    b.setModule(module);
    b.setIrPrecision(IrPrecision.SOURCE_FULL);
    if (isSynthetic(symbol, name)) {
      b.markSynthetic();
    }
    String enclosing = enclosingClassName(symbol);
    if (enclosing != null) {
      setEnclosingClass(b, enclosing);
    }
    this.builder = b;

    for (TacOperand.TacVar p : params) {
      locals.put(p.name(), p);
      b.addLocal(p);
    }

    Trees.Tree<Type> rhs = def.rhs(ctx);
    if (rhs == null || rhs instanceof Trees.EmptyTree) {
      // An abstract member, or a definition whose body the compiler generates.
      //
      // Whether that costs precision depends on what the body would have been.
      // A class constructor with parameters assigns them to fields, and the
      // compiler synthesizes those assignments AFTER the typer -- so at this
      // point the body is empty but the behaviour is not. Such a constructor is
      // degraded, because a checker cannot see the field writes.
      //
      // A constructor with no parameters, and an abstract member, have no body at
      // all: there is nothing to model and nothing lost, so they keep full
      // precision.
      if (hasUnmodelledBody(def, params)) {
        b.setIrPrecision(IrPrecision.SOURCE_LOWERED);
        problems.add(
            "the body of "
                + qualified
                + " is compiler-generated and was not lowered;"
                + " the function's precision was downgraded");
      }
      return b.build();
    }

    try {
      translateBody(rhs);
    } catch (RuntimeException ex) {
      problems.add(
          "lowering failure in "
              + qualified
              + ": "
              + ex.getClass().getSimpleName()
              + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
      b.setIrPrecision(IrPrecision.SOURCE_LOWERED);
    }

    if (degraded) {
      b.setIrPrecision(IrPrecision.SOURCE_LOWERED);
    }
    return b.build();
  }

  /**
   * Builds the parameter variables of a definition.
   *
   * <p>Only the first parameter list is a TAC parameter list. Scala's later lists are
   * curried or implicit parameter lists; they are still part of the function's
   * behaviour, so they are lowered as locals instead -- which keeps them visible to
   * the dataflow engine without pretending the function has a flat signature.
   */
  private List<TacOperand.TacVar> buildParams(Trees.DefDef<Type> def) {
    List<TacOperand.TacVar> params = new ArrayList<>();
    scala.collection.immutable.List<scala.collection.immutable.List<Trees.ValDef<Type>>> all =
        def.termParamss(ctx);
    scala.collection.Iterator<scala.collection.immutable.List<Trees.ValDef<Type>>> it =
        all.iterator();
    if (!it.hasNext()) {
      return params;
    }
    scala.collection.Iterator<Trees.ValDef<Type>> vit = it.next().iterator();
    while (vit.hasNext()) {
      Trees.ValDef<Type> v = vit.next();
      params.add(new TacOperand.TacVar(v.name().toString(), types.map(v.tpt().tpe()), false));
    }
    return params;
  }

  /**
   * Whether a definition with an empty body has behaviour the lowering does not model.
   *
   * <p>A class constructor that takes parameters assigns them to the class's fields.
   * Those assignments are synthesized by the compiler <i>after</i> the typer, so at
   * the point this frontend reads the tree the constructor's body is empty even though
   * the constructor does something. A checker that cannot see those writes must not
   * trust a precision-dependent fact about the constructor, so the constructor's
   * precision is downgraded.
   *
   * <p>A constructor with no parameters, and an abstract member, have no such hidden
   * behaviour: nothing was lost, and their precision stays full.
   *
   * @param def the definition
   * @param params the definition's parameters
   * @return whether the definition's behaviour is not fully modelled
   */
  private boolean hasUnmodelledBody(
      Trees.DefDef<Type> def, List<TacOperand.TacVar> params) {
    String name = defName(def);
    if (!"<init>".equals(name)) {
      // An abstract member has no body by definition.
      return false;
    }
    // A constructor with parameters assigns them to fields.
    return !params.isEmpty();
  }

  /** The qualified name of a definition, e.g. {@code "demo.Demo.add"}. */
  private String qualifiedName(Symbol symbol, String fallback) {
    try {
      String full = symbol.denot(ctx).fullName(ctx).toString();
      return full == null || full.isEmpty() ? fallback : full;
    } catch (RuntimeException ex) {
      return fallback;
    }
  }

  /** The short name of the class a definition lives in, or {@code null}. */
  private String enclosingClassName(Symbol symbol) {
    try {
      Symbol owner = symbol.denot(ctx).owner();
      if (owner == null) {
        return null;
      }
      String full = owner.denot(ctx).fullName(ctx).toString();
      if (full == null) {
        return null;
      }
      int dot = full.lastIndexOf('.');
      String simple = dot < 0 ? full : full.substring(dot + 1);
      // A module class is named after its companion object ("Plain$"). The class
      // the source declared is "Plain".
      return simple.endsWith("$") ? simple.substring(0, simple.length() - 1) : simple;
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /** The canonical signature of a definition. */
  private String signature(Symbol symbol, String fallback, List<TacOperand.TacVar> params) {
    StringBuilder sb = new StringBuilder(fallback).append('(');
    for (int i = 0; i < params.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(params.get(i).type().displayName());
    }
    sb.append(')');
    try {
      // The symbol's info is the whole method type, not the result type, so the
      // result is taken from the definition's declared type instead.
      Type result = symbol.denot(ctx).info(ctx);
      if (result != null) {
        Type resultType = result.resultType(ctx);
        if (resultType != null) {
          sb.append(':').append(types.map(resultType).displayName());
        }
      }
    } catch (RuntimeException ex) {
      // The result type is a nicety; the parameter list is the important part.
    }
    return sb.toString();
  }

  /** The name of a definition, for problem messages. */
  private String defName(Trees.DefDef<Type> def) {
    TermName name = def.name();
    return name == null ? "<anonymous>" : name.toString();
  }

  // ---------------------------------------------------------------- synthetic

  /**
   * Whether a definition is compiler-generated.
   *
   * <p><b>The primary test is the compiler's own flag.</b> dotc marks the members it
   * generates for a case class -- {@code copy}, {@code copy$default$N}, the {@code _N}
   * accessors -- and for a companion object -- {@code apply}, {@code unapply}, the
   * {@code product*} family -- with {@code Flags.Synthetic}. The compiler knows exactly
   * which members it generated, so its answer is authoritative.
   *
   * <p><b>The flag test is not a plain bitwise AND.</b> Scala's flags are 64 bits wide
   * and split into a two-bit "kind" field (bits 0 and 1: is this a term or a type?)
   * and the carrier bits that start at bit 2. A plain {@code flags & Flags.Synthetic()}
   * is therefore <i>always</i> non-zero, because the kind bits alone satisfy it. The
   * correct test masks off the kind bits first: {@code (flags >>> 2) & (flag >>> 2)}.
   *
   * <p><b>The owner and name checks are the fallback.</b> If the flag is missing (a
   * different dotc version lays the flags out differently), the owner and the name
   * still identify the generated members. {@code <init>} is never synthetic: the
   * constructor is the class's public API, and a checker that suppresses findings
   * about synthetic methods must not suppress them about the constructor.
   */
  private boolean isSynthetic(Symbol symbol, String name) {
    try {
      long flags = symbol.denot(ctx).flags(ctx);
      if (hasFlag(flags, Flags.Synthetic())) {
        return true;
      }
    } catch (RuntimeException ex) {
      // Fall through to the name-based test.
    }
    if ("<init>".equals(name)) {
      return false;
    }
    Symbol owner = symbol.denot(ctx).owner();
    if (owner == null) {
      return false;
    }
    long ownerFlags = owner.denot(ctx).flags(ctx);
    boolean ownerIsCase =
        hasFlag(ownerFlags, Flags.CaseClass()) || hasFlag(ownerFlags, Flags.CaseVal());
    boolean ownerIsModule = hasFlag(ownerFlags, Flags.ModuleClass());
    if (ownerIsModule) {
      return MODULE_SYNTHETIC_NAMES.contains(name);
    }
    if (ownerIsCase) {
      return CASE_SYNTHETIC_NAMES.contains(name)
          || name.startsWith("copy$default$")
          || isTupleAccessor(name);
    }
    return false;
  }

  /** The companion-object members dotc generates. */
  private static final Set<String> MODULE_SYNTHETIC_NAMES =
      Set.of(
          "apply",
          "unapply",
          "unapplySeq",
          "fromProduct",
          "productArity",
          "productPrefix",
          "productElement",
          "productElementName",
          "toString",
          "canEqual");

  /** The case-class members dotc generates. */
  private static final Set<String> CASE_SYNTHETIC_NAMES =
      Set.of(
          "copy",
          "productArity",
          "productPrefix",
          "productElement",
          "productElementName",
          "canEqual");

  /** Whether a name is a tuple accessor ({@code _1}, {@code _2}, ...). */
  private static boolean isTupleAccessor(String name) {
    if (name.length() < 2 || name.charAt(0) != '_') {
      return false;
    }
    for (int i = 1; i < name.length(); i++) {
      if (!Character.isDigit(name.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a flag is set.
   *
   * <p>Scala's flags carry their meaning in the bits above the two-bit "kind" field,
   * so the test shifts the kind bits out before comparing.
   */
  private static boolean hasFlag(long flags, long flag) {
    return ((flags >>> 2) & (flag >>> 2)) != 0;
  }

  // ---------------------------------------------------------------- lowering

  /** Lowers a definition's body, as a statement. */
  private void translateBody(Trees.Tree<Type> body) {
    if (body instanceof Trees.Block) {
      translateBlock((Trees.Block<Type>) body);
      return;
    }
    TacOperand value = translateExpr(body);
    if (value != null) {
      emitReturn(value, body);
    }
  }

  /** Lowers a block: its statements in order, then its value expression. */
  private void translateBlock(Trees.Block<Type> block) {
    for (Trees.Tree<Type> stat : toJavaList(block.stats())) {
      translateStatement(stat);
    }
    Trees.Tree<Type> expr = block.expr();
    if (expr != null && !(expr instanceof Trees.EmptyTree)) {
      TacOperand value = translateExpr(expr);
      if (value != null) {
        emitReturn(value, expr);
      }
    }
  }

  /** Lowers one statement. */
  private void translateStatement(Trees.Tree<Type> stat) {
    if (stat instanceof Trees.ValDef) {
      translateValDef((Trees.ValDef<Type>) stat);
      return;
    }
    if (stat instanceof Trees.DefDef) {
      // A local function or class. The compiler generates a separate function for
      // it; lowering it here would double-count it.
      return;
    }
    // Everything else is an expression. Its value is discarded -- a statement's
    // value only matters for the last expression of a block, which the block
    // lowering handles separately.
    translateExpr(stat);
  }

  /** Lowers a local variable definition. */
  private void translateValDef(Trees.ValDef<Type> val) {
    String name = val.name().toString();
    TacType type = types.map(val.tpt().tpe());
    Trees.Tree<Type> rhs = val.rhs(ctx);
    TacOperand.TacVar target = new TacOperand.TacVar(name, type, false);
    locals.put(name, target);
    builder.addLocal(target);
    if (rhs == null || rhs instanceof Trees.EmptyTree) {
      // A parameter-like definition with no initialiser. Nothing to lower.
      return;
    }
    TacOperand value = translateExpr(rhs);
    if (value != null) {
      emitMove(target, value, rhs);
    }
  }

  /**
   * Lowers an expression to a value.
   *
   * @param tree the expression
   * @return the value, or {@code null} when the expression produces nothing
   */
  private TacOperand translateExpr(Trees.Tree<Type> tree) {
    if (tree == null || tree instanceof Trees.EmptyTree) {
      return null;
    }
    if (tree instanceof Trees.Literal) {
      return translateLiteral((Trees.Literal<Type>) tree);
    }
    if (tree instanceof Trees.Ident) {
      return translateIdent((Trees.Ident<Type>) tree);
    }
    if (tree instanceof Trees.Select) {
      return translateSelect((Trees.Select<Type>) tree);
    }
    if (tree instanceof Trees.Apply) {
      return translateApply((Trees.Apply<Type>) tree);
    }
    if (tree instanceof Trees.TypeApply) {
      // A call with explicit type arguments ("List.apply[String](...)"). The type
      // arguments do not change the runtime behaviour, so the call is lowered as an
      // ordinary one.
      return translateTypeApply((Trees.TypeApply<Type>) tree);
    }
    if (tree instanceof Trees.New) {
      return translateNew((Trees.New<Type>) tree);
    }
    if (tree instanceof Trees.Assign) {
      return translateAssign((Trees.Assign<Type>) tree);
    }
    if (tree instanceof Trees.If) {
      return translateIf((Trees.If<Type>) tree);
    }
    if (tree instanceof Trees.WhileDo) {
      return translateWhile((Trees.WhileDo<Type>) tree);
    }
    if (tree instanceof Trees.Block) {
      return translateBlockValue((Trees.Block<Type>) tree);
    }
    if (tree instanceof Trees.Match) {
      return translateMatch((Trees.Match<Type>) tree);
    }
    if (tree instanceof Trees.Bind) {
      return translateBind((Trees.Bind<Type>) tree);
    }
    if (tree instanceof Trees.Typed) {
      // An ascription ("x: Int") or a type pattern's scrutinee. The type carries no
      // runtime behaviour, so the expression is lowered as-is.
      return translateExpr(((Trees.Typed<Type>) tree).expr());
    }
    if (tree instanceof Trees.Closure) {
      // A lambda. Its body is lowered into a synthetic function and the use site
      // becomes a CALL to it, so the body's taint survives the function boundary.
      return translateClosure((Trees.Closure<Type>) tree);
    }
    if (tree instanceof Trees.This) {
      // "this" is the receiver. A field read off it is a FIELDLOAD; the receiver
      // itself is not a value the dataflow engine tracks.
      return null;
    }
    if (tree instanceof Trees.Return) {
      Trees.Return<Type> ret = (Trees.Return<Type>) tree;
      TacOperand value = translateExpr(ret.expr());
      if (value != null) {
        emitReturn(value, tree);
      }
      return null;
    }
    if (tree instanceof dotty.tools.dotc.ast.untpd.Throw) {
      dotty.tools.dotc.ast.untpd.Throw thr = (dotty.tools.dotc.ast.untpd.Throw) tree;
      TacOperand value = translateExpr(thr.expr());
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.THROW).lhs1(value).line(lineOf(tree)).build());
      return null;
    }
    // Anything else (a lambda, an implicit conversion, a by-name argument) is not
    // lowered. The function is degraded, so a checker knows not to trust it.
    degraded = true;
    problems.add(
        "could not lower "
            + tree.getClass().getSimpleName()
            + " in "
            + currentFunction
            + "; the function's precision was downgraded");
    return null;
  }

  /** Lowers a block used as an expression: its value is the last expression's value. */
  private TacOperand translateBlockValue(Trees.Block<Type> block) {
    for (Trees.Tree<Type> stat : toJavaList(block.stats())) {
      translateStatement(stat);
    }
    Trees.Tree<Type> expr = block.expr();
    if (expr == null || expr instanceof Trees.EmptyTree) {
      return null;
    }
    return translateExpr(expr);
  }

  /** Lowers a literal. */
  private TacOperand translateLiteral(Trees.Literal<Type> lit) {
    dotty.tools.dotc.core.Constants.Constant constant = lit._1();
    if (constant == null) {
      return null;
    }
    Object value = constant.value();
    switch (constant.tag()) {
      case TAG_BOOLEAN:
        return TacOperand.Const.ofBool(Boolean.TRUE.equals(value));
      case TAG_BYTE:
      case TAG_SHORT:
        return TacOperand.Const.ofInt(((Number) value).intValue());
      case TAG_CHAR:
        return TacOperand.Const.ofChar((char) ((Number) value).intValue());
      case TAG_INT:
        return TacOperand.Const.ofInt(((Number) value).intValue());
      case TAG_LONG:
        return TacOperand.Const.ofLong(((Number) value).longValue());
      case TAG_FLOAT:
        return TacOperand.Const.ofFloat(((Number) value).floatValue());
      case TAG_DOUBLE:
        return TacOperand.Const.ofDouble(((Number) value).doubleValue());
      case TAG_STRING:
        return TacOperand.Const.ofString((String) value);
      case TAG_NULL:
        return TacOperand.Const.ofNull();
      case TAG_UNIT:
        return null;
      default:
        return null;
    }
  }

  /**
   * The constant tags dotc uses on a {@code Constant}.
   *
   * <p>These are {@code inline val}s in dotc's {@code Constants}, which erase to plain
   * integers -- there is no static field to read from Java. The values are part of
   * dotc's own contract and have not changed since Scala 3.0.
   */
  private static final int TAG_UNIT = 1;

  private static final int TAG_BOOLEAN = 2;
  private static final int TAG_BYTE = 3;
  private static final int TAG_SHORT = 4;
  private static final int TAG_CHAR = 5;
  private static final int TAG_INT = 6;
  private static final int TAG_LONG = 7;
  private static final int TAG_FLOAT = 8;
  private static final int TAG_DOUBLE = 9;
  private static final int TAG_STRING = 10;
  private static final int TAG_NULL = 11;

  /** Lowers an identifier: a local read, or a reference to something global. */
  private TacOperand translateIdent(Trees.Ident<Type> ident) {
    String name = ident.name().toString();
    TacOperand.TacVar local = locals.get(name);
    if (local != null) {
      return local;
    }
    // Not a local: a reference to a field of the enclosing class, to a top-level
    // value, or to a companion object. A field read is a FIELDLOAD off the receiver.
    Symbol symbol = ident.symbol(ctx);
    if (symbol != null && symbol.isTerm(ctx)) {
      return translateMemberAccess(null, name, symbol, ident);
    }
    // An unknown name. It is degraded rather than dropped, so the function's
    // precision tells a checker that something is missing.
    degraded = true;
    problems.add("unresolved identifier '" + name + "' in " + currentFunction);
    return null;
  }

  /** Lowers a selection ({@code this.field}, {@code obj.field}, {@code obj.method}). */
  private TacOperand translateSelect(Trees.Select<Type> select) {
    String name = select.name().toString();
    Trees.Tree<Type> qualifier = select.qualifier();
    Symbol symbol = select.symbol(ctx);

    // A selection off "this" is a field read.
    if (qualifier instanceof Trees.This) {
      return translateMemberAccess(null, name, symbol, select);
    }
    // A selection off a local is a field read off that local.
    if (qualifier instanceof Trees.Ident) {
      String qualName = ((Trees.Ident<Type>) qualifier).name().toString();
      TacOperand.TacVar receiver = locals.get(qualName);
      if (receiver != null) {
        return translateMemberAccess(receiver, name, symbol, select);
      }
    }
    // Otherwise the qualifier is a value: the selection is a method call or a field
    // read on an arbitrary expression.
    TacOperand receiver = translateExpr(qualifier);
    if (receiver == null) {
      return null;
    }
    return translateMemberAccess(receiver, name, symbol, select);
  }

  /**
   * Lowers a member access.
   *
   * @param receiver the receiver, or {@code null} for an implicit {@code this}
   * @param name the member's name
   * @param symbol the member's symbol, or {@code null} when it is unknown
   * @param tree the tree being lowered, for the source position
   * @return the read value
   */
  private TacOperand translateMemberAccess(
      TacOperand receiver, String name, Symbol symbol, Trees.Tree<Type> tree) {
    if (symbol != null && symbol.isTerm(ctx)) {
      Type resultType = symbol.denot(ctx).info(ctx);
      // A "val" or "var" is compiled into an accessor method, but reading it is a
      // field read -- not a method call. Only a real method, read as a value, is
      // something the lowering cannot express.
      if (isMethodSymbol(symbol) && !isAccessorSymbol(symbol)) {
        // A method reference used as a value ("f" where f is a method) is not a
        // call; lowering it as one would invent behaviour.
        degraded = true;
        problems.add("method reference '" + name + "' in " + currentFunction + " was not lowered");
        return null;
      }
      TacType type = types.map(resultType);
      TacOperand.TacVar dest = builder.newTemp(type);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
              .dest(dest)
              .lhs1(receiver == null ? thisReceiver(type) : receiver)
              .fieldName(name)
              .line(lineOf(tree))
              .build());
      return dest;
    }
    return null;
  }

  /** The receiver for an implicit {@code this} field read. */
  private TacOperand thisReceiver(TacType type) {
    // The Java frontend names the implicit receiver "this" and declares it as a
    // local, so a FIELDLOAD off it reads a named variable rather than a temp
    // nobody ever assigned. The same convention is used here.
    TacOperand.TacVar self = locals.get(THIS_NAME);
    if (self == null) {
      self = new TacOperand.TacVar(THIS_NAME, type, false);
      locals.put(THIS_NAME, self);
      builder.addLocal(self);
    }
    return self;
  }

  /**
   * The name the implicit {@code this} receiver is given.
   *
   * <p>It matches the Java frontend's convention, so a checker that reasons about
   * "the receiver" reads the same shape whichever language the code came from.
   */
  private static final String THIS_NAME = "this";

  /** Whether a symbol denotes a method rather than a value. */
  private boolean isMethodSymbol(Symbol symbol) {
    try {
      return symbol.denot(ctx).is(Flags.Method(), ctx)
          || symbol.denot(ctx).is(Flags.ParamAccessor(), ctx);
    } catch (RuntimeException ex) {
      return false;
    }
  }

  /**
   * Whether a symbol is the accessor the compiler generated for a {@code val} or a
   * {@code var}.
   *
   * <p>Scala compiles a {@code val x} into a field plus a getter method. Reading the
   * {@code val} is a field read, and the TAC models it as a {@code FIELDLOAD} -- the
   * getter is an implementation detail the source never mentions.
   */
  private boolean isAccessorSymbol(Symbol symbol) {
    try {
      return symbol.denot(ctx).is(Flags.Accessor(), ctx);
    } catch (RuntimeException ex) {
      return false;
    }
  }

  /** Lowers a call. */
  private TacOperand translateApply(Trees.Apply<Type> apply) {
    Trees.Tree<Type> fun = apply.fun();
    List<Trees.Tree<Type>> args = toJavaList(apply.args());

    // A constructor call: "new P(x, y)" is an Apply whose function is a Select of
    // "<init>" on a New.
    if (fun instanceof Trees.Select) {
      Trees.Select<Type> select = (Trees.Select<Type>) fun;
      if ("<init>".equals(select.name().toString())
          && select.qualifier() instanceof Trees.New) {
        return translateNew((Trees.New<Type>) select.qualifier(), args, apply);
      }
    }

    // A binary operator: "a + b" is an Apply of a Select of "+" to one argument.
    if (fun instanceof Trees.Select && args.size() == 1) {
      Trees.Select<Type> select = (Trees.Select<Type>) fun;
      String op = select.name().toString();
      BinaryOperator binop = binaryOperator(op);
      if (binop != null) {
        return translateBinary(binop, select, args.get(0), apply);
      }
    }

    // An ordinary method call.
    return translateCall(fun, args, apply);
  }

  /**
   * The receiver of a call, or {@code null} when the call has none.
   *
   * <p>An implicit {@code this} is not a receiver the dataflow engine tracks, so it
   * yields {@code null}: the TAC's {@code CALL} then carries only the real arguments,
   * exactly as a static call does.
   *
   * @param fun the function being called
   * @return the receiver, or {@code null}
   */
  private TacOperand receiverOf(Trees.Tree<Type> fun) {
    if (!(fun instanceof Trees.Select)) {
      return null;
    }
    Trees.Tree<Type> qualifier = ((Trees.Select<Type>) fun).qualifier();
    if (qualifier instanceof Trees.This) {
      return null;
    }
    if (qualifier instanceof Trees.Ident) {
      String name = ((Trees.Ident<Type>) qualifier).name().toString();
      TacOperand local = locals.get(name);
      if (local != null) {
        return local;
      }
      // A receiver that is a field of the enclosing class: a field read.
      Symbol symbol = qualifier.symbol(ctx);
      if (symbol != null && symbol.isTerm(ctx) && !isMethodSymbol(symbol)) {
        return translateMemberAccess(null, name, symbol, qualifier);
      }
      return null;
    }
    return translateExpr(qualifier);
  }

  /** Lowers a call with explicit type arguments. */
  private TacOperand translateTypeApply(Trees.TypeApply<Type> apply) {
    return translateCall(apply.fun(), toJavaList(apply.args()), apply);
  }

  /**
   * Lowers an ordinary method call.
   *
   * <p><b>The receiver comes first.</b> A TAC {@code CALL} has no receiver slot: the
   * receiver is passed as the first argument. That is the contract the Java frontend
   * established, and the dataflow engine reads it: without the receiver in the
   * argument list, a dereference of a possibly-null receiver ({@code value.length})
   * would be invisible to the engine, and the null-dereference rule would never fire.
   *
   * @param fun the function being called
   * @param args the argument expressions
   * @param tree the call tree, for the source position
   * @return the call's result
   */
  private TacOperand translateCall(
      Trees.Tree<Type> fun, List<Trees.Tree<Type>> args, Trees.Tree<Type> tree) {
    List<TacOperand> operands = new ArrayList<>();

    // The receiver, if the call has one.
    TacOperand receiver = receiverOf(fun);
    if (receiver != null) {
      operands.add(receiver);
    }

    for (Trees.Tree<Type> arg : args) {
      TacOperand value = translateExpr(arg);
      if (value != null) {
        operands.add(value);
      }
    }

    // The arity is the ARGUMENT count. The receiver is not an argument in Scala's
    // calling convention (it is the dispatch receiver), so `a.b(x)` has arity 1.
    // The arity is what the sink patterns' trailing `(/\d+)?` group matches against,
    // so it has to be the real argument count.
    String callee = calleeSignature(fun, args.size());
    Symbol symbol = fun.symbol(ctx);
    Type resultType = null;
    if (symbol != null) {
      try {
        Type info = symbol.denot(ctx).info(ctx);
        if (info != null) {
          // The symbol's info is the method type; the call's result is its result
          // type.
          Type result = info.resultType(ctx);
          resultType = result != null ? result : info;
        }
      } catch (RuntimeException ex) {
        resultType = null;
      }
    }
    TacType type = resultType == null ? TacType.UNKNOWN : types.map(resultType);
    TacOperand.TacVar dest = builder.newTemp(type);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(dest)
            .calleeSig(callee)
            .args(operands)
            .line(lineOf(tree))
            .build());
    return dest;
  }

  /**
   * The signature of a called function, in the form the sink families match: {@code
   * "java.sql.Statement.execute/1"}.
   *
   * <p><b>The form matters, and it is not the dotc form.</b> The sink families in {@code
   * sink-families.yaml} are anchored to a fully qualified name with a DOT separator and an arity
   * suffix: {@code '^java\.sql\.Statement\.execute(/\d+)?'}. The DFA matches a call against them
   * by its callee signature. A signature in any other shape matches NONE of them -- not one pattern
   * in the file -- so every taint sink rule is silently inert on Scala code.
   *
   * <p><b>Two things were wrong before.</b> The owner was separated by {@code #} (dotc's own
   * convention) rather than by {@code .}, and the arity suffix was missing entirely. Either one is
   * enough to make every pattern miss.
   *
   * <p>The owner comes from the RESOLVED symbol, not from the receiver's syntactic type. That is
   * what lets a Scala call to a Java method reach the SAME family a Java call reaches: {@code
   * stmt.execute(x)} resolves to a method declared in {@code java.sql.Statement}, so the owner is
   * that class, and the FQN-anchored pattern matches.
   *
   * <p>When the symbol does not resolve, the bare method name is returned. A bare name still
   * carries the call graph's bare-name edge; it just does not reach a FQN-anchored sink.
   */
  private String calleeSignature(Trees.Tree<Type> fun, int argCount) {
    String name = "<unknown>";
    if (fun instanceof Trees.Select) {
      name = ((Trees.Select<Type>) fun).name().toString();
    } else if (fun instanceof Trees.Ident) {
      name = ((Trees.Ident<Type>) fun).name().toString();
    }
    String owner = ownerOf(fun);
    if (owner == null || owner.isEmpty()) {
      return name + "/" + argCount;
    }
    return owner + "." + name + "/" + argCount;
  }

  /**
   * The fully qualified name of the class that declares the called member, or {@code null}.
   *
   * <p>Resolved through the compiler's symbol table: the call tree's symbol names the member, and
   * the member's own full name names the class it is declared in.
   *
   * <p><b>Why the symbol and not the receiver's type.</b> The receiver's type is what the call is
   * made ON; the owner is what the method is DECLARED in. It is the declaration that the sink
   * families name.
   *
   * <p>A call that does not resolve yields {@code null}, and the caller falls back to the bare
   * name. A WRONG owner would be worse than no owner: it would match the wrong sink family.
   */
  private String ownerOf(Trees.Tree<Type> fun) {
    Symbol symbol = fun.symbol(ctx);
    if (symbol == null) {
      return null;
    }
    try {
      // The member's own full name is "owner.member". Splitting it is more reliable than
      // asking for the owner separately: dotc's `fullName` on a member of a Java class
      // gives the Java class's own FQN, which is what the shared sink families name.
      String full = symbol.denot(ctx).fullName(ctx).toString();
      if (full != null && !full.isEmpty() && full.indexOf('#') < 0) {
        int dot = full.lastIndexOf('.');
        if (dot > 0) {
          return full.substring(0, dot);
        }
      }
    } catch (RuntimeException | LinkageError ex) {
      // Fall through to the owner-based form.
    }
    try {
      Symbol ownerSymbol = symbol.denot(ctx).owner();
      if (ownerSymbol != null) {
        String owner = ownerSymbol.denot(ctx).fullName(ctx).toString();
        if (owner != null && !owner.isEmpty()) {
          return owner;
        }
      }
    } catch (RuntimeException | LinkageError ex) {
      // Fall through to the bare name.
    }
    return null;
  }

  /** Lowers a binary operation. */
  private TacOperand translateBinary(
      BinaryOperator binop, Trees.Select<Type> select, Trees.Tree<Type> rhs, Trees.Tree<Type> tree) {
    Trees.Tree<Type> lhsTree = select.qualifier();
    TacOperand lhs = translateExpr(lhsTree);
    TacOperand rhsValue = translateExpr(rhs);
    if (lhs == null || rhsValue == null) {
      return null;
    }
    TacType type = types.map(tree.tpe());
    TacOperand.TacVar dest = builder.newTemp(type);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BINOP)
            .dest(dest)
            .lhs1(lhs)
            .lhs2(rhsValue)
            .binop(binop)
            .line(lineOf(tree))
            .build());
    return dest;
  }

  /** The TAC binary operator for a Scala operator name, or {@code null}. */
  private static BinaryOperator binaryOperator(String name) {
    switch (name) {
      case "+":
      case "add":
        return BinaryOperator.ADD;
      case "-":
      case "subtract":
        return BinaryOperator.SUB;
      case "*":
      case "multiply":
        return BinaryOperator.MUL;
      case "/":
      case "divide":
        return BinaryOperator.DIV;
      case "%":
      case "mod":
        return BinaryOperator.MOD;
      case "&":
      case "and":
        return BinaryOperator.AND;
      case "|":
      case "or":
        return BinaryOperator.OR;
      case "^":
      case "xor":
        return BinaryOperator.XOR;
      case "<<":
      case "shiftLeft":
        return BinaryOperator.SHL;
      case ">>":
      case "shiftRight":
        return BinaryOperator.SHR;
      case ">>>":
      case "ushiftRight":
        return BinaryOperator.USHR;
      case "==":
      case "equals":
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
      case "&&":
        return BinaryOperator.LAND;
      case "||":
        return BinaryOperator.LOR;
      default:
        return null;
    }
  }

  /** Lowers an object creation. */
  private TacOperand translateNew(Trees.New<Type> newTree) {
    return translateNew(newTree, List.of(), newTree);
  }

  /**
   * Lowers an object creation with arguments.
   *
   * @param newTree the {@code New} tree
   * @param args the constructor arguments
   * @param tree the tree being lowered, for the source position
   * @return the new object's value
   */
  private TacOperand translateNew(
      Trees.New<Type> newTree, List<Trees.Tree<Type>> args, Trees.Tree<Type> tree) {
    List<TacOperand> operands = new ArrayList<>();
    for (Trees.Tree<Type> arg : args) {
      TacOperand value = translateExpr(arg);
      if (value != null) {
        operands.add(value);
      }
    }
    Type tpe = newTree.tpt().tpe();
    TacType type = types.map(tpe);
    TacOperand.TacVar dest = builder.newTemp(type);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.NEW)
            .dest(dest)
            .ctorType(type)
            .args(operands)
            .line(lineOf(tree))
            .build());
    return dest;
  }

  /** Lowers an assignment. */
  private TacOperand translateAssign(Trees.Assign<Type> assign) {
    Trees.Tree<Type> lhsTree = assign.lhs();
    Trees.Tree<Type> rhsTree = assign.rhs();
    TacOperand value = translateExpr(rhsTree);

    if (lhsTree instanceof Trees.Ident) {
      String name = ((Trees.Ident<Type>) lhsTree).name().toString();
      TacOperand.TacVar target = locals.get(name);
      if (target == null) {
        // An assignment to an undeclared name: a field of the enclosing class.
        Symbol symbol = lhsTree.symbol(ctx);
        return translateFieldStore(null, name, value, symbol, assign);
      }
      if (value != null) {
        emitMove(target, value, assign);
      }
      return target;
    }
    if (lhsTree instanceof Trees.Select) {
      Trees.Select<Type> select = (Trees.Select<Type>) lhsTree;
      String name = select.name().toString();
      Symbol symbol = select.symbol(ctx);
      Trees.Tree<Type> qualifier = select.qualifier();
      TacOperand receiver = null;
      if (qualifier instanceof Trees.Ident) {
        String qualName = ((Trees.Ident<Type>) qualifier).name().toString();
        receiver = locals.get(qualName);
      } else if (!(qualifier instanceof Trees.This)) {
        receiver = translateExpr(qualifier);
      }
      return translateFieldStore(receiver, name, value, symbol, assign);
    }
    degraded = true;
    problems.add("could not lower an assignment in " + currentFunction);
    return value;
  }

  /**
   * Lowers a field write.
   *
   * @param receiver the receiver, or {@code null} for an implicit {@code this}
   * @param name the field's name
   * @param value the value being written
   * @param symbol the field's symbol, or {@code null}
   * @param tree the tree being lowered, for the source position
   * @return the written value
   */
  private TacOperand translateFieldStore(
      TacOperand receiver, String name, TacOperand value, Symbol symbol, Trees.Tree<Type> tree) {
    if (value == null) {
      return null;
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
            .dest(value)
            .lhs1(receiver == null ? thisReceiver(TacType.UNKNOWN) : receiver)
            .fieldName(name)
            .line(lineOf(tree))
            .build());
    return value;
  }

  /**
   * Lowers an {@code if} expression.
   *
   * <p>The shape is the one the Java frontend produces: a {@code BRANCH} to the "then"
   * block, a {@code JUMP} past the "else" block, and a merge label at the end. The
   * "else" block is emitted between the "then" block's jump and the merge label, so
   * the control flow is: true -> then -> jump to end; false -> else -> fall through to
   * end.
   */
  private TacOperand translateIf(Trees.If<Type> ifTree) {
    String thenLabel = builder.newLabel("then");
    String elseLabel = builder.newLabel("else");
    String endLabel = builder.newLabel("endif");

    TacOperand cond = translateExpr(ifTree.cond());
    if (cond == null) {
      degraded = true;
      problems.add("could not lower an if-condition in " + currentFunction);
      return null;
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(cond)
            .trueLabel(thenLabel)
            .falseLabel(elseLabel)
            .line(lineOf(ifTree))
            .build());

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(thenLabel).build());
    translateStatement(ifTree.thenp());
    builder.emit(TacInstruction.builder(TacInstruction.Kind.JUMP).label(endLabel).build());

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(elseLabel).build());
    Trees.Tree<Type> elsep = ifTree.elsep();
    if (elsep != null && !(elsep instanceof Trees.EmptyTree)) {
      translateStatement(elsep);
    }

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(endLabel).build());
    return null;
  }

  /**
   * Lowers a {@code while} loop.
   *
   * <p>The shape is a labelled condition, a {@code BRANCH} to the body or past it, and
   * a {@code JUMP} back to the condition -- the standard loop shape, the same one the
   * Java frontend produces for a {@code while} statement.
   */
  private TacOperand translateWhile(Trees.WhileDo<Type> whileTree) {
    String condLabel = builder.newLabel("whileCond");
    String bodyLabel = builder.newLabel("whileBody");
    String endLabel = builder.newLabel("whileEnd");

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(condLabel).build());
    TacOperand cond = translateExpr(whileTree.cond());
    if (cond == null) {
      degraded = true;
      problems.add("could not lower a while-condition in " + currentFunction);
      return null;
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(cond)
            .trueLabel(bodyLabel)
            .falseLabel(endLabel)
            .line(lineOf(whileTree))
            .build());

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(bodyLabel).build());
    translateStatement(whileTree.body());
    builder.emit(TacInstruction.builder(TacInstruction.Kind.JUMP).label(condLabel).build());

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(endLabel).build());
    return null;
  }

  /**
   * Lowers a {@code match} into a branch chain.
   *
   * <p><b>The shape.</b> A {@code match} becomes a chain of tests, one per case:
   *
   * <pre>
   *   test1 = selector instanceof T1
   *   if test1 goto case1 else goto case2
   * case1:
   *   ... case 1's body ...
   *   goto end
   * case2:
   *   test2 = selector instanceof T2
   *   if test2 goto case2body else goto case3
   *   ...
   * end:
   * </pre>
   *
   * <p>Each case's body is emitted inline after its own test, and the <i>next</i> case's
   * test follows it. The false edge of a test goes to the next case, not to the end --
   * that is what makes it a chain rather than a series of independent tests.
   *
   * <p>A wildcard case matches everything, so it has no test: its body is emitted
   * directly and the chain ends there.
   *
   * <p><b>Why the function's precision is downgraded.</b> The {@code match} is lowered
   * away: the branch chain says which case runs, but it does not carry the pattern's
   * binding information the way the source did -- a narrowed binding is a plain move
   * here, with no type refinement attached to it. A checker must therefore not trust a
   * precision-dependent fact (such as "this value is non-null because the pattern
   * narrowed it") about a function whose {@code match} was lowered. That is exactly
   * what {@link IrPrecision#SOURCE_LOWERED} means.
   */
  private TacOperand translateMatch(Trees.Match<Type> match) {
    degraded = true;
    // The gap is RECORDED, not only flagged. A degraded function whose degradation is
    // invisible produces findings that look exactly as complete as a fully lowered one --
    // and the degradation is the whole point of the flag.
    problems.add(
        "a pattern match was lowered as a branch chain without its pattern bindings in "
            + currentFunction);

    TacOperand selector = translateExpr(match.selector());
    if (selector == null) {
      problems.add("could not lower a match selector in " + currentFunction);
      return null;
    }

    String endLabel = builder.newLabel("matchEnd");
    List<Trees.CaseDef<Type>> cases = toJavaList(match.cases());

    // The labels are generated up front, one per case, because a case's false edge
    // has to name the label the NEXT case starts at. Generating a label lazily
    // would leave the false edge pointing at a label that is never emitted.
    List<String> caseLabels = new ArrayList<>(cases.size());
    for (int i = 0; i < cases.size(); i++) {
      caseLabels.add(builder.newLabel("matchCase"));
    }

    for (int i = 0; i < cases.size(); i++) {
      Trees.CaseDef<Type> caseDef = cases.get(i);
      Trees.Tree<Type> pattern = caseDef.pat();
      String bodyLabel = caseLabels.get(i);
      // The case after this one, or the end of the match.
      String nextLabel = i + 1 < cases.size() ? caseLabels.get(i + 1) : endLabel;

      // A typed pattern ("case s: String =>") tests the selector's runtime type.
      Type testedType = testedTypeOf(pattern);
      if (testedType != null) {
        TacType type = types.map(testedType);
        TacOperand.TacVar test = builder.newTemp(TacType.BOOLEAN);
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.INSTANCEOF)
                .dest(test)
                .lhs1(selector)
                .ctorType(type)
                .line(lineOf(caseDef))
                .build());
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.BRANCH)
                .lhs1(test)
                .trueLabel(bodyLabel)
                .falseLabel(nextLabel)
                .line(lineOf(caseDef))
                .build());
        builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(bodyLabel).build());

        // The pattern's binding: "s" receives the selector narrowed to the tested
        // type. The narrowing itself is not expressible in the TAC, so the binding
        // is a plain move -- which is why the precision is downgraded.
        String bound = boundName(pattern);
        if (bound != null && !"_".equals(bound)) {
          TacOperand.TacVar target = new TacOperand.TacVar(bound, type, false);
          locals.put(bound, target);
          builder.addLocal(target);
          emitMove(target, selector, caseDef);
        }
      } else {
        // A constant pattern ("case 1 =>") compares the selector against a value.
        Trees.Tree<Type> constant = constantOf(pattern);
        if (constant == null) {
          // A wildcard case: it matches everything, so there is no test. Its body is
          // emitted directly and the chain ends there. The label is still emitted,
          // because the previous case's false edge names it.
          builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(bodyLabel).build());
          translateCaseBody(caseDef);
          builder.emit(TacInstruction.builder(TacInstruction.Kind.JUMP).label(endLabel).build());
          builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(endLabel).build());
          return null;
        }
        TacOperand expected = translateExpr(constant);
        if (expected == null) {
          continue;
        }
        TacOperand.TacVar test = builder.newTemp(TacType.BOOLEAN);
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.BINOP)
                .dest(test)
                .lhs1(selector)
                .lhs2(expected)
                .binop(BinaryOperator.EQ)
                .line(lineOf(caseDef))
                .build());
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.BRANCH)
                .lhs1(test)
                .trueLabel(bodyLabel)
                .falseLabel(nextLabel)
                .line(lineOf(caseDef))
                .build());
        builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(bodyLabel).build());
      }

      // The case's guard, if it has one. A guard that fails falls through to the
      // next case.
      Trees.Tree<Type> guard = caseDef.guard();
      if (guard != null && !(guard instanceof Trees.EmptyTree)) {
        TacOperand guardValue = translateExpr(guard);
        if (guardValue != null) {
          String guardLabel = builder.newLabel("matchGuard");
          builder.emit(
              TacInstruction.builder(TacInstruction.Kind.BRANCH)
                  .lhs1(guardValue)
                  .trueLabel(guardLabel)
                  .falseLabel(nextLabel)
                  .line(lineOf(caseDef))
                  .build());
          builder.emit(
              TacInstruction.builder(TacInstruction.Kind.LABEL).label(guardLabel).build());
        }
      }

      translateCaseBody(caseDef);
      builder.emit(TacInstruction.builder(TacInstruction.Kind.JUMP).label(endLabel).build());
    }

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(endLabel).build());
    return null;
  }

  /** Lowers a case's body. */
  private void translateCaseBody(Trees.CaseDef<Type> caseDef) {
    Trees.Tree<Type> body = caseDef.body();
    if (body instanceof Trees.Block) {
      translateBlock((Trees.Block<Type>) body);
      return;
    }
    translateStatement(body);
  }

  /**
   * The type a pattern tests, or {@code null} when the pattern does not test a type.
   *
   * <p>A typed pattern is a {@code Bind} whose body is a {@code Typed} scrutinee: {@code
   * "case s: String =>"} is {@code Bind(s, Typed(_, String))}.
   */
  private Type testedTypeOf(Trees.Tree<Type> pattern) {
    Trees.Tree<Type> tested = pattern;
    if (tested instanceof Trees.Bind) {
      tested = ((Trees.Bind<Type>) tested).body();
    }
    if (tested instanceof Trees.Typed) {
      Trees.Typed<Type> typed = (Trees.Typed<Type>) tested;
      return typed.tpt().tpe();
    }
    return null;
  }

  /**
   * The name a pattern binds, or {@code null} when it binds nothing.
   *
   * <p>The wildcard pattern binds the name {@code "_"}, which is not a variable.
   */
  private String boundName(Trees.Tree<Type> pattern) {
    if (pattern instanceof Trees.Bind) {
      return ((Trees.Bind<Type>) pattern).name().toString();
    }
    return null;
  }

  /**
   * The constant a pattern matches against, or {@code null} when it is not a constant.
   *
   * <p>A constant pattern is a literal. A wildcard pattern is an {@code Ident} named
   * {@code "_"}, which matches everything and is not a constant.
   */
  private Trees.Tree<Type> constantOf(Trees.Tree<Type> pattern) {
    if (pattern instanceof Trees.Literal) {
      return pattern;
    }
    if (pattern instanceof Trees.Ident && "_".equals(((Trees.Ident<Type>) pattern).name().toString())) {
      return null;
    }
    return null;
  }

  /** Lowers a {@code Bind}: a pattern variable binding. */
  private TacOperand translateBind(Trees.Bind<Type> bind) {
    String name = bind.name().toString();
    TacType type = types.map(bind.tpe());
    TacOperand.TacVar target = new TacOperand.TacVar(name, type, false);
    locals.put(name, target);
    builder.addLocal(target);
    return target;
  }

  // ---------------------------------------------------------------- helpers

  /** Emits a move from one operand to another. */
  private void emitMove(TacOperand.TacVar target, TacOperand value, Trees.Tree<Type> tree) {
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(target)
            .lhs1(value)
            .line(lineOf(tree))
            .build());
  }

  /** Emits a return. */
  private void emitReturn(TacOperand value, Trees.Tree<Type> tree) {
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.RETURN)
            .lhs1(value)
            .line(lineOf(tree))
            .build());
  }

  /** The source line of a tree, or {@code -1} when it has none. */
  private int lineOf(Trees.Tree<Type> tree) {
    try {
      dotty.tools.dotc.util.SourcePosition pos = tree.sourcePos(ctx);
      return pos == null ? -1 : pos.line();
    } catch (RuntimeException ex) {
      return -1;
    }
  }

  /** Copies a Scala list into a Java list. */
  private static <T> List<T> toJavaList(scala.collection.immutable.List<T> source) {
    List<T> out = new ArrayList<>(source.size());
    scala.collection.Iterator<T> it = source.iterator();
    while (it.hasNext()) {
      out.add(it.next());
    }
    return out;
  }

  /**
   * Sets the builder's enclosing class.
   *
   * <p><b>Why this matters beyond metadata.</b> The enclosing class is not decoration: the call
   * graph indexes functions by it, the merged program uses it to keep same-named methods on
   * different classes apart, and the finding reporter resolves a finding's file path through it.
   *
   * <p>This used to be reached by reflection, because {@code TacBuilder.setEnclosingClass} was
   * package-private and this module lives in another package. Reflection had a cost beyond the
   * ugliness: when it failed, the enclosing class was silently lost, and a Scala function was
   * dropped from the call graph's merged-key index -- so a Scala caller had <b>zero</b> call sites
   * and no taint could cross the language boundary. The setter is public now, and a failure to
   * record the class is visible rather than silent.
   */
  private static void setEnclosingClass(TacBuilder b, String name) {
    if (name == null || name.isEmpty()) return;
    b.setEnclosingClass(name);
  }

  /**
   * The problems of the lowering, in BOTH forms.
   *
   * <p><b>Why one collector and not two lists.</b> The string form says "something went wrong".
   * The structured form says WHICH construct went wrong, in WHICH function, and whether the
   * cause was an unsupported construct or a broken source. A source-less consumer needs the
   * second: it cannot answer "is this file incomplete because the frontend lacks a feature, or
   * because the source does not compile?" -- and those need different responses.
   *
   * <p>Keeping them in one collector is what stops them drifting apart. A gap recorded only in
   * the strings is a gap a source-less consumer cannot see; a gap recorded only in the structured
   * list is a gap the log does not show. There is exactly one place a gap is recorded, so
   * neither can happen.
   */
  private final class ProblemCollector {

    /** The string form: what the log shows. */
    private final List<String> messages = new ArrayList<>();

    /** The structured form: what travels with the IR. */
    private final List<org.plumb.tac.ir.TacProgram.FunctionProblem> structured = new ArrayList<>();

    /**
     * Record a gap.
     *
     * @param message what went wrong, in the user's terms
     */
    void add(String message) {
      messages.add(message);
      structured.add(
          new org.plumb.tac.ir.TacProgram.FunctionProblem(
              currentFunction, "UNSUPPORTED_CONSTRUCT", message, null, -1, -1));
    }

    /** The string form: what the log shows. */
    List<String> messages() {
      return List.copyOf(messages);
    }

    /** The structured form: what travels with the IR. */
    List<org.plumb.tac.ir.TacProgram.FunctionProblem> structured() {
      return List.copyOf(structured);
    }
  }
  /**
   * Lowers a lambda's WRAPPER into a reference to the function that carries its body.
   *
   * <p><b>What the Scala typer leaves behind.</b> The typer desugars a lambda into a {@code
   * DefDef} -- {@code _$_$$anonfun} -- that holds the parameters, the captures and the body. The
   * translator's {@code collectDefs} finds that definition and lowers it like any other, so the
   * body IS in the program.
   *
   * <p>What the translator meets on the way there is the {@code Trees.Closure} that WRAPS the
   * reference: its parameter list is empty, its body is an {@code EmptyTree}, and its method is an
   * {@code Ident} naming the definition that carries the real body. Measured:
   *
   * <pre>
   *   [closure] params=0 meth=Ident:Ident($anonfun) body=EmptyTree:EmptyTree
   * </pre>
   *
   * <p><b>So the closure lowers to a CALL to that function.</b> Not to a new function: a new one
   * would have no body, and the enclosing function would call the EMPTY one. That is precisely the
   * defect the {@code for} comprehension had -- the loop body lived in a function nobody called,
   * the accumulator stayed at its initial value, every taint through the loop was destroyed, and
   * the analysis reported the function as CLEAN.
   *
   * @param closure the wrapper
   * @return the temp that holds the callable value
   */
  private TacOperand translateClosure(Trees.Closure<Type> closure) {
    // The method the closure names: the definition that carries the body.
    Trees.Tree<Type> meth = closure._2();
    String target = null;
    if (meth instanceof Trees.Ident) {
      target = ((Trees.Ident<Type>) meth).name().toString();
    } else if (meth instanceof Trees.Select) {
      target = ((Trees.Select<Type>) meth).name().toString();
    }

    if (target == null || target.isEmpty()) {
      // The closure names no function, so there is nothing to call. The gap is recorded and the
      // function degraded, so a consumer knows the value is not what it should be.
      degraded = true;
      problems.add(
          "a lambda in " + currentFunction + " names no callable; the function's precision was"
              + " downgraded");
      TacOperand.TacVar unknown = builder.newTemp(TacType.UNKNOWN);
      builder.emit(
          org.plumb.tac.ir.TacInstruction.builder(org.plumb.tac.ir.TacInstruction.Kind.HAVOC)
              .dest(unknown)
              .line(lineOf(closure))
              .build());
      return unknown;
    }

    // The function's qualified name, so the CALL reaches the SAME function the definition
    // produced. The definition's name comes from its SYMBOL's full name
    // (`demo.Demo._$_$$anonfun`), and the closure's method resolves to that same symbol -- so
    // the symbol is the authority here too, exactly as it is for the definition.
    String callee = qualifiedClosureTarget(meth);
    if (callee == null) {
      callee = target;
    }

    TacOperand.TacVar dest = builder.newTemp(TacType.UNKNOWN);
    builder.emit(
        org.plumb.tac.ir.TacInstruction.builder(org.plumb.tac.ir.TacInstruction.Kind.CALL)
            .dest(dest)
            .calleeSig(callee)
            .line(lineOf(closure))
            .build());
    return dest;
  }

  /**
   * The qualified name of the function a closure names.
   *
   * <p><b>The symbol is the authority.</b> The closure's method is an {@code Ident} whose NAME is
   * the short one ({@code $anonfun}), while the definition the translator produced is named from
   * its symbol's full name ({@code demo.Demo._$_$$anonfun}). The two spellings differ, so the
   * name cannot be used to join them -- but the SYMBOL is the same object on both sides, and the
   * symbol's full name is exactly what the definition was named with.
   *
   * <p>So the closure resolves the symbol and asks for its full name. That is the same answer the
   * definition produced, so the CALL reaches the function the definition created -- which is what
   * the call graph's edge needs, or the body's effect is never followed.
   *
   * @param meth the closure's method tree
   * @return the qualified callee name, or {@code null} when the symbol does not resolve
   */
  private String qualifiedClosureTarget(Trees.Tree<Type> meth) {
    if (meth == null) {
      return null;
    }
    try {
      Symbol symbol = meth.symbol(ctx);
      if (symbol == null) {
        return null;
      }
      String full = symbol.denot(ctx).fullName(ctx).toString();
      if (full != null && !full.isEmpty()) {
        return full;
      }
    } catch (RuntimeException | LinkageError ex) {
      // Fall through to the name-based answer.
    }
    return null;
  }
}
