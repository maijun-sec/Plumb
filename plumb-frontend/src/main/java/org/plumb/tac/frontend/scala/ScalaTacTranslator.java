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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacMemberKind;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;
import org.plumb.tac.ir.UnaryOperator;
import org.plumb.tac.translate.TacBuilder;

/**
 * Lowers a typed Scala 3 tree into Plumb's TAC.
 *
 * <p><b>What this lowers.</b> The subset the dataflow engine can act on: literals, local reads and
 * writes, field reads and writes, method calls, object creation, arithmetic and comparison,
 * branches, loops, blocks, {@code if} and {@code match} as expressions, string interpolation, and
 * the pattern kinds a {@code match} can carry. A {@code match} becomes a branch chain -- one test
 * plus one {@code BRANCH} per case, with a {@code JUMP} to a merge label -- which is exactly the
 * shape a Java {@code instanceof} chain has, so the engine needs no change to read it.
 *
 * <p><b>What this does not lower.</b> By-name arguments, implicit conversions, and the varargs
 * splice. A construct that is not lowered is reported as a problem rather than silently dropped,
 * and it downgrades the enclosing function's precision to {@link IrPrecision#SOURCE_LOWERED} -- so
 * a checker knows not to trust a precision-dependent fact about it.
 *
 * <p><b>No new opcodes.</b> Everything here lowers into the existing instruction kinds: {@code
 * MOVE}, {@code BINOP}, {@code UNARYOP}, {@code CALL}, {@code NEW}, {@code FIELDLOAD}, {@code
 * FIELDSTORE}, {@code INSTANCEOF}, {@code BRANCH}, {@code JUMP}, {@code LABEL}, {@code RETURN},
 * {@code THROW}, {@code NOP}, {@code HAVOC}.
 *
 * <p><b>Variables.</b> The TAC's variable identity is name + temp flag. A Scala local's source name
 * <i>is</i> its TAC name, so a body reference to a parameter or a local resolves to the same
 * storage location rather than to a fresh temp. Scala's name-mangled locals ({@code x$1}) keep
 * their mangled names, which is what the compiler itself uses and what keeps two distinct locals
 * apart.
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
   * that carries the body, while the definition the translator produced is named with the qualified
   * form. The call has to name the same function the definition produced, or the call graph's edge
   * points at nothing.
   */
  private String currentEnclosingClass;

  /** The name of the function being lowered; used for problem messages. */
  private String currentFunction = "<unknown>";

  /**
   * The locals of the function being lowered, by source name.
   *
   * <p>Keyed by the Scala source name, not by a {@link TacOperand.TacVar}: the TAC's variable
   * identity is name + temp flag, and a Scala local's name <i>is</i> its TAC name.
   */
  private final Map<String, TacOperand.TacVar> locals = new HashMap<>();

  /**
   * The variables a closure CAPTURES, by the compiler's own symbol.
   *
   * <p><b>Why the symbol is the key.</b> A Scala closure does not receive its captured variables as
   * parameters. The typer leaves them as FREE IDENTIFIERS in the closure's body, and the caller's
   * {@code val}/{@code var} definitions stay where they are, in the caller's block. The two are
   * nevertheless THE SAME VARIABLE -- and the proof is the compiler's own symbol: the caller's
   * {@code ValDef} and the closure's {@code Ident} resolve to one and the same {@code Symbol}
   * object. Measured on {@code var captured = cmd; val f = () => captured}:
   *
   * <pre>
   *   ValDef captured  symId=740735495
   *   Ident  captured  symId=740735495     &lt;- the same symbol
   * </pre>
   *
   * <p>So the symbol is what carries the capture across the function boundary. A NAME is not
   * enough: two different closures in the same method can each have their own {@code captured}, and
   * the caller's scope can shadow one of them.
   *
   * <p>The map spans the whole compilation unit, because the capture crosses a function boundary:
   * the caller's definition and the closure's body are lowered into two different {@link
   * TacFunction}s, and the closure's body is lowered AFTER the caller (the typer's tree walks the
   * definition before the use site).
   */
  private final Map<Symbol, CaptureCell> captures = new HashMap<>();

  /**
   * The shared holders already declared in the function being lowered, by name.
   *
   * <p>A holder is declared once per function: the same capture read twice does not declare the
   * receiver twice.
   */
  private final Map<String, TacOperand.TacVar> holders = new HashMap<>();

  /**
   * The FIELD initialisers the unit declares, by the class that owns them.
   *
   * <p><b>Why they are collected before anything is lowered.</b> A {@code val} in a class body is a
   * field, and the value that fills it is the class's own behaviour: it runs on every construction,
   * before the constructor's own body. The constructor has to CALL it, and the call has to name the
   * function that carries the value -- so both sides have to agree on the name before either is
   * lowered. Collecting them first is what makes that agreement possible.
   *
   * <p>The key is the OWNING CLASS ITSELF, not its name: the constructor's owner and the template's
   * class are the same symbol, and only the symbol says so for every shape a class can take. A name
   * is not enough -- a class declared inside a package object carries a different name in the
   * template than in the members it declares.
   */
  private final Map<Symbol, List<FieldInitializer>> fieldInitializers = new HashMap<>();

  /**
   * One field's initialiser: the value a class body's {@code val} carries.
   *
   * <p><b>Why it is a function and not a field write in the constructor.</b> The compiler
   * synthesises the constructor's field assignments AFTER the typer, so at the point this frontend
   * reads the tree the constructor's body is empty -- the assignment is not there to be read. A
   * separate function for the initialiser is what makes the value visible at all: the constructor
   * calls it, and the call graph's edge says the constructor runs the initialiser.
   */
  private static final class FieldInitializer {

    /** The class that declares the field. */
    private final String ownerFqn;

    /** The short name of the class that declares the field. */
    private final String ownerSimpleName;

    /** The field's name. */
    private final String field;

    /** The declaration, which carries the field's type and its initialiser. */
    private final Trees.ValDef<Type> declaration;

    FieldInitializer(
        String ownerFqn, String ownerSimpleName, String field, Trees.ValDef<Type> declaration) {
      this.ownerFqn = ownerFqn;
      this.ownerSimpleName = ownerSimpleName;
      this.field = field;
      this.declaration = declaration;
    }

    /** The name of the function that carries this initialiser's value. */
    String functionName() {
      return ownerFqn + "." + field + "$init";
    }
  }

  /**
   * A captured variable, modelled as a shared CELL.
   *
   * <p><b>Why a cell and not a plain variable.</b> The TAC has no "captured local" concept, and
   * adding one would be a language-specific concept in a language-neutral IR. But the IR already
   * has a mechanism for exactly this shape: a field read and a field write on a shared receiver are
   * visible across function boundaries, because the dataflow engine keys its cross-function field
   * tracker on {@code (receiver, field)}.
   *
   * <p>So a captured variable is lowered as a field of a synthetic HOLDER: the holder is a named
   * receiver, the variable is a field on it, and the holder's name is the same in the caller and in
   * the closure. The engine then sees one storage location on both sides, and a write inside the
   * closure is visible to the caller -- which is the behaviour the source has.
   *
   * <p>The holder's name starts with an uppercase letter because the engine's "is this a class
   * name" heuristic -- the one that decides whether a field write is class-shared or instance-local
   * -- is exactly that. A lowercase name would make the capture instance-local, and the write would
   * stop at the function boundary.
   */
  private static final class CaptureCell {

    /** The receiver the captured variable is read from and written to. */
    private final TacOperand.TacVar holder;

    /** The field name the captured variable occupies on the holder. */
    private final String field;

    /** The type of the captured variable. */
    private final TacType type;

    CaptureCell(TacOperand.TacVar holder, String field, TacType type) {
      this.holder = holder;
      this.field = field;
      this.type = type;
    }
  }

  /**
   * The prefix of a synthetic capture holder's name.
   *
   * <p>Uppercase, so the engine's class-name heuristic treats the holder as a shared class rather
   * than as one instance. The name is prefixed so it cannot collide with a source name.
   */
  private static final String CAPTURE_HOLDER_PREFIX = "CaptureHolder_";

  /** The problems found while lowering, in the order they were found. */
  private final ProblemCollector problems = new ProblemCollector();

  /**
   * Records a construct that could not be lowered, in BOTH channels.
   *
   * <p>A problem is the sentence a human reads; a drop is the receipt the engine reads. The two say
   * different things, and a front end that records only the first produces findings that look
   * exactly as complete as a fully lowered one's.
   *
   * <p><b>The impact says WHAT was lost, and it decides the function's precision.</b> The engine
   * derives the precision from the drops ({@link org.plumb.tac.ir.TacFunction#derivePrecision}), so
   * a drop that claims the wrong grade makes the precision a lie in the same direction the loss is.
   * A construct whose effect is gone is {@link org.plumb.tac.ir.TacDrop.Impact#SEMANTICS}; one that
   * only lost its shape is {@link org.plumb.tac.ir.TacDrop.Impact#STRUCTURE}; one that only lost a
   * single value is {@link org.plumb.tac.ir.TacDrop.Impact#VALUE}.
   */
  private void reportLoss(
      org.plumb.tac.ir.TacDrop.Impact impact, String construct, String message) {
    problems.add(message);
    if (builder != null) {
      builder.reportDrop(construct, message, impact, -1, -1);
    }
  }

  /**
   * Records a construct that was not lowered faithfully, in the drop channel only.
   *
   * <p>Some losses are not a PROBLEM: the construct is understood, and what is missing is a control
   * flow that only the compiler's own expansion could produce. The receipt is what tells a checker
   * how far the function may be trusted, and it is the receipt alone -- there is no malformed
   * source to complain about.
   *
   * @param impact what was lost
   * @param construct the construct's id
   * @param message the human-readable description
   */
  private void recordDrop(
      org.plumb.tac.ir.TacDrop.Impact impact, String construct, String message) {
    if (builder != null) {
      builder.reportDrop(construct, message, impact, -1, -1);
    }
  }

  /**
   * The structured problems of the UNIT-level failures -- the ones raised outside a function's own
   * lowering.
   *
   * <p>They are kept apart from the collector's own, because a unit-level failure has no enclosing
   * function to name: the {@code def} it happened in is the subject.
   */
  private final List<org.plumb.tac.ir.TacProgram.FunctionProblem> unitStructuredProblems =
      new ArrayList<>();

  /**
   * The module the unit being lowered belongs to, or {@code null} when there is no module boundary.
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
   * <p><b>Why it matters most for {@code for} comprehensions.</b> The Scala typer desugars {@code
   * for (x <- xs) body} into {@code xs.foreach(x => body)}. Before this, the lambda was dropped, so
   * the loop body lived in a function that was never called: the accumulator stayed at its initial
   * value, every taint that flowed through the loop was destroyed, and the analysis reported the
   * function as CLEAN. A silent wrong answer, not a visible gap.
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
    // The capture pre-pass runs FIRST, before anything is lowered. It has
    // to: a captured variable is lowered as a field of a shared holder, and
    // the CALLER has to write it there. If the caller were lowered
    // before the capture was known, the caller's write would already be
    // in its instruction list as a plain local assignment, and the
    // closure would read a different storage location.
    detectCaptures(tree);
    // The field initialisers are found BEFORE anything is lowered, for the same
    // reason the captures are: the constructor has to CALL the initialiser, and
    // the call has to name the function the initialiser produced. One side
    // cannot be lowered against a decision the other side has not made yet.
    detectFieldInitializers(tree);
    for (FieldInitializer init : fieldInitializersInSourceOrder()) {
      try {
        TacFunction fn = translateFieldInitializer(init);
        if (fn != null) {
          functions.add(fn);
        }
      } catch (RuntimeException ex) {
        unitProblems.add(
            "lowering failure in "
                + init.functionName()
                + ": "
                + ex.getClass().getSimpleName()
                + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
      }
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
   * <p>These travel with the IR, so a source-less consumer can see WHICH construct was not lowered,
   * in WHICH function -- instead of only learning that "something went wrong somewhere".
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
    dotty.tools.dotc.ast.tpd.TreeOps$.MODULE$.foreachSubTree$extension(
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
    this.holders.clear();

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
      //
      // The class's FIELD INITIALISERS are a different thing: they are the
      // class's own behaviour, and the compiler runs them from the constructor.
      // They are called here, because the constructor is where they run.
      if ("<init>".equals(name)) {
        emitFieldInitializerCalls(symbol, def);
      }
      if (hasUnmodelledBody(def, params)) {
        // The body was not lowered, and the field writes the compiler synthesizes for
        // a constructor with parameters are not in the TAC at all -- so what the
        // constructor does is not there. The loss is RECORDED: a gap that leaves no
        // receipt is a gap the engine cannot see.
        reportLoss(
            org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
            "scala.body.unmodelled",
            "the body of "
                + qualified
                + " is compiler-generated and was not lowered;"
                + " the function's precision was downgraded");
      }
      return b.build();
    }

    try {
      translateBody(rhs);
      // A constructor that DOES have a body of its own still runs the class's
      // field initialisers: they are part of the construction, not of the
      // constructor's own statements.
      if ("<init>".equals(name)) {
        emitFieldInitializerCalls(symbol, def);
      }
    } catch (RuntimeException ex) {
      problems.add(
          "lowering failure in "
              + qualified
              + ": "
              + ex.getClass().getSimpleName()
              + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
      reportLoss(
          org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
          "scala.translation.failed",
          "lowering failure in "
              + qualified
              + ": "
              + ex.getClass().getSimpleName()
              + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
    }

    // The precision is NOT stated here: it is derived from the drops (the builder
    // combines the two), and a front end that states it as well can only talk the
    // loss up.
    return b.build();
  }

  /**
   * Builds the parameter variables of a definition.
   *
   * <p><b>Every parameter list is flattened into one.</b> Scala allows a definition to take several
   * lists -- {@code def f(a: Int, b: Int)(c: String)} -- and the later ones are curried or implicit
   * parameters, but they are still parameters: the caller supplies them, and the body reads them.
   * Reading only the first list made the later ones read as {@code this.b}, so a body that used a
   * second-list parameter saw a field of the enclosing class instead of its argument -- a wrong
   * value, and a wrong arity on the call graph's edge.
   *
   * @param def the definition
   * @return the parameters, in declaration order
   */
  private List<TacOperand.TacVar> buildParams(Trees.DefDef<Type> def) {
    List<TacOperand.TacVar> params = new ArrayList<>();
    scala.collection.Iterator<scala.collection.immutable.List<Trees.ValDef<Type>>> lists =
        def.termParamss(ctx).iterator();
    while (lists.hasNext()) {
      for (Trees.ValDef<Type> v : toJavaList(lists.next())) {
        params.add(new TacOperand.TacVar(v.name().toString(), types.map(v.tpt().tpe()), false));
      }
    }
    return params;
  }

  /**
   * Whether a definition with an empty body has behaviour the lowering does not model.
   *
   * <p>A class constructor that takes parameters assigns them to the class's fields. Those
   * assignments are synthesized by the compiler <i>after</i> the typer, so at the point this
   * frontend reads the tree the constructor's body is empty even though the constructor does
   * something. A checker that cannot see those writes must not trust a precision-dependent fact
   * about the constructor, so the constructor's precision is downgraded.
   *
   * <p>A constructor with no parameters, and an abstract member, have no such hidden behaviour:
   * nothing was lost, and their precision stays full.
   *
   * @param def the definition
   * @param params the definition's parameters
   * @return whether the definition's behaviour is not fully modelled
   */
  private boolean hasUnmodelledBody(Trees.DefDef<Type> def, List<TacOperand.TacVar> params) {
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
   * <p><b>The primary test is the compiler's own flag.</b> dotc marks the members it generates for
   * a case class -- {@code copy}, {@code copy$default$N}, the {@code _N} accessors -- and for a
   * companion object -- {@code apply}, {@code unapply}, the {@code product*} family -- with {@code
   * Flags.Synthetic}. The compiler knows exactly which members it generated, so its answer is
   * authoritative.
   *
   * <p><b>The flag test is not a plain bitwise AND.</b> Scala's flags are 64 bits wide and split
   * into a two-bit "kind" field (bits 0 and 1: is this a term or a type?) and the carrier bits that
   * start at bit 2. A plain {@code flags & Flags.Synthetic()} is therefore <i>always</i> non-zero,
   * because the kind bits alone satisfy it. The correct test masks off the kind bits first: {@code
   * (flags >>> 2) & (flag >>> 2)}.
   *
   * <p><b>The owner and name checks are the fallback.</b> If the flag is missing (a different dotc
   * version lays the flags out differently), the owner and the name still identify the generated
   * members. {@code <init>} is never synthetic: the constructor is the class's public API, and a
   * checker that suppresses findings about synthetic methods must not suppress them about the
   * constructor.
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
   * <p>Scala's flags carry their meaning in the bits above the two-bit "kind" field, so the test
   * shifts the kind bits out before comparing.
   */
  private static boolean hasFlag(long flags, long flag) {
    return ((flags >>> 2) & (flag >>> 2)) != 0;
  }

  /**
   * Finds the FIELD initialisers the unit declares.
   *
   * <p><b>What a template-level {@code val} is.</b> A {@code val} in a class body is a field, and
   * the value that fills it is part of the class's behaviour. dotc keeps it in the class's template
   * as a {@code ValDef}; it is not a definition of its own, so the walk that collects the unit's
   * definitions never sees it, and the value was in no function at all.
   *
   * <p><b>Which ones are skipped.</b> A {@code val} with no initialiser -- the {@code val x} of a
   * case class's constructor parameter -- is filled by the constructor, not by an initialiser, so
   * it has no value to carry.
   *
   * @param tree the typed compilation unit
   */
  private void detectFieldInitializers(Trees.Tree<Type> tree) {
    for (Trees.Tree<Type> node : subTreesOf(tree)) {
      if (!(node instanceof Trees.Template)) {
        continue;
      }
      Trees.Template<Type> template = (Trees.Template<Type>) node;
      Symbol owner = templateOwner(template);
      if (owner == null) {
        continue;
      }
      for (Trees.Tree<Type> member : toJavaList(template.body(ctx))) {
        if (!(member instanceof Trees.ValDef)) {
          continue;
        }
        Trees.ValDef<Type> val = (Trees.ValDef<Type>) member;
        Trees.Tree<Type> rhs = val.rhs(ctx);
        if (rhs == null || rhs instanceof Trees.EmptyTree) {
          // A constructor parameter: the constructor fills it.
          continue;
        }
        fieldInitializers
            .computeIfAbsent(owner, key -> new ArrayList<>())
            .add(
                new FieldInitializer(
                    fqnOf(owner), simpleNameOf(fqnOf(owner)), val.name().toString(), val));
      }
    }
  }

  /**
   * The class a template declares, or {@code null}.
   *
   * <p><b>Why the constructor is asked.</b> The template's own symbol is not always the class the
   * members belong to: a class declared inside a package object carries a different name in the
   * template than in its members. The constructor's owner IS the class, and it is the same symbol
   * the constructor's own lookup finds -- which is what makes the two sides agree.
   *
   * @param template the template
   * @return the class symbol
   */
  private Symbol templateOwner(Trees.Template<Type> template) {
    try {
      Symbol constructor = template.constr().symbol(ctx);
      if (constructor == null) {
        return null;
      }
      Symbol owner = constructor.denot(ctx).owner();
      return owner;
    } catch (RuntimeException | LinkageError ex) {
      return null;
    }
  }

  /**
   * The short name of a fully qualified class name.
   *
   * @param fqn the qualified name
   * @return the name without its package
   */
  private static String simpleNameOf(String fqn) {
    if (fqn == null || fqn.isEmpty()) {
      return null;
    }
    int dot = fqn.lastIndexOf('.');
    String simple = dot < 0 ? fqn : fqn.substring(dot + 1);
    // A module class is named after its companion object ("Plain$"). The class the
    // source declared is "Plain".
    return simple.endsWith("$") ? simple.substring(0, simple.length() - 1) : simple;
  }

  /**
   * The field initialisers of the unit, in the order the classes were declared.
   *
   * <p>The map is keyed by class, so the order across classes is the order the classes were found
   * in -- which is the order the source declares them in.
   *
   * @return the initialisers
   */
  private List<FieldInitializer> fieldInitializersInSourceOrder() {
    List<FieldInitializer> out = new ArrayList<>();
    for (List<FieldInitializer> inits : fieldInitializers.values()) {
      out.addAll(inits);
    }
    return out;
  }

  /**
   * Lowers one field's initialiser into a function of its own.
   *
   * <p><b>Why a function.</b> The value is the class's behaviour, and it runs on every
   * construction. A separate function says that: the constructor CALLS it, and the call graph's
   * edge carries the value's taint from the initialiser into the constructor.
   *
   * <p><b>Why a MOVE before the FIELDSTORE.</b> The store's value is a temp, and the temp is what
   * the engine tracks. Naming it first means a later read of the field and this write refer to the
   * same value, rather than to a temp nothing else can name.
   *
   * @param init the initialiser
   * @return the function, or {@code null} when the value could not be lowered
   */
  private TacFunction translateFieldInitializer(FieldInitializer init) {
    TacType type = types.map(init.declaration.tpt().tpe());
    String name = init.functionName();

    this.currentFunction = name;
    this.currentEnclosingClass = init.ownerSimpleName;
    this.locals.clear();
    this.holders.clear();

    TacBuilder b =
        new TacBuilder(
            new TacBuilder.FunctionSpec(name, name + "():void", TacType.VOID), List.of());
    b.setSourceLanguage(SourceLanguage.SCALA);
    b.setModule(module);
    b.setIrPrecision(IrPrecision.SOURCE_FULL);
    b.setMemberKind(TacMemberKind.FIELD_INITIALIZER);
    if (init.ownerSimpleName != null) {
      setEnclosingClass(b, init.ownerSimpleName);
    }
    this.builder = b;

    Trees.Tree<Type> rhs = init.declaration.rhs(ctx);
    TacOperand value = translateExpr(rhs);
    if (value == null) {
      // The value could not be lowered. The function is still produced -- an
      // empty one -- so the constructor's CALL has a target, and the loss is
      // recorded rather than silent.
      reportLoss(
          org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
          "scala.fieldInitializer.unmodelled",
          "the initialiser of '" + init.ownerFqn + "." + init.field + "' was not lowered");
    } else {
      TacOperand.TacVar stored = builder.newTemp(type);
      emitMove(stored, value, rhs);
      // The receiver is the object the field belongs to, so its type is the
      // CLASS's type -- not the field's.
      TacType ownerType = init.ownerFqn == null ? TacType.UNKNOWN : TacType.ofClass(init.ownerFqn);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
              .dest(stored)
              .lhs1(thisReceiver(ownerType))
              .fieldName(init.field)
              .ownerType(init.ownerFqn)
              .line(lineOf(rhs))
              .build());
    }

    // The precision is NOT stated here: it is derived from the drops (the builder
    // combines the two), and a front end that states it as well can only talk the
    // loss up.
    return b.build();
  }

  /**
   * Emits the calls a constructor makes to its class's field initialisers.
   *
   * <p><b>Why the constructor has to make them.</b> The compiler synthesises the field assignments
   * into the constructor's body AFTER the typer, so the body this frontend reads is empty. Without
   * these calls the class's initialisers are not part of the program at all, and every value they
   * carry -- a constant, a read of a parameter, a call whose result is tainted -- is invisible.
   *
   * @param symbol the constructor's symbol
   * @param tree the constructor, for the source position
   */
  private void emitFieldInitializerCalls(Symbol symbol, Trees.Tree<Type> tree) {
    Symbol owner = ownerSymbolOf(symbol);
    if (owner == null) {
      return;
    }
    List<FieldInitializer> inits = fieldInitializers.get(owner);
    if (inits == null) {
      return;
    }
    for (FieldInitializer init : inits) {
      TacOperand.TacVar dest = builder.newTemp(TacType.VOID);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.CALL)
              .dest(dest)
              .calleeSig(init.functionName() + "/0")
              .args(List.of())
              .line(lineOf(tree))
              .build());
    }
  }

  /**
   * The class that owns a symbol, or {@code null}.
   *
   * @param symbol the symbol
   * @return the owning class
   */
  private Symbol ownerSymbolOf(Symbol symbol) {
    if (symbol == null) {
      return null;
    }
    try {
      Symbol owner = symbol.denot(ctx).owner();
      return owner;
    } catch (RuntimeException | LinkageError ex) {
      return null;
    }
  }

  /**
   * The fully qualified name of a symbol, or {@code null}.
   *
   * @param symbol the symbol
   * @return the name
   */
  private String fqnOf(Symbol symbol) {
    if (symbol == null) {
      return null;
    }
    try {
      String full = symbol.denot(ctx).fullName(ctx).toString();
      return full == null || full.isEmpty() ? null : full;
    } catch (RuntimeException | LinkageError ex) {
      return null;
    }
  }

  // ---------------------------------------------------------------- lowering

  /**
   * Finds the variables the unit's closures CAPTURE.
   *
   * <p><b>What a capture is.</b> A closure that refers to a variable defined in the ENCLOSING
   * method -- a {@code var} the closure writes to, or a {@code val} it reads -- captures it. The
   * typer leaves the reference as a free identifier in the closure's body; the compiler puts the
   * captured value into a field of a synthetic holder object at runtime, but that object does not
   * exist in the tree the typer hands over.
   *
   * <p><b>How a capture is detected.</b> By the compiler's own SYMBOL and its own ENCLOSING METHOD.
   * The closure's free identifier and the enclosing method's definition resolve to the same {@code
   * Symbol} object; and the compiler knows which method a symbol belongs to. A symbol the closure
   * itself declares has the CLOSURE as its enclosing method; a symbol it merely refers to has the
   * ENCLOSING METHOD as its enclosing method. So the test is: the symbol's enclosing method is not
   * the closure, but it IS a method in this unit.
   *
   * <p><b>Why the compiler's answer and not a tree walk.</b> Deciding "is this variable the
   * closure's own or the caller's?" by walking the tree means reproducing the compiler's notion of
   * scope -- and a flat subtree walk cannot: the nested definition's children are in the same list
   * as the enclosing body's, with nothing to say which belongs to which. The compiler already
   * answers it exactly, so the frontend asks it.
   *
   * <p><b>Why this is a pre-pass.</b> The caller has to WRITE the captured variable into the shared
   * holder, and the closure has to READ it from there. If the caller were lowered first -- which it
   * is, because the typer's tree lists the definition before the use site -- then the caller's
   * write would already be a plain local assignment by the time the capture was discovered, and the
   * closure would read a different storage location. Detecting the captures first means both sides
   * are lowered against the same decision.
   *
   * @param tree the typed compilation unit
   */
  private void detectCaptures(Trees.Tree<Type> tree) {
    for (Trees.DefDef<Type> def : collectDefs(tree)) {
      // Only a closure captures. A plain method does not: its free
      // identifiers are fields or top-level values, not captures.
      if (!isClosure(def)) {
        continue;
      }
      Symbol closureSymbol = def.symbol(ctx);
      for (Symbol symbol : freeSymbolsOf(def)) {
        Symbol enclosing = enclosingMethodOf(symbol);
        if (enclosing == null || enclosing == closureSymbol) {
          // The closure's OWN variable, or something the compiler does
          // not place in a method at all (a field, a top-level value).
          continue;
        }
        if (!isMethodSymbol(enclosing)) {
          // A FIELD of the enclosing class. The closure reads the
          // class's field, not a captured local, so the field-read
          // lowering is the right one.
          continue;
        }
        captures.computeIfAbsent(symbol, s -> newCellFor(s));
      }
    }
  }

  /**
   * The method a symbol belongs to, or {@code null} when it belongs to none.
   *
   * <p>The compiler's own answer: a local's enclosing method is the method that declares it, a
   * field's is the class that declares it, and a top-level value's is the package object.
   *
   * @param symbol the symbol
   * @return the enclosing method's symbol, or {@code null}
   */
  private Symbol enclosingMethodOf(Symbol symbol) {
    if (symbol == null) {
      return null;
    }
    try {
      return symbol.denot(ctx).enclosingMethod(ctx);
    } catch (RuntimeException | LinkageError ex) {
      return null;
    }
  }

  /**
   * Creates the shared cell a captured variable lives in.
   *
   * <p>The holder's name is derived from the symbol, so the SAME variable always gets the SAME
   * holder -- in the caller and in every closure that captures it. Two closures capturing the same
   * variable share one cell, which is what the source says.
   *
   * @param symbol the captured variable's symbol
   * @return the cell
   */
  private CaptureCell newCellFor(Symbol symbol) {
    String field = symbol.name(ctx).toString();
    // The holder is named after the field, so two different variables
    // never share a cell, and the same variable always does.
    String holderName = CAPTURE_HOLDER_PREFIX + field;
    TacType type = typeOfSymbol(symbol);
    TacOperand.TacVar holder = new TacOperand.TacVar(holderName, TacType.UNKNOWN, false);
    return new CaptureCell(holder, field, type);
  }

  /** The type of a symbol, or UNKNOWN when it cannot be resolved. */
  private TacType typeOfSymbol(Symbol symbol) {
    try {
      return types.map(symbol.denot(ctx).info(ctx));
    } catch (RuntimeException | LinkageError ex) {
      return TacType.UNKNOWN;
    }
  }

  /**
   * Whether a definition is a CLOSURE.
   *
   * <p>The compiler generates a synthetic method for a lambda -- {@code _$_$$anonfun} -- and that
   * method carries the lambda's body. A closure is exactly such a definition: a synthetic one whose
   * name contains {@code anonfun}.
   *
   * <p>The name is checked with {@code contains}, not for equality: the compiler mangles the name
   * differently depending on the form ({@code _$_$$anonfun} for a {@code val f = (x) => ...},
   * {@code _$$anonfun} for a lambda inside {@code items.map(x => ...)}), and the mangling is the
   * compiler's business, not the frontend's.
   *
   * @param def the definition
   * @return whether it is a closure
   */
  private boolean isClosure(Trees.DefDef<Type> def) {
    String name = defName(def);
    return name.contains("anonfun");
  }

  /**
   * Every subtree of a tree, in the compiler's own order.
   *
   * <p>The compiler's walker is used rather than a hand-rolled one, so the set of trees is the
   * compiler's set -- a tree the compiler does not walk is a tree the frontend does not see.
   *
   * @param tree the tree
   * @return its subtrees
   */
  private List<Trees.Tree<Type>> subTreesOf(Trees.Tree<Type> tree) {
    List<Trees.Tree<Type>> out = new ArrayList<>();
    if (tree == null) {
      return out;
    }
    try {
      dotty.tools.dotc.ast.tpd.TreeOps$.MODULE$.foreachSubTree$extension(
          tree,
          new scala.Function1<Trees.Tree<Type>, scala.runtime.BoxedUnit>() {
            @Override
            public scala.runtime.BoxedUnit apply(Trees.Tree<Type> t) {
              out.add(t);
              return scala.runtime.BoxedUnit.UNIT;
            }
          },
          ctx);
    } catch (RuntimeException | LinkageError ex) {
      // A tree that cannot be walked contributes no subtrees.
    }
    return out;
  }

  /**
   * The symbols a definition's body refers to that are NOT defined inside it.
   *
   * @param def the definition
   * @return the free symbols
   */
  private Set<Symbol> freeSymbolsOf(Trees.DefDef<Type> def) {
    Set<Symbol> out = new LinkedHashSet<>();
    for (Trees.Tree<Type> t : subTreesOf(def.rhs(ctx))) {
      if (t instanceof Trees.Ident) {
        Symbol symbol = t.symbol(ctx);
        if (symbol != null) {
          out.add(symbol);
        }
      }
    }
    return out;
  }

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

    // A variable a closure captures lives in a SHARED cell, not only in
    // this function's frame. The value is written to the cell as well as
    // to the local, so the closure reads what the caller wrote.
    CaptureCell cell = captureCellOf(val.symbol(ctx));
    if (cell != null) {
      declareHolder(cell);
    }

    if (rhs == null || rhs instanceof Trees.EmptyTree) {
      // A parameter-like definition with no initialiser. Nothing to lower.
      return;
    }
    TacOperand value = translateExpr(rhs);
    if (value != null) {
      emitMove(target, value, rhs);
      if (cell != null) {
        writeCapture(cell, value, rhs);
      }
    }
  }

  /**
   * The shared cell a symbol's value lives in, or {@code null} when the symbol is not captured.
   *
   * @param symbol the symbol
   * @return the cell, or {@code null}
   */
  private CaptureCell captureCellOf(Symbol symbol) {
    if (symbol == null) {
      return null;
    }
    return captures.get(symbol);
  }

  /**
   * Declares the shared holder in the function being lowered.
   *
   * <p>The holder is a local of the function: it is the receiver of the field reads and writes that
   * carry the captured variable's value. It is named, not a temp, because the engine's
   * cross-function field tracker keys on the receiver's NAME -- a temp's name is unique per
   * function, and a unique name would make the capture function-local, which is exactly what the
   * capture is not.
   */
  private void declareHolder(CaptureCell cell) {
    if (holders.containsKey(cell.holder.name())) {
      return;
    }
    holders.put(cell.holder.name(), cell.holder);
    builder.addLocal(cell.holder);
  }

  /**
   * Reads a captured variable from its shared cell.
   *
   * @param cell the cell
   * @param tree the tree being lowered, for the source position
   * @return the value
   */
  private TacOperand readCapture(CaptureCell cell, Trees.Tree<Type> tree) {
    declareHolder(cell);
    TacOperand.TacVar dest = builder.newTemp(cell.type);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
            .dest(dest)
            .lhs1(cell.holder)
            .fieldName(cell.field)
            .line(lineOf(tree))
            .build());
    return dest;
  }

  /**
   * Writes a captured variable into its shared cell.
   *
   * @param cell the cell
   * @param value the value to store
   * @param tree the tree being lowered, for the source position
   */
  private void writeCapture(CaptureCell cell, TacOperand value, Trees.Tree<Type> tree) {
    declareHolder(cell);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
            .dest(value)
            .lhs1(cell.holder)
            .fieldName(cell.field)
            .line(lineOf(tree))
            .build());
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
    if (tree instanceof Trees.Try) {
      return translateTry((Trees.Try<Type>) tree);
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
    reportLoss(
        org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
        "scala.expression.unmodelled",
        "could not lower "
            + tree.getClass().getSimpleName()
            + " in "
            + currentFunction
            + "; the function's precision was downgraded");
    return null;
  }

  /**
   * The character value of a boxed Scala constant.
   *
   * <p>dotc boxes a {@code Char} as {@link java.lang.Character}. A {@link Character} is not a
   * {@link Number}, so the obvious {@code ((Number) value).intValue()} throws a {@link
   * ClassCastException} on every character literal. The throw propagated out of the literal
   * lowering and aborted the whole function, so a file containing a single {@code 'x'} lost every
   * instruction after it -- and the function was reported as degraded.
   *
   * @param value the boxed constant value
   * @return the character it denotes
   */
  private static char charValueOf(Object value) {
    if (value instanceof Character c) {
      return c;
    }
    if (value instanceof Number n) {
      return (char) n.intValue();
    }
    if (value instanceof String s && !s.isEmpty()) {
      return s.charAt(0);
    }
    return '\0';
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
        // A Scala Char is boxed as java.lang.Character, which is NOT a java.lang.Number.
        // Casting it to Number would throw, and the throw aborted the whole function's
        // lowering -- so a file with a single character literal ('x') lost every
        // instruction after it.
        return TacOperand.Const.ofChar(charValueOf(value));
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
   * <p>These are {@code inline val}s in dotc's {@code Constants}, which erase to plain integers --
   * there is no static field to read from Java. The values are part of dotc's own contract and have
   * not changed since Scala 3.0.
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

  /** Lowers an identifier: a local read, a capture read, or a reference to something global. */
  private TacOperand translateIdent(Trees.Ident<Type> ident) {
    String name = ident.name().toString();
    TacOperand.TacVar local = locals.get(name);
    if (local != null) {
      return local;
    }
    Symbol symbol = ident.symbol(ctx);
    // A variable a closure CAPTURES: it is not a local of this function, but it is
    // not a field of the enclosing class either. It lives in the shared cell, so
    // the read goes through the holder.
    //
    // Before this, such an identifier fell through to the field-read path and
    // became a FIELDLOAD off "this" -- a field the class does not have. The
    // value the closure read was therefore never the caller's value, and a
    // write inside the closure went to a field nobody reads.
    CaptureCell cell = captureCellOf(symbol);
    if (cell != null) {
      return readCapture(cell, ident);
    }
    // Not a local: a reference to a field of the enclosing class, to a top-level
    // value, or to a companion object. A field read is a FIELDLOAD off the receiver.
    if (symbol != null && symbol.isTerm(ctx)) {
      return translateMemberAccess(null, name, symbol, ident);
    }
    // An unknown name. It is recorded rather than dropped, and the grade is
    // VALUE: what is missing is ONE value -- this name's -- while everything
    // else about the construct is intact. So the function's precision does not
    // move; the receipt is what says that something is missing.
    reportLoss(
        org.plumb.tac.ir.TacDrop.Impact.VALUE,
        "scala.name.unresolved",
        "unresolved identifier '" + name + "' in " + currentFunction);
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
    // The logical negation. The compiler rewrites "!x" to "x.unary_!", and the
    // tree is a SELECT with no argument list -- so this is reached before the
    // "a method selected on a value is a parameterless call" branch below, which
    // would have made it an ordinary CALL.
    //
    // Lowering it as a CALL would be wrong twice over: the operation's taint
    // behaviour would never be classified, so a rule about the operation could
    // not fire.
    if (isNegationOperator(name)) {
      return translateNegation(receiver, tree);
    }
    if (symbol != null && symbol.isTerm(ctx)) {
      Type resultType = symbol.denot(ctx).info(ctx);
      // A "val" or "var" is compiled into an accessor method, but reading it is a
      // field read -- not a method call. Only a real method, read as a value, is
      // something the lowering cannot express.
      if (isMethodSymbol(symbol) && !isAccessorSymbol(symbol)) {
        // A method on a VALUE receiver, selected without an argument list, is a
        // PARAMETERLESS CALL -- "s.trim" calls String.trim. dotc represents it as a
        // Select (not an Apply), because there are no arguments to apply.
        //
        // Lowering it as a field read was wrong twice over: the read produced a
        // FIELDLOAD of a field no class has, and the operation's taint behaviour was
        // never classified, so a rule about the operation could not fire. It was
        // also reported as an unsupported construct, so a file full of ordinary
        // String operations was flagged as degraded.
        //
        // The distinction is the receiver: a method selected on "this" is a
        // parameterless call on the enclosing object, and a method selected on a
        // local or a field is a parameterless call on that value. A method used as
        // a VALUE (passed as a function, not called) has no receiver at all -- it is
        // a bare Ident, and it reaches this path with a null receiver.
        if (receiver != null) {
          return translateCall(tree, List.of(), tree);
        }
        // A method reference used as a value ("f" where f is a method) is not a
        // call; lowering it as one would invent behaviour.
        reportLoss(
            org.plumb.tac.ir.TacDrop.Impact.VALUE,
            "scala.methodReference.asValue",
            "method reference '" + name + "' in " + currentFunction + " was not lowered");
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
   * <p>It matches the Java frontend's convention, so a checker that reasons about "the receiver"
   * reads the same shape whichever language the code came from.
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
   * Whether a symbol is the accessor the compiler generated for a {@code val} or a {@code var}.
   *
   * <p>Scala compiles a {@code val x} into a field plus a getter method. Reading the {@code val} is
   * a field read, and the TAC models it as a {@code FIELDLOAD} -- the getter is an implementation
   * detail the source never mentions.
   */
  private boolean isAccessorSymbol(Symbol symbol) {
    try {
      return symbol.denot(ctx).is(Flags.Accessor(), ctx);
    } catch (RuntimeException ex) {
      return false;
    }
  }

  /**
   * Whether a symbol denotes an inline method.
   *
   * <p>The compiler's own flag is the answer: {@code inline def} and {@code transparent inline}
   * both carry it, and nothing else does.
   *
   * @param symbol the symbol
   * @return whether it is an inline method
   */
  private boolean isInlineSymbol(Symbol symbol) {
    if (symbol == null) {
      return false;
    }
    try {
      return symbol.denot(ctx).is(Flags.Inline(), ctx);
    } catch (RuntimeException | LinkageError ex) {
      return false;
    }
  }

  /** Lowers a call. */
  private TacOperand translateApply(Trees.Apply<Type> apply) {
    Trees.Tree<Type> fun = apply.fun();
    List<Trees.Tree<Type>> args = toJavaList(apply.args());

    // A string interpolation: s"text $value". The compiler builds it as a call
    // on StringContext, and the call carries the literal parts and the values
    // in two separate lists.
    if (isInterpolationCall(apply)) {
      return translateInterpolation(apply);
    }

    // A constructor call: "new P(x, y)" is an Apply whose function is a Select of
    // "<init>" on a New.
    if (fun instanceof Trees.Select) {
      Trees.Select<Type> select = (Trees.Select<Type>) fun;
      if ("<init>".equals(select.name().toString()) && select.qualifier() instanceof Trees.New) {
        return translateNew((Trees.New<Type>) select.qualifier(), args, apply);
      }
    }

    // A binary operator: "a + b" is an Apply of a Select of "+" to one argument.
    //
    // The operator is not always a Select: an operator with an explicit type
    // argument ("xs ++[Int](ys)") is a TypeApply around one, and the TypeApply
    // has to be unwrapped before the operator's name can be read.
    Trees.Select<Type> operator = operatorSelectOf(fun);
    if (operator != null && args.size() == 1) {
      String op = operator.name().toString();
      if ("&&".equals(op) || "||".equals(op)) {
        return translateShortCircuit(operator, args.get(0), apply, "&&".equals(op));
      }
      if (isNegationOperator(op)) {
        return translateNegation(translateExpr(operator.qualifier()), apply);
      }
      String method = collectionOperatorMethod(op);
      if (method != null) {
        return translateOperatorCall(operator, args.get(0), apply, method);
      }
      BinaryOperator binop = binaryOperator(op);
      if (binop != null) {
        return translateBinary(binop, operator, args.get(0), apply);
      }
    }

    // "a -> b" is not a Select-based operator: the compiler builds it as an
    // application of an application, and the operator is an Ident in the middle.
    if (isTypeLevelOperator(operatorNameOf(fun))) {
      return translateTypeLevelOperator(operatorNameOf(fun), fun, args, apply);
    }

    // An ordinary method call.
    return translateCall(fun, args, apply);
  }

  /**
   * Whether a call is a string interpolation.
   *
   * <p>The compiler builds {@code s"text $value"} as a call on {@code StringContext}: the inner
   * call carries the literal PARTS, and the outer one carries the VALUES. Both are sequences -- a
   * {@code SeqLiteral}, wrapped in the type ascription the varargs carry.
   *
   * @param apply the call
   * @return whether the call is an interpolation
   */
  private static boolean isInterpolationCall(Trees.Apply<Type> apply) {
    return interpolationParts(apply) != null && interpolationValues(apply) != null;
  }

  /**
   * The literal parts of a string interpolation.
   *
   * @param apply the call
   * @return the parts, or {@code null} when the call is not an interpolation
   */
  private static Trees.SeqLiteral<Type> interpolationParts(Trees.Apply<Type> apply) {
    Trees.Tree<Type> inner = interpolationCallee(apply);
    if (inner == null) {
      return null;
    }
    List<Trees.Tree<Type>> innerArgs = toJavaList(((Trees.Apply<Type>) inner).args());
    if (innerArgs.size() != 1) {
      return null;
    }
    return seqLiteralOf(innerArgs.get(0));
  }

  /**
   * The interpolated values of a string interpolation.
   *
   * @param apply the call
   * @return the values, or {@code null} when the call is not an interpolation
   */
  private static Trees.SeqLiteral<Type> interpolationValues(Trees.Apply<Type> apply) {
    List<Trees.Tree<Type>> args = toJavaList(apply.args());
    if (args.size() != 1) {
      return null;
    }
    return seqLiteralOf(args.get(0));
  }

  /**
   * The {@code StringContext.apply(parts)} call an interpolation is built on.
   *
   * @param apply the call
   * @return the inner call, or {@code null} when the call is not an interpolation
   */
  private static Trees.Tree<Type> interpolationCallee(Trees.Apply<Type> apply) {
    Trees.Tree<Type> fun = apply.fun();
    if (!(fun instanceof Trees.Select)) {
      return null;
    }
    Trees.Select<Type> select = (Trees.Select<Type>) fun;
    // Only the "s" interpolator builds a string. The others ("f", "raw") have
    // their own semantics and are left to the ordinary call lowering.
    if (!"s".equals(select.name().toString())) {
      return null;
    }
    Trees.Tree<Type> inner = select.qualifier();
    if (!(inner instanceof Trees.Apply)) {
      return null;
    }
    // The inner call must be the one on StringContext.
    Trees.Tree<Type> innerFun = ((Trees.Apply<Type>) inner).fun();
    if (!(innerFun instanceof Trees.Select)) {
      return null;
    }
    if (!"apply".equals(((Trees.Select<Type>) innerFun).name().toString())) {
      return null;
    }
    return inner;
  }

  /**
   * The sequence a varargs argument carries, or {@code null}.
   *
   * <p>The compiler wraps a varargs argument in the type ascription the sequence's element type
   * gives it, so the sequence is under the ascription.
   *
   * @param tree the argument
   * @return the sequence, or {@code null}
   */
  private static Trees.SeqLiteral<Type> seqLiteralOf(Trees.Tree<Type> tree) {
    if (tree instanceof Trees.SeqLiteral) {
      return (Trees.SeqLiteral<Type>) tree;
    }
    if (tree instanceof Trees.Typed) {
      Trees.Tree<Type> expr = ((Trees.Typed<Type>) tree).expr();
      if (expr instanceof Trees.SeqLiteral) {
        return (Trees.SeqLiteral<Type>) expr;
      }
    }
    return null;
  }

  /**
   * Lowers a string interpolation into a concatenation of its parts.
   *
   * <p><b>What the runtime does.</b> The {@code s} interpolator writes the literal parts and the
   * values, in that order, into one string. That is what the concatenation chain says: the parts
   * and the values, interleaved, added together.
   *
   * <p><b>Why the values matter.</b> An interpolated value is an ordinary expression: it can be
   * tainted, and it can be a call with side effects. Lowering the template to a constant would have
   * hidden both.
   *
   * @param apply the interpolation
   * @return the string
   */
  private TacOperand translateInterpolation(Trees.Apply<Type> apply) {
    Trees.SeqLiteral<Type> parts = interpolationParts(apply);
    Trees.SeqLiteral<Type> values = interpolationValues(apply);
    if (parts == null || values == null) {
      return unknownInterpolationValue(apply);
    }
    List<Trees.Tree<Type>> partTrees = toJavaList(parts.elems());
    List<Trees.Tree<Type>> valueTrees = toJavaList(values.elems());

    List<TacOperand> components = new ArrayList<>();
    for (int i = 0; i < partTrees.size(); i++) {
      Trees.Tree<Type> partTree = partTrees.get(i);
      // An empty literal part contributes nothing: the string it would add is
      // the empty string.
      if (!isEmptyStringLiteral(partTree)) {
        TacOperand part = translateExpr(partTree);
        if (part == null) {
          return unknownInterpolationValue(apply);
        }
        components.add(part);
      }
      if (i < valueTrees.size()) {
        TacOperand value = translateExpr(valueTrees.get(i));
        if (value == null) {
          return unknownInterpolationValue(apply);
        }
        components.add(value);
      }
    }
    if (components.isEmpty()) {
      return TacOperand.Const.ofString("");
    }
    TacOperand result = components.get(0);
    for (int i = 1; i < components.size(); i++) {
      result = concat(result, components.get(i), apply);
    }
    return result;
  }

  /**
   * Whether a tree is a literal empty string.
   *
   * @param tree the tree
   * @return whether it is the empty string
   */
  private static boolean isEmptyStringLiteral(Trees.Tree<Type> tree) {
    if (!(tree instanceof Trees.Literal)) {
      return false;
    }
    Object value = ((Trees.Literal<Type>) tree)._1().value();
    return value instanceof String && ((String) value).isEmpty();
  }

  /**
   * The value of an interpolation this frontend could not lower.
   *
   * @param tree the interpolation, for the source position
   * @return an unknown value
   */
  private TacOperand unknownInterpolationValue(Trees.Tree<Type> tree) {
    reportLoss(
        org.plumb.tac.ir.TacDrop.Impact.VALUE,
        "scala.interpolation.unmodelled",
        "could not lower a string interpolation in " + currentFunction);
    TacOperand.TacVar unknown = builder.newTemp(TacType.UNKNOWN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.HAVOC).dest(unknown).line(lineOf(tree)).build());
    return unknown;
  }

  /**
   * Concatenates two values into a string.
   *
   * @param left the value on the left
   * @param right the value on the right
   * @param tree the expression, for the source position
   * @return the concatenation
   */
  private TacOperand concat(TacOperand left, TacOperand right, Trees.Tree<Type> tree) {
    TacOperand.TacVar dest = builder.newTemp(TacType.STRING);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BINOP)
            .dest(dest)
            .lhs1(left)
            .lhs2(right)
            .binop(BinaryOperator.ADD)
            .line(lineOf(tree))
            .build());
    return dest;
  }

  /**
   * The operator a call names, when the call is written as an operator.
   *
   * <p>An operator with an explicit type argument is a {@code TypeApply} around the {@code Select}
   * that names it, so the wrapper is unwrapped first.
   *
   * @param fun the call's function
   * @return the operator's selection, or {@code null} when the call names no operator
   */
  private static Trees.Select<Type> operatorSelectOf(Trees.Tree<Type> fun) {
    Trees.Tree<Type> current = fun;
    for (int depth = 0; depth < 4 && current != null; depth++) {
      if (current instanceof Trees.Select) {
        return (Trees.Select<Type>) current;
      }
      if (current instanceof Trees.TypeApply) {
        current = ((Trees.TypeApply<Type>) current).fun();
        continue;
      }
      return null;
    }
    return null;
  }

  /**
   * The name of the method a call names, looking through the wrappers a call is written in.
   *
   * <p>A call may be an application of an application -- {@code "a -> b"} is one -- and the
   * operator's name is in the middle of the chain. The chain is walked to its end, and the
   * innermost name is the one the call is really about.
   *
   * @param fun the call's function
   * @return the name, or {@code null} when the chain names nothing
   */
  private static String operatorNameOf(Trees.Tree<Type> fun) {
    Trees.Tree<Type> current = fun;
    for (int depth = 0; depth < 8 && current != null; depth++) {
      if (current instanceof Trees.TypeApply) {
        current = ((Trees.TypeApply<Type>) current).fun();
        continue;
      }
      if (current instanceof Trees.Apply) {
        current = ((Trees.Apply<Type>) current).fun();
        continue;
      }
      if (current instanceof Trees.Select) {
        return ((Trees.Select<Type>) current).name().toString();
      }
      if (current instanceof Trees.Ident) {
        return ((Trees.Ident<Type>) current).name().toString();
      }
      return null;
    }
    return null;
  }

  /**
   * Whether an operator name is the logical negation.
   *
   * <p>The compiler rewrites {@code !x} to {@code x.unary_!}, so both spellings have to be
   * recognised.
   *
   * @param name the operator's name
   * @return whether it is the negation
   */
  private static boolean isNegationOperator(String name) {
    return "!".equals(name) || "unary_!".equals(name);
  }

  /**
   * The method an operator name really is, or {@code null}.
   *
   * <p>{@code ::}, {@code :::} and {@code ++} are METHODS on the collections, not operators the TAC
   * has. Lowering them as something else -- an ADD, say -- would report behaviour the program does
   * not have, so they become CALLs to the method they name.
   *
   * <p>{@code ++=} is the JVM name of the method: the compiler spells it {@code $plus$plus$eq}.
   *
   * @param name the operator's name
   * @return the method's name, or {@code null} when the operator is not a collection method
   */
  private static String collectionOperatorMethod(String name) {
    switch (name) {
      case "::":
      case ":::":
      case "++":
        return name;
      case "++=":
      case "$plus$plus$eq":
        return "$plus$plus$eq";
      default:
        return null;
    }
  }

  /**
   * Whether an operator name is a TYPE-LEVEL construct.
   *
   * <p>{@code ->} builds a tuple, {@code <-} is the generator of a for-comprehension, and {@code ?}
   * is a type wildcard. None of them is an operation on values the TAC can express.
   *
   * @param name the operator's name
   * @return whether the operator is type-level
   */
  private static boolean isTypeLevelOperator(String name) {
    return "->".equals(name) || "<-".equals(name) || "?".equals(name);
  }

  /**
   * Lowers {@code a && b} / {@code a || b} into a short-circuit branch.
   *
   * <p><b>Why not LAND / LOR.</b> The engine evaluates both sides of a binary operation, always. A
   * Scala {@code &&} that evaluated its right operand when the left one was false would report a
   * dereference of a null the program never performs -- as a FINDING, in code that is correct. So
   * the operator becomes a branch chain: the right operand is only evaluated on the branch that
   * reaches it, and the result is a temp the two branches assign.
   *
   * <p>This is the shape the Kotlin frontend produces for the same construct.
   *
   * @param select the operator's selection
   * @param rhsTree the right operand
   * @param tree the call, for the source position
   * @param and whether the operator is {@code &&}
   * @return the operator's value
   */
  private TacOperand translateShortCircuit(
      Trees.Select<Type> select, Trees.Tree<Type> rhsTree, Trees.Tree<Type> tree, boolean and) {
    TacOperand.TacVar dest = builder.newTemp(TacType.BOOLEAN);
    String rhsLabel = builder.newLabel("scRhs");
    String shortLabel = builder.newLabel("scShort");
    String endLabel = builder.newLabel("scEnd");

    TacOperand lhs = translateExpr(select.qualifier());
    if (lhs == null) {
      reportLoss(
          org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
          "scala.operator.unmodelled",
          "could not lower the left operand of '"
              + (and ? "&&" : "||")
              + "' in "
              + currentFunction);
      return null;
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(lhs)
            .trueLabel(and ? rhsLabel : shortLabel)
            .falseLabel(and ? shortLabel : rhsLabel)
            .line(lineOf(tree))
            .build());

    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL)
            .label(rhsLabel)
            .line(lineOf(tree))
            .build());
    TacOperand rhs = translateExpr(rhsTree);
    if (rhs != null) {
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.MOVE)
              .dest(dest)
              .lhs1(rhs)
              .line(lineOf(tree))
              .build());
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.JUMP)
            .label(endLabel)
            .line(lineOf(tree))
            .build());

    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL)
            .label(shortLabel)
            .line(lineOf(tree))
            .build());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(dest)
            .lhs1(TacOperand.Const.ofBool(!and))
            .line(lineOf(tree))
            .build());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.LABEL)
            .label(endLabel)
            .line(lineOf(tree))
            .build());
    return dest;
  }

  /**
   * Lowers the logical negation.
   *
   * @param receiver the negated value
   * @param tree the call, for the source position
   * @return the negated value
   */
  private TacOperand translateNegation(TacOperand receiver, Trees.Tree<Type> tree) {
    if (receiver == null) {
      reportLoss(
          org.plumb.tac.ir.TacDrop.Impact.VALUE,
          "scala.operator.unmodelled",
          "could not lower a negation in " + currentFunction);
      return null;
    }
    TacOperand.TacVar dest = builder.newTemp(TacType.BOOLEAN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.UNARYOP)
            .dest(dest)
            .lhs1(receiver)
            .unop(UnaryOperator.NOT)
            .line(lineOf(tree))
            .build());
    return dest;
  }

  /**
   * Lowers a collection operator as the CALL it really is.
   *
   * <p>{@code xs :: x} is {@code List.::}, {@code xs ++ ys} is {@code Seq.++}. They are methods,
   * and the TAC's convention is that a call's receiver is its first argument -- so the receiver
   * goes there, and the callee's name is the method's own.
   *
   * @param select the operator's selection
   * @param argTree the operator's argument
   * @param tree the call, for the source position
   * @param method the method's name
   * @return the call's result
   */
  private TacOperand translateOperatorCall(
      Trees.Select<Type> select, Trees.Tree<Type> argTree, Trees.Tree<Type> tree, String method) {
    TacOperand receiver = translateExpr(select.qualifier());
    TacOperand argument = translateExpr(argTree);
    TacOperand.TacVar dest = builder.newTemp(types.map(tree.tpe()));
    List<TacOperand> args = new ArrayList<>();
    // The receiver slot is always there: the engine binds a callee's formal i
    // to args[i+1], so a call without the slot would mis-bind its arguments.
    args.add(receiver == null ? thisReceiver(TacType.UNKNOWN) : receiver);
    if (argument != null) {
      args.add(argument);
    }
    String owner = ownerOf(select);
    String callee = (owner == null || owner.isEmpty() ? method : owner + "." + method) + "/1";
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(dest)
            .calleeSig(callee)
            .args(args)
            .line(lineOf(tree))
            .build());
    return dest;
  }

  /**
   * Lowers an operator that is not an operation on values.
   *
   * <p><b>Why nothing is emitted for the operator.</b> The operator itself is not something the TAC
   * can express, and emitting something else would report behaviour the program does not have. The
   * operands are still real expressions, so they are evaluated -- their effects and their taint
   * belong in the program -- and the operator's own result is a value the analysis does not know: a
   * {@code HAVOC}, which says "unknown" rather than "nothing".
   *
   * @param op the operator's name
   * @param fun the call's function
   * @param args the call's arguments
   * @param tree the call, for the source position
   * @return the operator's unknown value
   */
  private TacOperand translateTypeLevelOperator(
      String op, Trees.Tree<Type> fun, List<Trees.Tree<Type>> args, Trees.Tree<Type> tree) {
    for (Trees.Tree<Type> operand : operandsOf(fun, args)) {
      translateExpr(operand);
    }
    reportLoss(
        org.plumb.tac.ir.TacDrop.Impact.STRUCTURE,
        "scala.operator.unmodelled",
        "the operator '" + op + "' is type-level and was not lowered in " + currentFunction);
    TacOperand.TacVar unknown = builder.newTemp(TacType.UNKNOWN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.HAVOC).dest(unknown).line(lineOf(tree)).build());
    return unknown;
  }

  /**
   * The operand expressions of a call written as a chain of applications.
   *
   * <p>Each application in the chain contributes its own arguments, and the chain is walked from
   * the outside in. The operator's own application is the one whose function IS the operator, so
   * its arguments are the operands the operator was applied to.
   *
   * @param fun the call's function
   * @param args the call's arguments
   * @return the operands
   */
  private static List<Trees.Tree<Type>> operandsOf(
      Trees.Tree<Type> fun, List<Trees.Tree<Type>> args) {
    List<Trees.Tree<Type>> out = new ArrayList<>(args);
    Trees.Tree<Type> current = fun;
    for (int depth = 0; depth < 8 && current != null; depth++) {
      if (current instanceof Trees.TypeApply) {
        current = ((Trees.TypeApply<Type>) current).fun();
        continue;
      }
      if (current instanceof Trees.Apply) {
        Trees.Apply<Type> apply = (Trees.Apply<Type>) current;
        for (Trees.Tree<Type> arg : toJavaList(apply.args())) {
          if (!out.contains(arg)) {
            out.add(arg);
          }
        }
        current = apply.fun();
        continue;
      }
      break;
    }
    return out;
  }

  /**
   * The receiver of a call, or {@code null} when the call has none.
   *
   * <p><b>An implicit {@code this} is not a receiver</b> the dataflow engine tracks, so it yields
   * {@code null}: the TAC's {@code CALL} then carries only the real arguments, exactly as a static
   * call does.
   *
   * <p><b>A {@code super} IS a receiver.</b> {@code super.f(x)} calls the base class's {@code f} on
   * this object, so the object is the receiver and the TAC's convention puts it in the first
   * argument. Without it the call had no receiver at all, and a dereference of the base class's
   * state was invisible to the engine.
   *
   * @param fun the function being called
   * @return the receiver, or {@code null}
   */
  private TacOperand receiverOf(Trees.Tree<Type> fun) {
    Trees.Tree<Type> callee = fun;
    // An operator with an explicit type argument is a TypeApply around the
    // Select that names it, and the receiver is under the wrapper.
    if (callee instanceof Trees.TypeApply) {
      callee = ((Trees.TypeApply<Type>) callee).fun();
    }
    if (!(callee instanceof Trees.Select)) {
      return null;
    }
    Trees.Tree<Type> qualifier = ((Trees.Select<Type>) callee).qualifier();
    if (qualifier instanceof Trees.This) {
      return null;
    }
    if (qualifier instanceof Trees.Super) {
      // The base class's member is read from this object.
      return thisReceiver(TacType.UNKNOWN);
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
   * <p><b>The receiver comes first.</b> A TAC {@code CALL} has no receiver slot: the receiver is
   * passed as the first argument. That is the contract the Java frontend established, and the
   * dataflow engine reads it: without the receiver in the argument list, a dereference of a
   * possibly-null receiver ({@code value.length}) would be invisible to the engine, and the
   * null-dereference rule would never fire.
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
    if (receiver == null) {
      // The slot is RESERVED even when the call is unqualified.
      //
      // <b>Why an implicit {@code this} IS a receiver here.</b> The engine binds a
      // callee's formal i to args[i+1], and falls back to args[i] only when
      // args[i+1] is out of range. A call whose first argument is the USER's
      // first argument therefore binds formal 0 to the SECOND argument -- and
      // formal 1 to that same second argument, because args[2] does not exist.
      // The first argument reached NO parameter at all, and the taint in it
      // stopped at the call boundary.
      //
      // Every other front end reserves the slot the same way: a static call
      // carries the declaring type as a constant (Java, C#), a package-level
      // call carries the package (Go), a module call carries the owner module
      // (TypeScript). Scala's implicit receiver is this object, so this object
      // is what the slot carries.
      receiver = thisReceiver(TacType.UNKNOWN);
    }
    operands.add(receiver);

    // The arguments, in the order the CALLEE declares them.
    List<Trees.Tree<Type>> ordered = orderArguments(fun, args);
    for (Trees.Tree<Type> arg : ordered) {
      Trees.Tree<Type> valueTree = arg;
      if (arg instanceof Trees.NamedArg) {
        // A named argument carries its value one level down.
        valueTree = ((Trees.NamedArg<Type>) arg).arg();
      }
      TacOperand value = translateExpr(valueTree);
      if (value != null) {
        operands.add(value);
      }
    }

    // The arity is the ARGUMENT count. The receiver is not an argument in Scala's
    // calling convention (it is the dispatch receiver), so `a.b(x)` has arity 1.
    // The arity is what the sink patterns' trailing `(/\d+)?` group matches against,
    // so it has to be the real argument count.
    String callee = calleeSignature(fun, ordered.size());
    Symbol symbol = fun.symbol(ctx);

    // An INLINE method: the compiler copies its body into every call site, and the copy is
    // what the call site really runs.
    //
    // WHY THE COPY IS NOT HERE. This frontend reads the typed tree BEFORE dotc's inliner
    // runs, so the tree holds the call, not the copy. The copy's control flow -- and the
    // non-local return that only the copy can express -- is therefore not in the TAC, and the
    // loss is RECORDED: a function whose copy is missing says so instead of looking complete.
    if (isInlineSymbol(symbol)) {
      recordDrop(
          org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
          "inline.notExpanded",
          "the inline method's body is not expanded at the call site: the compiler's copy of it "
              + "is not in the tree this frontend reads");
    }

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
   * The arguments of a call, in the order the CALLEE declares them.
   *
   * <p><b>Why the order matters.</b> A named argument -- {@code d(a = 1, b = 3)} -- says which
   * parameter the value belongs to. Without the names, the value was dropped: the call was emitted
   * with NO arguments at all, and every taint that flowed through the argument was lost with it.
   * And keeping the values in the order they were WRITTEN is not enough either: a callee whose
   * parameters are {@code (a, b)} and a call {@code f(b = 2, a = 1)} would receive {@code (2, 1)}.
   *
   * <p>The declaration order comes from the compiler's own symbol: the method's type carries the
   * parameter names in the order the method declares them. When the symbol does not resolve, the
   * source order is kept -- an argument is never DROPPED, because dropping it would say the callee
   * was called with fewer arguments than the source passed.
   *
   * @param fun the called function
   * @param args the arguments as written
   * @return the arguments, in the callee's order
   */
  private List<Trees.Tree<Type>> orderArguments(Trees.Tree<Type> fun, List<Trees.Tree<Type>> args) {
    boolean anyNamed = false;
    for (Trees.Tree<Type> arg : args) {
      if (arg instanceof Trees.NamedArg) {
        anyNamed = true;
        break;
      }
    }
    if (!anyNamed) {
      return args;
    }
    List<String> declared = declaredParameterNames(fun);
    if (declared == null || declared.isEmpty()) {
      // The declaration order is not known: the source order is kept.
      return args;
    }
    List<Trees.Tree<Type>> slots = new ArrayList<>(declared.size());
    for (int i = 0; i < declared.size(); i++) {
      slots.add(null);
    }
    List<Trees.Tree<Type>> extras = new ArrayList<>();
    for (Trees.Tree<Type> arg : args) {
      if (arg instanceof Trees.NamedArg) {
        String name = ((Trees.NamedArg<Type>) arg).name().toString();
        int index = declared.indexOf(name);
        if (index >= 0 && slots.get(index) == null) {
          slots.set(index, arg);
          continue;
        }
        extras.add(arg);
        continue;
      }
      // A positional argument fills the next free slot.
      int index = 0;
      while (index < slots.size() && slots.get(index) != null) {
        index++;
      }
      if (index < slots.size()) {
        slots.set(index, arg);
      } else {
        extras.add(arg);
      }
    }
    List<Trees.Tree<Type>> out = new ArrayList<>();
    for (Trees.Tree<Type> slot : slots) {
      if (slot != null) {
        out.add(slot);
      }
    }
    out.addAll(extras);
    return out;
  }

  /**
   * The names of a called method's parameters, in the order it declares them.
   *
   * @param fun the called function
   * @return the parameter names, or {@code null} when the declaration cannot be read
   */
  private List<String> declaredParameterNames(Trees.Tree<Type> fun) {
    try {
      Symbol symbol = fun.symbol(ctx);
      if (symbol == null) {
        return null;
      }
      Type info = symbol.denot(ctx).info(ctx);
      if (!(info instanceof dotty.tools.dotc.core.Types.MethodType)) {
        return null;
      }
      List<String> names = new ArrayList<>();
      scala.collection.Iterator<TermName> it =
          ((dotty.tools.dotc.core.Types.MethodType) info).paramNames().iterator();
      while (it.hasNext()) {
        names.add(it.next().toString());
      }
      return names;
    } catch (RuntimeException | LinkageError ex) {
      return null;
    }
  }

  /**
   * The signature of a called function, in the form the sink families match: {@code
   * "java.sql.Statement.execute/1"}.
   *
   * <p><b>The form matters, and it is not the dotc form.</b> The sink families in {@code
   * sink-families.yaml} are anchored to a fully qualified name with a DOT separator and an arity
   * suffix: {@code '^java\.sql\.Statement\.execute(/\d+)?'}. The DFA matches a call against them by
   * its callee signature. A signature in any other shape matches NONE of them -- not one pattern in
   * the file -- so every taint sink rule is silently inert on Scala code.
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
    // The tree that names the method is not always the tree the call is written
    // as. Three wrappers appear around it, and each one hides the method's name:
    //
    // <ul>
    //   <li>a call with explicit type arguments ("sortBy[String](...)") is a
    //       TypeApply whose function is the real call;
    //   <li>a call with a DEFAULT argument ("sortBy(f)", where sortBy also takes an
    //       implicit Ordering) is an Apply whose function is ANOTHER Apply, and it
    //       is the INNER one that names the method;
    //   <li>a plain call is a Select or an Ident.
    // </ul>
    //
    // Without unwrapping them the name stayed "<unknown>", so the emitted key was
    // "scala.collection.StringOps.<unknown>/1" -- a key that matches no sink
    // pattern and no semantics table, and the call was effectively invisible.
    Trees.Tree<Type> callee = fun;
    for (int depth = 0; depth < 4; depth++) {
      if (callee instanceof Trees.TypeApply) {
        callee = ((Trees.TypeApply<Type>) callee).fun();
      } else if (callee instanceof Trees.Apply) {
        Trees.Tree<Type> inner = ((Trees.Apply<Type>) callee).fun();
        // An Apply whose function is a Select is a binary operator or a call; the
        // Select names the method. An Apply whose function is ANOTHER Apply is the
        // default-argument wrapper, and the inner Apply names the method.
        if (inner instanceof Trees.Apply || inner instanceof Trees.TypeApply) {
          callee = inner;
        } else {
          break;
        }
      } else {
        break;
      }
    }
    if (callee instanceof Trees.Select) {
      name = ((Trees.Select<Type>) callee).name().toString();
    } else if (callee instanceof Trees.Ident) {
      name = ((Trees.Ident<Type>) callee).name().toString();
    }
    String owner = ownerOf(callee);
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
      BinaryOperator binop,
      Trees.Select<Type> select,
      Trees.Tree<Type> rhs,
      Trees.Tree<Type> tree) {
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

  /**
   * The TAC binary operator for a Scala operator name, or {@code null}.
   *
   * <p><b>Why {@code &&} and {@code ||} are NOT here.</b> The TAC has {@code LAND} and {@code LOR},
   * and the engine evaluates BOTH sides of a binary operation, always. A Scala {@code &&} that
   * evaluated its right operand when the left one was false would report a dereference of a null
   * the program never performs -- and it would report it as a FINDING, in code that is correct. So
   * the two logical operators are lowered as a branch chain instead, by {@link
   * #translateShortCircuit}.
   *
   * <p>A name that is not in this table is not an operator the TAC can express. It is never
   * reinterpreted as something else: an unknown operator is either a CALL to the method it names or
   * a recorded loss.
   */
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
            .calleeSig(constructorSignature(type, operands.size()))
            .args(operands)
            .line(lineOf(tree))
            .build());
    return dest;
  }

  /**
   * The signature of a CONSTRUCTOR, in the form the sink families match: {@code
   * "java.io.File.<init>/1"}.
   *
   * <p><b>Why a construction needs one.</b> The DFA's checkers match a sink family against an
   * instruction's callee signature, and a construction is matched the same way: {@code
   * FilePermissionChecker} and {@code PathTraversalChecker} both handle {@code Kind.NEW} and both
   * skip an instruction whose signature is null. A NEW with no signature is therefore invisible to
   * every constructor family -- the analysis runs, reports nothing, and looks like clean code.
   *
   * <p>The Java lane has always emitted the signature for a construction, and so has Kotlin.
   * Without it the Scala lane was the odd one out.
   *
   * @param type the constructed type
   * @param arity the number of constructor arguments
   * @return the constructor's signature, or null when the type does not name a class
   */
  private String constructorSignature(TacType type, int arity) {
    if (type == null || type.kind() != TacType.Kind.CLASS) {
      return null;
    }
    String owner = type.className();
    if (owner == null || owner.isEmpty()) {
      return null;
    }
    return owner + ".<init>/" + arity;
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
        Symbol symbol = lhsTree.symbol(ctx);
        // A write to a CAPTURED variable: the write goes into the shared
        // cell, so the caller sees it. Before this, such a write became a
        // field store on the enclosing class -- a field nobody reads -- and
        // the caller's variable kept its old value.
        CaptureCell cell = captureCellOf(symbol);
        if (cell != null) {
          if (value != null) {
            writeCapture(cell, value, assign);
          }
          return null;
        }
        // An assignment to an undeclared name: a field of the enclosing class.
        return translateFieldStore(null, name, value, symbol, assign);
      }
      if (value != null) {
        emitMove(target, value, assign);
        // The variable is captured: the shared cell has to be updated too,
        // or a closure that reads it sees the value from before the write.
        CaptureCell cell = captureCellOf(lhsTree.symbol(ctx));
        if (cell != null) {
          writeCapture(cell, value, assign);
        }
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
    reportLoss(
        org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
        "scala.assignment.unmodelled",
        "could not lower an assignment in " + currentFunction);
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
   * <p>The shape is the one the Java frontend produces: a {@code BRANCH} to the "then" block, a
   * {@code JUMP} past the "else" block, and a merge label at the end. The "else" block is emitted
   * between the "then" block's jump and the merge label, so the control flow is: true -> then ->
   * jump to end; false -> else -> fall through to end.
   *
   * <p><b>Why there is a result.</b> A Scala {@code if} is an EXPRESSION: {@code val x = if (c) a
   * else b} binds {@code x} to the value of the arm that ran. Lowering it to a branch with no value
   * lost both arms' values -- {@code x} was bound to nothing, and every taint that flowed through
   * either arm stopped at the branch. So the arms assign a result temp, and the temp is what the
   * expression produces.
   */
  private TacOperand translateIf(Trees.If<Type> ifTree) {
    String thenLabel = builder.newLabel("then");
    String elseLabel = builder.newLabel("else");
    String endLabel = builder.newLabel("endif");

    TacOperand cond = translateExpr(ifTree.cond());
    if (cond == null) {
      reportLoss(
          org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
          "scala.if.condition",
          "could not lower an if-condition in " + currentFunction);
      return null;
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(cond)
            .trueLabel(thenLabel)
            .falseLabel(elseLabel)
            .line(lineOf(ifTree))
            .build());

    // The value of the whole expression.
    TacOperand.TacVar dest = builder.newTemp(types.map(ifTree.tpe()));

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(thenLabel).build());
    TacOperand thenValue = lowerArmValue(ifTree.thenp());
    if (thenValue != null) {
      emitMove(dest, thenValue, ifTree);
    }
    builder.emit(TacInstruction.builder(TacInstruction.Kind.JUMP).label(endLabel).build());

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(elseLabel).build());
    Trees.Tree<Type> elsep = ifTree.elsep();
    if (elsep != null && !(elsep instanceof Trees.EmptyTree)) {
      TacOperand elseValue = lowerArmValue(elsep);
      if (elseValue != null) {
        emitMove(dest, elseValue, ifTree);
      }
    }

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(endLabel).build());
    return dest;
  }

  /**
   * Lowers one arm of an {@code if} and returns the arm's value.
   *
   * <p><b>Why an arm is not always a value.</b> An arm may be a statement -- {@code if (c) x = 1
   * else y = 2} -- and a statement produces no value. The result temp is then left unassigned on
   * that path, which is the honest answer: the path produces nothing, and the TAC says so by not
   * assigning anything.
   *
   * @param arm the arm
   * @return the arm's value, or {@code null} when the arm is a statement
   */
  private TacOperand lowerArmValue(Trees.Tree<Type> arm) {
    if (arm == null || arm instanceof Trees.EmptyTree) {
      return null;
    }
    if (arm instanceof Trees.ValDef) {
      // A definition is a statement: it binds a name and produces Unit.
      translateValDef((Trees.ValDef<Type>) arm);
      return null;
    }
    if (arm instanceof Trees.DefDef) {
      // A local definition: the compiler generates a separate function for it.
      return null;
    }
    return translateExpr(arm);
  }

  /**
   * Lowers a {@code while} loop.
   *
   * <p>The shape is a labelled condition, a {@code BRANCH} to the body or past it, and a {@code
   * JUMP} back to the condition -- the standard loop shape, the same one the Java frontend produces
   * for a {@code while} statement.
   */
  private TacOperand translateWhile(Trees.WhileDo<Type> whileTree) {
    String condLabel = builder.newLabel("whileCond");
    String bodyLabel = builder.newLabel("whileBody");
    String endLabel = builder.newLabel("whileEnd");

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(condLabel).build());
    TacOperand cond = translateExpr(whileTree.cond());
    if (cond == null) {
      reportLoss(
          org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
          "scala.while.condition",
          "could not lower a while-condition in " + currentFunction);
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

  /** What kind of pattern a case carries. */
  private enum PatternKind {
    /** A type test: "case s: String". */
    TYPED,
    /** A constant: "case 1". */
    CONSTANT,
    /** A binding: "case x". It matches anything, and names the selector. */
    BINDING,
    /** A tuple: "case (a, b)". */
    TUPLE,
    /** An extractor: "case Point(a, b)". */
    EXTRACTOR,
    /** An alternative: "case 1 | 2". */
    ALTERNATIVE,
    /** A sequence: "case Seq(a, b, rest*)". */
    SEQUENCE,
    /** The wildcard: "case _". */
    WILDCARD,
    /** A pattern this frontend cannot classify. */
    UNKNOWN
  }

  /**
   * A classified case pattern.
   *
   * <p>The compiler's own shapes say what a pattern is, and this holds the answer: the kind, the
   * name the pattern binds, the constant it matches, the type it tests, and the parts it pulls out
   * of the matched value.
   */
  private static final class MatchPattern {

    /** What the pattern is. */
    private final PatternKind kind;

    /** The name the pattern binds at its own level, or {@code null}. */
    private String binding;

    /** The type of the bound name, or {@code null}. */
    private Type bindingType;

    /** The constant a CONSTANT pattern matches, or {@code null}. */
    private final Trees.Tree<Type> constant;

    /** The type a TYPED, TUPLE, EXTRACTOR or SEQUENCE pattern tests, or {@code null}. */
    private final Type testedType;

    /** The extractor call of an EXTRACTOR pattern, or {@code null}. */
    private final Trees.Tree<Type> extractor;

    /** The sub-patterns of a TUPLE, EXTRACTOR or SEQUENCE pattern. */
    private final List<Trees.Tree<Type>> parts;

    /** The alternatives of an ALTERNATIVE pattern. */
    private final List<Trees.Tree<Type>> alternatives;

    private MatchPattern(
        PatternKind kind,
        String binding,
        Type bindingType,
        Trees.Tree<Type> constant,
        Type testedType,
        Trees.Tree<Type> extractor,
        List<Trees.Tree<Type>> parts,
        List<Trees.Tree<Type>> alternatives) {
      this.kind = kind;
      this.binding = binding;
      this.bindingType = bindingType;
      this.constant = constant;
      this.testedType = testedType;
      this.extractor = extractor;
      this.parts = parts;
      this.alternatives = alternatives;
    }

    static MatchPattern typed(String binding, Type bindingType, Type testedType) {
      return new MatchPattern(
          PatternKind.TYPED, binding, bindingType, null, testedType, null, List.of(), List.of());
    }

    static MatchPattern constant(String binding, Type bindingType, Trees.Tree<Type> constant) {
      return new MatchPattern(
          PatternKind.CONSTANT, binding, bindingType, constant, null, null, List.of(), List.of());
    }

    static MatchPattern binding(String binding, Type bindingType) {
      return new MatchPattern(
          PatternKind.BINDING, binding, bindingType, null, null, null, List.of(), List.of());
    }

    static MatchPattern tuple(List<Trees.Tree<Type>> parts, Type testedType) {
      return new MatchPattern(
          PatternKind.TUPLE, null, null, null, testedType, null, parts, List.of());
    }

    static MatchPattern extractor(
        Trees.Tree<Type> extractor, List<Trees.Tree<Type>> parts, Type testedType) {
      return new MatchPattern(
          PatternKind.EXTRACTOR, null, null, null, testedType, extractor, parts, List.of());
    }

    static MatchPattern sequence(List<Trees.Tree<Type>> parts, Type testedType) {
      return new MatchPattern(
          PatternKind.SEQUENCE, null, null, null, testedType, null, parts, List.of());
    }

    static MatchPattern alternative(List<Trees.Tree<Type>> alternatives) {
      return new MatchPattern(
          PatternKind.ALTERNATIVE, null, null, null, null, null, List.of(), alternatives);
    }

    static MatchPattern wildcard() {
      return new MatchPattern(
          PatternKind.WILDCARD, null, null, null, null, null, List.of(), List.of());
    }

    static MatchPattern unknown() {
      return new MatchPattern(
          PatternKind.UNKNOWN, null, null, null, null, null, List.of(), List.of());
    }
  }

  /**
   * Lowers a {@code try} expression.
   *
   * <p><b>The shape is the Java frontend's, copied exactly.</b> The try body, then a {@code LABEL}
   * and a {@code CATCH_HANDLER} per catch clause, then the {@code FINALLY_ENTER} marker and the
   * finalizer -- and a {@code TryRegion}, which is what tells the CFG builder where the exception
   * edges go. A catch handler's parameter is the {@code CATCH_HANDLER}'s {@code dest}, and the
   * caught type is its {@code ctorType}.
   *
   * <p>Without this, a Scala {@code try} was not lowered at all: the whole construct fell into the
   * "unrecognised expression" branch, so the catch handlers' bodies and the finalizer were not in
   * the program, and the code after the {@code try} was not either.
   *
   * @param tryTree the try expression
   * @return the value of the try, or of the catch that ran
   */
  private TacOperand translateTry(Trees.Try<Type> tryTree) {
    // The value of the whole expression. A Scala "try" is an expression, and the
    // body that ran -- the try's or a catch's -- is what produced the value.
    TacOperand.TacVar dest = builder.newTemp(types.map(tryTree.tpe()));

    // The try body.
    int tryStart = builder.instructions().size();
    TacOperand bodyValue = lowerArmValue(tryTree.expr());
    if (bodyValue != null) {
      emitMove(dest, bodyValue, tryTree);
    }
    int tryEnd = builder.instructions().size() - 1;

    // The catch clauses.
    List<String> catchLabels = new ArrayList<>();
    int firstCatchStart = -1;
    int lastCatchEnd = -1;
    for (Trees.CaseDef<Type> clause : toJavaList(tryTree.cases())) {
      Type caughtType = caughtTypeOf(clause);
      String labelName = builder.newLabel("L_CATCH_" + simpleTypeName(types.map(caughtType)));
      catchLabels.add(labelName);
      if (firstCatchStart < 0) {
        firstCatchStart = builder.instructions().size();
      }
      // The LABEL comes first, so the label-name-based tooling keeps working.
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.LABEL)
              .label(labelName)
              .line(lineOf(clause))
              .build());
      TacOperand.TacVar parameter = catchParameter(clause, caughtType);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.CATCH_HANDLER)
              .dest(parameter)
              .ctorType(types.map(caughtType))
              .line(lineOf(clause))
              .build());
      TacOperand catchValue = translateCaseBody(clause);
      if (catchValue != null) {
        emitMove(dest, catchValue, clause);
      }
      lastCatchEnd = builder.instructions().size() - 1;
    }

    // The finally block.
    String finallyLabel = null;
    int finallyStart = -1;
    int finallyEnd = -1;
    Trees.Tree<Type> finalizer = tryTree.finalizer();
    if (finalizer != null && !(finalizer instanceof Trees.EmptyTree)) {
      finallyLabel = builder.newLabel("L_FINALLY");
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.LABEL)
              .label(finallyLabel)
              .line(lineOf(finalizer))
              .build());
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.FINALLY_ENTER)
              .label(finallyLabel)
              .line(lineOf(finalizer))
              .build());
      finallyStart = builder.instructions().size();
      translateStatement(finalizer);
      finallyEnd = builder.instructions().size() - 1;
    }

    // The region, so the CFG builder can add the exception-flow edges.
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
    return dest;
  }

  /**
   * The type a catch clause catches, or {@code null}.
   *
   * <p>The clause's pattern is a binding of the exception's name to a typed scrutinee: {@code "case
   * e: IOException =>"} is {@code Bind(e, Typed(_, IOException))}.
   *
   * @param clause the catch clause
   * @return the caught type
   */
  private Type caughtTypeOf(Trees.CaseDef<Type> clause) {
    Trees.Tree<Type> pattern = clause.pat();
    if (pattern instanceof Trees.Bind) {
      pattern = ((Trees.Bind<Type>) pattern).body();
    }
    if (pattern instanceof Trees.Typed) {
      return ((Trees.Typed<Type>) pattern).tpt().tpe();
    }
    return null;
  }

  /**
   * The variable a catch clause binds the caught exception to.
   *
   * @param clause the catch clause
   * @param caughtType the caught type
   * @return the parameter
   */
  private TacOperand.TacVar catchParameter(Trees.CaseDef<Type> clause, Type caughtType) {
    String name = boundName(clause.pat());
    if (name == null || name.isEmpty()) {
      name = "exc";
    }
    TacOperand.TacVar parameter = new TacOperand.TacVar(name, types.map(caughtType), false);
    locals.put(name, parameter);
    builder.addLocal(parameter);
    return parameter;
  }

  /**
   * The short name of a type, for a label.
   *
   * @param type the type
   * @return the name without its package
   */
  private static String simpleTypeName(TacType type) {
    if (type == null || type.kind() != TacType.Kind.CLASS) {
      return "";
    }
    String name = type.className();
    if (name == null || name.isEmpty()) {
      return "";
    }
    int dot = name.lastIndexOf('.');
    return dot < 0 ? name : name.substring(dot + 1);
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
   * <p>Each case's body is emitted inline after its own test, and the <i>next</i> case's test
   * follows it. The false edge of a test goes to the next case, not to the end -- that is what
   * makes it a chain rather than a series of independent tests.
   *
   * <p><b>Why the pattern kinds matter.</b> Every pattern that was neither a type test nor a
   * constant used to fall into the "wildcard" branch, and a wildcard matches unconditionally AND
   * ends the chain. So {@code case Point(x, y) =>} swallowed every case after it: the rest of the
   * match was never lowered at all. Each kind is now classified and lowered on its own, and no case
   * is dropped.
   *
   * <p><b>Why the function's precision is downgraded.</b> The {@code match} is lowered away: the
   * branch chain says which case runs, but it does not carry the pattern's binding information the
   * way the source did -- a narrowed binding is a plain move here, with no type refinement attached
   * to it. A checker must therefore not trust a precision-dependent fact (such as "this value is
   * non-null because the pattern narrowed it") about a function whose {@code match} was lowered.
   * That is exactly what {@link IrPrecision#SOURCE_LOWERED} means.
   */
  private TacOperand translateMatch(Trees.Match<Type> match) {
    // The gap is RECORDED, not only flagged. A degraded function whose degradation is
    // invisible produces findings that look exactly as complete as a fully lowered one --
    // and the degradation is the whole point of the flag.
    //
    // What the lowering DOES reproduce: the pattern's type test, its constant test, its
    // bindings, an extractor's own call, and a guard. What it does NOT reproduce is the
    // compiler's own decision tree: the cases are a branch chain, so a match's
    // EXHAUSTIVENESS is not decided, and a checker must not read "the match always
    // matches" into a function whose matcher may not.
    reportLoss(
        org.plumb.tac.ir.TacDrop.Impact.STRUCTURE,
        "scala.match.shape",
        "a pattern match was lowered as a branch chain in "
            + currentFunction
            + "; its bindings are lowered, but the match's exhaustiveness is not decided");

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

    // The value of the whole match. A Scala "match" is an expression, and the case
    // that ran is what produced the value.
    TacOperand.TacVar dest = builder.newTemp(types.map(match.tpe()));

    for (int i = 0; i < cases.size(); i++) {
      Trees.CaseDef<Type> caseDef = cases.get(i);
      String bodyLabel = caseLabels.get(i);
      // The case after this one, or the end of the match.
      String nextLabel = i + 1 < cases.size() ? caseLabels.get(i + 1) : endLabel;

      MatchPattern pattern = classifyPattern(caseDef.pat());
      Trees.Tree<Type> guard = caseDef.guard();
      boolean hasGuard = guard != null && !(guard instanceof Trees.EmptyTree);

      // A wildcard matches everything, so the chain ends there -- unless it carries
      // a guard, in which case a guard that fails falls through to the next case
      // and the chain continues.
      boolean endsChain = pattern.kind == PatternKind.WILDCARD && !hasGuard;

      switch (pattern.kind) {
        case TYPED:
          emitTypeTest(pattern, pattern.testedType, selector, caseDef, bodyLabel, nextLabel);
          break;
        case CONSTANT:
          emitConstantTest(pattern, selector, caseDef, bodyLabel, nextLabel);
          break;
        case BINDING:
          emitBindingCase(pattern, selector, caseDef, bodyLabel);
          break;
        case TUPLE:
          {
            // The tuple's type is the type its extractor matches, which the compiler
            // writes down only when it differs from the scrutinee's type.
            Type matchedType = resolvedMatchedType(pattern);
            if (matchedType == null) {
              emitUnknownPattern(caseDef, bodyLabel, nextLabel);
              break;
            }
            emitTypeTest(pattern, matchedType, selector, caseDef, bodyLabel, nextLabel);
            bindProductParts(pattern.parts, selector, caseDef);
            break;
          }
        case EXTRACTOR:
          emitExtractorTest(pattern, selector, caseDef, bodyLabel, nextLabel);
          break;
        case SEQUENCE:
          {
            Type matchedType = resolvedMatchedType(pattern);
            if (matchedType == null) {
              emitUnknownPattern(caseDef, bodyLabel, nextLabel);
              break;
            }
            emitTypeTest(pattern, matchedType, selector, caseDef, bodyLabel, nextLabel);
            bindSequenceParts(pattern.parts, selector, caseDef);
            break;
          }
        case ALTERNATIVE:
          emitAlternativeTest(pattern, selector, caseDef, bodyLabel, nextLabel);
          break;
        case WILDCARD:
          // No test: the case matches everything.
          builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(bodyLabel).build());
          break;
        default:
          emitUnknownPattern(caseDef, bodyLabel, nextLabel);
          break;
      }

      // The case's guard, if it has one. A guard that fails falls through to the
      // next case.
      if (hasGuard) {
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
          builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(guardLabel).build());
        }
      }

      TacOperand caseValue = translateCaseBody(caseDef);
      if (caseValue != null) {
        emitMove(dest, caseValue, caseDef);
      }
      builder.emit(TacInstruction.builder(TacInstruction.Kind.JUMP).label(endLabel).build());

      if (endsChain) {
        break;
      }
    }

    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(endLabel).build());
    return dest;
  }

  /**
   * Emits the test of a pattern that tests a TYPE: "case s: String", "case (a, b)".
   *
   * <p>The test is the selector's runtime type, and the false edge goes to the next case.
   *
   * @param pattern the pattern
   * @param selector the value being matched
   * @param caseDef the case, for the source position
   * @param bodyLabel the label the case's body starts at
   * @param nextLabel the label the next case starts at
   */
  private void emitTypeTest(
      MatchPattern pattern,
      Type testedType,
      TacOperand selector,
      Trees.CaseDef<Type> caseDef,
      String bodyLabel,
      String nextLabel) {
    TacType type = testedType == null ? TacType.UNKNOWN : types.map(testedType);
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
    bindPatternVariable(
        pattern.binding,
        pattern.bindingType == null ? type : types.map(pattern.bindingType),
        selector,
        caseDef);
  }

  /**
   * Emits the test of a CONSTANT pattern: "case 1" and "case y @ 1".
   *
   * @param pattern the pattern
   * @param selector the value being matched
   * @param caseDef the case, for the source position
   * @param bodyLabel the label the case's body starts at
   * @param nextLabel the label the next case starts at
   */
  private void emitConstantTest(
      MatchPattern pattern,
      TacOperand selector,
      Trees.CaseDef<Type> caseDef,
      String bodyLabel,
      String nextLabel) {
    TacOperand expected = translateExpr(pattern.constant);
    if (expected == null) {
      // The constant could not be lowered. The case still has to run, and the chain
      // must not break, so the case is emitted on an unknown condition.
      emitUnknownPattern(caseDef, bodyLabel, nextLabel);
      return;
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

    // A named constant ("case y @ 1") binds the selector once the constant matched.
    bindPatternVariable(
        pattern.binding,
        pattern.bindingType == null ? TacType.UNKNOWN : types.map(pattern.bindingType),
        selector,
        caseDef);
  }

  /**
   * Emits a BINDING pattern: "case x".
   *
   * <p>It matches anything, so there is no test: the name is bound and the body runs. The chain is
   * NOT ended -- a binding pattern whose guard fails falls through to the next case, exactly as any
   * other case does.
   *
   * @param pattern the pattern
   * @param selector the value being matched
   * @param caseDef the case, for the source position
   * @param bodyLabel the label the case's body starts at
   */
  private void emitBindingCase(
      MatchPattern pattern, TacOperand selector, Trees.CaseDef<Type> caseDef, String bodyLabel) {
    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(bodyLabel).build());
    bindPatternVariable(
        pattern.binding,
        pattern.bindingType == null ? TacType.UNKNOWN : types.map(pattern.bindingType),
        selector,
        caseDef);
  }

  /**
   * Emits the test of an EXTRACTOR pattern: "case Point(a, b)".
   *
   * <p><b>The type test comes first.</b> The compiler's own shape is: the selector has to have the
   * matched type, and only then is the extractor's own call made. So the lowering is a type test, a
   * branch to the case's body or to the next case, and -- on the path where the type matched -- the
   * extractor's own call.
   *
   * <p><b>Three shapes after the type test, decided by the extractor's own result type.</b> An
   * extractor that returns an {@code Option} -- the usual shape for a general extractor -- is asked
   * whether the Option is defined, and the parts are read from the Option's content. An extractor
   * that returns the product ITSELF -- the shape the compiler gives a case class -- has its parts
   * read from the product. An extractor that returns the CONTENT -- the shape of an extractor whose
   * {@code unapply} yields the matched value -- has its parts read from the content.
   *
   * <p><b>Why the result type decides.</b> A case class's {@code unapply} returns the case class,
   * not an {@code Option}: asking such a value whether it is defined would be a call to a method
   * the class does not have, and a wrong call is worse than a missing one.
   *
   * @param pattern the pattern
   * @param selector the value being matched
   * @param caseDef the case, for the source position
   * @param bodyLabel the label the case's body starts at
   * @param nextLabel the label the next case starts at
   */
  private void emitExtractorTest(
      MatchPattern pattern,
      TacOperand selector,
      Trees.CaseDef<Type> caseDef,
      String bodyLabel,
      String nextLabel) {
    Type matchedType = resolvedMatchedType(pattern);
    if (matchedType == null) {
      // Neither the pattern's type nor the extractor's own parameter is known, so
      // the pattern's shape is not either.
      emitUnknownPattern(caseDef, bodyLabel, nextLabel);
      return;
    }
    Type resultType = extractorResultType(pattern);

    // The type test comes FIRST. The compiler's own shape is: the selector has
    // to have the matched type, and only then is the extractor's own call made.
    // The call therefore never runs on a value the pattern cannot match.
    String typeOkLabel = builder.newLabel("matchExtractType");
    emitTypeTest(pattern, matchedType, selector, caseDef, typeOkLabel, nextLabel);

    if (resultType != null && isOptionType(resultType)) {
      emitOptionExtractorTest(pattern, selector, caseDef, bodyLabel, nextLabel);
      return;
    }
    if (resultType == null || sameType(resultType, matchedType)) {
      // A product extractor: the pattern matches instances of the product, and the
      // parts are the product's own fields.
      builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(bodyLabel).build());
      bindProductParts(pattern.parts, selector, caseDef);
      return;
    }
    // The extractor returns the CONTENT itself. The pattern still tests the
    // selector's type, and the content is what the parts are read from.
    TacOperand content = callExtractor(pattern, selector, caseDef);
    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(bodyLabel).build());
    if (content != null) {
      bindExtractedParts(pattern.parts, content, caseDef);
    }
  }

  /**
   * The type a pattern tests: the type the pattern wrote down, or -- when it wrote none -- the
   * extractor's own first parameter.
   *
   * <p>The compiler writes the type down as an ascription only when it differs from the scrutinee's
   * type; when it does not, the extractor's own parameter is the only place the matched type is
   * written.
   *
   * @param pattern the pattern
   * @return the matched type, or {@code null} when it cannot be read
   */
  private Type resolvedMatchedType(MatchPattern pattern) {
    if (pattern.testedType != null) {
      return pattern.testedType;
    }
    return extractorMatchedType(pattern);
  }

  /**
   * Binds the parts an extractor produced.
   *
   * <p><b>Why one part is the content itself.</b> When the extractor yields a single value, that
   * value IS the part -- "case Some(v)" binds {@code v} to the option's content, not to a field of
   * it. Several parts are the fields of a product, and they are read as such.
   *
   * @param parts the pattern's sub-patterns
   * @param content the extracted value
   * @param tree the case, for the source position
   */
  private void bindExtractedParts(
      List<Trees.Tree<Type>> parts, TacOperand content, Trees.Tree<Type> tree) {
    if (parts.size() == 1) {
      bindPatternVariable(boundName(parts.get(0)), partType(parts.get(0)), content, tree);
      return;
    }
    bindProductParts(parts, content, tree);
  }

  /**
   * Calls an extractor's own method.
   *
   * @param pattern the pattern
   * @param selector the value being matched
   * @param caseDef the case, for the source position
   * @return the extracted value
   */
  private TacOperand callExtractor(
      MatchPattern pattern, TacOperand selector, Trees.CaseDef<Type> caseDef) {
    String owner = ownerOf(pattern.extractor);
    String unapply = (owner == null || owner.isEmpty() ? "unapply" : owner + ".unapply") + "/1";
    TacOperand.TacVar result = builder.newTemp(TacType.UNKNOWN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(result)
            .calleeSig(unapply)
            .args(List.of(selector))
            .line(lineOf(caseDef))
            .build());
    return result;
  }

  /**
   * Emits the test of an extractor that returns an {@code Option}.
   *
   * <p>The type test has already been emitted by the caller: this runs on the path where the
   * selector had the matched type, and it asks the Option whether it is defined. The parts are read
   * from the Option's content.
   *
   * <p><b>Why this shape.</b> It is the compiler's own: Scala 3's {@code Some.unapply} and {@code
   * Tuple2.unapply} both yield an {@code Option}, and the pattern matches when that Option is
   * defined.
   *
   * @param pattern the pattern
   * @param selector the value being matched
   * @param caseDef the case, for the source position
   * @param bodyLabel the label the case's body starts at
   * @param nextLabel the label the next case starts at
   */
  private void emitOptionExtractorTest(
      MatchPattern pattern,
      TacOperand selector,
      Trees.CaseDef<Type> caseDef,
      String bodyLabel,
      String nextLabel) {
    TacOperand option = callExtractor(pattern, selector, caseDef);

    TacOperand.TacVar defined = builder.newTemp(TacType.BOOLEAN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(defined)
            .calleeSig("scala.Option.isDefined/1")
            .args(List.of(option))
            .line(lineOf(caseDef))
            .build());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(defined)
            .trueLabel(bodyLabel)
            .falseLabel(nextLabel)
            .line(lineOf(caseDef))
            .build());
    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(bodyLabel).build());

    // The content of the Option holds the parts. The content is read only on the
    // branch where the Option is defined, which is the branch the body runs on.
    TacOperand.TacVar content = builder.newTemp(TacType.UNKNOWN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(content)
            .calleeSig("scala.Option.get/1")
            .args(List.of(option))
            .line(lineOf(caseDef))
            .build());

    bindExtractedParts(pattern.parts, content, caseDef);
  }

  /**
   * Emits the test of an ALTERNATIVE pattern: "case 1 | 2".
   *
   * <p><b>One test.</b> The pattern matches when the selector equals ANY of the alternatives, so
   * the test is the alternatives' comparisons OR-ed together. A chain of tests would be wrong: the
   * second alternative would only be tried when the first one failed, and the case would run on the
   * wrong path.
   *
   * @param pattern the pattern
   * @param selector the value being matched
   * @param caseDef the case, for the source position
   * @param bodyLabel the label the case's body starts at
   * @param nextLabel the label the next case starts at
   */
  private void emitAlternativeTest(
      MatchPattern pattern,
      TacOperand selector,
      Trees.CaseDef<Type> caseDef,
      String bodyLabel,
      String nextLabel) {
    TacOperand test = null;
    for (Trees.Tree<Type> alternative : pattern.alternatives) {
      TacOperand expected = translateExpr(alternative);
      if (expected == null) {
        // One of the alternatives could not be lowered, so the test as a whole is
        // unknown: the case is emitted on an unknown condition.
        emitUnknownPattern(caseDef, bodyLabel, nextLabel);
        return;
      }
      TacOperand.TacVar equals = builder.newTemp(TacType.BOOLEAN);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.BINOP)
              .dest(equals)
              .lhs1(selector)
              .lhs2(expected)
              .binop(BinaryOperator.EQ)
              .line(lineOf(caseDef))
              .build());
      if (test == null) {
        test = equals;
        continue;
      }
      TacOperand.TacVar either = builder.newTemp(TacType.BOOLEAN);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.BINOP)
              .dest(either)
              .lhs1(test)
              .lhs2(equals)
              .binop(BinaryOperator.OR)
              .line(lineOf(caseDef))
              .build());
      test = either;
    }
    if (test == null) {
      emitUnknownPattern(caseDef, bodyLabel, nextLabel);
      return;
    }
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(test)
            .trueLabel(bodyLabel)
            .falseLabel(nextLabel)
            .line(lineOf(caseDef))
            .build());
    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(bodyLabel).build());
  }

  /**
   * Emits a case whose pattern this frontend could not classify.
   *
   * <p><b>Why the condition is unknown rather than true.</b> The old lowering treated every
   * unrecognised pattern as a wildcard: it matched unconditionally, and -- because a wildcard ends
   * the chain -- it DROPPED every case after it. The case is still emitted here, but on a condition
   * the analysis does not know: the engine then sees both edges as live, so the case runs on one
   * path and the NEXT case runs on the other. No case is dropped, and nothing claims the pattern
   * matched when it may not have.
   *
   * <p>The gap is recorded, so the function's precision says the match was not fully lowered.
   *
   * @param caseDef the case, for the source position
   * @param bodyLabel the label the case's body starts at
   * @param nextLabel the label the next case starts at
   */
  private void emitUnknownPattern(Trees.CaseDef<Type> caseDef, String bodyLabel, String nextLabel) {
    reportLoss(
        org.plumb.tac.ir.TacDrop.Impact.VALUE,
        "scala.match.patternKind",
        "a case pattern of an unknown kind was not lowered in " + currentFunction);
    TacOperand.TacVar unknown = builder.newTemp(TacType.BOOLEAN);
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.HAVOC)
            .dest(unknown)
            .line(lineOf(caseDef))
            .build());
    builder.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(unknown)
            .trueLabel(bodyLabel)
            .falseLabel(nextLabel)
            .line(lineOf(caseDef))
            .build());
    builder.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(bodyLabel).build());
  }

  /**
   * Lowers a case's body.
   *
   * <p><b>Why the body is not lowered as a function body.</b> A block's value is normally the
   * function's RETURN, and lowering a case's block that way ended the FUNCTION at the first case:
   * every case after it was unreachable, and so was everything the function did after the match. A
   * case's block is an expression, and its value is the case's value.
   *
   * @param caseDef the case
   * @return the case's value, or {@code null} when the body produces none
   */
  private TacOperand translateCaseBody(Trees.CaseDef<Type> caseDef) {
    Trees.Tree<Type> body = caseDef.body();
    if (body == null || body instanceof Trees.EmptyTree) {
      return null;
    }
    if (body instanceof Trees.Block) {
      return translateBlockValue((Trees.Block<Type>) body);
    }
    if (body instanceof Trees.ValDef) {
      // A definition is a statement: it binds a name and produces Unit.
      translateValDef((Trees.ValDef<Type>) body);
      return null;
    }
    return translateExpr(body);
  }

  /**
   * Classifies a case's pattern.
   *
   * <p><b>What the compiler leaves behind.</b> The typer rewrites a pattern into one of a few
   * shapes, and the shape says what the pattern is:
   *
   * <ul>
   *   <li>a type test is a {@code Bind} of a name to a {@code Typed} scrutinee -- "case s: String";
   *   <li>a constant is a literal, or a stable identifier -- "case 1", "case SomeValue";
   *   <li>a binding is a {@code Bind} of a name to the wildcard -- "case x";
   *   <li>a tuple and an extractor are a {@code Typed} scrutinee around an {@code UnApply} -- "case
   *       (a, b)" and "case Point(a, b)";
   *   <li>a sequence is an {@code UnApply} of "unapplySeq" -- "case Seq(a, b, rest*)";
   *   <li>an alternative is an {@code Alternative} -- "case 1 | 2";
   *   <li>the wildcard is the name "_" on its own.
   * </ul>
   *
   * <p>A pattern this frontend cannot classify is UNKNOWN, and it is lowered as an unknown
   * condition -- never as a wildcard, which would swallow the cases after it.
   *
   * @param pattern the pattern
   * @return the classified pattern
   */
  private MatchPattern classifyPattern(Trees.Tree<Type> pattern) {
    if (pattern == null) {
      return MatchPattern.unknown();
    }
    if (pattern instanceof Trees.Bind) {
      Trees.Bind<Type> bind = (Trees.Bind<Type>) pattern;
      String name = bind.name().toString();
      Trees.Tree<Type> body = bind.body();
      if (body instanceof Trees.Typed) {
        // "case s: String" -- a type test that also binds the narrowed value.
        return MatchPattern.typed(
            name, bindingTypeOf(bind), ((Trees.Typed<Type>) body).tpt().tpe());
      }
      if (body instanceof Trees.Literal) {
        // "case y @ 1" -- the constant is tested first, and the name is bound after it.
        return MatchPattern.constant(name, bindingTypeOf(bind), body);
      }
      if (isWildcardIdent(body)) {
        // "case x" -- a binding: it matches anything.
        return MatchPattern.binding(name, bindingTypeOf(bind));
      }
      MatchPattern inner = classifyPattern(body);
      if (inner.kind == PatternKind.UNKNOWN) {
        return inner;
      }
      // "case x @ Point(a, b)" -- the outer name binds the whole match.
      inner.binding = name;
      inner.bindingType = bindingTypeOf(bind);
      return inner;
    }
    if (pattern instanceof Trees.Alternative) {
      return MatchPattern.alternative(toJavaList(((Trees.Alternative<Type>) pattern).trees()));
    }
    if (pattern instanceof Trees.Literal) {
      return MatchPattern.constant(null, null, pattern);
    }
    if (pattern instanceof Trees.Ident) {
      if (isWildcardIdent(pattern)) {
        return MatchPattern.wildcard();
      }
      // A stable identifier: "case SomeValue =>" compares the selector with it.
      return MatchPattern.constant(null, null, pattern);
    }
    if (pattern instanceof Trees.Select) {
      // A stable identifier written as a selection: "case Point.X =>".
      return MatchPattern.constant(null, null, pattern);
    }
    if (pattern instanceof Trees.UnApply) {
      Trees.UnApply<Type> un = (Trees.UnApply<Type>) pattern;
      return classifyExtractor(un.fun(), toJavaList(un.patterns()), null);
    }
    if (pattern instanceof Trees.Typed) {
      Trees.Typed<Type> typed = (Trees.Typed<Type>) pattern;
      Trees.Tree<Type> expr = typed.expr();
      if (expr instanceof Trees.UnApply) {
        Trees.UnApply<Type> un = (Trees.UnApply<Type>) expr;
        return classifyExtractor(un.fun(), toJavaList(un.patterns()), typed.tpt().tpe());
      }
      // A type test with no binding: "case _: String".
      return MatchPattern.typed(null, null, typed.tpt().tpe());
    }
    return MatchPattern.unknown();
  }

  /**
   * Classifies the extractor of a pattern.
   *
   * @param extractor the extractor's call
   * @param parts the pattern's sub-patterns
   * @param testedType the type the pattern tests, or {@code null}
   * @return the classified pattern
   */
  private MatchPattern classifyExtractor(
      Trees.Tree<Type> extractor, List<Trees.Tree<Type>> parts, Type testedType) {
    String method = operatorNameOf(extractor);
    Type resultType = extractorResultTypeOf(extractor);
    // The type the pattern TESTS. The compiler writes it down as an ascription
    // only when it differs from the scrutinee's type; when it does not, the
    // extractor's own first parameter is where the type is written.
    Type matchedType = testedType != null ? testedType : extractorMatchedTypeOf(extractor);
    if ("unapplySeq".equals(method)) {
      return MatchPattern.sequence(parts, matchedType);
    }
    if (isTupleType(resultType)) {
      // An extractor that yields the tuple ITSELF: the parts are the product's
      // own fields.
      //
      // Scala 3's own tuple does NOT take this path: its "unapply" yields an
      // Option -- measured, "scala.Tuple2$.unapply" returns Option[Tuple2] --
      // so a tuple pattern is lowered as an Option extractor, which is the shape
      // the compiler itself uses.
      return MatchPattern.tuple(parts, matchedType);
    }
    return MatchPattern.extractor(extractor, parts, matchedType);
  }

  /**
   * Whether a type is a tuple.
   *
   * @param type the type
   * @return whether it is a tuple
   */
  private boolean isTupleType(Type type) {
    if (type == null) {
      return false;
    }
    TacType mapped = types.map(type);
    if (mapped.kind() != TacType.Kind.CLASS) {
      return false;
    }
    String name = mapped.className();
    return "scala.Tuple2".equals(name) || "scala.Tuple3".equals(name);
  }

  /**
   * The result type of an extractor's own call, or {@code null}.
   *
   * @param extractor the extractor's call
   * @return the result type
   */
  private Type extractorResultTypeOf(Trees.Tree<Type> extractor) {
    return resultTypeOf(extractorInfoOf(extractor));
  }

  /**
   * The result type of a method, or {@code null}.
   *
   * <p><b>Why the method type is unwrapped.</b> A method with type parameters has a POLY type, and
   * the poly type's "result" is the METHOD type -- not what the method returns. Reading it as the
   * result said that every extractor's result was a method type, and then no extractor could be
   * recognised: the case class's own extractor looked like something that yields a method.
   *
   * @param info the method's type
   * @return the result type
   */
  private Type resultTypeOf(Type info) {
    if (info == null) {
      return null;
    }
    try {
      Type result = info.resultType(ctx);
      if (result instanceof dotty.tools.dotc.core.Types.MethodType) {
        result = ((dotty.tools.dotc.core.Types.MethodType) result).resultType(ctx);
      }
      return result;
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /**
   * The type of an extractor's own call, or {@code null}.
   *
   * @param extractor the extractor's call
   * @return the method type
   */
  private Type extractorInfoOf(Trees.Tree<Type> extractor) {
    if (extractor == null) {
      return null;
    }
    try {
      Symbol symbol = extractor.symbol(ctx);
      if (symbol == null) {
        return null;
      }
      return symbol.denot(ctx).info(ctx);
    } catch (RuntimeException | LinkageError ex) {
      return null;
    }
  }

  /**
   * The type an extractor's own call MATCHES: the type of its first parameter.
   *
   * <p>This is the type the pattern tests, whether or not the compiler wrapped the pattern in a
   * type ascription. The wrapper is only there when the pattern's type differs from the
   * scrutinee's, so a pattern on a typed scrutinee has no wrapper -- and then the extractor's own
   * parameter is the only place the matched type is written down.
   *
   * @param extractor the extractor's call
   * @return the matched type, or {@code null} when it cannot be read
   */
  private Type extractorMatchedTypeOf(Trees.Tree<Type> extractor) {
    Type info = extractorInfoOf(extractor);
    if (info == null) {
      return null;
    }
    try {
      List<Type> params = toJavaList(info.firstParamTypes(ctx));
      return params.isEmpty() ? null : params.get(0);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /**
   * The type an extractor MATCHES: the type of its first parameter.
   *
   * @param pattern the pattern
   * @return the matched type
   */
  private Type extractorMatchedType(MatchPattern pattern) {
    return extractorMatchedTypeOf(pattern.extractor);
  }

  /**
   * The result type of an extractor's own call, or {@code null}.
   *
   * @param pattern the pattern
   * @return the result type
   */
  private Type extractorResultType(MatchPattern pattern) {
    return resultTypeOf(extractorInfoOf(pattern.extractor));
  }

  /**
   * Whether a type is {@code scala.Option}.
   *
   * @param type the type
   * @return whether it is an Option
   */
  private boolean isOptionType(Type type) {
    TacType mapped = types.map(type);
    return mapped.kind() == TacType.Kind.CLASS && "scala.Option".equals(mapped.className());
  }

  /**
   * Whether two types are the same class.
   *
   * <p><b>Why the names and not the symbols.</b> A method's own result type mentions the method's
   * type parameters -- {@code Tuple2.unapply} yields {@code Tuple2[T1, T2]} -- while the type the
   * pattern tests is {@code Tuple2[Any, Any]}. The two are the same class, but their symbols are
   * not the same object, so the symbol test said "different" where the names say "the same".
   *
   * @param a the first type
   * @param b the second type
   * @return whether they name the same class
   */
  private boolean sameType(Type a, Type b) {
    if (a == null || b == null) {
      return false;
    }
    TacType left = types.map(a);
    TacType right = types.map(b);
    if (left.kind() != TacType.Kind.CLASS || right.kind() != TacType.Kind.CLASS) {
      return false;
    }
    String leftName = left.className();
    String rightName = right.className();
    return leftName != null && leftName.equals(rightName);
  }

  /**
   * The type of a bound name, or {@code null} when it cannot be read.
   *
   * @param bind the binding
   * @return the type
   */
  private Type bindingTypeOf(Trees.Tree<Type> bind) {
    try {
      return bind.tpe();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /**
   * Whether a tree is the wildcard.
   *
   * @param tree the tree
   * @return whether it is the wildcard
   */
  private static boolean isWildcardIdent(Trees.Tree<Type> tree) {
    return tree instanceof Trees.Ident && "_".equals(((Trees.Ident<Type>) tree).name().toString());
  }

  /**
   * Whether a pattern's part is the varargs part -- the "rest*" of "case Seq(a, b, rest*)".
   *
   * @param part the part
   * @return whether it is the varargs part
   */
  private static boolean isVarargsPart(Trees.Tree<Type> part) {
    if (!(part instanceof Trees.Bind)) {
      return false;
    }
    Trees.Tree<Type> body = ((Trees.Bind<Type>) part).body();
    if (!(body instanceof Trees.Typed)) {
      return false;
    }
    Trees.Tree<Type> expr = ((Trees.Typed<Type>) body).expr();
    return expr instanceof Trees.Ident && "_*".equals(((Trees.Ident<Type>) expr).name().toString());
  }

  /**
   * The type of a pattern's part.
   *
   * @param part the part
   * @return the type
   */
  private TacType partType(Trees.Tree<Type> part) {
    try {
      return types.map(part.tpe());
    } catch (RuntimeException ex) {
      return TacType.UNKNOWN;
    }
  }

  /**
   * Declares a pattern's binding and fills it.
   *
   * @param name the bound name, or {@code null} when the pattern binds nothing
   * @param type the bound name's type
   * @param value the value the name receives
   * @param tree the case, for the source position
   */
  private void bindPatternVariable(
      String name, TacType type, TacOperand value, Trees.Tree<Type> tree) {
    if (name == null || name.isEmpty() || "_".equals(name)) {
      return;
    }
    TacOperand.TacVar target = new TacOperand.TacVar(name, type, false);
    locals.put(name, target);
    builder.addLocal(target);
    emitMove(target, value, tree);
  }

  /**
   * Binds a product pattern's parts by reading the product's positional fields.
   *
   * <p>The fields are numbered, the way a product's accessors are: the first part is "_1", the
   * second "_2".
   *
   * <p><b>Why the positional accessor and not the declared field.</b> A tuple's parts ARE its
   * positional accessors, and a case class -- which is a product too -- has the same ones, so "_1"
   * and "_2" name methods that really exist on the matched value. A name that does not exist on it
   * would be a field the source never wrote.
   *
   * @param parts the pattern's sub-patterns
   * @param product the matched product
   * @param tree the case, for the source position
   */
  private void bindProductParts(
      List<Trees.Tree<Type>> parts, TacOperand product, Trees.Tree<Type> tree) {
    for (int i = 0; i < parts.size(); i++) {
      String name = boundName(parts.get(i));
      if (name == null) {
        continue;
      }
      TacType type = partType(parts.get(i));
      TacOperand.TacVar value = builder.newTemp(type);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
              .dest(value)
              .lhs1(product)
              .fieldName("_" + (i + 1))
              .line(lineOf(tree))
              .build());
      bindPatternVariable(name, type, value, tree);
    }
  }

  /**
   * Binds a sequence pattern's parts by reading the sequence's elements.
   *
   * @param parts the pattern's sub-patterns
   * @param sequence the matched sequence
   * @param tree the case, for the source position
   */
  private void bindSequenceParts(
      List<Trees.Tree<Type>> parts, TacOperand sequence, Trees.Tree<Type> tree) {
    for (int i = 0; i < parts.size(); i++) {
      Trees.Tree<Type> part = parts.get(i);
      String name = boundName(part);
      if (name == null) {
        continue;
      }
      if (isVarargsPart(part)) {
        // The REST of the sequence. The TAC has no slice, so the value is unknown
        // rather than wrong.
        TacOperand.TacVar unknown = builder.newTemp(TacType.UNKNOWN);
        builder.emit(
            TacInstruction.builder(TacInstruction.Kind.HAVOC)
                .dest(unknown)
                .line(lineOf(tree))
                .build());
        bindPatternVariable(name, TacType.UNKNOWN, unknown, tree);
        continue;
      }
      TacType type = partType(part);
      TacOperand.TacVar value = builder.newTemp(type);
      builder.emit(
          TacInstruction.builder(TacInstruction.Kind.ARRAYLOAD)
              .dest(value)
              .lhs1(sequence)
              .lhs2(TacOperand.Const.ofInt(i))
              .line(lineOf(tree))
              .build());
      bindPatternVariable(name, type, value, tree);
    }
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
        TacInstruction.builder(TacInstruction.Kind.RETURN).lhs1(value).line(lineOf(tree)).build());
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
   * <p><b>Why one collector and not two lists.</b> The string form says "something went wrong". The
   * structured form says WHICH construct went wrong, in WHICH function, and whether the cause was
   * an unsupported construct or a broken source. A source-less consumer needs the second: it cannot
   * answer "is this file incomplete because the frontend lacks a feature, or because the source
   * does not compile?" -- and those need different responses.
   *
   * <p>Keeping them in one collector is what stops them drifting apart. A gap recorded only in the
   * strings is a gap a source-less consumer cannot see; a gap recorded only in the structured list
   * is a gap the log does not show. There is exactly one place a gap is recorded, so neither can
   * happen.
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
   * <p><b>What the Scala typer leaves behind.</b> The typer desugars a lambda into a {@code DefDef}
   * -- {@code _$_$$anonfun} -- that holds the parameters, the captures and the body. The
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
      reportLoss(
          org.plumb.tac.ir.TacDrop.Impact.SEMANTICS,
          "scala.lambda.unnamed",
          "a lambda in "
              + currentFunction
              + " names no callable; the function's precision was"
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
   * its symbol's full name ({@code demo.Demo._$_$$anonfun}). The two spellings differ, so the name
   * cannot be used to join them -- but the SYMBOL is the same object on both sides, and the
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
