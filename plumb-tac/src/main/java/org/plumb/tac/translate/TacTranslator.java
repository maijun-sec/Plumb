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

import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.Type;
import java.util.ArrayList;
import java.util.List;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.SourceMap;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;
import org.plumb.tac.ir.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Top-level AST⇒⇒AC translator. One instance per {@link TacProgram} being built.
 *
 * <p>The translator intentionally emits a "linear" instruction stream (not yet a CFG). {@link
 * org.plumb.tac.ir.TacCfgBuilder} runs over the result to split it into basic blocks.
 *
 * <p>Scope of supported Java constructs (intentionally narrow, see the README for the design
 * goals):
 *
 * <ul>
 *   <li>Method / constructor bodies: declarations, assignments, if/else, while / do-while / for /
 *       for-each, try-catch-finally (skeleton), return, throw, switch as if-else chain, expression
 *       statements.
 *   <li>Expressions: binary, unary, assignment, conditional, method call, {@code new}, array
 *       creation, field access, array access, cast, {@code instanceof}, literals, {@code this}.
 *   <li>Out of scope: lambdas, anonymous inner classes (skipped), record patterns, sealed-type
 *       patterns, annotations on local decls (ignored for now).
 * </ul>
 */
public final class TacTranslator {

  private static final Logger LOG = LoggerFactory.getLogger(TacTranslator.class);

  private final ParsedSource source;
  private final TacProgram program;
  private final ExpressionTranslator exprTranslator;
  private final StatementTranslator stmtTranslator;

  public TacTranslator(ParsedSource source) {
    this.source = source;
    this.program = new TacProgram(source.path(), source.primaryTypeName());
    this.exprTranslator = new ExpressionTranslator(this);
    this.stmtTranslator = new StatementTranslator(this);
  }

  public TacProgram program() {
    return program;
  }

  public ParsedSource source() {
    return source;
  }

  public SourceMap sourceMap() {
    return program.sourceMap();
  }

  public ExpressionTranslator exprs() {
    return exprTranslator;
  }

  public StatementTranslator stmts() {
    return stmtTranslator;
  }

  /**
   * Register a synthetic function (e.g. a lambda body). The function is added to the program with
   * its spec preserved.
   */
  public void registerLambda(TacBuilder b) {
    TacFunction fn = b.build();
    program.addFunction(fn);
  }

  /**
   * Bind a user-var to a lambda's signature. The inter-procedural taint engine uses this to follow
   * {@code f.apply(...)} into the lambda body.
   */
  public void bindLambda(String varName, String lambdaSignature) {
    program.bindLambda(varName, lambdaSignature);
  }

  public TacProgram translate() {
    CompilationUnit unit = source.unit();
    for (TypeDeclaration<?> td : unit.getTypes()) {
      translateType(td);
    }
    return program;
  }

  private void translateType(TypeDeclaration<?> td) {
    translateTypeWithPrefix(td, "", "");
  }

  /**
   * Translate a (possibly nested) type declaration. The {@code prefix} qualifies inner-class method
   * names with the enclosing-class hierarchy (using {@code $} as the separator, matching the
   * JVM-style inner-class naming). For top-level types the prefix is empty.
   *
   * <p>The {@code outerChain} is the JVM-style qualified name of the type that CONTAINS this one:
   * {@code ""} for a top-level type, {@code "Outer"} for a type nested in {@code Outer}, {@code
   * "Outer$Middle"} for a type nested in {@code Outer.Middle}. It is what the nested type's
   * callables record as their enclosing class, so the DFA engine can resolve the file the nested
   * type lives in.
   */
  /**
   * The qualified name of the type that declares a callable.
   *
   * <p>Top-level types are their own short name. Nested types use the JVM-style {@code $}
   * separator, matching the prefix the translator already builds for inner-class method names (e.g.
   * {@code "Outer$Inner"}).
   */
  private static String qualifiedDeclaringType(String prefix, String classShortName) {
    if (prefix == null || prefix.isEmpty()) {
      return classShortName;
    }
    return prefix;
  }

  private void translateTypeWithPrefix(TypeDeclaration<?> td, String prefix, String outerChain) {
    // Capture class-level annotations (e.g.
    // {@code @RestController}, {@code @Configuration}). These
    // are propagated to every function emitted from this class
    // so checkers can recognise roles from any function they
    // look at.
    List<String> classAnns = extractAnnotationNames(td.getAnnotations());
    // Capture the class hierarchy. We only model the
    // direct parent (Java has no multiple-inheritance of
    // classes); interfaces are not recorded. The full FQN of
    // the extended class is reduced to its short name to
    // match {@link TacFunction#enclosingClass()}, which is
    // set just below when we hand each function its
    // declaring class. The translation is deliberately
    // tolerant: a type with no {@code extends} clause (most
    // Juliet test cases) leaves the map untouched.
    //
    // The resulting table is not read by the current analysis
    // (the call-graph strategies resolve a virtual call by
    // simple name and arity), so it is recorded for a future
    // hierarchy-aware resolver rather than used today.
    String classShortName = td.getNameAsString();
    // The ENCLOSING CLASS of every callable this type declares.
    //
    // For a top-level type this is the class's own short name. For a
    // NESTED type it is the qualified {@code Outer$Inner} name --
    // the same JVM-style naming the translator already uses to
    // qualify the nested type's method names.
    //
    // WHY THE NESTED CASE MATTERS. The DFA engine resolves a
    // finding's source file by looking the enclosing class up in
    // the per-class source-path map, and that map is built from
    // the SAME `enclosingClass()` value (see ScanRunner's
    // mergedSourcePathMap and DfaEngine's registerSourcePaths).
    //
    // The nested case used to pass `null` here, on the reasoning
    // that "inner classes' method names already encode the outer
    // scope, so the enclosingClass short name would not match the
    // outer-class's lookup anyway". That reasoning was right about
    // the lookup MISSING and wrong about it being HARMLESS: with a
    // null enclosing class the map had no entry for the nested
    // type, so the reporter could not resolve the file and the
    // finding was emitted with filePath="unknown".
    //
    // The consequence was a whole finding losing its location: the
    // Juliet harness matches a case to a finding by FILE, so a
    // finding in a nested class stopped matching its case and was
    // scored as a miss. The nested class's file path is knowable --
    // the nested class lives in the file of the type that declares
    // it -- so the qualified name is the key that finds it.
    String enclosingClassName =
        outerChain.isEmpty() ? classShortName : outerChain + "$" + classShortName;
    if (prefix.isEmpty()
        && td instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cid) {
      // Only register the parent at the top-level class
      // declaration; inner classes get a synthetic prefix
      // (e.g. {@code Outer$Inner}) and are not what the
      // CHA walk matches against.
      for (com.github.javaparser.ast.type.ClassOrInterfaceType ext : cid.getExtendedTypes()) {
        String parentName = ext.getNameAsString();
        if (parentName != null && !parentName.isEmpty()) {
          program.registerClassParent(classShortName, parentName);
        }
      }
      // Also walk the implemented
      // interfaces and register each as a
      // synthetic parent. The classToParent map
      // is flat (no separate implements list),
      // so a class that "implements
      // Serializable" ends up with Serializable
      // in its parent chain. This lets a checker
      // (e.g. MissingSerialVersionUIDChecker)
      // walk the parent chain via
      // {@code program.classToParent()} to ask
      // "is this class Serializable?" without a
      // dedicated interface-tracking pass.
      for (com.github.javaparser.ast.type.ClassOrInterfaceType impl : cid.getImplementedTypes()) {
        String ifName = impl.getNameAsString();
        if (ifName != null && !ifName.isEmpty()) {
          program.registerClassParent(classShortName, ifName);
        }
      }
    }
    if (prefix.contains("$")) {
      // Inner class: also include the outer class's
      // annotations so a {@code @RestController} on the
      // outer controller class is visible from the inner's
      // methods too. The outer prefix's annotations are
      // already accumulated on the outer class's child
      // builders; we just record them at lookup time below.
    }

    List<BodyDeclaration<?>> members = td.getMembers();

    // Record the field annotations of THIS class, on the program itself.
    //
    // A function's own fieldAnnotations() describes the fields of the class
    // that DECLARES the function, and it is only populated when the class
    // happens to emit a synthetic <clinit> or <init> -- which needs a static
    // field or an initialized instance field. A request bean is exactly the
    // shape that breaks this:
    //
    //   public class Parameters {
    //     @RestQuery("customer")
    //     public String customer;      // no initializer, no methods
    //   }
    //
    // Such a class emits NO function at all, so its binding annotation was
    // invisible to the whole engine. The program-level table below records
    // the annotations of EVERY declared class, so a bean with nothing but
    // annotated fields is still representable in the IR.
    String declaringClassName = prefix.isEmpty() ? classShortName : prefix;
    for (BodyDeclaration<?> member : members) {
      if (!(member instanceof FieldDeclaration memberField)) continue;
      List<String> fieldAnns = extractAnnotationNames(memberField.getAnnotations());
      if (fieldAnns.isEmpty()) continue;
      for (VariableDeclarator variable : memberField.getVariables()) {
        program.addClassFieldAnnotations(declaringClassName, variable.getNameAsString(), fieldAnns);
      }
    }

    // Collect static field initializers into a synthetic <clinit>.
    TacBuilder clinit = null;
    for (BodyDeclaration<?> bd : members) {
      if (!(bd instanceof FieldDeclaration fd)) continue;
      if (!fd.isStatic()) continue;
      boolean anyInit = false;
      for (VariableDeclarator v : fd.getVariables()) {
        if (v.getInitializer().isPresent()) {
          anyInit = true;
          break;
        }
      }
      if (!anyInit) continue;
      if (clinit == null) {
        clinit =
            newBuilder(
                new TacBuilder.FunctionSpec("<clinit>", "<clinit>()", TacType.VOID), List.of());
        // The synthetic <clinit> also belongs to
        // this class for CHA walk purposes.
        if (prefix.isEmpty()) clinit.setEnclosingClass(classShortName);
      }
      clinit.addClassAnnotations(classAnns);
      // Field-level annotations on static fields (e.g.
      // {@code @Value("${...}")} on a static config field).
      for (VariableDeclarator v : fd.getVariables()) {
        List<String> fAnns = extractAnnotationNames(fd.getAnnotations());
        if (!fAnns.isEmpty()) {
          clinit.addFieldAnnotations(v.getNameAsString(), fAnns);
        }
      }
      for (VariableDeclarator v : fd.getVariables()) {
        if (v.getInitializer().isPresent()) {
          emitFieldInit(clinit, fd, v, v.getInitializer().get());
        }
      }
    }
    // Collect instance field initializers into a synthetic
    // {@code <init>}. The translator previously dropped
    // non-static field declarations entirely (only static
    // fields were captured into {@code <clinit>}), which
    // made {@code private String password = "literal"} invisible
    // to FIELDSTORE-based checks. We emit a default-argument
    // {@code <init>} that holds one FIELDSTORE per initialized
    // instance field; if a real constructor is present, the
    // real one wins (we don't double-emit). The synthetic
    // {@code <init>} is what {@link
    // org.plumb.dfa.checker.FieldHardcodedPasswordCheck}
    // looks at.
    boolean hasExplicitCtor = false;
    for (BodyDeclaration<?> bd : members) {
      if (bd instanceof ConstructorDeclaration) {
        hasExplicitCtor = true;
        break;
      }
    }
    TacBuilder syntheticInit = null;
    if (!hasExplicitCtor) {
      for (BodyDeclaration<?> bd : members) {
        if (!(bd instanceof FieldDeclaration fd)) continue;
        if (fd.isStatic()) continue;
        boolean anyInit = false;
        for (VariableDeclarator v : fd.getVariables()) {
          if (v.getInitializer().isPresent()) {
            anyInit = true;
            break;
          }
        }
        if (!anyInit) continue;
        if (syntheticInit == null) {
          String qualifiedCtor = prefix.isEmpty() ? "<init>" : prefix + ".<init>";
          syntheticInit =
              newBuilder(
                  new TacBuilder.FunctionSpec(qualifiedCtor, qualifiedCtor + "()", TacType.VOID),
                  List.of());
          // Synthetic instance <init> belongs to
          // this class. The enclosing class is the
          // qualified name for a nested type, so the
          // source-path lookup can find the file the
          // nested class lives in.
          syntheticInit.setEnclosingClass(enclosingClassName);
        }
        syntheticInit.addClassAnnotations(classAnns);
        // Field-level annotations on instance fields
        // (e.g. {@code @Value("${app.password}")} on a
        // {@code @ConfigurationProperties} class).
        for (VariableDeclarator v : fd.getVariables()) {
          List<String> fAnns = extractAnnotationNames(fd.getAnnotations());
          if (!fAnns.isEmpty()) {
            syntheticInit.addFieldAnnotations(v.getNameAsString(), fAnns);
          }
        }
        for (VariableDeclarator v : fd.getVariables()) {
          if (v.getInitializer().isPresent()) {
            emitInstanceFieldInit(syntheticInit, fd, v, v.getInitializer().get());
          }
        }
      }
    }
    for (BodyDeclaration<?> bd : members) {
      if (bd instanceof MethodDeclaration md) {
        String qualified =
            prefix.isEmpty() ? md.getNameAsString() : prefix + "." + md.getNameAsString();
        translateCallable(
            md,
            qualified,
            signatureOf(qualified, md.getParameters(), md.getType()),
            md.getType(),
            md.getBody().orElse(null),
            classAnns,
            extractAnnotationNames(md.getAnnotations()),
            enclosingClassName,
            qualifiedDeclaringType(prefix, classShortName));
      } else if (bd instanceof ConstructorDeclaration cd) {
        String qualifiedCtor = prefix.isEmpty() ? "<init>" : prefix + ".<init>";
        translateCallable(
            cd,
            qualifiedCtor,
            signatureOf(prefix.isEmpty() ? td.getNameAsString() : prefix, cd.getParameters(), null),
            null,
            cd.getBody(),
            classAnns,
            List.of(),
            enclosingClassName,
            qualifiedDeclaringType(prefix, classShortName));
      } else if (bd instanceof InitializerDeclaration id) {
        String fnName = id.isStatic() ? "<clinit>" : "<init>";
        TacBuilder.FunctionSpec spec =
            new TacBuilder.FunctionSpec(fnName, fnName + "()", TacType.VOID);
        TacBuilder b = newBuilder(spec, List.of());
        b.addClassAnnotations(classAnns);
        b.setEnclosingClass(enclosingClassName);
        BlockStmt body = id.getBody();
        if (body != null) stmtTranslator.translateBlock(body, b);
        finishFunction(b, nodeLine(id));
      } else if (bd instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration inner) {
        String newPrefix =
            prefix.isEmpty() ? inner.getNameAsString() : prefix + "$" + inner.getNameAsString();
        // The chain of the type that CONTAINS the nested one: this type's own qualified name.
        String newOuterChain =
            outerChain.isEmpty() ? classShortName : outerChain + "$" + classShortName;
        translateTypeWithPrefix(inner, newPrefix, newOuterChain);
      } else if (bd instanceof com.github.javaparser.ast.body.EnumDeclaration en) {
        String newPrefix =
            prefix.isEmpty() ? en.getNameAsString() : prefix + "$" + en.getNameAsString();
        String newOuterChain =
            outerChain.isEmpty() ? classShortName : outerChain + "$" + classShortName;
        translateTypeWithPrefix(en, newPrefix, newOuterChain);
      }
    }
    if (clinit != null) finishFunction(clinit, -1);
    if (syntheticInit != null) finishFunction(syntheticInit, -1);
  }

  /**
   * Emit an instance-field initializer as a MOVE + FIELDSTORE pair. The MOVE evaluates the
   * initializer expression into a temp; the FIELDSTORE assigns the temp to {@code this.field}. A
   * bare FIELDSTORE with a literal destination would lose array / object initializers that need a
   * temp, so we route the initializer through the same statement-translation path the
   * variable-declarator code uses (so {@code new int[]{1,2,3}} works, etc.) and then emit a
   * follow-up FIELDSTORE.
   */
  private void emitInstanceFieldInit(
      TacBuilder b, FieldDeclaration fd, VariableDeclarator v, Expression init) {
    // Translate the initializer into a temp var. We synthesize
    // a VariableDeclarationExpr that uses the same name as the
    // field; the translation emits a MOVE (or whatever the
    // initializer lowers to) into a TacVar that the next call
    // can FIELDSTORE from.
    stmtTranslator.translate(
        new com.github.javaparser.ast.stmt.ExpressionStmt(syntheticDeclaration(v)), b);
    // Now emit {@code this.<field> = <temp>} where the temp is
    // the TacVar the statement-translation just produced. We
    // look up the last MOVE / NEWARRAY / etc. that wrote to
    // a temp named like the field, and emit the FIELDSTORE
    // targeting that temp.
    TacOperand rhs = null;
    java.util.List<TacInstruction> insns = b.instructions();
    for (int i = insns.size() - 1; i >= 0; i--) {
      TacInstruction ins = insns.get(i);
      if (ins.dest instanceof TacOperand.TacVar dv && dv.name().equals(v.getNameAsString())) {
        rhs = dv;
        break;
      }
    }
    if (rhs == null) {
      // Fall back: the initializer may have been folded into a
      // single instruction (e.g. CONST). Walk back to the
      // last instruction overall and use its dest.
      for (int i = insns.size() - 1; i >= 0; i--) {
        TacInstruction ins = insns.get(i);
        if (ins.dest != null) {
          rhs = ins.dest;
          break;
        }
      }
    }
    if (rhs == null) return; // give up; better no FIELDSTORE than a wrong one
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
            .lhs1(new TacOperand.TacVar("this", TacType.UNKNOWN, false))
            .fieldName(v.getNameAsString())
            .dest(rhs)
            .line(nodeLine(v))
            .build());
  }

  private void emitFieldInit(
      TacBuilder b, FieldDeclaration fd, VariableDeclarator v, Expression init) {
    // Synthesize a VariableDeclarationExpr and route through the
    // existing statement-translation path.
    stmtTranslator.translate(
        new com.github.javaparser.ast.stmt.ExpressionStmt(syntheticDeclaration(v)), b);
  }

  /**
   * A detached copy of {@code declarator}, wrapped in a {@link VariableDeclarationExpr}.
   *
   * <p><b>Why the copy matters.</b> The obvious spelling is
   *
   * <pre>
   *   new VariableDeclarationExpr(NodeList.nodeList(v));
   * </pre>
   *
   * but {@code NodeList.nodeList(v)} RE-PARENTS the original declarator: it is removed from its
   * {@link FieldDeclaration} and attached to the new node. The new node is not attached to the
   * {@code CompilationUnit}, so the declarator's parent chain no longer reaches the compilation
   * unit -- and JavaParser's {@code getSymbolResolver()} walks that chain to find the unit. The
   * result is that {@code resolve()} on the declarator and everything under it throws {@code
   * IllegalStateException: com.github.javaparser.ast.expr.VariableDeclarationExpr}.
   *
   * <p>That is not a cosmetic problem. The scan pipeline TRANSLATES to TAC and only then runs the
   * AST checkers, so a translation that breaks the parent chain silently disables type resolution
   * for every AST checker that runs afterwards. A checker that needs to know whether a method
   * returns {@code void} cannot answer, and has to assume the worst.
   *
   * <p>Cloning leaves the original tree intact: the copy carries its own children, and the original
   * declarator keeps its parent, so both trees resolve.
   *
   * @param declarator the declarator to copy
   */
  private VariableDeclarationExpr syntheticDeclaration(VariableDeclarator declarator) {
    VariableDeclarator copy = declarator.clone();
    return new VariableDeclarationExpr(com.github.javaparser.ast.NodeList.nodeList(copy));
  }

  private <T extends CallableDeclaration<T>> void translateCallable(
      T decl, String name, String signature, Type retType, BlockStmt body) {
    translateCallable(decl, name, signature, retType, body, List.of(), List.of(), null, null);
  }

  private <T extends CallableDeclaration<T>> void translateCallable(
      T decl,
      String name,
      String signature,
      Type retType,
      BlockStmt body,
      List<String> classAnns,
      List<String> methodAnns) {
    translateCallable(decl, name, signature, retType, body, classAnns, methodAnns, null, null);
  }

  /**
   * Translate a method/constructor while threading its enclosing class's annotation list and its
   * own method-level annotation list. Both are stored as simple names (e.g. {@code "PreAuthorize"}
   * not {@code "org.springframework.security.access.prepost.PreAuthorize"}).
   *
   * <p>The {@code enclosingClass} parameter carries the short name of the declaring class (e.g.
   * {@code "MyThread"}). It distinguishes two same-named methods on different classes, so the
   * merged program can keep both instead of collapsing them into one entry. {@code null} means "no
   * enclosing class" (free functions, synthetic inits where the class isn't available, etc.).
   *
   * <p>The {@code declaringType} parameter carries the qualified name of the type that declares
   * this callable (e.g. {@code "Controller"} for a top-level class, {@code "Outer$Inner"} for a
   * nested one). It is the type assigned to the implicit {@code this} local.
   */
  private <T extends CallableDeclaration<T>> void translateCallable(
      T decl,
      String name,
      String signature,
      Type retType,
      BlockStmt body,
      List<String> classAnns,
      List<String> methodAnns,
      String enclosingClass,
      String declaringType) {

    TacType mappedRet =
        retType == null
            ? TacType.VOID
            // Preserve generic type args on the
            // return type so INFORMATION_EXPOSURE can
            // inspect ResponseEntity<User> /
            // List<User> / Map<String, User>. The
            // param types still use the lossy map()
            // because param type-arg inspection isn't
            // a current consumer.
            : TypeMapper.mapPreservingTypeArgs(retType);
    TacBuilder.FunctionSpec spec = new TacBuilder.FunctionSpec(name, signature, mappedRet);
    List<TacOperand.TacVar> params = new ArrayList<>();
    for (Parameter p : decl.getParameters()) {
      TacType pt = TypeMapper.map(p.getType());
      params.add(new TacOperand.TacVar(p.getNameAsString(), pt, false));
    }
    TacBuilder b = newBuilder(spec, params);
    // Tag every emitted function with its declaring
    // class. For a top-level class that is the class's
    // short name; for a NESTED class it is the qualified
    // {@code Outer$Inner} name, so the DFA engine's
    // per-class source-path lookup can resolve the file
    // the nested class lives in.
    if (enclosingClass != null) b.setEnclosingClass(enclosingClass);
    // Class-level annotations always propagate into the
    // function (so a {@code @RestController} on the class makes
    // every method recognizable as an endpoint from the IR),
    // and method-level annotations are added on top.
    b.addClassAnnotations(classAnns);
    b.addMethodAnnotations(methodAnns);
    // Capture parameter-level annotations. Bean Validation
    // markers like {@code @Valid} / {@code @Validated} are
    // usually on the parameter (or the form-backing object
    // type); Spring's {@code @RequestParam} / {@code @PathVariable}
    // / {@code @RequestBody} are also parameter-level.
    for (Parameter p : decl.getParameters()) {
      List<String> pAnns = extractAnnotationNames(p.getAnnotations());
      if (!pAnns.isEmpty()) {
        b.addParamAnnotations(p.getNameAsString(), pAnns);
      }
    }
    if (!decl.isStatic() && !(decl instanceof ConstructorDeclaration)) {
      // The implicit `this` must carry the type of the class that
      // DECLARES this callable -- not the primary type of the whole
      // compilation unit. `source.primaryTypeName()` is the FIRST
      // top-level class of the file, so using it here typed every
      // class after the first as the first class: in a file with
      // `class Repository` and `class Controller`, `Controller.this`
      // was typed `Repository`, and `this.helper(...)` produced the
      // callee signature `Repository.helper/1`.
      //
      // That wrong type propagates everywhere the receiver's type is
      // read: FQN-anchored sink patterns, the cross-function field
      // taint fold (`classNameOfType(recv.type())`), the CHA
      // resolver's receiver-type lookup, and the points-to receiver
      // identity. DeclaringType is the qualified declaring type
      // ("Controller", or "Outer$Inner" for a nested type); fall back
      // to the primary type only when it is genuinely unavailable.
      String thisType =
          (declaringType != null && !declaringType.isEmpty())
              ? declaringType
              : source.primaryTypeName();
      b.locals().add(new TacOperand.TacVar("this", TacType.ofClass(thisType), false));
    }
    if (body != null) stmtTranslator.translateBlock(body, b);
    ensureReturn(b, mappedRet);
    finishFunction(b, nodeLine(decl));
  }

  /**
   * Convert a JavaParser annotation list to simple-name form. The FQN {@code
   * org.springframework.beans.factory.annotation.Value} becomes {@code Value} -- that's the only
   * shape checkers need. Strips the leading {@code @}. Deduplicates within the list.
   */
  private static List<String> extractAnnotationNames(NodeList<AnnotationExpr> anns) {
    List<String> out = new ArrayList<>();
    if (anns == null || anns.isEmpty()) return out;
    for (AnnotationExpr a : anns) {
      String n = a.getNameAsString();
      // Strip the leading "@" if any (defensive : getNameAsString
      // usually returns it without the "@", but older JavaParser
      // builds vary).
      if (n.startsWith("@")) n = n.substring(1);
      // Convert FQN to simple name: take the last segment.
      int dot = n.lastIndexOf('.');
      if (dot >= 0) n = n.substring(dot + 1);
      if (!n.isEmpty() && !out.contains(n)) out.add(n);
    }
    return out;
  }

  /**
   * The program-wide lambda counter.
   *
   * <p><b>Why it is program-wide and not per-builder.</b> A lambda body is lowered to a synthetic
   * function named {@code lambda$N}. When the counter lived on the {@link TacBuilder}, each method
   * restarted at zero, so every method's first lambda was named {@code lambda$0} -- and the
   * program's function table then held SEVERAL functions with the same name.
   *
   * <p>That is not cosmetic. Anything that looks a lambda up BY NAME then gets an arbitrary answer:
   * the deserialization-filter tracker asks "does this lambda's body ever return REJECTED?" and,
   * finding two {@code lambda$0}s -- one rejecting and one not -- concludes that both reject. A
   * permissive JEP-290 filter that allows {@code java.lang.*} and leaves the rest UNDECIDED was
   * then treated as a shipped mitigation, and the CWE-502 finding it should have produced was
   * silently dropped.
   *
   * <p>So the counter is a translator field: every lambda in the program gets a distinct name, and
   * a name lookup resolves to exactly one body.
   */
  private int lambdaCounter = 0;

  /**
   * Allocate the next program-unique lambda name.
   *
   * @return a name of the form {@code lambda$N}
   */
  String nextLambdaName() {
    return "lambda$" + (lambdaCounter++);
  }

  private TacBuilder newBuilder(TacBuilder.FunctionSpec spec, List<TacOperand.TacVar> params) {
    return new TacBuilder(spec, params);
  }

  private void ensureReturn(TacBuilder b, TacType retType) {
    var insns = b.instructions();
    if (insns.isEmpty()) {
      b.emit(TacInstruction.builder(TacInstruction.Kind.RETURN).build());
      return;
    }
    TacInstruction.Kind last = insns.get(insns.size() - 1).kind;
    if (last == TacInstruction.Kind.RETURN || last == TacInstruction.Kind.THROW) return;
    if (retType == TacType.VOID) {
      b.emit(TacInstruction.builder(TacInstruction.Kind.RETURN).build());
    }
  }

  private void finishFunction(TacBuilder b, int lineHint) {
    TacFunction fn = b.build();
    program.addFunction(fn);
    for (int i = 0; i < fn.instructions().size(); i++) {
      TacInstruction ins = fn.instructionAt(i);
      if (ins.line > 0) {
        program.sourceMap().record(fn.signature(), i, ins.line, ins.column);
      } else if (lineHint > 0) {
        program.sourceMap().record(fn.signature(), i, lineHint, -1);
      }
    }
  }

  private static String signatureOf(String name, List<Parameter> params, Type ret) {
    StringBuilder sb = new StringBuilder(name).append('(');
    for (int i = 0; i < params.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(params.get(i).getType().asString());
    }
    sb.append(')');
    if (ret != null) sb.append(':').append(ret.asString());
    return sb.toString();
  }

  // ------------------------------------------------------------------
  // Helpers shared with sub-translators.
  // ------------------------------------------------------------------

  public static int nodeLine(Node n) {
    return n.getRange().map(r -> r.begin.line).orElse(-1);
  }

  public static int nodeColumn(Node n) {
    return n.getRange().map(r -> r.begin.column).orElse(-1);
  }

  public static Range nodeRange(Node n) {
    return n.getRange().orElse(null);
  }

  public static String methodSig(String receiver, String methodName, int argCount) {
    return (receiver == null ? "" : receiver + ".") + methodName + "/" + argCount;
  }

  public static String ctorSig(String typeName, int argCount) {
    return typeName + ".<init>/" + argCount;
  }

  // ------------------------------------------------------------------
  // Common BinaryOperator / UnaryOperator mappings from JavaParser.
  // ------------------------------------------------------------------

  public static BinaryOperator mapBinOp(com.github.javaparser.ast.expr.BinaryExpr.Operator op) {
    return switch (op) {
      case PLUS -> BinaryOperator.ADD;
      case MINUS -> BinaryOperator.SUB;
      case MULTIPLY -> BinaryOperator.MUL;
      case DIVIDE -> BinaryOperator.DIV;
      case REMAINDER -> BinaryOperator.MOD;
      case BINARY_AND -> BinaryOperator.AND;
      case BINARY_OR -> BinaryOperator.OR;
      case XOR -> BinaryOperator.XOR;
      case LEFT_SHIFT -> BinaryOperator.SHL;
      case SIGNED_RIGHT_SHIFT -> BinaryOperator.SHR;
      case UNSIGNED_RIGHT_SHIFT -> BinaryOperator.USHR;
      case EQUALS -> BinaryOperator.EQ;
      case NOT_EQUALS -> BinaryOperator.NE;
      case LESS -> BinaryOperator.LT;
      case LESS_EQUALS -> BinaryOperator.LE;
      case GREATER -> BinaryOperator.GT;
      case GREATER_EQUALS -> BinaryOperator.GE;
      case AND -> BinaryOperator.LAND;
      case OR -> BinaryOperator.LOR;
    };
  }

  public static BinaryOperator mapAssignOp(com.github.javaparser.ast.expr.AssignExpr.Operator op) {
    return switch (op) {
      case PLUS -> BinaryOperator.ADD;
      case MINUS -> BinaryOperator.SUB;
      case MULTIPLY -> BinaryOperator.MUL;
      case DIVIDE -> BinaryOperator.DIV;
      case REMAINDER -> BinaryOperator.MOD;
      case BINARY_AND -> BinaryOperator.AND;
      case BINARY_OR -> BinaryOperator.OR;
      case XOR -> BinaryOperator.XOR;
      case LEFT_SHIFT -> BinaryOperator.SHL;
      case SIGNED_RIGHT_SHIFT -> BinaryOperator.SHR;
      case UNSIGNED_RIGHT_SHIFT -> BinaryOperator.USHR;
      default -> null;
    };
  }

  public static UnaryOperator mapPreIncDec(com.github.javaparser.ast.expr.UnaryExpr.Operator op) {
    return switch (op) {
      case PREFIX_INCREMENT -> UnaryOperator.PRE_INC;
      case PREFIX_DECREMENT -> UnaryOperator.PRE_DEC;
      default -> null;
    };
  }

  public static UnaryOperator mapPostIncDec(com.github.javaparser.ast.expr.UnaryExpr.Operator op) {
    return switch (op) {
      case POSTFIX_INCREMENT -> UnaryOperator.POST_INC;
      case POSTFIX_DECREMENT -> UnaryOperator.POST_DEC;
      default -> null;
    };
  }

  public static UnaryOperator mapUnary(com.github.javaparser.ast.expr.UnaryExpr.Operator op) {
    return switch (op) {
      case MINUS -> UnaryOperator.NEG;
      case LOGICAL_COMPLEMENT -> UnaryOperator.NOT;
      case BITWISE_COMPLEMENT -> UnaryOperator.BITNOT;
      default -> null;
    };
  }
}
