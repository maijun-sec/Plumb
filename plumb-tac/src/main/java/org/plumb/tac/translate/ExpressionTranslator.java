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

  ExpressionTranslator(TacTranslator owner) {
    this.owner = owner;
  }

  public ExpressionResult translate(Expression expr, TacBuilder b) {
    if (expr == null) return new ExpressionResult(TacOperand.Const.ofNull(), TacType.NULL);
    if (expr instanceof BinaryExpr be) return translateBinary(be, b);
    if (expr instanceof UnaryExpr ue) return translateUnary(ue, b);
    if (expr instanceof AssignExpr ae) return translateAssign(ae, b);
    if (expr instanceof NameExpr ne) return translateName(ne, b);
    if (expr instanceof ThisExpr) return translateThis(b);
    if (expr instanceof SuperExpr)
      return new ExpressionResult(TacOperand.Const.ofNull(), TacType.UNKNOWN);
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
    if (expr instanceof NullLiteralExpr) {
      return new ExpressionResult(TacOperand.Const.ofNull(), TacType.NULL);
    }
    if (expr instanceof CastExpr ce) return translateCast(ce, b);
    if (expr instanceof InstanceOfExpr ioe) return translateInstanceOf(ioe, b);
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
      // Variable declarations may appear as expression statements;
      // the StatementTranslator handles the common case. We still
      // emit a no-op so the resulting ExpressionResult is well-typed.
      return new ExpressionResult(TacOperand.Const.ofNull(), TacType.VOID);
    }
    if (expr instanceof TypeExpr) {
      return new ExpressionResult(TacOperand.Const.ofNull(), TacType.UNKNOWN);
    }
    if (expr instanceof LambdaExpr le) {
      // Lower the lambda body to a synthetic function. We then
      // emit a CALL to the synthetic function so call-site
      // taint analysis can still see the body.
      return translateLambda(le, b);
    }
    // Unhandled : log and return null sentinel.
    LOG.debug(
        "Unhandled expression: {} at line {}",
        expr.getClass().getSimpleName(),
        TacTranslator.nodeLine(expr));
    return new ExpressionResult(TacOperand.Const.ofNull(), TacType.UNKNOWN);
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
        TacOperand.TacVar tmp = b.newTemp(TacType.UNKNOWN);
        b.emit(
            TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
                .dest(tmp)
                .lhs1(v)
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
   * Hardcoded getter → FQN map for the most common chain-call receivers. JavaParser's symbol solver
   * is not wired up with a JDK type solver, so {@code response.getWriter().println(...)} ends up
   * with the dest TacVar of `getWriter()` carrying type UNKNOWN and the chained `println` call
   * emitting a "scope" receiver (e.g. {@code scope.println/1}) that no sink-family pattern matches.
   * By hardcoding the most common getter returns, the chained call gets a proper FQN receiver (e.g.
   * {@code java.io.PrintWriter.println/1}) and the sink patterns fire.
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
      // Fall back to the simple name so the legacy
      // catalog patterns (InputSourcePatterns) still
      // match on substring. Sink families won't match
      // but they were already FQN-only.
      return ne.getNameAsString();
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
      int n = ace.getInitializer().get().getValues().size();
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.NEWARRAY)
              .dest(dest)
              .ctorType(component)
              .lhs1(TacOperand.Const.ofInt(n))
              .line(TacTranslator.nodeLine(ace))
              .build());
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
   * <p>The individual element values are not lowered; the dataflow engine reasons about array
   * elements through {@code ARRAYLOAD}/{@code ARRAYSTORE} when the user code reads or writes them.
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
    return new ExpressionResult(dest, arrayType);
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
    String lambdaName = "lambda$" + (b.lambdaCounter++);
    String signature = lambdaName + "(" + le.getParameters().size() + ")";
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
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(dest)
            .calleeSig("scope." + lambdaName + "/" + le.getParameters().size())
            .args(argOps)
            .line(TacTranslator.nodeLine(le))
            .build());
    // The CALL dest holds the function value. The CALL is also
    // a "binding" so the lambda can be looked up by the
    // program-wide binding table once a user var aliases it.
    return ExpressionResult.ofLambda(dest, lambdaName);
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
