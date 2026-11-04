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
package org.plumb.tac.translate;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.ArrayList;
import java.util.List;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;
import org.plumb.tac.ir.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recursive JavaParser-expression → TAC translator. Returns an {@link ExpressionResult}; side
 * effects (emitted instructions) are accumulated on the supplied {@link TacBuilder}.
 */
public final class ExpressionTranslator {

  private static final Logger LOG = LoggerFactory.getLogger(ExpressionTranslator.class);

  private final TacTranslator owner;

  /**
   * The variable a {@code yield} statement assigns to, when one is being translated.
   *
   * <p>Java's switch <i>expression</i> produces a value, and its arms hand that value over with
   * {@code yield v;}. The lowering gives the switch expression a temp, and a {@code yield} inside
   * it becomes a MOVE of the yielded value into that temp.
   *
   * <p>This is a ThreadLocal for the same reason {@link StatementTranslator}'s loop and switch
   * label stacks are: the translator is re-entrant (an expression's translation can reach into
   * another expression), and a plain field would be clobbered by the inner call.
   *
   * <p>It is {@code null} outside a switch expression, which is the case for a {@code yield}
   * statement in a switch <i>statement</i> -- that is a compile error in Java, so the lowering
   * simply has nothing to assign to.
   */
  private final ThreadLocal<TacOperand.TacVar> yieldTarget = new ThreadLocal<>();

  ExpressionTranslator(TacTranslator owner) {
    this.owner = owner;
  }

  /**
   * Sets the current {@code yield} target and returns the previous one, so the caller can restore
   * it.
   *
   * @param target the variable a {@code yield} assigns to, or {@code null} for "no switch
   *     expression in scope"
   * @return the previous target
   */
  TacOperand.TacVar setYieldTarget(TacOperand.TacVar target) {
    TacOperand.TacVar prev = yieldTarget.get();
    if (target == null) {
      yieldTarget.remove();
    } else {
      yieldTarget.set(target);
    }
    return prev;
  }

  /** The current {@code yield} target, or {@code null}. */
  TacOperand.TacVar yieldTarget() {
    return yieldTarget.get();
  }

  public ExpressionResult translate(Expression expr, TacBuilder b) {
    if (expr == null) return new ExpressionResult(TacOperand.Const.ofNull(), TacType.NULL);
    if (expr instanceof BinaryExpr be) return translateBinary(be, b);
    if (expr instanceof UnaryExpr ue) return translateUnary(ue, b);
    if (expr instanceof AssignExpr ae) return translateAssign(ae, b);
    if (expr instanceof NameExpr ne) return translateName(ne, b);
    if (expr instanceof ThisExpr) return translateThis(b);
    if (expr instanceof SuperExpr se) {
      // `super` is the enclosing instance, which in the TAC is the SAME object as
      // `this` -- only the dispatch target differs, and the TAC's callee signature
      // already carries the method name for the call-graph resolver to work on.
      //
      // WHY THIS MATTERS. The previous lowering returned a NULL constant. In
      // `super.foo(x)`, the scope is the SuperExpr, so the CALL's receiver
      // (args[0], per the TAC convention) was the null constant -- and the
      // null-dereference checker saw a call on a null receiver and reported a
      // FALSE POSITIVE on every `super.method()` call. It also destroyed the
      // taint: the receiver carried no facts.
      //
      // Falling back to the `this` local keeps the receiver non-null and keeps
      // the receiver's identity, which is what the points-to solver needs.
      ExpressionResult thisResult = translateThis(b);
      if (thisResult.operand() instanceof TacOperand.Const) {
        // No `this` in scope (a static method cannot use `super`, so this is only
        // reachable from a recovered tree). Report it rather than silently
        // substituting a null receiver.
        b.reportUnhandled("SuperExpr", TacTranslator.nodeLine(se), TacTranslator.nodeColumn(se));
      }
      return thisResult;
    }
    if (expr instanceof BooleanLiteralExpr ble)
      return new ExpressionResult(TacOperand.Const.ofBool(ble.getValue()), TacType.BOOLEAN);
    if (expr instanceof IntegerLiteralExpr ile) {
      String raw = ile.getValue();
      String stripped =
          raw.endsWith("L") || raw.endsWith("l") ? raw.substring(0, raw.length() - 1) : raw;
      try {
        int v = parseJavaInt(stripped);
        return new ExpressionResult(TacOperand.Const.ofInt(v), TacType.INT);
      } catch (NumberFormatException nfe) {
        // value didn't fit in int, fall through to long
        long lv = parseJavaLong(stripped);
        return new ExpressionResult(TacOperand.Const.ofLong(lv), TacType.LONG);
      }
    }
    if (expr instanceof LongLiteralExpr lle) {
      String raw = lle.getValue();
      if (raw.endsWith("L") || raw.endsWith("l")) raw = raw.substring(0, raw.length() - 1);
      return new ExpressionResult(TacOperand.Const.ofLong(parseJavaLong(raw)), TacType.LONG);
    }
    if (expr instanceof CharLiteralExpr cle) {
      return new ExpressionResult(TacOperand.Const.ofChar(cle.getValue().charAt(0)), TacType.CHAR);
    }
    if (expr instanceof DoubleLiteralExpr dle) {
      String raw = dle.getValue();
      if (raw.endsWith("f") || raw.endsWith("F")) {
        return new ExpressionResult(
            TacOperand.Const.ofFloat(Float.parseFloat(raw.substring(0, raw.length() - 1))),
            TacType.FLOAT);
      }
      return new ExpressionResult(
          TacOperand.Const.ofDouble(Double.parseDouble(raw)), TacType.DOUBLE);
    }
    if (expr instanceof StringLiteralExpr sle) {
      return new ExpressionResult(TacOperand.Const.ofString(sle.getValue()), TacType.STRING);
    }
    if (expr instanceof com.github.javaparser.ast.expr.TextBlockLiteralExpr tble) {
      // A text block is a string literal spelled with triple quotes (Java 15+).
      // It is a distinct AST node, not a StringLiteralExpr, so it needs its own arm --
      // without one it falls through to the unhandled path and the string's VALUE is
      // lost, which silently drops any taint that flowed into it.
      //
      // The value is the text with the incidental whitespace removed, exactly as
      // javac computes it; JavaParser already gives us that via getValue().
      return new ExpressionResult(TacOperand.Const.ofString(tble.getValue()), TacType.STRING);
    }
    if (expr instanceof com.github.javaparser.ast.expr.ClassExpr ce) {
      // A class literal: `Foo.class`, `int.class`, `String[].class`.
      //
      // It denotes a Class object, not an instance. The TAC has no "class object"
      // value, so it is modelled as a reference to the java.lang.Class type with the
      // literal's type name as the payload. What matters for the dataflow is that the
      // value is NOT null: a null here would look like a null dereference candidate.
      String typeName = ce.getTypeAsString();
      TacType t =
          typeName == null || typeName.isEmpty()
              ? TacType.ofClass("java.lang.Class")
              : TacType.ofClass("java.lang.Class");
      return new ExpressionResult(TacOperand.Const.ofString(typeName), t);
    }
    if (expr instanceof com.github.javaparser.ast.expr.MethodReferenceExpr mre) {
      return translateMethodReference(mre, b);
    }
    if (expr instanceof NullLiteralExpr) {
      return new ExpressionResult(TacOperand.Const.ofNull(), TacType.NULL);
    }
    if (expr instanceof CastExpr ce) return translateCast(ce, b);
    if (expr instanceof InstanceOfExpr ioe) {
      // Java 16 added pattern matching to `instanceof`: `x instanceof String s`. The
      // pattern form binds a variable, so it is lowered by the modern-features
      // translator; the plain form is the original type test.
      if (ioe.getPattern().isPresent()) {
        return owner.modern().translateInstanceOfWithPattern(ioe, b);
      }
      return translateInstanceOf(ioe, b);
    }
    if (expr instanceof SwitchExpr se) {
      // Java 14 added switch expressions. The lowering needs the expected type to
      // type the result temp; without a type context it is UNKNOWN, which is
      // enough for the dataflow (the value's taint, not its type, is what matters).
      return owner.modern().translateSwitchExpr(se, b, TacType.UNKNOWN);
    }
    if (expr instanceof MethodCallExpr mce) return translateCall(mce, b);
    if (expr instanceof ObjectCreationExpr oce) return translateNew(oce, b);
    if (expr instanceof ArrayCreationExpr ace) return translateNewArray(ace, b);
    if (expr instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr aie) {
      // Bare array initializer like `{1, 2, 3}` on the
      // RHS of a variable declarator. The component type is
      // not directly available on the AST node; for variable
      // declarators, the StatementTranslator wraps the
      // initializer in a typed context. We default to UNKNOWN
      // here and let the assignment propagate the actual
      // declared type via the dest var.
      return translateArrayInitializer(aie, b, TacType.UNKNOWN);
    }
    if (expr instanceof ArrayAccessExpr aae) return translateArrayAccess(aae, b);
    if (expr instanceof FieldAccessExpr fae) return translateFieldAccess(fae, b);
    if (expr instanceof EnclosedExpr ee) return translate(ee.getInner(), b);
    if (expr instanceof ConditionalExpr ce) return translateConditional(ce, b);
    if (expr instanceof VariableDeclarationExpr vde) {
      // A declaration in expression position. Java allows this in a few places
      // (a `for` init, a try-with-resources resource). The declaration's real
      // effect -- declaring locals and initialising them -- is the same wherever
      // it appears, so it is routed to the STATEMENT translator's declaration
      // lowering. That keeps one implementation of "declare and initialise"
      // instead of two that can drift apart.
      //
      // WHY THIS MATTERS. The previous lowering returned a null constant and
      // emitted NOTHING. So a declaration reached through an expression path
      // declared no local at all: every read of that variable resolved to a
      // synthetic UNKNOWN var, and the initialiser's taint was dropped on the
      // floor. The gap was SILENT -- no problem was recorded, so the IR claimed
      // to be a complete translation of a function whose local was missing.
      owner.stmts().translateVarDecl(vde, b);
      // The declaration itself has no value. VOID says "no value", which is
      // distinct from NULL ("the value null") -- a distinction that matters
      // because a null constant is a dereference candidate.
      return new ExpressionResult(TacOperand.Const.ofNull(), TacType.VOID);
    }
    if (expr instanceof TypeExpr te) {
      // A type used as a VALUE. In Java this is only legal in a class literal
      // (`Foo.class`), and JavaParser represents `Foo.class` as a ClassExpr, not
      // a TypeExpr -- so a bare TypeExpr here comes from a method-reference scope
      // (`String::length`) or from a recovered tree.
      //
      // The value is a TYPE, not an instance. Modelling it as a null constant
      // would make a null-dereference checker report a dereference of a type
      // reference, which is not a thing. Modelling it as a non-null reference
      // to the type name keeps it visible and harmless.
      //
      // A method reference's scope is handled separately in
      // translateMethodReference, which does NOT add the scope to the argument
      // list when it is a type -- so this path only fires for a genuine type
      // used as a value.
      String typeName = te.getTypeAsString();
      return new ExpressionResult(
          TacOperand.Const.ofString(typeName == null ? "" : typeName),
          TacType.ofClass("java.lang.Class"));
    }
    if (expr instanceof LambdaExpr le) {
      // Lower the lambda body to a synthetic function. We then
      // emit a CALL to the synthetic function so call-site
      // taint analysis can still see the body.
      return translateLambda(le, b);
    }
    // Unhandled: report the gap AND emit an explicit unknown.
    //
    // The gap must NOT be silent. A null here silently drops every taint fact about the value, so
    // the construct is recorded on the IR as an UNHANDLED_NODE problem. A caller can then see
    // that the analysis of this function is incomplete, rather than mistaking a missing
    // finding for a safe program.
    //
    // And the value itself must be marked unknown in the IR, not handed back as a null constant.
    // A null constant is a *claim* -- "this value is null" -- and the engine would act on it: the
    // null-dereference checker would have to reason about it, and a downstream use of it would
    // look like a known value. A HAVOC says "the analysis does not know", which is what is true.
    // The difference matters: with a null constant the taint fact about the expression is lost
    // silently, and the IR looks complete.
    String nodeClass = expr.getClass().getSimpleName();
    b.reportUnhandled(nodeClass, TacTranslator.nodeLine(expr), TacTranslator.nodeColumn(expr));
    LOG.debug("Unhandled expression: {} at line {}", nodeClass, TacTranslator.nodeLine(expr));
    TacOperand.TacVar unknownDest = b.newTemp(TacType.UNKNOWN);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.HAVOC)
            .dest(unknownDest)
            .line(TacTranslator.nodeLine(expr))
            .column(TacTranslator.nodeColumn(expr))
            .build());
    return new ExpressionResult(unknownDest, TacType.UNKNOWN);
  }

  // ------------------------------------------------------------------

  private ExpressionResult translateBinary(BinaryExpr be, TacBuilder b) {
    ExpressionResult l = translate(be.getLeft(), b);
    ExpressionResult r = translate(be.getRight(), b);
    TacOperand.TacVar dest = b.newTemp(TacType.UNKNOWN);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.BINOP)
            .dest(dest)
            .binop(TacTranslator.mapBinOp(be.getOperator()))
            .lhs1(l.operand())
            .lhs2(r.operand())
            .line(TacTranslator.nodeLine(be))
            .column(TacTranslator.nodeColumn(be))
            .build());
    return new ExpressionResult(dest, TacType.UNKNOWN);
  }

  private ExpressionResult translateUnary(UnaryExpr ue, TacBuilder b) {
    UnaryOperator pre = TacTranslator.mapPreIncDec(ue.getOperator());
    UnaryOperator post = TacTranslator.mapPostIncDec(ue.getOperator());
    UnaryOperator simple = TacTranslator.mapUnary(ue.getOperator());

    if (pre != null || post != null || simple != null) {
      ExpressionResult inner = translate(ue.getExpression(), b);
      TacOperand.TacVar dest =
          (pre != null || simple != null) ? b.newTemp(TacType.UNKNOWN) : b.newTemp(TacType.UNKNOWN);
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.UNARYOP)
              .dest(dest)
              .unop(pre != null ? pre : (post != null ? post : simple))
              .lhs1(inner.operand())
              .line(TacTranslator.nodeLine(ue))
              .column(TacTranslator.nodeColumn(ue))
              .build());
      return new ExpressionResult(dest, TacType.UNKNOWN);
    }
    // Unknown unary: log and return null.
    LOG.debug("Unknown unary operator: {}", ue.getOperator());
    return new ExpressionResult(TacOperand.Const.ofNull(), TacType.UNKNOWN);
  }

  private ExpressionResult translateAssign(AssignExpr ae, TacBuilder b) {
    ExpressionResult rhs = translate(ae.getValue(), b);
    Expression target = ae.getTarget();

    // Compound assignment: target = target OP rhs  => dest = FIELDLOAD / VAR ; BINOP ; FIELDSTORE /
    // MOVE
    if (ae.getOperator() != AssignExpr.Operator.ASSIGN) {
      // Read current value of target
      ExpressionResult cur = translateLhsRead(target, b);
      TacOperand.TacVar tmp = b.newTemp(TacType.UNKNOWN);
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.BINOP)
              .dest(tmp)
              .binop(TacTranslator.mapAssignOp(ae.getOperator()))
              .lhs1(cur.operand())
              .lhs2(rhs.operand())
              .line(TacTranslator.nodeLine(ae))
              .build());
      writeLhs(target, tmp, b, ae);
      return new ExpressionResult(tmp, TacType.UNKNOWN);
    }

    // Simple a = b
    writeLhs(target, rhs.operand(), b, ae);
    return rhs;
  }

  private ExpressionResult translateName(NameExpr ne, TacBuilder b) {
    String name = ne.getNameAsString();
    // Look for a local / param by name (linear; methods are short).
    for (TacOperand.TacVar v : b.params()) {
      if (v.name().equals(name)) return new ExpressionResult(v, v.type());
    }
    for (TacOperand.TacVar v : b.locals()) {
      if (v.name().equals(name)) return new ExpressionResult(v, v.type());
    }
    // Implicit this.<name> for unqualified field access.
    // The previous behaviour created a synthetic UNKNOWN var and
    // emitted a MOVE off it, silently losing the field taint for
    // cross-method private field dispatch (Juliet _45 / _68a /
    // _72a-75a). Now: if `this` is in scope (we're in a non-static
    // method), emit a FIELDLOAD from `this.<name>` and return a
    // fresh temp. This pairs with the FIELDSTORE for the
    // unqualified write handled in writeLhs.
    for (TacOperand.TacVar v : b.locals()) {
      if ("this".equals(v.name())) {
        // A STATIC field belongs to the class, not to the instance. Bind the
        // class identity as the receiver so the dataflow engine keys the
        // field's state on the class: a static field written through one
        // instance has to be visible when read through another.
        TacOperand staticRecv = fieldReceiver(null, name, b);
        TacOperand recv = staticRecv != null ? staticRecv : v;
        TacOperand.TacVar tmp = b.newTemp(TacType.UNKNOWN);
        b.emit(
            TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
                .dest(tmp)
                .lhs1(recv)
                .fieldName(name)
                .line(TacTranslator.nodeLine(ne))
                .build());
        return new ExpressionResult(tmp, TacType.UNKNOWN);
      }
    }
    // No `this` in scope (static context or top-level): keep the
    // fallback (synthetic UNKNOWN var).
    TacOperand.TacVar v = new TacOperand.TacVar(name, TacType.UNKNOWN, false);
    return new ExpressionResult(v, TacType.UNKNOWN);
  }

  /**
   * The declaring class's simple name when {@code ne} names a STATIC field, or {@code null}
   * otherwise.
   *
   * <p>A static field belongs to the CLASS, not to an instance. The TAC has to represent that
   * difference, because the dataflow engine keys its field state on the receiver: a static field
   * written through one instance has to be visible when read through another, and an instance field
   * must not be.
   *
   * <p>Java's own convention -- a static field's name starts with an upper-case letter -- is not a
   * reliable signal: instance fields are named that way too, and plenty of static fields are not.
   * The symbol resolution is the reliable one, so it is what this uses.
   *
   * @param ne the name expression
   * @return the declaring class's simple name, or {@code null} when the name is not a static field
   */
  private String staticFieldDeclaringClass(com.github.javaparser.ast.expr.NameExpr ne) {
    String name = ne.getNameAsString();
    // The translator's own table first. A class declared in the scanned
    // source is not on the reflection type solver's classpath, so
    // `ne.resolve()` throws for a field of a user-defined class -- and the
    // declaration is right there in the AST.
    String current = owner.currentClassName();
    if (!current.isEmpty() && owner.isStaticFieldOf(current, name)) {
      return current;
    }
    try {
      var resolved = ne.resolve();
      if (!resolved.isField()) return null;
      var field = resolved.asField();
      if (!field.isStatic()) return null;
      String typeName = field.declaringType().getQualifiedName();
      if (typeName == null || typeName.isEmpty()) return null;
      int dot = typeName.lastIndexOf('.');
      return dot < 0 ? typeName : typeName.substring(dot + 1);
    } catch (Throwable ignore) {
      // Unresolvable (no symbol solver, recovered tree, ...): treat it as an
      // ordinary field. The engine's field tracker then keeps the per-instance
      // semantics, which is the conservative choice.
      return null;
    }
  }

  /**
   * The receiver operand for a field access, emitting the class-identity load when the field is
   * static.
   *
   * <p>For a STATIC field the receiver is the CLASS. The class identity is bound with a {@code
   * FIELDLOAD} of the class's own name off {@code this} -- the shape the dataflow engine already
   * recognises as a class reference -- so the field state is keyed on the class rather than on an
   * instance.
   *
   * @param scopeExpr the scope expression, or {@code null} for an unqualified access
   * @param name the field name
   * @param b the builder to emit into
   * @return the receiver operand for the field access
   */
  private TacOperand fieldReceiver(
      com.github.javaparser.ast.expr.Expression scopeExpr, String name, TacBuilder b) {
    if (scopeExpr instanceof com.github.javaparser.ast.expr.NameExpr ne) {
      String staticOwner = staticFieldDeclaringClass(ne);
      if (staticOwner != null) {
        return classIdentityReceiver(staticOwner, b);
      }
    } else if (scopeExpr == null) {
      // An unqualified access (`FIELD`). It is static when the enclosing
      // class declares it static.
      String current = owner.currentClassName();
      if (!current.isEmpty() && owner.isStaticFieldOf(current, name)) {
        return classIdentityReceiver(current, b);
      }
    }
    // Qualified access: `Other.FIELD` where Other is a class.
    if (scopeExpr instanceof com.github.javaparser.ast.expr.TypeExpr) {
      String owner2 = scopeExpr.toString();
      int dot = owner2.lastIndexOf('.');
      String simple = dot < 0 ? owner2 : owner2.substring(dot + 1);
      return classIdentityReceiver(simple, b);
    }
    return null;
  }

  /**
   * Bind a class's identity into a fresh temp: {@code t = this.<ClassName>}.
   *
   * <p>This is the shape the dataflow engine reads as "the receiver is a class, not an object". The
   * class name is upper-case by construction (a Java class name starts with an upper-case letter),
   * which is what the engine's class-reference recognition looks for.
   */
  private TacOperand classIdentityReceiver(String className, TacBuilder b) {
    TacOperand.TacVar thisVar = null;
    for (TacOperand.TacVar v : b.locals()) {
      if ("this".equals(v.name())) {
        thisVar = v;
        break;
      }
    }
    if (thisVar == null) {
      return null;
    }
    TacOperand.TacVar classRef = b.newTemp(TacType.ofClass(className));
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
            .dest(classRef)
            .lhs1(thisVar)
            .fieldName(className)
            .line(-1)
            .build());
    return classRef;
  }

  private ExpressionResult translateThis(TacBuilder b) {
    for (TacOperand.TacVar v : b.locals()) {
      if ("this".equals(v.name())) return new ExpressionResult(v, v.type());
    }
    return new ExpressionResult(TacOperand.Const.ofNull(), TacType.NULL);
  }

  private ExpressionResult translateCast(CastExpr ce, TacBuilder b) {
    ExpressionResult inner = translate(ce.getExpression(), b);
    TacType target = TypeMapper.map(ce.getType());
    TacOperand.TacVar dest = b.newTemp(target);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.UNARYOP)
            .dest(dest)
            .unop(UnaryOperator.CAST)
            .castType(target)
            .lhs1(inner.operand())
            .line(TacTranslator.nodeLine(ce))
            .build());
    return new ExpressionResult(dest, target);
  }

  private ExpressionResult translateInstanceOf(InstanceOfExpr ioe, TacBuilder b) {
    ExpressionResult inner = translate(ioe.getExpression(), b);
    TacType target = TypeMapper.map(ioe.getType());
    TacOperand.TacVar dest = b.newTemp(TacType.BOOLEAN);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.INSTANCEOF)
            .dest(dest)
            .lhs1(inner.operand())
            .ctorType(target)
            .line(TacTranslator.nodeLine(ioe))
            .build());
    return new ExpressionResult(dest, TacType.BOOLEAN);
  }

  private ExpressionResult translateCall(MethodCallExpr mce, TacBuilder b) {
    List<ExpressionResult> argResults = new ArrayList<>();
    List<TacOperand> argOps = new ArrayList<>();
    for (Expression arg : mce.getArguments()) {
      ExpressionResult r = translate(arg, b);
      argResults.add(r);
      argOps.add(r.operand());
    }
    String receiverTypeName = null;
    if (mce.getScope().isPresent()) {
      Expression scopeExpr = mce.getScope().get();
      // A scope that names a CLASS is a static call: `String.valueOf(x)`,
      // `Integer.parseInt(s)`. JavaParser parses the scope as a NameExpr
      // (`String`), not a TypeExpr, so translating it as a VALUE read two
      // wrong things:
      //
      //   1. it emitted a bogus `FIELDLOAD this.C.String` -- the class name
      //      read as an instance field, which corresponds to nothing in the
      //      source; and
      //   2. the callee signature came out as `String.valueOf/1` while the
      //      taint-passthrough catalogue is written in fully-qualified form
      //      (`java.lang.String.valueOf/`), so the passthrough never fired
      //      and the taint was dropped at the call.
      //
      // A class scope is a TYPE reference, not a value, so it is modelled as
      // one: a constant naming the type, and an FQN callee signature.
      String classScopeFqn = classScopeFqn(scopeExpr, b);
      if (classScopeFqn != null) {
        argOps.add(0, TacOperand.Const.ofString(classScopeFqn));
        receiverTypeName = classScopeFqn;
      } else {
        ExpressionResult scope = translate(scopeExpr, b);
        argOps.add(0, scope.operand());
        // Derive the receiver's textual name for the callee signature.
        // Prefer the FQN type of the receiver
        // operand (e.g. "java.sql.Statement") so the
        // sink-families.yaml FQN patterns match. Fall
        // back to the textual scope when the type is
        // unknown (field access, call result, etc.).
        receiverTypeName = receiverTypeName(scopeExpr, scope);
      }
    }
    // A functional-interface application whose receiver holds a method reference is
    // really a call to the referenced method:
    //
    //     Function<String, String> f = String::trim;
    //     f.apply(userInput);        // ==  String.trim(userInput)
    //
    // Rewriting the application into the call it is keeps the argument's taint
    // flowing to the result. Without the rewrite the call is opaque -- the
    // argument's taint stops at the application, and a program that hands
    // attacker-controlled input to a sink through a method reference is
    // reported as clean.
    String deferredTarget = deferredTargetFor(mce, argOps);
    if (deferredTarget != null) {
      // The referenced method's own receiver (for a bound reference such as
      // `obj::method`) is the functional interface's first argument, so the
      // deferred call takes the receiver first and then the application's own
      // arguments.
      List<TacOperand> deferredArgs = new ArrayList<>();
      if (!argOps.isEmpty()) {
        deferredArgs.add(argOps.get(0));
      }
      deferredArgs.addAll(argOps.subList(Math.min(1, argOps.size()), argOps.size()));
      argOps = deferredArgs;
      TacType deferredReturn = TacType.UNKNOWN;
      TacOperand.TacVar dest = b.newTemp(deferredReturn);
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.CALL)
              .dest(dest)
              .calleeSig(deferredTarget)
              .args(argOps)
              .line(TacTranslator.nodeLine(mce))
              .column(TacTranslator.nodeColumn(mce))
              .build());
      return new ExpressionResult(dest, deferredReturn);
    }
    // Propagate the method's declared return
    // type to the dest TacVar. JavaParser exposes
    // this via calculateResolvedType() (returns
    // ResolvedType) on MethodCallExpr. The
    // caller-side code (e.g. `.exec()` on the
    // result of `Runtime.getRuntime()`) then sees a
    // TacVar with type java.lang.Runtime and the
    // sink pattern matches. Without this, the dest
    // is TacType.UNKNOWN and the chain defaults to
    // "scope" + simple-name fallbacks that miss
    // FQN-anchored sink patterns.
    TacType returnType = TacType.UNKNOWN;
    try {
      returnType = TypeMapper.mapResolved(mce.calculateResolvedType());
    } catch (Exception ignore) {
      // unresolved / unsupported type; fall back to UNKNOWN
    }
    // Fall back to the hardcoded GETTER_RETURNS
    // map when symbol resolution didn't yield a useful
    // type. Juliet chain calls (response.getWriter().
    // println(...), request.getParameter(...)) need
    // this; the JDK type solver isn't wired into
    // plumb-tac so the symbol resolver returns
    // java.lang.Object for these and the chain
    // downstream loses its FQN.
    if (returnType == TacType.UNKNOWN
        || (returnType.kind() == TacType.Kind.CLASS
            && "java.lang.Object".equals(returnType.className()))) {
      String mname = mce.getNameAsString();
      String fqnReturn = GETTER_RETURNS.get(mname);
      if (fqnReturn != null) {
        returnType = TacType.ofClass(fqnReturn);
      }
    }
    TacOperand.TacVar dest = b.newTemp(returnType);
    String sig =
        TacTranslator.methodSig(receiverTypeName, mce.getNameAsString(), mce.getArguments().size());
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(dest)
            .calleeSig(sig)
            .args(argOps)
            .line(TacTranslator.nodeLine(mce))
            .build());
    return new ExpressionResult(dest, returnType);
  }

  /**
   * Translate a method reference: {@code String::length}, {@code obj::method}, {@code Type::new}.
   *
   * <p><b>What a method reference is.</b> It is a <i>callable value</i> -- a reference to a method,
   * not an invocation of it. {@code f = String::length} captures the method; the call happens
   * later, when {@code f} is invoked.
   *
   * <p><b>How the TAC models it.</b> The TAC has no "callable value" operand. The nearest sound
   * representation is a {@code CALL} whose callee signature is the referenced method: the dataflow
   * engine's sink matching keys on the callee signature, so a reference to a sink method is visible
   * as reaching that sink. The argument list is the reference's own receiver, if any.
   *
   * <p><b>Why not just a constant.</b> A constant would make the reference invisible to the sink
   * matching -- a {@code java.sql.Statement::execute} reference would never be seen to reach the
   * sink, and the finding would be missed. A CALL keeps it visible.
   *
   * <p><b>Directional rule.</b> This is a LOWERING, not an invocation: the referenced method's body
   * is not executed at this point. It is modelled as a reference so that the sink family matching
   * sees the method being named. The precision of the enclosing function is not downgraded, because
   * nothing about the method's semantics was lost -- only its deferred invocation.
   *
   * <p><b>The deferred target is also recorded.</b> The result carries the referenced method's
   * callee signature so the translator can bind the user variable to it. The application site can
   * then be rewritten into the call it actually is -- {@code f.apply(x)} where {@code f} holds
   * {@code String::trim} becomes {@code String.trim(x)}. Without that rewrite the application is an
   * opaque call to the functional interface, the argument's taint stops there, and the analysis
   * reports a clean program for code that hands attacker-controlled input to a sink.
   */
  private ExpressionResult translateMethodReference(
      com.github.javaparser.ast.expr.MethodReferenceExpr mre, TacBuilder b) {
    String scopeText = mre.getScope().toString();
    String referenceName = mre.getIdentifier();
    List<TacOperand> argOps = new ArrayList<>();

    // The scope of a method reference is the receiver, when it is an expression
    // (`obj::method`). For a type name (`String::length`) it is a type, not a value.
    boolean scopeIsType = mre.getScope() instanceof com.github.javaparser.ast.expr.TypeExpr;
    ExpressionResult scopeValue = null;
    if (!scopeIsType) {
      scopeValue = translate((com.github.javaparser.ast.expr.Expression) mre.getScope(), b);
      argOps.add(scopeValue.operand());
    }

    // `Type::new` is a constructor reference, not a method reference.
    String methodName = "new".equals(referenceName) ? "<init>" : referenceName;

    // The receiver's type name, when the scope is a type: that is the class the method
    // belongs to, and it is what the sink patterns match on.
    String receiverTypeName = null;
    if (scopeIsType) {
      receiverTypeName = scopeText;
      // A type scope is a TYPE reference, not a value. Resolve it to its
      // fully-qualified name so the callee signature matches the same
      // catalogue the sink patterns and the passthrough rules are written
      // against (`java.lang.String.trim/1`, not `String.trim/0`).
      String fqn = TypeMapper.resolveWellKnownFqnStatic(scopeText);
      if (fqn != null && !fqn.isEmpty()) {
        receiverTypeName = fqn;
      }
    } else {
      receiverTypeName = receiverTypeName(mre.getScope(), scopeValue);
    }

    // The arity of the DEFERRED call. For a static reference the functional interface
    // supplies every argument, so the arity is the number of parameters the referenced
    // method takes -- one for `String::trim`, zero for `String::length`.
    int deferredArity = referencedArity(mre, receiverTypeName, methodName);

    String sig = TacTranslator.methodSig(receiverTypeName, methodName, argOps.size());
    TacOperand.TacVar dest = b.newTemp(TacType.UNKNOWN);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(dest)
            .calleeSig(sig)
            .args(argOps)
            .line(TacTranslator.nodeLine(mre))
            .column(TacTranslator.nodeColumn(mre))
            .build());
    String deferredTarget = TacTranslator.methodSig(receiverTypeName, methodName, deferredArity);
    return ExpressionResult.ofDeferred(dest, deferredTarget);
  }

  /**
   * The number of arguments the deferred invocation of a referenced method takes.
   *
   * <p>For an unbound reference to an instance method ({@code String::trim}) the functional
   * interface supplies the receiver as its first argument, so the deferred call takes the
   * referenced method's own parameter count plus one.
   *
   * <p>JavaParser exposes the referenced method's parameter count through the resolved symbol when
   * the symbol solver can see it. When it cannot, the count is unknown, and {@code -1} is returned:
   * the caller then leaves the arity alone rather than inventing one.
   *
   * @param mre the method reference
   * @param receiverTypeName the referenced method's declaring type
   * @param methodName the referenced method's name
   * @return the deferred call's arity, or {@code -1} when it cannot be determined
   */
  private int referencedArity(
      com.github.javaparser.ast.expr.MethodReferenceExpr mre,
      String receiverTypeName,
      String methodName) {
    try {
      var resolved = mre.resolve();
      var method = resolved.asMethod();
      int params = method.getNumberOfParams();
      // An unbound instance-method reference receives the receiver as the
      // functional interface's first argument.
      boolean staticRef = method.isStatic();
      return staticRef ? params : params + 1;
    } catch (Throwable ignore) {
      // Unresolvable: leave the arity alone rather than guess.
      return -1;
    }
  }

  /**
   * The deferred target of a functional-interface application, when the receiver holds a method
   * reference.
   *
   * <p>Returns {@code null} when the call is not an application of a functional interface, or when
   * the receiver is not bound to a known method reference. In both cases the call is lowered
   * normally.
   *
   * @param mce the call expression
   * @param argOps the argument operands, receiver first
   * @return the referenced method's callee signature, or {@code null}
   */
  private String deferredTargetFor(
      com.github.javaparser.ast.expr.MethodCallExpr mce, List<TacOperand> argOps) {
    if (!mce.getScope().isPresent()) return null;
    if (argOps.isEmpty()) return null;
    if (!(argOps.get(0) instanceof TacOperand.TacVar recv)) return null;
    String methodName = mce.getNameAsString();
    if (!isFunctionalInterfaceApplication(methodName)) return null;
    return owner.program().deferredTargetFor(recv.name());
  }

  /**
   * Is this method name one of the functional-interface methods whose application forwards its
   * argument to the referenced target?
   *
   * <p>The names are the single-abstract-method names of the standard functional interfaces. The
   * list is deliberately restricted to those: a name like {@code get} or {@code test} would also
   * match ordinary methods ({@code Map.get}) that have nothing to do with a functional interface.
   */
  private static boolean isFunctionalInterfaceApplication(String methodName) {
    if (methodName == null) return false;
    return switch (methodName) {
      case "apply",
          "applyAsInt",
          "applyAsLong",
          "applyAsDouble",
          "accept",
          "call",
          "run",
          "test",
          "get",
          "compare",
          "compute",
          "compose",
          "andThen" ->
          true;
      default -> false;
    };
  }

  /**
   * Hardcoded getter → FQN map for the most common chain-call receivers. JavaParser's symbol solver
   * is not wired up with a JDK type solver, so {@code response.getWriter().println(...)} ends up
   * with the dest TacVar of {@code getWriter()} carrying type UNKNOWN and the chained {@code
   * println} call emitting a "scope" receiver (e.g. {@code scope.println/1}) that no sink-family
   * pattern matches. By hardcoding the most common getter returns, the chained call gets a proper
   * FQN receiver (e.g. {@code java.io.PrintWriter.println/1}) and the sink patterns fire.
   *
   * <p>Add new entries whenever a Juliet / real codebase shows a chain call whose receiver type the
   * symbol solver can't resolve.
   */
  private static final java.util.Map<String, String> GETTER_RETURNS =
      java.util.Map.ofEntries(
          java.util.Map.entry("getWriter", "java.io.PrintWriter"),
          java.util.Map.entry("getOutputStream", "javax.servlet.ServletOutputStream"),
          java.util.Map.entry("getInputStream", "javax.servlet.ServletInputStream"),
          java.util.Map.entry("getRequest", "javax.servlet.http.HttpServletRequest"),
          java.util.Map.entry("getResponse", "javax.servlet.http.HttpServletResponse"),
          java.util.Map.entry("getSession", "javax.servlet.http.HttpSession"),
          java.util.Map.entry("getServletContext", "javax.servlet.ServletContext"),
          java.util.Map.entry("getServletConfig", "javax.servlet.ServletConfig"),
          java.util.Map.entry("getAttribute", "java.lang.Object"),
          java.util.Map.entry("getParameter", "java.lang.String"),
          java.util.Map.entry("getHeader", "java.lang.String"),
          java.util.Map.entry("getCookies", "javax.servlet.http.Cookie[]"),
          java.util.Map.entry("getQueryString", "java.lang.String"),
          java.util.Map.entry("getMethod", "java.lang.String"),
          java.util.Map.entry("getPathInfo", "java.lang.String"),
          java.util.Map.entry("getRequestURI", "java.lang.String"),
          java.util.Map.entry("getRequestURL", "java.lang.StringBuffer"));

  /**
   * The FQN of a static call's scope, when the scope names a CLASS rather than a value.
   *
   * <p>{@code String.valueOf(x)} has the scope {@code String} -- a class name -- and {@code
   * sb.append(x)} has the scope {@code sb} -- a value. JavaParser gives both the same node type (a
   * NameExpr), so the two are told apart by asking what the name resolves to.
   *
   * <p>The test is deliberately conservative, and it has to be: the cost of a false positive here
   * is turning a real receiver into a type constant, which would break every instance call whose
   * receiver happens to share a name with a JDK type. So the scope must satisfy ALL of:
   *
   * <ul>
   *   <li>it is a bare name (a NameExpr);
   *   <li>that name is not a local, not a parameter, and not a field of the enclosing class -- so a
   *       receiver that happens to be called {@code String} is left alone;
   *   <li>the name resolves through the well-known FQN table, so this only ever applies to JDK
   *       types the table knows.
   * </ul>
   *
   * @return the FQN, or {@code null} when the scope is a value
   */
  private static String classScopeFqn(Expression scopeExpr, TacBuilder b) {
    if (!(scopeExpr instanceof NameExpr ne)) {
      // A dotted scope (`java.lang.String`) already produced an FQN through the
      // FieldAccessExpr path; only the bare form needs this.
      return null;
    }
    String text = ne.getNameAsString();
    if (text == null || text.isEmpty()) return null;
    // A local or a parameter with this name is a VALUE receiver.
    for (TacOperand.TacVar v : b.params()) {
      if (v.name().equals(text)) return null;
    }
    for (TacOperand.TacVar v : b.locals()) {
      if (v.name().equals(text)) return null;
    }
    // A field of the enclosing class with this name is a VALUE receiver too
    // (`LOG.info(msg)` where LOG is a static field).
    try {
      TypeDeclaration<?> enclosing = scopeExpr.findAncestor(TypeDeclaration.class).orElse(null);
      if (enclosing != null && fieldTypeIn(enclosing, text) != null) {
        return null;
      }
    } catch (RuntimeException ignored) {
      // best effort -- fall through to the FQN table
    }
    String fqn = TypeMapper.resolveWellKnownFqnStatic(text);
    if (fqn == null || fqn.equals(text)) {
      // Not a known type: it is a value receiver.
      return null;
    }
    return fqn;
  }

  private static String receiverTypeName(Expression scopeExpr, ExpressionResult scope) {
    // Prefer the FQN type of the receiver operand (e.g.
    // "java.sql.Statement") so the sink-families.yaml
    // FQN patterns can match. The TacVar type comes from
    // the variable's declared type (kept in scope.type())
    // for NameExpr references. For FieldAccessExpr the
    // FQN chain is already a usable hint.
    TacType t = null;
    if (scope.operand() instanceof TacOperand.TacVar tv) {
      t = tv.type();
    }
    if (t != null
        && t.kind() == TacType.Kind.CLASS
        && t.className() != null
        && !t.className().isEmpty()
        && !"java.lang.Object".equals(t.className())) {
      // Resolve simple-name class strings
      // through the well-known FQN map so e.g.
      // "Runtime" lands on "java.lang.Runtime"
      // before being used to build the callee sig.
      return TypeMapper.resolveWellKnownFqnStatic(t.className());
    }
    if (scopeExpr instanceof FieldAccessExpr fae) {
      // For well-known static field
      // accesses (System.out, System.err),
      // hardcode the FQN of the declared type
      // so the sink patterns anchor on the
      // FQN class.
      String f = fae.toString();
      if ("System.out".equals(f) || "System.err".equals(f)) {
        return "java.lang.System";
      }
      // A scope that names a CLASS is a static call on a type name:
      // `String.valueOf(x)`, `Integer.parseInt(s)`.
      //
      // JavaParser parses the scope of such a call as a FieldAccessExpr
      // (`String`), not a TypeExpr, so returning the raw text produced the
      // callee signature `String.valueOf/1`. The taint-passthrough catalogue
      // is written in fully-qualified form (`java.lang.String.valueOf/`), so
      // the passthrough never fired and the taint was dropped at the call --
      // the analysis reported a clean program for code that flows user input
      // into a command.
      //
      // Resolving the simple name through the well-known FQN table fixes that.
      // A field access that is NOT a class name (a real instance field,
      // `obj.field`) keeps the old behaviour: there the text is a field path,
      // not a type name, and the table would not match it anyway.
      String resolved = TypeMapper.resolveWellKnownFqnStatic(f);
      if (resolved != null && !resolved.equals(f)) {
        return resolved;
      }
      return f;
    }
    // resolve the DECLARED type of a field reference used as a call
    // receiver.
    //
    // `private static final Logger LOG = ...; LOG.info(msg)` produced the callee
    // signature `LOG.info/1` -- the bare variable name, no type -- so every
    // FQN-anchored sink pattern in sink-families.yaml missed it. The Var's own
    // type is UNKNOWN for a field reference (fields do not become typed locals),
    // which is why the block above falls through. The declared type is one
    // symbol-solver lookup away:
    //
    //   LOG.info/1                     -> java.util.logging.Logger.info/1   (matched)
    //   LOCAL_LOGGER.info/1            -> unmatchable
    //
    // This is not logging-specific: it affects every object reached through a
    // field, which in real code is most logging and most connection holders.
    String declared = resolveDeclaredTypeName(scopeExpr);
    if (declared != null) {
      return declared;
    }
    if (scopeExpr instanceof NameExpr ne) {
      // A bare name as the scope of a call: either a static call on a TYPE
      // (`String.valueOf(x)` -- the scope is the class name `String`) or a
      // call on a VARIABLE (`sb.append(x)`).
      //
      // The type-name case used to fall through to the bare simple name, so
      // the callee signature came out as `String.valueOf/1` while the
      // taint-passthrough catalogue is written in fully-qualified form
      // (`java.lang.String.valueOf/`). The passthrough therefore never fired
      // and the taint was dropped at the call.
      //
      // The well-known FQN table resolves the type-name case. A variable
      // scope is not in that table, so it keeps the bare name -- which is
      // what the legacy catalogue patterns (InputSourcePatterns) match on.
      String simple = ne.getNameAsString();
      String resolved = TypeMapper.resolveWellKnownFqnStatic(simple);
      if (resolved != null && !resolved.equals(simple)) {
        return resolved;
      }
      return simple;
    }
    return "scope";
  }

  /**
   * the declared type of a field used as a call receiver, as an FQN string.
   *
   * <p>Needed because {@code private static final Logger LOG = ...; LOG.info(msg)} lowers to the
   * callee signature {@code LOG.info/1} — the bare variable name, no type — so every FQN-anchored
   * pattern in {@code sink-families.yaml} misses it, including the whole {@code log.sinks} family.
   * The receiver's {@link TacOperand.TacVar} type is {@code UNKNOWN} for a field reference (fields
   * are not typed locals), which is why {@link #receiverTypeName} falls through to the variable
   * name.
   *
   * <p><b>Why this walks the AST instead of asking the symbol solver.</b> Both JavaParser entry
   * points fail on this shape, in ways that look like a parser/translator handoff bug but are not
   * one — the AST's parent chain is intact:
   *
   * <pre>
   *   LOG.resolve()                  -> IllegalStateException
   *                                       (its ancestor chain contains the
   *                                       VariableDeclarationExpr that declared LOG)
   *   LOG.calculateResolvedType()     -> IllegalStateException
   *                                       ("not inserted in a CompilationUnit")
   * </pre>
   *
   * <p>The declaration is right there in the same tree, so read it syntactically: find the field
   * with this name anywhere in the enclosing type and take its declared type. This needs no solver,
   * so it also works on a source tree that has none configured.
   */
  private static String resolveDeclaredTypeName(Expression expr) {
    final String fieldName;
    if (expr instanceof NameExpr ne) {
      fieldName = ne.getNameAsString();
    } else if (expr instanceof FieldAccessExpr fae) {
      fieldName = fae.getNameAsString();
    } else {
      return null;
    }

    // 1. Look in the enclosing type first, then more broadly. A field reference can only
    //    name a field of the enclosing class (or a supertype / enclosing class), and the
    //    nearest declaration is the one that would win in Java too.
    try {
      TypeDeclaration<?> enclosing = expr.findAncestor(TypeDeclaration.class).orElse(null);
      if (enclosing != null) {
        String t = fieldTypeIn(enclosing, fieldName);
        if (t != null) return TypeMapper.resolveWellKnownFqnStatic(t);
      }
      // 2. Fall back to any type in the file. Cheap, and it covers the odd case where the
      //    reference sits in a static initializer or an inner class with a shadowed name.
      try {
        com.github.javaparser.ast.CompilationUnit cu = expr.findCompilationUnit().orElse(null);
        if (cu != null) {
          for (TypeDeclaration<?> td : cu.getTypes()) {
            String t = fieldTypeIn(td, fieldName);
            if (t != null) return TypeMapper.resolveWellKnownFqnStatic(t);
          }
        }
      } catch (RuntimeException ignored) {
        // No compilation unit reachable; fall through.
      }
    } catch (RuntimeException ignored) {
      return null;
    }
    return null;
  }

  /** The declared type name of {@code fieldName} anywhere under {@code td}, or null. */
  private static String fieldTypeIn(TypeDeclaration<?> td, String fieldName) {
    for (FieldDeclaration fd : td.findAll(FieldDeclaration.class)) {
      for (VariableDeclarator vd : fd.getVariables()) {
        if (vd.getNameAsString().equals(fieldName)) {
          Type t = vd.getType();
          if (t instanceof ClassOrInterfaceType ci) {
            return ci.getNameAsString();
          }
          return t.asString();
        }
      }
    }
    return null;
  }

  private ExpressionResult translateNew(ObjectCreationExpr oce, TacBuilder b) {
    List<TacOperand> argOps = new ArrayList<>();
    for (Expression arg : oce.getArguments()) {
      argOps.add(translate(arg, b).operand());
    }
    // An ANONYMOUS class: `new Runnable() { public void run() {...} }`.
    //
    // The class body declares real methods, and those methods can hold a
    // vulnerability. Without lowering them, the body is invisible -- and it is
    // invisible SILENTLY, because the `new` itself is perfectly well modelled.
    // So the body is translated into its own functions.
    if (oce.getAnonymousClassBody().isPresent()) {
      owner.translateAnonymousType(oce.getAnonymousClassBody().get(), b);
    }
    // Preserve the textual type-parameters by
    // calling asString() on the AST type rather than
    // running it through TypeMapper. TypeMapper
    // simplifies the type to its raw simple name
    // (e.g. "ArrayList"), losing the "ArrayList<User>"
    // shape that the consumer needs. We still call TypeMapper
    // for the structured TacType (used elsewhere for
    // typeOf() lookups), but the ctorType's textual
    // form is the asString() of the AST node so the
    // type args survive.
    String textual = oce.getType().asString();
    // Resolve the textual type to an FQN via
    // TypeMapper.resolveWellKnownFqnStatic so that
    // `new File(...)` emits the callee
    // `java.io.File.<init>/1` (FQN form) rather than
    // the bare `File.<init>/1`. Sink families in
    // sink-families.yaml are anchored on FQN, so
    // bare short-form ctors silently never match.
    // We keep `textual` (the source-typed form) for
    // ctorType to preserve the type-argument shape.
    String ctorSigName = TypeMapper.resolveWellKnownFqnStatic(textual);
    TacType type = TypeMapper.map(oce.getType());
    TacOperand.TacVar dest = b.newTemp(type);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.NEW)
            .dest(dest)
            .ctorType(TacType.ofClass(textual))
            .calleeSig(TacTranslator.ctorSig(ctorSigName, oce.getArguments().size()))
            .args(argOps)
            .line(TacTranslator.nodeLine(oce))
            .build());
    return new ExpressionResult(dest, type);
  }

  private ExpressionResult translateNewArray(ArrayCreationExpr ace, TacBuilder b) {
    TacType component = TypeMapper.map(ace.getElementType());
    TacType arrayType = TacType.arrayOf(component);
    TacOperand.TacVar dest = b.newTemp(arrayType);
    // Support "new T[]{a, b, c}" (anonymous initializer)
    // and "new T[size]{a, b, c}" (size + initializer). When an
    // initializer is present, emit the size as the literal count
    // of values in the initializer — that gives the array a
    // concrete known size for downstream checks (e.g.
    // PREDICTABLE_KEY_PASSWORD detects a literal-initialized
    // char[]/byte[] as a likely key/password).
    if (ace.getInitializer().isPresent()) {
      com.github.javaparser.ast.expr.ArrayInitializerExpr init = ace.getInitializer().get();
      int n = init.getValues().size();
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.NEWARRAY)
              .dest(dest)
              .ctorType(component)
              .lhs1(TacOperand.Const.ofInt(n))
              .line(TacTranslator.nodeLine(ace))
              .build());
      // The elements have to actually reach the array. Without the stores the
      // initializer's values were dropped and a later arr[0] read produced a
      // clean value even when the initializer carried attacker-controlled data.
      storeArrayInitializerElements(init, dest, component, b);
    } else if (ace.getLevels().size() == 1) {
      ExpressionResult size = translate(ace.getLevels().get(0).getDimension().orElseThrow(), b);
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.NEWARRAY)
              .dest(dest)
              .ctorType(component)
              .lhs1(size.operand())
              .line(TacTranslator.nodeLine(ace))
              .build());
    } else {
      // Multi-dim without initializer: emit nested NEWARRAY.
      TacOperand.TacVar outer = dest;
      for (int lvl = 0; lvl < ace.getLevels().size(); lvl++) {
        ExpressionResult size = translate(ace.getLevels().get(lvl).getDimension().orElseThrow(), b);
        b.emit(
            TacInstruction.builder(TacInstruction.Kind.NEWARRAY)
                .dest(outer)
                .ctorType(component)
                .lhs1(size.operand())
                .line(TacTranslator.nodeLine(ace))
                .build());
        if (lvl + 1 < ace.getLevels().size()) {
          outer = b.newTemp(TacType.arrayOf(component));
        }
      }
    }
    return new ExpressionResult(dest, arrayType);
  }

  /**
   * Handle bare array-initializer expressions like {@code {a, b, c}} that appear on the right-hand
   * side of a variable declaration, e.g. {@code int[] p = {1, 2, 3};}. The JavaParser AST produces
   * an {@code ArrayInitializerExpr} that previously fell through to the "unhandled expression" log.
   * We lower it to a {@code NEWARRAY} with the literal count of initializers as the size, so the
   * resulting array is at least shaped for downstream checkers (e.g. {@link
   * org.plumb.dfa.checker.PredictableKeyPasswordCheck}).
   *
   * <p>Each element is then stored into the fresh array with an {@code ARRAYSTORE}, so the values
   * actually reach the array. Dropping them (the earlier behaviour) silently deleted every element
   * of the initializer: a tainted element became unreachable, and a later {@code arr[0]} read
   * produced a clean value. The array would then look empty to the dataflow engine even though the
   * program had put attacker-controlled data in it.
   */
  private ExpressionResult translateArrayInitializer(
      com.github.javaparser.ast.expr.ArrayInitializerExpr aie, TacBuilder b, TacType component) {
    TacType arrayType = TacType.arrayOf(component);
    TacOperand.TacVar dest = b.newTemp(arrayType);
    int n = aie.getValues().size();
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.NEWARRAY)
            .dest(dest)
            .ctorType(component)
            .lhs1(TacOperand.Const.ofInt(n))
            .line(TacTranslator.nodeLine(aie))
            .build());
    storeArrayInitializerElements(aie, dest, component, b);
    return new ExpressionResult(dest, arrayType);
  }

  /**
   * The component type of a nested array initializer.
   *
   * <p>Derived from the OUTER array's component type: in {@code String[][] x = {{"a"}}} the outer
   * component type is {@code String[]}, so the nested array's component type is {@code String}.
   * When the outer component type is unknown there is nothing to derive from, so the type stays
   * unknown.
   *
   * @param outerComponent the outer array's component type
   * @return the component type of the nested array
   */
  private TacType nestedComponentType(TacType outerComponent) {
    if (outerComponent == null) return TacType.UNKNOWN;
    if (outerComponent.kind() == TacType.Kind.ARRAY) {
      return outerComponent.arrayComponent();
    }
    return TacType.UNKNOWN;
  }

  /**
   * Store each value of an array initializer into the freshly allocated array.
   *
   * <p>Emits one {@code ARRAYSTORE} per element, in source order, using the element's own index as
   * the constant index. A nested initializer ({@code {a, {b, c}}}) recurses, so a nested array
   * keeps its own elements rather than becoming an opaque value.
   *
   * @param aie the initializer expression
   * @param array the destination array operand
   * @param b the builder to emit into
   */
  private void storeArrayInitializerElements(
      com.github.javaparser.ast.expr.ArrayInitializerExpr aie,
      TacOperand.TacVar array,
      TacType outerComponent,
      TacBuilder b) {
    List<com.github.javaparser.ast.expr.Expression> values = aie.getValues();
    for (int i = 0; i < values.size(); i++) {
      com.github.javaparser.ast.expr.Expression value = values.get(i);
      ExpressionResult stored;
      if (value instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr nested) {
        // A nested initializer is itself an array: allocate it, then store its
        // own elements, and store the resulting array into the outer one.
        stored = translateArrayInitializer(nested, b, nestedComponentType(outerComponent));
      } else {
        stored = translate(value, b);
      }
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.ARRAYSTORE)
              .lhs1(array)
              .lhs2(TacOperand.Const.ofInt(i))
              .dest(stored.operand())
              .line(TacTranslator.nodeLine(value))
              .build());
    }
  }

  private ExpressionResult translateArrayAccess(ArrayAccessExpr aae, TacBuilder b) {
    ExpressionResult arr = translate(aae.getName(), b);
    ExpressionResult idx = translate(aae.getIndex(), b);
    TacOperand.TacVar dest = b.newTemp(TacType.UNKNOWN);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.ARRAYLOAD)
            .dest(dest)
            .lhs1(arr.operand())
            .lhs2(idx.operand())
            .line(TacTranslator.nodeLine(aae))
            .build());
    return new ExpressionResult(dest, TacType.UNKNOWN);
  }

  private ExpressionResult translateFieldAccess(FieldAccessExpr fae, TacBuilder b) {
    // recognise well-known static final fields
    // (Integer.MAX_VALUE, Long.MAX_VALUE, Integer.MIN_VALUE,
    // Long.MIN_VALUE, etc.) at translation time and emit a
    // direct CONST load. Without this fold, the IR sees
    // `count = Integer.MAX_VALUE` as a FIELDLOAD off a
    // synthetic class-name receiver, which loses the
    // constant value entirely. CWE-400 (resource
    // exhaustion) and CWE-789 (uncontrolled memory alloc)
    // both rely on these constants being recognisable as
    // "obviously-large" sources — a max_value flowing into
    // a for-loop bound or Thread.sleep is the Juliet
    // bad-source pattern.
    if (fae.getScope() instanceof NameExpr scopeName) {
      String cls = scopeName.getNameAsString();
      String field = fae.getNameAsString();
      TacOperand.Const folded = foldStaticFinal(cls, field);
      if (folded != null) {
        return new ExpressionResult(
            folded, TacType.INT.equals(folded.type()) ? TacType.INT : TacType.LONG);
      }
    }
    ExpressionResult scope = translate(fae.getScope(), b);
    TacOperand.TacVar dest = b.newTemp(TacType.UNKNOWN);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
            .dest(dest)
            .lhs1(scope.operand())
            .fieldName(fae.getNameAsString())
            .line(TacTranslator.nodeLine(fae))
            .build());
    return new ExpressionResult(dest, TacType.UNKNOWN);
  }

  /**
   * fold a small set of well-known {@code public static final} numeric constants to a {@link
   * TacOperand.Const}. Returns {@code null} if the {@code (class, field)} pair is not in the
   * catalogue.
   *
   * <p>The current catalogue covers the JDK primitive wrapper {@code MAX_VALUE} / {@code MIN_VALUE}
   * constants that Juliet uses as resource-exhaustion sources. Adding more is mechanical -- insert
   * the entry and the downstream CWE-400 / CWE-789 checkers will see the constant without further
   * changes.
   */
  private static TacOperand.Const foldStaticFinal(String cls, String field) {
    if ("MAX_VALUE".equals(field)) {
      switch (cls) {
        case "Integer":
          return TacOperand.Const.ofInt(Integer.MAX_VALUE);
        case "Long":
          return TacOperand.Const.ofLong(Long.MAX_VALUE);
        case "Short":
          return TacOperand.Const.ofInt(Short.MAX_VALUE);
        case "Byte":
          return TacOperand.Const.ofInt(Byte.MAX_VALUE);
        case "Double":
          return TacOperand.Const.ofDouble(Double.MAX_VALUE);
        case "Float":
          return TacOperand.Const.ofFloat(Float.MAX_VALUE);
        default:
          return null;
      }
    }
    if ("MIN_VALUE".equals(field)) {
      switch (cls) {
        case "Integer":
          return TacOperand.Const.ofInt(Integer.MIN_VALUE);
        case "Long":
          return TacOperand.Const.ofLong(Long.MIN_VALUE);
        case "Short":
          return TacOperand.Const.ofInt(Short.MIN_VALUE);
        case "Byte":
          return TacOperand.Const.ofInt(Byte.MIN_VALUE);
        case "Double":
          return TacOperand.Const.ofDouble(Double.MIN_VALUE);
        case "Float":
          return TacOperand.Const.ofFloat(Float.MIN_VALUE);
        default:
          return null;
      }
    }
    return null;
  }

  private ExpressionResult translateConditional(ConditionalExpr ce, TacBuilder b) {
    // cond -- a : b
    //   t1 = cond
    //   if t1 goto L_TRUE else goto L_FALSE
    // L_TRUE:
    //   t2 = a
    //   goto L_END
    // L_FALSE:
    //   t2 = b
    // L_END:
    //   result = t2
    ExpressionResult cond = translate(ce.getCondition(), b);
    String lTrue = b.newLabel("L_TRUE");
    String lFalse = b.newLabel("L_FALSE");
    String lEnd = b.newLabel("L_END");
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(cond.operand())
            .trueLabel(lTrue)
            .falseLabel(lFalse)
            .line(TacTranslator.nodeLine(ce))
            .build());
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lTrue).build());
    ExpressionResult a = translate(ce.getThenExpr(), b);
    TacOperand.TacVar result = b.newTemp(TacType.UNKNOWN);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(result)
            .lhs1(a.operand())
            .line(TacTranslator.nodeLine(ce))
            .build());
    b.emit(TacInstruction.builder(TacInstruction.Kind.JUMP).label(lEnd).build());
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lFalse).build());
    ExpressionResult c = translate(ce.getElseExpr(), b);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(result)
            .lhs1(c.operand())
            .line(TacTranslator.nodeLine(ce))
            .build());
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lEnd).build());
    return new ExpressionResult(result, TacType.UNKNOWN);
  }

  // ------------------------------------------------------------------
  // LHS helpers for assignments.
  // ------------------------------------------------------------------

  private ExpressionResult translateLhsRead(Expression target, TacBuilder b) {
    if (target instanceof NameExpr ne) {
      return translateName(ne, b);
    }
    if (target instanceof FieldAccessExpr fae) {
      return translateFieldAccess(fae, b);
    }
    if (target instanceof ArrayAccessExpr aae) {
      return translateArrayAccess(aae, b);
    }
    // Unsupported LHS : return null sentinel.
    LOG.debug("Unsupported LHS read: {}", target.getClass().getSimpleName());
    return new ExpressionResult(TacOperand.Const.ofNull(), TacType.UNKNOWN);
  }

  /**
   * Translate a lambda expression. We lower the body to a synthetic function on the enclosing type
   * and emit a CALL to that function. The returned {@link ExpressionResult} carries the lambda's
   * signature so the caller (typically {@code translateVarDecl}) can register the user-var → lambda
   * binding.
   */
  private ExpressionResult translateLambda(LambdaExpr le, TacBuilder b) {
    // The name is allocated from the TRANSLATOR's program-wide counter, not
    // from the builder's. A per-builder counter restarted at zero for every
    // method, so every method's first lambda was named `lambda$0` and the
    // program's function table held several functions with the same name.
    // Anything that resolves a lambda BY NAME then got an arbitrary answer.
    String lambdaName = owner.nextLambdaName();
    // The variables the lambda body captures from the enclosing scope. A
    // capture is a real parameter of the lowered function -- that is how a
    // compiler lowers it, and it is what keeps the captured value's taint
    // alive across the function boundary.
    List<TacOperand.TacVar> captures = capturedVariables(le, b);
    int lambdaArity = le.getParameters().size() + captures.size();
    String signature = lambdaName + "(" + lambdaArity + ")";
    TacBuilder.FunctionSpec spec =
        new TacBuilder.FunctionSpec(lambdaName, signature, TacType.UNKNOWN);
    TacBuilder lambdaB = new TacBuilder(spec, List.of());
    // First param: 'this' for non-static lambdas (we conservatively
    // pass null for now).
    // The translator side: parameters
    for (var p : le.getParameters()) {
      TacType pt = TypeMapper.map(p.getType());
      lambdaB.params().add(new TacOperand.TacVar(p.getNameAsString(), pt, false));
    }
    // The captured variables become the lambda function's remaining
    // parameters, in the order they were captured.
    for (TacOperand.TacVar capture : captures) {
      lambdaB.params().add(capture);
    }
    // Body: either a BlockStmt or a single expression
    var body = le.getBody();
    if (body instanceof com.github.javaparser.ast.stmt.BlockStmt bs) {
      // Re-use the statement translator via the owner
      owner.stmts().translateBlock(bs, lambdaB);
    } else if (body instanceof com.github.javaparser.ast.stmt.ExpressionStmt es) {
      // Expression-bodied lambda as an ExpressionStmt (JavaParser
      // sometimes wraps the single expression in an ExpressionStmt).
      var expr = es.getExpression();
      ExpressionResult er = translate(expr, lambdaB);
      lambdaB.emit(
          TacInstruction.builder(TacInstruction.Kind.RETURN)
              .lhs1(er.operand())
              .line(TacTranslator.nodeLine(le))
              .build());
    } else if (body instanceof com.github.javaparser.ast.stmt.ReturnStmt rs) {
      // Wrap in a tiny block so the existing statement path
      // handles return uniformly.
      var block = new com.github.javaparser.ast.stmt.BlockStmt();
      block.addStatement(rs);
      owner.stmts().translateBlock(block, lambdaB);
    } else {
      // Empty / unknown body : synthesize `return null;`
      lambdaB.emit(
          TacInstruction.builder(TacInstruction.Kind.RETURN)
              .lhs1(TacOperand.Const.ofNull())
              .line(TacTranslator.nodeLine(le))
              .build());
    }
    // Register the lambda body in the program
    owner.registerLambda(lambdaB);
    // Emit a CALL site so the lambda's taint surface is visible
    TacOperand.TacVar dest = b.newTemp(TacType.UNKNOWN);
    java.util.List<TacOperand> argOps = new java.util.ArrayList<>();
    // Pass 'this' as a sentinel null in the receiver slot; the cross-
    // function engine can read it but for the first pass we don't
    // model capture.
    argOps.add(TacOperand.Const.ofNull());
    // The captured variables follow, in the order the lambda function
    // declares them. Passing them is what carries their taint into the
    // lambda body: without it the captured value was a synthetic UNKNOWN
    // local inside the lambda, the taint stopped at the lambda boundary,
    // and a program that ran attacker-controlled input through a lambda
    // into a command was reported as clean.
    for (TacOperand.TacVar capture : captures) {
      argOps.add(capture);
    }
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(dest)
            .calleeSig("scope." + lambdaName + "/" + lambdaArity)
            .args(argOps)
            .line(TacTranslator.nodeLine(le))
            .build());
    // The CALL dest holds the function value. The CALL is also
    // a "binding" so the lambda can be looked up by the
    // program-wide binding table once a user var aliases it.
    return ExpressionResult.ofLambda(dest, lambdaName);
  }

  /**
   * The enclosing-scope variables a lambda body captures.
   *
   * <p>A lambda that reads a local of the enclosing method captures it. A compiler lowers that
   * capture into a parameter of the lowered function, and the lowered call passes the value. This
   * method finds the captured variables so the TAC can do the same.
   *
   * <p>Without the capture the captured name resolved to a synthetic UNKNOWN local inside the
   * lambda body: the value's taint stopped at the lambda boundary, and a program that ran
   * attacker-controlled input through a lambda into a command was reported as clean.
   *
   * <p>The scan covers the names the body reads. A name that is a lambda's OWN parameter, or a
   * local declared inside the body, is not a capture.
   *
   * @param le the lambda expression
   * @param enclosing the builder of the enclosing scope
   * @return the captured variables, in the order they were first seen
   */
  private List<TacOperand.TacVar> capturedVariables(LambdaExpr le, TacBuilder enclosing) {
    List<TacOperand.TacVar> captures = new java.util.ArrayList<>();
    java.util.Set<String> ownNames = new java.util.LinkedHashSet<>();
    for (var p : le.getParameters()) {
      ownNames.add(p.getNameAsString());
    }
    // A local declared inside the body shadows the enclosing one.
    le.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
        .forEach(v -> ownNames.add(v.getNameAsString()));
    for (com.github.javaparser.ast.expr.NameExpr ne :
        le.findAll(com.github.javaparser.ast.expr.NameExpr.class)) {
      String name = ne.getNameAsString();
      if (ownNames.contains(name)) continue;
      TacOperand.TacVar found = null;
      for (TacOperand.TacVar v : enclosing.params()) {
        if (v.name().equals(name)) {
          found = v;
          break;
        }
      }
      if (found == null) {
        for (TacOperand.TacVar v : enclosing.locals()) {
          if (v.name().equals(name)) {
            found = v;
            break;
          }
        }
      }
      if (found == null) continue;
      boolean already = false;
      for (TacOperand.TacVar c : captures) {
        if (c.name().equals(found.name())) {
          already = true;
          break;
        }
      }
      if (!already) captures.add(found);
    }
    return captures;
  }

  private void writeLhs(Expression target, TacOperand value, TacBuilder b, AssignExpr src) {
    if (target instanceof NameExpr ne) {
      // Find existing local or create one.
      String name = ne.getNameAsString();
      TacOperand.TacVar existing = null;
      for (TacOperand.TacVar v : b.locals())
        if (v.name().equals(name)) {
          existing = v;
          break;
        }
      for (TacOperand.TacVar v : b.params())
        if (v.name().equals(name)) {
          existing = v;
          break;
        }
      if (existing != null) {
        // A name that resolves to a local / param is
        // a normal MOVE. (see below for
        // the fall-through case.)
        TacOperand.TacVar dest = existing;
        b.emit(
            TacInstruction.builder(TacInstruction.Kind.MOVE)
                .dest(dest)
                .lhs1(value)
                .line(TacTranslator.nodeLine(src))
                .build());
        return;
      }
      // A name that does NOT resolve to a local or
      // param in this method's scope is almost certainly
      // an unqualified instance field reference. Java
      // lets users write `password = "..."` instead of
      // `this.password = "..."` inside instance methods;
      // the AST shows the same NameExpr in both cases,
      // so we have to make the call here. We emit a
      // FIELDSTORE with `this` as the receiver. (If
      // the name is a static field, the IR will
      // represent it as a static initializer / class
      // field, not a per-instance assignment — but
      // emitting FIELDSTORE for those is conservative:
      // the check filters to non-<clinit> methods
      // and won't double-report static-field patterns.)
      //
      // reuse the `this` from b.locals() if
      // present, instead of synthesising a fresh
      // TacVar("this", UNKNOWN). The locals form is
      // typed with the enclosing class name
      // (TacType.ofClass(source.primaryTypeName())),
      // which is required by the cross-fn field
      // taint fold in TaintedCheckerBase
      // (classNameOfType(recv.type()) on this lhs1).
      // Without the enclosing-class type the fold's
      // thisRecvTypeName lookup returns null and the
      // crossFnClassFieldTaint branch never writes,
      // so a `bad()` → FIELDSTORE then badSink() →
      // FIELDLOAD pair (Juliet _45 / _72a-75a) misses
      // silently.
      TacOperand.TacVar thisRecv = null;
      for (TacOperand.TacVar v : b.locals()) {
        if ("this".equals(v.name())) {
          thisRecv = v;
          break;
        }
      }
      TacOperand lhs1This =
          thisRecv != null ? thisRecv : new TacOperand.TacVar("this", TacType.UNKNOWN, false);
      // A STATIC field belongs to the class. Bind the class identity as
      // the receiver so the dataflow engine keys the field's state on the
      // class -- the same key the read path uses.
      TacOperand staticRecv = fieldReceiver(null, name, b);
      if (staticRecv != null) {
        lhs1This = staticRecv;
      }
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
              .dest(value)
              .lhs1(lhs1This)
              .fieldName(name)
              .line(TacTranslator.nodeLine(src))
              .build());
      return;
    }
    if (target instanceof FieldAccessExpr fae) {
      // Chained field write (e.g. {@code o.a.b = X}).
      // The scope {@code o.a} may already have a local that
      // holds the result of {@code String t1 = o.a;}. In
      // that case, we want the FIELDSTORE's lhs1 to be that
      // local ({@code t1}) directly — not a fresh FIELDLOAD
      // temp — so the chain walk later discovers
      // {@code fieldMap[t1][b]} when sinking a deep read.
      Expression scopeExpr = fae.getScope();
      String scopeKey = scopeExpr.toString();
      TacOperand.TacVar cachedLhs1 = null;
      if (scopeExpr instanceof FieldAccessExpr) {
        cachedLhs1 = b.fieldExprCache().get(scopeKey);
      }
      TacOperand lhs1Operand;
      if (cachedLhs1 != null) {
        // Reuse the cached local directly : no FIELDLOAD
        // needed, since the local already holds the
        // field's value.
        lhs1Operand = cachedLhs1;
      } else {
        ExpressionResult scope = translate(scopeExpr, b);
        lhs1Operand = scope.operand();
      }
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
              .dest(value)
              .lhs1(lhs1Operand)
              .fieldName(fae.getNameAsString())
              .line(TacTranslator.nodeLine(src))
              .build());
      return;
    }
    if (target instanceof ArrayAccessExpr aae) {
      ExpressionResult arr = translate(aae.getName(), b);
      ExpressionResult idx = translate(aae.getIndex(), b);
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.ARRAYSTORE)
              .lhs1(arr.operand())
              .lhs2(idx.operand())
              .dest(value)
              .line(TacTranslator.nodeLine(src))
              .build());
      return;
    }
    LOG.debug("Unsupported LHS write: {}", target.getClass().getSimpleName());
  }

  // ---------------- numeric literal helpers ----------------

  /**
   * Parse a Java {@code int} literal accepting all four radixes the Java Language Specification
   * defines:
   *
   * <ul>
   *   <li>Decimal: {@code 123}, {@code 1_000_000}, {@code -1}
   *   <li>Hex: {@code 0xff}, {@code 0XFF}, {@code 0xff_ff}, {@code -0xff}
   *   <li>Octal: {@code 010} (legacy leading-zero form, JLS §3.10.1)
   *   <li>Binary: {@code 0b101}, {@code 0B1010_1010}
   * </ul>
   *
   * Underscores (JLS §3.10.1) are stripped before parsing; the optional {@code L}/{@code l} long
   * suffix is stripped; the optional leading {@code -} sign is applied after the absolute value
   * parses.
   *
   * <p>Previously the translator only called {@code Integer.parseInt} which doesn't accept {@code
   * 0x} / {@code 0b} prefixes -- Juliet's {@code testcasesupport.IO} class uses {@code 0xff} in a
   * regex-flag computation, so the entire {@code IO.java} file failed to translate, which meant
   * {@code IO.staticReturnsTrue()} had no {@link org.plumb.dfa.summary.MethodSummary} in the cache,
   * which meant fold consumers saw UNKNOWN and never produced findings on real Juliet CWE113 /
   * CWE571 sweeps.
   */
  public static int parseJavaInt(String s) {
    String stripped = s.replace("_", "");
    // Strip optional L/l long
    // suffix — the previous code
    // did this for the long form
    // but not the int form.
    if (stripped.endsWith("L") || stripped.endsWith("l")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    // Handle leading sign by
    // parsing the absolute value
    // and negating.
    boolean negative = false;
    if (stripped.startsWith("-")) {
      negative = true;
      stripped = stripped.substring(1);
    } else if (stripped.startsWith("+")) {
      stripped = stripped.substring(1);
    }
    int v = parseJavaIntUnsigned(stripped);
    return negative ? -v : v;
  }

  /** {@code long} counterpart of {@link #parseJavaInt}. Same prefix conventions. */
  public static long parseJavaLong(String s) {
    String stripped = s.replace("_", "");
    if (stripped.endsWith("L") || stripped.endsWith("l")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    boolean negative = false;
    if (stripped.startsWith("-")) {
      negative = true;
      stripped = stripped.substring(1);
    } else if (stripped.startsWith("+")) {
      stripped = stripped.substring(1);
    }
    long v = parseJavaLongUnsigned(stripped);
    return negative ? -v : v;
  }

  /** Internal: parse the absolute value of an int literal. */
  private static int parseJavaIntUnsigned(String stripped) {
    // Hex prefix
    if (stripped.startsWith("0x") || stripped.startsWith("0X")) {
      return (int) Long.parseUnsignedLong(stripped.substring(2), 16);
    }
    // Binary prefix
    if (stripped.startsWith("0b") || stripped.startsWith("0B")) {
      return (int) Long.parseUnsignedLong(stripped.substring(2), 2);
    }
    // Octal: leading 0 followed by
    // a digit, but NOT plain "0"
    // alone (which is 0 in either
    // form).
    if (stripped.length() > 1
        && stripped.charAt(0) == '0'
        && Character.isDigit(stripped.charAt(1))) {
      return Integer.parseInt(stripped, 8);
    }
    return Integer.parseInt(stripped);
  }

  /** Internal: parse the absolute value of a long literal. */
  private static long parseJavaLongUnsigned(String stripped) {
    if (stripped.startsWith("0x") || stripped.startsWith("0X")) {
      return Long.parseUnsignedLong(stripped.substring(2), 16);
    }
    if (stripped.startsWith("0b") || stripped.startsWith("0B")) {
      return Long.parseUnsignedLong(stripped.substring(2), 2);
    }
    if (stripped.length() > 1
        && stripped.charAt(0) == '0'
        && Character.isDigit(stripped.charAt(1))) {
      return Long.parseLong(stripped, 8);
    }
    return Long.parseLong(stripped);
  }
}
