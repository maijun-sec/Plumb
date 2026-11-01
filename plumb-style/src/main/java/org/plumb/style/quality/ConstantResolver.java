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
package org.plumb.style.quality;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves an expression to the compile-time constant it denotes.
 *
 * <p>The defect classes this serves all ask the same question in different words: <i>is this
 * condition's value already decided?</i> A condition whose value is decided is not a condition --
 * it is a constant, and the code around it does not do what it appears to do.
 *
 * <p>Four shapes are recognised, and they are all <i>local</i> facts:
 *
 * <ol>
 *   <li><b>Literals</b> -- {@code true}, {@code 5}, {@code "text"}.
 *   <li><b>Locals</b> -- a variable whose initialiser is a literal and which is never reassigned:
 *       {@code String s = "true"; s.equals("true")}.
 *   <li><b>Fields of the enclosing class</b> -- {@code private static final boolean TRUE = true;}
 *       and also a non-final field that is initialised to a literal and never assigned again:
 *       {@code private boolean flag = true;}
 *   <li><b>Methods of the enclosing class</b> -- a no-argument method whose body is a single {@code
 *       return <literal>;}: {@code private static boolean returnsTrue() { return true; }}
 * </ol>
 *
 * <p><b>What is deliberately not resolved.</b> A field of another class, and a method of another
 * class, are not resolved, because the other class's body is not available here -- the answer would
 * be a guess. A field that is assigned somewhere other than its initialiser is not resolved either,
 * because its value depends on the path that reached the use.
 *
 * <p>The resolver is deliberately <i>path-insensitive but assignment-aware</i>: a name is resolved
 * only when every assignment in the enclosing class agrees on the same value. That is sound for the
 * "always true" question, because a name with two possible values is not decided.
 */
public final class ConstantResolver {

  /** The constant a name denotes, or {@code null} when it is not decided. */
  public static final class Value {
    private final Object value;
    private final String description;

    private Value(Object value, String description) {
      this.value = value;
      this.description = description;
    }

    /** The constant: a {@link Boolean}, a {@link Long}, or a {@link String}. */
    public Object value() {
      return value;
    }

    /** Human-readable form of the constant, e.g. {@code "true"} or {@code "5"}. */
    public String description() {
      return description;
    }

    /** Is the constant the boolean {@code true}? */
    public boolean isTrue() {
      return Boolean.TRUE.equals(value);
    }

    /** Is the constant the boolean {@code false}? */
    public boolean isFalse() {
      return Boolean.FALSE.equals(value);
    }

    /** The constant as a boolean, or {@code null} when it is not a boolean. */
    public Boolean asBoolean() {
      return value instanceof Boolean b ? b : null;
    }

    /** The constant as a long, or {@code null} when it is not a number. */
    public Long asLong() {
      return value instanceof Long l ? l : null;
    }

    /** The constant as a string, or {@code null} when it is not a string. */
    public String asString() {
      return value instanceof String s ? s : null;
    }

    @Override
    public String toString() {
      return description;
    }
  }

  /** Names that are assigned somewhere in the class, so their value is not decided. */
  private final Set<String> reassignedNames = new HashSet<>();

  /** Field name -> its constant value, for fields that are never reassigned. */
  private final Map<String, Value> fieldValues = new HashMap<>();

  /** Method key -> (local name -> its constant value). */
  private final Map<String, Map<String, Value>> localValuesByMethod = new HashMap<>();

  /** Method key -> the local names declared in it. */
  private final Map<String, Set<String>> localNamesByMethod = new HashMap<>();

  /** Method key + name of a local whose declarations disagree, so it is not resolvable. */
  private final Set<String> conflictingLocals = new HashSet<>();

  /** Method name -> the constant it returns, for methods with a single literal return. */
  private final Map<String, Value> methodValues = new HashMap<>();

  /**
   * Build a resolver for one compilation unit.
   *
   * <p>The resolver is built once per file and reused for every expression, so the class is walked
   * once rather than once per query.
   */
  public ConstantResolver(com.github.javaparser.ast.CompilationUnit unit) {
    this(unit, null);
  }

  /**
   * Build a resolver for one compilation unit, with access to the scan's cross-file caches.
   *
   * <p>The cross-file caches let the resolver answer questions about ANOTHER class: a static field
   * that holds a constant, and a static method whose body is a single constant return. Without them
   * the resolver can only speak about the file it was given, which is the right answer when the
   * other class's body is not available.
   */
  public ConstantResolver(
      com.github.javaparser.ast.CompilationUnit unit, org.plumb.style.StyleContext ctx) {
    this.ctx = ctx;
    if (unit == null) return;
    collectReassignedNames(unit);
    collectFieldValues(unit);
    collectMethodValues(unit);
    collectLocalValues(unit);
  }

  /** The scan's cross-file caches, or {@code null} when there are none. */
  private final org.plumb.style.StyleContext ctx;

  // ── collection ────────────────────────────────────────────────────────

  /**
   * Every name that is the target of an assignment, or is passed to a method that could change it.
   *
   * <p>Only a plain assignment is considered. A name that is only ever read keeps its initial
   * value, so it stays resolvable.
   */
  private void collectReassignedNames(com.github.javaparser.ast.CompilationUnit unit) {
    unit.walk(
        com.github.javaparser.ast.expr.AssignExpr.class,
        a -> {
          if (a.getTarget() instanceof NameExpr ne) {
            reassignedNames.add(ne.getNameAsString());
          }
        });
    // An increment or a decrement is also an assignment.
    unit.walk(
        com.github.javaparser.ast.expr.UnaryExpr.class,
        u -> {
          UnaryExpr.Operator op = u.getOperator();
          boolean increment =
              op == UnaryExpr.Operator.POSTFIX_INCREMENT
                  || op == UnaryExpr.Operator.PREFIX_INCREMENT;
          boolean decrement =
              op == UnaryExpr.Operator.POSTFIX_DECREMENT
                  || op == UnaryExpr.Operator.PREFIX_DECREMENT;
          if (!increment && !decrement) return;
          if (u.getExpression() instanceof NameExpr ne) {
            reassignedNames.add(ne.getNameAsString());
          }
        });
  }

  /** The constant value of each field that is initialised to a literal and never reassigned. */
  private void collectFieldValues(com.github.javaparser.ast.CompilationUnit unit) {
    for (FieldDeclaration fd : unit.findAll(FieldDeclaration.class)) {
      for (VariableDeclarator vd : fd.getVariables()) {
        String name = vd.getNameAsString();
        if (reassignedNames.contains(name)) continue;
        if (!vd.getInitializer().isPresent()) continue;
        Value v = literalValue(vd.getInitializer().get());
        if (v != null) {
          fieldValues.put(name, v);
        }
      }
    }
  }

  /** The constant a no-argument method returns, when its body is a single literal return. */
  private void collectMethodValues(com.github.javaparser.ast.CompilationUnit unit) {
    for (MethodDeclaration md : unit.findAll(MethodDeclaration.class)) {
      if (!md.getParameters().isEmpty()) continue;
      Optional<com.github.javaparser.ast.stmt.BlockStmt> body = md.getBody();
      if (body.isEmpty()) continue;
      List<ReturnStmt> returns = body.get().findAll(ReturnStmt.class);
      if (returns.size() != 1) continue;
      Optional<Expression> returned = returns.get(0).getExpression();
      if (returned.isEmpty()) continue;
      Value v = literalValue(returned.get());
      if (v != null) {
        methodValues.put(md.getNameAsString(), v);
      }
    }
  }

  /**
   * The constant value of each local that is initialised to a literal and never reassigned.
   *
   * <p>Locals are keyed by their enclosing method, because a local declared in one method is not
   * visible from another. A name that is declared in more than one method with different values is
   * not resolvable from either, because the resolver cannot tell which declaration a use refers to
   * without position tracking.
   */
  private void collectLocalValues(com.github.javaparser.ast.CompilationUnit unit) {
    // method key -> (local name -> value). A null key means "declared outside any method".
    Map<String, Map<String, Value>> byMethod = new HashMap<>();
    Map<String, Set<String>> namesByMethod = new HashMap<>();
    for (VariableDeclarationExpr vde : unit.findAll(VariableDeclarationExpr.class)) {
      String methodKey = methodKeyOf(vde);
      for (VariableDeclarator vd : vde.getVariables()) {
        String name = vd.getNameAsString();
        if (reassignedNames.contains(name)) continue;
        if (!vd.getInitializer().isPresent()) continue;
        Value v = literalValue(vd.getInitializer().get());
        if (v == null) continue;
        Map<String, Value> locals = byMethod.computeIfAbsent(methodKey, k -> new HashMap<>());
        Set<String> names = namesByMethod.computeIfAbsent(methodKey, k -> new HashSet<>());
        names.add(name);
        // Two declarations of the same name in the same scope with different values make the
        // name undecidable, so the conflicting value is dropped.
        Value previous = locals.putIfAbsent(name, v);
        if (previous != null && !previous.description().equals(v.description())) {
          locals.remove(name);
          names.remove(name);
          conflictingLocals.add(methodKey + "#" + name);
        }
      }
    }
    for (Map.Entry<String, Map<String, Value>> e : byMethod.entrySet()) {
      localValuesByMethod.put(e.getKey(), e.getValue());
    }
    localNamesByMethod.putAll(namesByMethod);
  }

  /** The key that identifies the scope a declaration belongs to. */
  private static String methodKeyOf(Node node) {
    Node scope = node;
    while (scope != null) {
      if (scope instanceof MethodDeclaration md) {
        return "m:" + md.getNameAsString() + "/" + md.getParameters().size();
      }
      scope = scope.getParentNode().orElse(null);
    }
    return "class";
  }

  // ── resolution ────────────────────────────────────────────────────────

  /**
   * The constant an expression denotes, or {@code null} when it is not decided.
   *
   * <p>Recognised: literals; a local or field whose value is decided; a call to a method of the
   * same class whose return value is decided; and the arithmetic on two decided numbers.
   */
  public Value resolve(Expression e) {
    return resolve(e, null);
  }

  /**
   * The constant an expression denotes, when the expression appears in the given scope.
   *
   * <p>The scope is the node the expression sits in -- usually the expression itself. It decides
   * which method's locals are visible, so a local of another method is never resolved by mistake.
   */
  public Value resolve(Expression e, Node scopeNode) {
    if (e == null) return null;
    if (e instanceof EnclosedExpr enc) {
      return resolve(enc.getInner(), scopeNode);
    }
    // A literal is its own value.
    Value literal = literalValue(e);
    if (literal != null) return literal;
    // A name that resolves to a decided local of the enclosing method, or to a decided field.
    if (e instanceof NameExpr ne) {
      String name = ne.getNameAsString();
      Value local = localValueInScope(name, scopeNode);
      if (local != null) return local;
      return fieldValues.get(name);
    }
    // A method call on this class whose return value is decided.
    if (e instanceof MethodCallExpr call) {
      if (!call.getArguments().isEmpty()) return null;
      // A call with an explicit scope is a call on another object, not on this class.
      if (call.getScope().isPresent()) {
        if (!(call.getScope().get() instanceof NameExpr)) return null;
        if (!"this".equals(((NameExpr) call.getScope().get()).getNameAsString())) {
          // The scope names another class. The answer may be in the scan's cross-file cache.
          Value crossFile = resolveCrossFileCall(call);
          if (crossFile != null) return crossFile;
          return null;
        }
      }
      return methodValues.get(call.getNameAsString());
    }
    // A field access on this class: `this.FIELD`.
    if (e instanceof FieldAccessExpr fae) {
      Value viaThis = fieldValueThroughThis(fae);
      if (viaThis != null) return viaThis;
      Value crossFile = resolveCrossFileField(fae);
      if (crossFile != null) return crossFile;
    }
    // Arithmetic on two decided numbers.
    if (e instanceof com.github.javaparser.ast.expr.BinaryExpr be) {
      Value left = resolve(be.getLeft(), scopeNode);
      Value right = resolve(be.getRight(), scopeNode);
      if (left == null || right == null) return null;
      Long l = left.asLong();
      Long r = right.asLong();
      if (l == null || r == null) return null;
      Long result = null;
      switch (be.getOperator()) {
        case PLUS -> result = l + r;
        case MINUS -> result = l - r;
        case MULTIPLY -> result = l * r;
        case DIVIDE -> {
          if (r != 0L) result = l / r;
        }
        case REMAINDER -> {
          if (r != 0L) result = l % r;
        }
        default -> {
          /* not arithmetic */
        }
      }
      if (result != null) {
        return new Value(result, String.valueOf(result));
      }
    }
    // A negated number.
    if (e instanceof UnaryExpr u && u.getOperator() == UnaryExpr.Operator.MINUS) {
      Value inner = resolve(u.getExpression(), scopeNode);
      Long l = inner == null ? null : inner.asLong();
      if (l != null) {
        long negated = -l;
        return new Value(negated, String.valueOf(negated));
      }
    }
    return null;
  }

  /**
   * The value of a local that is visible in the given scope.
   *
   * <p>A local shadows a field of the same name, so the local is tried first. A local whose
   * declarations disagree is not resolvable, and a local of a different method is not visible.
   */
  private Value localValueInScope(String name, Node scopeNode) {
    String key = scopeNode == null ? "class" : methodKeyOf(scopeNode);
    if (conflictingLocals.contains(key + "#" + name)) return null;
    Map<String, Value> locals = localValuesByMethod.get(key);
    if (locals == null) {
      // Not a method scope: only class-level declarations are visible.
      locals = localValuesByMethod.get("class");
      if (locals == null) return null;
      return locals.get(name);
    }
    Value v = locals.get(name);
    if (v != null) return v;
    // A local of an OUTER method is not visible from a nested scope, but a local declared at class
    // level (an initializer block or a field initialiser) is.
    return localValuesByMethod.getOrDefault("class", Map.of()).get(name);
  }

  /** The field value reached through an explicit {@code this} scope. */
  private Value fieldValueThroughThis(FieldAccessExpr fae) {
    if (!(fae.getScope() instanceof NameExpr scope)) return null;
    if (!"this".equals(scope.getNameAsString())) return null;
    return fieldValues.get(fae.getNameAsString());
  }

  /**
   * The constant returned by a static method of ANOTHER class, when the scan has that class's body.
   *
   * <p>The scope is the class name: {@code IO.staticReturnsTrue()}. The cache is keyed by the
   * class's fully-qualified name, so the simple name in the source has to be mapped to it first. A
   * name that does not map is not resolved -- guessing a package would be a guess.
   */
  private Value resolveCrossFileCall(MethodCallExpr call) {
    if (ctx == null) return null;
    if (!call.getArguments().isEmpty()) return null;
    Expression scopeExpr = call.getScope().orElse(null);
    if (!(scopeExpr instanceof NameExpr scope)) return null;
    String simpleName = scope.getNameAsString();
    if ("this".equals(simpleName)) return null;
    String fqcn = resolveTypeName(simpleName);
    if (fqcn == null) return null;
    com.github.javaparser.ast.body.MethodDeclaration callee =
        ctx.lookupStaticMethod(fqcn, call.getNameAsString());
    if (callee == null) return null;
    if (!callee.getParameters().isEmpty()) return null;
    java.util.Optional<com.github.javaparser.ast.stmt.BlockStmt> body = callee.getBody();
    if (body.isEmpty()) return null;
    java.util.List<com.github.javaparser.ast.stmt.ReturnStmt> returns =
        body.get().findAll(com.github.javaparser.ast.stmt.ReturnStmt.class);
    if (returns.size() != 1) return null;
    java.util.Optional<Expression> returned = returns.get(0).getExpression();
    if (returned.isEmpty()) return null;
    return literalValue(returned.get());
  }

  /**
   * The constant held by a static field of ANOTHER class, when the scan has that class's body.
   *
   * <p>The scope is the class name: {@code IO.STATIC_FINAL_TRUE}.
   */
  private Value resolveCrossFileField(FieldAccessExpr fae) {
    if (ctx == null) return null;
    Expression scopeExpr = fae.getScope();
    if (!(scopeExpr instanceof NameExpr scope)) return null;
    String simpleName = scope.getNameAsString();
    if ("this".equals(simpleName)) return null;
    String fqcn = resolveTypeName(simpleName);
    if (fqcn == null) return null;
    Object value = ctx.lookupStaticField(fqcn, fae.getNameAsString());
    if (value == null) return null;
    return new Value(value, describe(value));
  }

  /**
   * The fully-qualified name of a class referred to by its simple name.
   *
   * <p>The resolution is by import and by same-package declaration -- the two ways a simple name
   * can name a class in Java source. A name that resolves to neither is not resolved.
   */
  private String resolveTypeName(String simpleName) {
    if (ctx == null) return null;
    com.github.javaparser.ast.CompilationUnit unit = ctx.source().unit();
    if (unit == null) return null;
    // A single-type import names the class outright.
    for (com.github.javaparser.ast.ImportDeclaration imp : unit.getImports()) {
      if (!imp.isAsterisk()) {
        String imported = imp.getNameAsString();
        if (imported.endsWith("." + simpleName)) {
          return imported;
        }
      }
    }
    // A same-package class: the package of this file plus the simple name.
    String pkg = ctx.source().packageName();
    if (pkg != null && !pkg.isEmpty()) {
      String candidate = pkg + "." + simpleName;
      if (ctx.knowsClass(candidate)) return candidate;
    }
    return null;
  }

  /** A human-readable form of a constant value. */
  private static String describe(Object value) {
    if (value instanceof Boolean b) return String.valueOf(b);
    if (value instanceof Long l) return String.valueOf(l);
    if (value instanceof String s) return "\"" + s + "\"";
    return String.valueOf(value);
  }

  /**
   * The constant a literal expression denotes, or {@code null} when the expression is not a
   * literal.
   *
   * <p>Only literals count. A name is not a literal, even when the name happens to hold a constant
   * -- that is what {@link #resolve} is for.
   */
  public static Value literalValue(Expression e) {
    if (e == null) return null;
    if (e instanceof BooleanLiteralExpr ble) {
      return new Value(ble.getValue(), String.valueOf(ble.getValue()));
    }
    if (e instanceof IntegerLiteralExpr ili) {
      try {
        long v = Long.parseLong(ili.getValue().replace("_", ""));
        return new Value(v, String.valueOf(v));
      } catch (NumberFormatException nfe) {
        return null;
      }
    }
    if (e instanceof LongLiteralExpr lli) {
      try {
        String raw = lli.getValue().replace("_", "").replace("L", "").replace("l", "");
        long v = Long.parseLong(raw);
        return new Value(v, String.valueOf(v));
      } catch (NumberFormatException nfe) {
        return null;
      }
    }
    if (e instanceof CharLiteralExpr cle) {
      return new Value(String.valueOf(cle.getValue()), "'" + cle.getValue() + "'");
    }
    if (e instanceof StringLiteralExpr sle) {
      return new Value(sle.getValue(), "\"" + sle.getValue() + "\"");
    }
    return null;
  }

  /**
   * The enclosing class or method of a node.
   *
   * <p>Used to scope a lookup: a name declared in a method is not visible from another method, and
   * a field is visible from anywhere in its class.
   */
  public static Node enclosingScope(Node node) {
    Node scope = node;
    while (scope != null) {
      if (scope instanceof MethodDeclaration) return scope;
      scope = scope.getParentNode().orElse(null);
    }
    return null;
  }
}
