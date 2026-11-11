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
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private final JavaModernFeatures modernFeatures;

  /**
   * The module the translated file belongs to, or {@code null} when there is no module boundary.
   *
   * <p>Every function the translator produces carries it, so a single-module analysis can report
   * this module's findings and only those.
   */
  private String module;

  /**
   * The counter that names anonymous classes.
   *
   * <p>An anonymous class has no source name, so one is allocated. The counter is
   * <b>program-wide</b>, not per-method, because a single method can declare several anonymous
   * classes and two of them can implement the same interface -- a per-method counter would make
   * them collide on name.
   */
  private int anonymousClassCounter = 0;

  public TacTranslator(ParsedSource source) {
    this.source = source;
    this.program = new TacProgram(source.path(), source.primaryTypeName());
    this.exprTranslator = new ExpressionTranslator(this);
    this.stmtTranslator = new StatementTranslator(this);
    this.modernFeatures = new JavaModernFeatures(this);
  }

  /**
   * Sets the module the translated file belongs to.
   *
   * @param module the module id, or {@code null} for "no module boundary"
   */
  public void setModule(String module) {
    this.module = module == null || module.isBlank() ? null : module;
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
   * The translator for the Java features that arrived after Java 8: switch expressions, pattern
   * matching, record patterns.
   *
   * <p>Separate from the statement and expression translators because these constructs span both: a
   * switch expression is an expression whose arms contain statements, and a pattern binding is an
   * expression that declares a variable.
   *
   * @see JavaModernFeatures
   */
  public JavaModernFeatures modern() {
    return modernFeatures;
  }

  /**
   * Register a synthetic function (e.g. a lambda body). The function is added to the program with
   * its spec preserved.
   */
  /**
   * The STATIC fields declared by each class, keyed by the class's simple name.
   *
   * <p>A static field belongs to the class, not to an instance, and the dataflow engine keys its
   * field state on the receiver. The translator therefore has to be able to tell a static field
   * from an instance one.
   *
   * <p>The symbol resolver cannot always answer that: a class declared in the scanned source is not
   * on the reflection type solver's classpath, so {@code name.resolve()} throws for a field of a
   * user-defined class. The declaration itself is right here in the AST, so the translator records
   * the static fields as it walks the type declarations and consults that table.
   */
  private final java.util.Map<String, java.util.Set<String>> staticFieldsByClass =
      new java.util.HashMap<>();

  /** Record that {@code className} declares the static field {@code fieldName}. */
  void recordStaticField(String className, String fieldName) {
    if (className == null || fieldName == null || fieldName.isEmpty()) return;
    staticFieldsByClass.computeIfAbsent(className, k -> new java.util.HashSet<>()).add(fieldName);
  }

  /** Is {@code fieldName} a static field of {@code className}? */
  boolean isStaticFieldOf(String className, String fieldName) {
    if (className == null || fieldName == null) return false;
    java.util.Set<String> fields = staticFieldsByClass.get(className);
    return fields != null && fields.contains(fieldName);
  }

  /**
   * The name of the class currently being translated, so an unqualified field access can be
   * classified.
   */
  private String currentClassName = "";

  /** The name of the class currently being translated ("" at the top level). */
  String currentClassName() {
    return currentClassName;
  }

  private void setCurrentClassName(String name) {
    this.currentClassName = name == null ? "" : name;
  }

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

  /**
   * Register a deferred target: the user var {@code varName} holds a method reference to the method
   * named {@code calleeSig}. The application site is rewritten into that call.
   */
  public void bindDeferredTarget(String varName, String calleeSig) {
    program.bindDeferredTarget(varName, calleeSig);
  }

  public TacProgram translate() {
    // Carry the PARSE problems into the IR.
    //
    // WHY THIS MATTERS. JavaParser recovers from a syntax error and still produces a tree
    // for the rest of the file. So a file with a typo yields a program that looks
    // complete -- except that the broken part is missing. Without recording the parse
    // problems, the IR would claim to be a faithful translation of a file it did not
    // fully understand, and a consumer would have no way to know.
    //
    // The problems are recorded on the program, not on a function, because a parse
    // problem is not attributable to one function: the syntax error may be between two
    // declarations.
    for (com.github.javaparser.Problem p : source.problems()) {
      // A Problem's location is an Optional<TokenRange>. The TokenRange is a PAIR of
      // tokens, so its own begin/end are token objects -- the line/column pair a
      // finding needs lives on the Range that the token range converts to.
      //
      // Every step is Optional-guarded: a problem raised before any token was
      // consumed has no range at all, and a missing location must degrade the
      // problem to "no position", never throw.
      com.github.javaparser.Position pos = null;
      if (p.getLocation().isPresent()) {
        com.github.javaparser.Range range = p.getLocation().get().toRange().orElse(null);
        if (range != null) pos = range.begin;
      }
      int line = pos == null ? -1 : pos.line;
      int column = pos == null ? -1 : pos.column;
      program.addProgramProblem(
          new TacProgram.FunctionProblem(
              "<parse>",
              "PARSE_PROBLEM",
              p.getMessage(),
              // nodeClass identifies the AST node the problem attaches to, when
              // JavaParser could attribute it to one. It is null for a problem
              // that is not tied to a node (e.g. a lexer error).
              p.getLocation().isPresent() ? "<token-range>" : null,
              line,
              column));
    }
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
    // The class currently being translated. An unqualified field access
    // inside it has to be classified as static or instance, and the symbol
    // resolver cannot do that for a class declared in the scanned source.
    setCurrentClassName(td.getNameAsString());
    // Capture class-level annotations (e.g.
    // {@code @RestController}, {@code @Configuration}). These
    // are propagated to every function emitted from this class
    // so checkers can recognise roles from any function they
    // look at.
    List<String> classAnns = extractAnnotationNames(td.getAnnotations());
    // The VALUES the class's annotations carry.
    //
    // WHY A FIELD AND NOT A PARAMETER. A class's annotation values apply to every
    // function the class declares, exactly like the class's annotation NAMES do. The
    // names travel as a parameter; the values would need a parallel parameter on
    // every overload of translateCallable, and a parallel parameter is a parallel
    // parameter that a future call site can forget. A field cannot be forgotten: it
    // is set wherever the names are set.
    //
    // It is a FIELD rather than a local because translateCallable reads it from a
    // different call depth. The value is reset for every type declaration, so a
    // nested class does not inherit its outer class's annotation values.
    this.currentClassAnnotationValues = extractAnnotationValues(td.getAnnotations());
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
      // Record the static field so the expression translator can tell a
      // static field access from an instance one. The symbol resolver
      // cannot do it for a class declared in the scanned source.
      for (VariableDeclarator v : fd.getVariables()) {
        recordStaticField(classShortName, v.getNameAsString());
      }
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
    // A Java record's implicit members. Java 16 records get, for free:
    //
    //   - a canonical constructor that assigns each component to its field
    //   - one accessor per component (`x()` for the component `x`)
    //   - `equals`, `hashCode`, `toString`
    //
    // The first two matter for the dataflow: the constructor is where a component's taint
    // enters the record, and the accessor is where it leaves. Without them, a record's
    // fields would be invisible to every field-taint rule -- the constructor would not
    // exist to assign them and the accessor would not exist to read them.
    //
    // The accessors and the canonical constructor are also SYNTHETIC: the compiler
    // generated them, nobody wrote them. Marking them as such keeps an
    // "unused method" rule from reporting a method that no human was ever expected
    // to call.
    //
    // `equals` / `hashCode` / `toString` are not modelled: they carry no taint of their
    // own, and a record's own accessors already expose its components.
    if (td instanceof com.github.javaparser.ast.body.RecordDeclaration rd) {
      synthesizeRecordMembers(rd, prefix, classShortName, classAnns, enclosingClassName);
    }

    // An ENUM's constants are the enum's data: `RED`, `GREEN` are instances of the enum,
    // and `Color.RED` is a real reference that code can pass around, store in a field, and
    // return from a method.
    //
    // Without them, an enum contributes NOTHING to the program when it declares no methods --
    // which is the common case. `Color.RED` then resolves to nothing, and any taint that
    // flowed through an enum-typed value is lost.
    //
    // The compiler generates the constants, so they are SYNTHETIC: nobody wrote them, and
    // an "unused member" rule must not report them.
    if (td instanceof com.github.javaparser.ast.body.EnumDeclaration en) {
      synthesizeEnumMembers(en, prefix, classShortName, classAnns, enclosingClassName);
    }

    // An ANNOTATION TYPE declares members that are methods with default values. They
    // are real callables: the value an annotation processor sees for an unspecified
    // member is the member's default.
    //
    // The annotation type's own members are handled here rather than in the member
    // dispatch below, because the dispatch only sees them when the annotation type is
    // NESTED. A top-level `@interface` reaches this method directly, and without this
    // its members would be absent from the program entirely.
    if (td instanceof com.github.javaparser.ast.body.AnnotationDeclaration ad) {
      for (com.github.javaparser.ast.body.BodyDeclaration<?> amd : ad.getMembers()) {
        if (!(amd instanceof com.github.javaparser.ast.body.AnnotationMemberDeclaration member)) {
          continue;
        }
        String qualified =
            prefix.isEmpty() ? member.getNameAsString() : prefix + "." + member.getNameAsString();
        translateAnnotationMember(
            ad, member, qualified, classAnns, enclosingClassName, prefix, classShortName);
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
      } else if (bd instanceof com.github.javaparser.ast.body.CompactConstructorDeclaration ccd) {
        // A COMPACT constructor: `record Point(int x, int y) { Point { validate(x); } }`.
        //
        // It is the canonical constructor written in the compact form: the parameters
        // are the record's components, and the body is real code that runs on every
        // construction. A validation check or a sanitisation inside it is exactly the
        // kind of code a dataflow rule must see, so it is lowered as the constructor.
        //
        // The signature uses the record's components, matching the synthesized
        // canonical constructor -- so a call site resolves to exactly one of them.
        //
        // A CompactConstructorDeclaration is NOT a CallableDeclaration in the AST (it
        // has no parameter list of its own -- the parameters ARE the components), so it
        // cannot go through translateCallable.
        //
        // The components come from the ENCLOSING type declaration: a compact
        // constructor can only appear inside a record, so `td` IS the record.
        if (td instanceof com.github.javaparser.ast.body.RecordDeclaration recordDecl) {
          translateCompactConstructor(
              ccd, recordDecl, prefix, classShortName, classAnns, enclosingClassName);
        } else {
          // Unreachable in valid Java (a compact constructor outside a record does
          // not parse), but the translator must not crash on a recovered tree.
          program.addProgramProblem(
              new TacProgram.FunctionProblem(
                  classShortName,
                  "UNHANDLED_NODE",
                  "Compact constructor outside a record",
                  null,
                  -1,
                  -1));
        }
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
        // An enum constant may carry a CLASS BODY: `PLUS { int apply(...) {...} }`.
        // That body declares an anonymous subclass of the enum, and its methods are
        // real code that can hold a vulnerability. Without lowering it, the body is
        // invisible -- not reported as unhandled, just absent, which is the worst
        // kind of gap because nothing says so.
        for (com.github.javaparser.ast.body.EnumConstantDeclaration ec : en.getEntries()) {
          if (ec.getClassBody().isEmpty()) continue;
          String constPrefix = newPrefix + "$" + ec.getNameAsString();
          translateAnonymousMembers(
              ec.getClassBody(), constPrefix, newOuterChain + "$" + en.getNameAsString());
        }
      } else if (bd instanceof com.github.javaparser.ast.body.AnnotationDeclaration ad) {
        // The annotation type's members are lowered by translateTypeWithPrefix, which
        // handles a top-level `@interface` and a nested one the same way. Recursing here
        // would emit the members twice.
        String newPrefix =
            prefix.isEmpty() ? ad.getNameAsString() : prefix + "$" + ad.getNameAsString();
        String newOuterChain =
            outerChain.isEmpty() ? classShortName : outerChain + "$" + classShortName;
        translateTypeWithPrefix(ad, newPrefix, newOuterChain);
      } else if (bd instanceof com.github.javaparser.ast.body.RecordDeclaration rd) {
        // A record declared as a MEMBER of another type (a nested record).
        String newPrefix =
            prefix.isEmpty() ? rd.getNameAsString() : prefix + "$" + rd.getNameAsString();
        String newOuterChain =
            outerChain.isEmpty() ? classShortName : outerChain + "$" + classShortName;
        translateTypeWithPrefix(rd, newPrefix, newOuterChain);
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
    // The annotation VALUES, from the callable's OWN annotations and from the class's.
    //
    // The class's annotations matter too: `@RestController` on the class and
    // `@Value("${...}")` on the class are both facts about every method in it, and a
    // value declared on the class is exactly the kind of configuration placeholder a
    // consumer needs to see.
    for (Map.Entry<String, List<String>> entry :
        extractAnnotationValues(decl.getAnnotations()).entrySet()) {
      for (String value : entry.getValue()) {
        b.addAnnotationValue(entry.getKey(), value);
      }
    }
    // The CLASS's annotation values, which apply to every method it declares.
    for (Map.Entry<String, List<String>> entry : currentClassAnnotationValues.entrySet()) {
      for (String value : entry.getValue()) {
        b.addAnnotationValue(entry.getKey(), value);
      }
    }
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
      // An explicit RECEIVER parameter (`void m(Foo this)`) declares the type of
      // the enclosing instance. It is not an argument -- JavaParser keeps it out
      // of getParameters() -- so it does not appear in the signature or the
      // parameter list. But it IS the most precise statement of what `this` is,
      // so when it is present it wins over the declaring type.
      //
      // WHY IT MATTERS. The receiver type is read by the FQN-anchored sink
      // patterns, the cross-function field taint fold, the CHA resolver and the
      // points-to receiver identity. A method that spells out its receiver type
      // gives the analysis a stronger fact than "the type that happens to
      // declare me" -- and in the nested-class case the two differ.
      if (decl.getReceiverParameter().isPresent()) {
        TacType receiverType = TypeMapper.map(decl.getReceiverParameter().get().getType());
        if (receiverType != null && receiverType != TacType.UNKNOWN) {
          thisType = receiverType.displayName();
        }
      }
      b.locals().add(new TacOperand.TacVar("this", TacType.ofClass(thisType), false));
    }
    if (body != null) stmtTranslator.translateBlock(body, b);
    ensureReturn(b, mappedRet);
    finishFunction(b, nodeLine(decl));
  }

  /**
   * Translate an anonymous class body: {@code new Runnable() { public void run() {...} }}.
   *
   * <p><b>Why it matters.</b> The anonymous class's methods are ordinary methods with ordinary
   * bodies. A vulnerability inside one is a vulnerability, and the enclosing method's analysis
   * would miss it entirely if the body were not lowered. The {@code new} expression itself is
   * modelled perfectly well without the body, so the gap would be SILENT -- the worst kind.
   *
   * <p><b>The name.</b> An anonymous class has no source name, so one is allocated: the enclosing
   * type, the enclosing method, and a counter. The counter matters because a single method can
   * declare several anonymous classes, and two of them can implement the same interface.
   *
   * @param body the anonymous class body's members
   * @param enclosing the builder of the enclosing function
   */
  void translateAnonymousType(
      com.github.javaparser.ast.NodeList<com.github.javaparser.ast.body.BodyDeclaration<?>> body,
      TacBuilder enclosing) {
    if (body == null || body.isEmpty()) return;
    String enclosingClass =
        enclosing == null || enclosing.enclosingClass() == null
            ? source.primaryTypeName()
            : enclosing.enclosingClass();
    String methodName = enclosing == null ? "anon" : enclosing.spec().name();
    int index = anonymousClassCounter++;
    String qualified = enclosingClass + "$" + methodName + "$" + index;

    // The anonymous class extends or implements the type named in the `new`, so the
    // enclosing chain is the enclosing class -- the anonymous class itself has no
    // source name to contribute.
    translateAnonymousMembers(body, qualified, enclosingClass);
  }

  /**
   * Lower the members of an anonymous class.
   *
   * <p>An anonymous class body is a list of body declarations (methods, fields, initialisers). It
   * is NOT a {@code TypeDeclaration}, so it cannot go through {@code translateTypeWithPrefix}. The
   * members are lowered individually instead.
   */
  private void translateAnonymousMembers(
      com.github.javaparser.ast.NodeList<com.github.javaparser.ast.body.BodyDeclaration<?>> body,
      String prefix,
      String enclosingClass) {
    for (com.github.javaparser.ast.body.BodyDeclaration<?> bd : body) {
      if (bd instanceof MethodDeclaration md) {
        String qualified = prefix + "." + md.getNameAsString();
        translateCallable(
            md,
            qualified,
            signatureOf(qualified, md.getParameters(), md.getType()),
            md.getType(),
            md.getBody().orElse(null),
            List.of(),
            extractAnnotationNames(md.getAnnotations()),
            enclosingClass,
            prefix);
      } else if (bd instanceof com.github.javaparser.ast.body.InitializerDeclaration id) {
        String fnName = id.isStatic() ? "<clinit>" : "<init>";
        TacBuilder.FunctionSpec spec =
            new TacBuilder.FunctionSpec(fnName, fnName + "()", TacType.VOID);
        TacBuilder b = newBuilder(spec, List.of());
        b.setEnclosingClass(enclosingClass);
        BlockStmt idBody = id.getBody();
        if (idBody != null) stmtTranslator.translateBlock(idBody, b);
        finishFunction(b, nodeLine(id));
      } else if (bd instanceof com.github.javaparser.ast.body.FieldDeclaration fd) {
        // A field of an anonymous class. Its initializer is real data flow, so it is
        // lowered into a synthetic initialiser.
        TacBuilder b =
            newBuilder(new TacBuilder.FunctionSpec("<init>", "<init>()", TacType.VOID), List.of());
        b.setEnclosingClass(enclosingClass);
        for (VariableDeclarator v : fd.getVariables()) {
          if (!v.getInitializer().isPresent()) continue;
          ExpressionResult r = exprTranslator.translate(v.getInitializer().get(), b);
          TacOperand.TacVar tmp = b.newTemp(TacType.UNKNOWN);
          b.emit(
              TacInstruction.builder(TacInstruction.Kind.MOVE)
                  .dest(tmp)
                  .lhs1(r.operand())
                  .line(nodeLine(v))
                  .build());
          b.emit(
              TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
                  .dest(tmp)
                  .fieldName(v.getNameAsString())
                  .line(nodeLine(v))
                  .build());
        }
        finishFunction(b, nodeLine(fd));
      }
    }
  }

  /**
   * Translate a type declared INSIDE a method body: a local class or a local record.
   *
   * <p><b>Why it needs its own path.</b> A local type is not a member of any type, so the member
   * dispatch never sees it. Without this, the local class's methods are simply absent from the
   * program -- not reported as unhandled, just missing, which is the worst kind of gap because
   * nothing says so.
   *
   * <p><b>The name.</b> A local class is named after the method that declares it, with the
   * enclosing type as the qualifier: {@code Outer$methodName$Local}. That mirrors how the JVM names
   * local classes ({@code Outer$1Local}) and keeps the name unique within the program -- two
   * methods in the same class can each declare a local class named {@code Helper}.
   */
  void translateLocalType(TypeDeclaration<?> td, TacBuilder enclosing) {
    if (td == null) return;
    String enclosingClass =
        enclosing == null || enclosing.enclosingClass() == null
            ? source.primaryTypeName()
            : enclosing.enclosingClass();
    String methodName = enclosing == null ? "local" : enclosing.spec().name();
    String qualified = enclosingClass + "$" + methodName + "$" + td.getNameAsString();
    translateTypeWithPrefix(td, qualified, enclosingClass);
  }

  /**
   * Translate a record's COMPACT constructor.
   *
   * <p><b>What it is.</b> {@code record Point(int x, int y) { Point { validate(x); } }} writes the
   * canonical constructor in the compact form: no parameter list is spelled out, because the
   * parameters ARE the record's components.
   *
   * <p><b>Why it is lowered separately.</b> A {@code CompactConstructorDeclaration} is not a {@code
   * CallableDeclaration} in the AST -- it has no parameter list of its own -- so it cannot go
   * through {@code translateCallable}. The parameters are taken from the record's components, which
   * is exactly what the compact form means.
   *
   * <p><b>Why the body matters.</b> The body runs on every construction. A validation check or a
   * sanitisation written there is precisely the kind of code a dataflow rule must see: it is the
   * place where a component is checked before it is stored. Losing it would lose the check.
   */
  private void translateCompactConstructor(
      com.github.javaparser.ast.body.CompactConstructorDeclaration ccd,
      com.github.javaparser.ast.body.RecordDeclaration rd,
      String prefix,
      String classShortName,
      List<String> classAnns,
      String enclosingClassName) {
    List<com.github.javaparser.ast.body.Parameter> components = rd.getParameters();
    List<TacOperand.TacVar> params = new ArrayList<>();
    for (com.github.javaparser.ast.body.Parameter component : components) {
      params.add(
          new TacOperand.TacVar(
              component.getNameAsString(), TypeMapper.map(component.getType()), false));
    }
    String qualifiedPrefix = prefix.isEmpty() ? "" : prefix + ".";
    String qualifiedCtor = qualifiedPrefix + "<init>";
    String ctorSig = signatureOf(classShortName, components, null);

    TacBuilder b =
        newBuilder(new TacBuilder.FunctionSpec(qualifiedCtor, ctorSig, TacType.VOID), params);
    b.addClassAnnotations(classAnns);
    b.setEnclosingClass(enclosingClassName);
    // The `this` local carries the declaring type, matching an ordinary constructor.
    b.locals().add(new TacOperand.TacVar("this", TacType.ofClass(classShortName), false));

    BlockStmt body = ccd.getBody();
    if (body != null) stmtTranslator.translateBlock(body, b);

    // The implicit field assignments the compiler adds at the END of the compact
    // constructor. They are what makes a component's taint visible to the field
    // rules, so they are modelled -- the same assignments the synthesized canonical
    // constructor makes.
    TacOperand.TacVar thisVar =
        new TacOperand.TacVar("this", TacType.ofClass(classShortName), false);
    for (TacOperand.TacVar param : params) {
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
              .dest(param)
              .lhs1(thisVar)
              .fieldName(param.name())
              .line(nodeLine(ccd))
              .column(nodeColumn(ccd))
              .build());
    }
    finishFunction(b, nodeLine(ccd));
  }

  /**
   * Translate an annotation type's member.
   *
   * <p><b>Why this is not {@code translateCallable}.</b> An {@code AnnotationMemberDeclaration} is
   * not a {@code CallableDeclaration} in the AST -- it has no parameter list and no body, only a
   * type, a name and an optional default value. So it needs its own lowering.
   *
   * <p><b>What the lowering models.</b> The member is a method that returns its default value. The
   * default is the value an annotation processor sees when the member is not specified at the use
   * site, so it IS the member's data flow: if a default is a tainted value, the member carries that
   * taint. Modelling it as {@code RETURN <default>} keeps that visible to the analysis.
   *
   * <p>A member with no default returns the type's zero value, which the TAC has no representation
   * for; the member is emitted with an empty body and the default type, and no value is invented.
   */
  private void translateAnnotationMember(
      com.github.javaparser.ast.body.AnnotationDeclaration owner,
      com.github.javaparser.ast.body.AnnotationMemberDeclaration amd,
      String qualified,
      List<String> classAnns,
      String enclosingClass,
      String prefix,
      String classShortName) {
    TacType mappedRet = TypeMapper.map(amd.getType());
    TacBuilder.FunctionSpec spec =
        new TacBuilder.FunctionSpec(
            qualified, signatureOf(qualified, List.of(), amd.getType()), mappedRet);
    TacBuilder b = newBuilder(spec, List.of());
    if (enclosingClass != null) b.setEnclosingClass(enclosingClass);
    b.addClassAnnotations(classAnns);
    b.addMethodAnnotations(extractAnnotationNames(owner.getAnnotations()));

    if (amd.getDefaultValue().isPresent()) {
      // The default value is the member's data flow: lower it and RETURN it.
      com.github.javaparser.ast.expr.Expression def = amd.getDefaultValue().get();
      ExpressionResult r = exprTranslator.translate(def, b);
      TacOperand.TacVar ret = b.newTemp(mappedRet);
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.MOVE)
              .dest(ret)
              .lhs1(r.operand())
              .line(nodeLine(amd))
              .column(nodeColumn(amd))
              .build());
      // The returned value goes in lhs1: that is the TAC's convention for
      // RETURN. A RETURN with the value in dest is invisible to the dataflow
      // engine, which reads the returned value from lhs1.
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.RETURN)
              .lhs1(ret)
              .line(nodeLine(amd))
              .column(nodeColumn(amd))
              .build());
    } else {
      // No default: the member has no value to return. Emit nothing rather than
      // inventing a zero value -- a fabricated value could look like real data flow.
      b.emit(TacInstruction.builder(TacInstruction.Kind.NOP).line(nodeLine(amd)).build());
    }
    finishFunction(b, nodeLine(amd));
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
   * Extract the VALUES that annotations carry, keyed by the annotation's simple name.
   *
   * <p><b>Why the names are not enough.</b> {@code @Value("${app.password}")} and
   * {@code @Value("${app.username}")} are the same annotation by name and completely different
   * facts. The name channel answers "is this annotated with Value?"; the value channel answers
   * "which property does it bind to?", and that second question is the one a consumer of the IR
   * actually needs when the annotation value is a configuration placeholder or an authorization
   * expression.
   *
   * <p><b>What each annotation shape contributes.</b>
   *
   * <ul>
   *   <li>{@code @Marker} -- no value, contributes nothing.
   *   <li>{@code @Single("v")} -- contributes {@code "v"}.
   *   <li>{@code @Normal(a = "v", b = 1)} -- contributes {@code "v"} and {@code "1"}. The member
   *       NAME is not part of the value: the map is keyed by annotation name, and the values are
   *       what the annotation says. When the same annotation appears with different member names,
   *       the values are collected together -- a consumer that needs to distinguish them should
   *       read the source, which the IR's source map still points at.
   *   <li>{@code @Normal(a = {"x", "y"})} -- an array value contributes each element.
   *   <li>{@code @Outer(@Inner("z"))} -- a nested annotation contributes its own values under the
   *       OUTER annotation's name, so the value does not get lost behind a key nothing looks up.
   * </ul>
   *
   * <p><b>Directional rule.</b> These are METADATA read from source text, not runtime observations.
   * They may enable a checker to suppress a false positive or recognise a role. They must never be
   * used to prove the program is safe.
   *
   * @param anns the annotation list to read
   * @return annotation simple name -&gt; the values it carries, never null
   */
  private static Map<String, List<String>> extractAnnotationValues(NodeList<AnnotationExpr> anns) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    if (anns == null || anns.isEmpty()) return out;
    for (AnnotationExpr a : anns) {
      String name = simpleAnnotationName(a.getNameAsString());
      if (name.isEmpty()) continue;
      List<String> values = out.computeIfAbsent(name, k -> new ArrayList<>());
      collectAnnotationValue(a, values);
    }
    // An annotation with no value contributes an empty list, not a missing key. The key's
    // presence is itself information: "@Marker is present" vs "@Marker is absent".
    return out;
  }

  /**
   * The annotation's simple name: strip the {@code @} and the package prefix.
   *
   * @param raw the name as JavaParser reports it
   * @return the simple name, never null
   */
  private static String simpleAnnotationName(String raw) {
    if (raw == null) return "";
    String n = raw;
    if (n.startsWith("@")) n = n.substring(1);
    int dot = n.lastIndexOf('.');
    if (dot >= 0) n = n.substring(dot + 1);
    return n;
  }

  /**
   * Collect one annotation's values into a list.
   *
   * @param annotation the annotation to read
   * @param out the list to append to
   */
  private static void collectAnnotationValue(AnnotationExpr annotation, List<String> out) {
    if (annotation instanceof MarkerAnnotationExpr) {
      // A marker carries no value.
      return;
    }
    if (annotation instanceof SingleMemberAnnotationExpr single) {
      collectAnnotationExpressionValue(single.getMemberValue(), out);
      return;
    }
    if (annotation instanceof NormalAnnotationExpr normal) {
      for (MemberValuePair pair : normal.getPairs()) {
        collectAnnotationExpressionValue(pair.getValue(), out);
      }
      return;
    }
    // An annotation shape we do not know: record its textual form so the key exists and a
    // consumer can tell that a value was present but not statically resolved.
    String text = annotation.toString();
    if (text != null && !text.isEmpty()) out.add(text);
  }

  /**
   * Collect the value(s) of an annotation member's expression.
   *
   * @param expr the member's value expression
   * @param out the list to append to
   */
  private static void collectAnnotationExpressionValue(Expression expr, List<String> out) {
    if (expr == null) return;
    if (expr instanceof StringLiteralExpr sle) {
      out.add(sle.getValue());
      return;
    }
    if (expr instanceof com.github.javaparser.ast.expr.TextBlockLiteralExpr tble) {
      out.add(tble.getValue());
      return;
    }
    if (expr instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr aie) {
      for (Expression element : aie.getValues()) {
        collectAnnotationExpressionValue(element, out);
      }
      return;
    }
    if (expr instanceof AnnotationExpr nested) {
      // A nested annotation: its values belong to the OUTER annotation as far as the consumer is
      // concerned, because the consumer looks up the outer annotation's name. The nested
      // annotation's own name is not lost -- the nested annotation also appears in the name
      // channel when it is a declaration-site annotation.
      collectAnnotationValue(nested, out);
      return;
    }
    // A non-string constant: a number, a boolean, a char, an enum constant, a class literal.
    // The textual form is the value. It is not quoted, so a consumer can tell it apart from a
    // string literal.
    String text = expr.toString();
    if (text != null && !text.isEmpty()) out.add(text);
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
   * The annotation VALUES of the type currently being translated.
   *
   * <p>Reset for every type declaration. See the assignment in {@code translateTypeWithPrefix} for
   * why this is a field rather than a parameter.
   */
  private Map<String, List<String>> currentClassAnnotationValues = Map.of();

  /**
   * Allocate the next program-unique lambda name.
   *
   * @return a name of the form {@code lambda$N}
   */
  String nextLambdaName() {
    return "lambda$" + (lambdaCounter++);
  }

  private TacBuilder newBuilder(TacBuilder.FunctionSpec spec, List<TacOperand.TacVar> params) {
    TacBuilder b = new TacBuilder(spec, params);
    // Every function this translator produces belongs to the same module: the one the
    // source file came from. It is what lets a single-module analysis report this
    // module's findings and only those, while the other modules are still read so
    // the call graph keeps crossing the boundary.
    b.setModule(module);
    return b;
  }

  /**
   * Emits the members Java gives an enum for free.
   *
   * <p>An enum declaration {@code enum Color { RED, GREEN }} has:
   *
   * <ul>
   *   <li>one <b>constant</b> per declared name ({@code RED}, {@code GREEN}) -- a static, final
   *       instance of the enum;
   *   <li>a static {@code values()} that returns them all;
   *   <li>a static {@code valueOf(String)} that looks one up by name.
   * </ul>
   *
   * <p><b>Why the constants matter.</b> {@code Color.RED} is a real reference. Code can store it in
   * a field, return it, pass it to a method. Without the constant existing in the IR, a reference
   * to it resolves to nothing and any taint flowing through an enum-typed value is lost. An enum
   * that declares no methods is the common case, and it would otherwise contribute <i>nothing at
   * all</i> to the program.
   *
   * <p><b>Why the constants are synthetic.</b> The compiler generates them. Nobody wrote {@code
   * RED}, so a rule that reports an unused member must not report it.
   *
   * <p><b>Why {@code values()} and {@code valueOf()} are modelled.</b> They are the only way to
   * enumerate the constants, and {@code valueOf(String)} takes an untrusted string -- a route into
   * the enum that a dataflow rule should be able to see. They are emitted as functions with the
   * constant's own type, so a call to them is visible in the call graph.
   */
  private void synthesizeEnumMembers(
      com.github.javaparser.ast.body.EnumDeclaration en,
      String prefix,
      String classShortName,
      List<String> classAnns,
      String enclosingClassName) {

    String enumName = en.getNameAsString();
    String qualifiedPrefix = prefix.isEmpty() ? "" : prefix + ".";
    TacType enumType = TacType.ofClass(enumName);

    // ---- the constants ----
    for (com.github.javaparser.ast.body.EnumConstantDeclaration ec : en.getEntries()) {
      String constName = ec.getNameAsString();
      String fnName = qualifiedPrefix + constName;
      TacBuilder.FunctionSpec spec = new TacBuilder.FunctionSpec(fnName, fnName + "()", enumType);
      TacBuilder b = newBuilder(spec, List.of());
      b.setEnclosingClass(enclosingClassName);
      b.addClassAnnotations(classAnns);
      // The constant is compiler-generated.
      b.markSynthetic();
      b.markGenerated(true);
      // The constant's own value is the constant. There is nothing further to model: it
      // carries no taint of its own, because a constant in an enum declaration is a
      // literal.
      b.emit(TacInstruction.builder(TacInstruction.Kind.NOP).line(nodeLine(ec)).build());
      finishFunction(b, nodeLine(ec));
    }

    // ---- values() ----
    // Returns an array of the enum's constants. Modelled as a NEWARRAY of the enum type:
    // the array's contents are the constants, and a caller that iterates them reaches
    // every constant.
    {
      String fnName = qualifiedPrefix + "values";
      TacBuilder.FunctionSpec spec =
          new TacBuilder.FunctionSpec(fnName, fnName + "()", TacType.UNKNOWN);
      TacBuilder b = newBuilder(spec, List.of());
      b.setEnclosingClass(enclosingClassName);
      b.addClassAnnotations(classAnns);
      b.markSynthetic();
      b.markGenerated(true);
      TacOperand.TacVar arr = b.newTemp(TacType.arrayOf(enumType));
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.NEWARRAY)
              .dest(arr)
              .ctorType(TacType.arrayOf(enumType))
              .line(nodeLine(en))
              .build());
      // The returned array goes in lhs1: that is the TAC's convention for
      // RETURN. A RETURN with the value in dest is invisible to the
      // dataflow engine, which reads the returned value from lhs1.
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.RETURN).lhs1(arr).line(nodeLine(en)).build());
      finishFunction(b, nodeLine(en));
    }

    // ---- valueOf(String) ----
    // The one route by which an untrusted string becomes an enum constant. The parameter
    // carries the taint, and the returned constant carries it onward -- which is exactly
    // the flow a dataflow rule needs to see.
    {
      String fnName = qualifiedPrefix + "valueOf";
      TacBuilder.FunctionSpec spec =
          new TacBuilder.FunctionSpec(fnName, fnName + "(String)", enumType);
      TacBuilder b =
          newBuilder(spec, List.of(new TacOperand.TacVar("name", TacType.STRING, false)));
      b.setEnclosingClass(enclosingClassName);
      b.addClassAnnotations(classAnns);
      b.markSynthetic();
      b.markGenerated(true);
      b.emit(TacInstruction.builder(TacInstruction.Kind.NOP).line(nodeLine(en)).build());
      finishFunction(b, nodeLine(en));
    }
  }

  /**
   * Emits the members Java gives a record for free.
   *
   * <p>A record declaration {@code record Point(int x, int y) {}} has:
   *
   * <ul>
   *   <li>a <b>canonical constructor</b> that assigns each component to its backing field;
   *   <li>one <b>accessor</b> per component ({@code x()} for the component {@code x}).
   * </ul>
   *
   * <p>Both are modelled, because they are where a record's data enters and leaves:
   *
   * <ul>
   *   <li>The constructor is where a component's taint enters the record. A record built from
   *       tainted input is a tainted record, and without the constructor the field assignment would
   *       simply not exist.
   *   <li>The accessor is where it leaves. {@code point.x()} hands the field's value to whatever
   *       the caller does with it, and without the accessor the read would not exist.
   * </ul>
   *
   * <p>Both are marked <b>synthetic</b>: the compiler generated them, so a rule that reports an
   * unused method must not report them.
   *
   * <p>An explicitly declared canonical constructor wins over the synthesized one, because a
   * program that writes it out means it. Same for an explicitly declared accessor.
   */
  private void synthesizeRecordMembers(
      com.github.javaparser.ast.body.RecordDeclaration rd,
      String prefix,
      String classShortName,
      List<String> classAnns,
      String enclosingClassName) {

    List<com.github.javaparser.ast.body.Parameter> components = rd.getParameters();
    if (components.isEmpty()) return;

    // The naming convention is the SAME one the member loop uses for an ordinary class:
    // a top-level type's methods are named `m(...)` and its constructor `<init>(...)`,
    // with the type name appearing only for a NESTED type (via the prefix).
    //
    // This matters for more than tidiness. The synthesized member and an explicitly
    // declared one must land on the SAME name, or a call site resolves to only one of
    // them and the other becomes dead code -- and, worse, a dataflow rule that looks
    // the callee up by signature would find the explicit member but not the
    // synthesized one, or the reverse.
    String qualifiedPrefix = prefix.isEmpty() ? "" : prefix + ".";

    // ---- the canonical constructor ----
    boolean hasExplicitCtor = false;
    java.util.Set<String> hasExplicitAccessor = new java.util.HashSet<>();
    for (BodyDeclaration<?> member : rd.getMembers()) {
      if (member instanceof ConstructorDeclaration
          || member instanceof com.github.javaparser.ast.body.CompactConstructorDeclaration) {
        hasExplicitCtor = true;
      } else if (member instanceof MethodDeclaration md) {
        // An explicitly declared accessor wins over the synthesized one.
        if (md.getParameters().isEmpty() && md.getType() != null) {
          for (com.github.javaparser.ast.body.Parameter component : components) {
            if (component.getNameAsString().equals(md.getNameAsString())) {
              hasExplicitAccessor.add(md.getNameAsString());
            }
          }
        }
      }
    }

    if (!hasExplicitCtor) {
      // Build the parameter list FIRST: the builder takes it by reference, so a list
      // that is filled afterwards would leave the function with no parameters.
      List<TacOperand.TacVar> ctorParams = new ArrayList<>();
      for (com.github.javaparser.ast.body.Parameter component : components) {
        ctorParams.add(
            new TacOperand.TacVar(
                component.getNameAsString(), TypeMapper.map(component.getType()), false));
      }
      // The signature's owner is the type's own name, matching what the member loop
      // produces for an EXPLICIT constructor (`signatureOf(td.getNameAsString(), ...)`).
      // The synthesized and the explicit form must agree, or a call site resolves to
      // only one of them.
      String ctorSig = signatureOf(classShortName, componentParams(components), null);
      String qualifiedCtor = qualifiedPrefix + "<init>";

      TacBuilder b =
          newBuilder(new TacBuilder.FunctionSpec(qualifiedCtor, ctorSig, TacType.VOID), ctorParams);
      b.addClassAnnotations(classAnns);
      b.setEnclosingClass(enclosingClassName);
      b.markSynthetic();
      b.markGenerated(true);

      // Each component is assigned to its backing field. The FIELDSTORE is what makes
      // the component's taint visible to every field-taint rule.
      TacOperand.TacVar thisVar =
          new TacOperand.TacVar("this", TacType.ofClass(classShortName), false);
      b.addLocal(thisVar);
      for (TacOperand.TacVar param : ctorParams) {
        b.emit(
            TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
                .lhs1(thisVar)
                .fieldName(param.name())
                .dest(param)
                .line(-1)
                .build());
      }
      b.emit(TacInstruction.builder(TacInstruction.Kind.RETURN).build());
      finishFunction(b, TacTranslator.nodeLine(rd));
    }

    // ---- the accessors ----
    for (com.github.javaparser.ast.body.Parameter component : components) {
      String accessorName = component.getNameAsString();
      if (hasExplicitAccessor.contains(accessorName)) continue;
      TacType componentType = TypeMapper.map(component.getType());
      String qualified = qualifiedPrefix + accessorName;

      TacBuilder b =
          newBuilder(
              new TacBuilder.FunctionSpec(
                  qualified, qualified + "():" + component.getType().asString(), componentType),
              List.of());
      b.addClassAnnotations(classAnns);
      b.setEnclosingClass(enclosingClassName);
      b.markSynthetic();
      b.markGenerated(true);

      // The accessor reads its backing field and returns it.
      //
      // The returned VALUE goes in lhs1, not dest: that is the TAC's
      // convention for RETURN (see TacInstruction's toString, which renders
      // `return <lhs1>`). Putting it in dest made the return value invisible
      // to the dataflow engine -- the engine reads the returned value from
      // lhs1 -- so a record whose component held attacker-controlled data
      // reported a clean program when the component was read back through
      // the accessor.
      TacOperand.TacVar thisVar =
          new TacOperand.TacVar("this", TacType.ofClass(classShortName), false);
      b.addLocal(thisVar);
      TacOperand.TacVar fieldValue = b.newTemp(componentType);
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
              .dest(fieldValue)
              .lhs1(thisVar)
              .fieldName(accessorName)
              .line(-1)
              .build());
      b.emit(TacInstruction.builder(TacInstruction.Kind.RETURN).lhs1(fieldValue).line(-1).build());
      finishFunction(b, TacTranslator.nodeLine(rd));
    }
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
    // Promote the constructs this function could not lower into program-level problems.
    //
    // WHY THIS PROMOTION EXISTS. The translator's fallback for an unrecognised construct is a
    // null value, which silently corrupts the data flow: a tainted value flowing through an
    // unsupported construct becomes null and every downstream fact about it disappears. Nothing
    // throws, and at the default log level nothing is printed -- the only symptom is a missing
    // finding, which is indistinguishable from "the code is safe".
    //
    // So the gap is recorded on the IR itself. A caller can then see exactly which constructs the
    // analyser could not model, and judge the completeness of the result instead of assuming it.
    for (TacProgram.FunctionProblem p : b.problems()) {
      program.addProgramProblem(p);
    }
    for (int i = 0; i < fn.instructions().size(); i++) {
      TacInstruction ins = fn.instructionAt(i);
      if (ins.line > 0) {
        program.sourceMap().record(fn.signature(), i, ins.line, ins.column);
      } else if (lineHint > 0) {
        program.sourceMap().record(fn.signature(), i, lineHint, -1);
      }
    }
  }

  /**
   * The record's components as ordinary parameters, so the canonical constructor's signature can be
   * built with the same {@link #signatureOf} the member loop uses.
   */
  private static List<com.github.javaparser.ast.body.Parameter> componentParams(
      List<com.github.javaparser.ast.body.Parameter> components) {
    return components;
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

  /**
   * The parameter types of the overload a call selects, as the resolved declaration names them.
   *
   * <p>One entry per parameter, written the way a function signature writes its parameter list
   * ({@code "java.lang.String"}, {@code "int"}), so a consumer compares the list against the
   * parameter list of a signature directly. The bytecode lane writes the descriptor's own names and
   * this writes the resolved type's qualified name; both spell {@code java.lang.String} the same
   * way, so a consumer that compares the two lists compares their last segment.
   *
   * <p>{@code null} when the declaration could not be resolved, and {@code null} as well when one
   * of the parameter types did not resolve: an unknown type is not "a parameter of type Object",
   * and a list that guesses would make a consumer resolve the call to the WRONG overload. A class
   * declared in the scanned source is not on the reflection type solver's classpath, so resolving
   * it throws and the call keeps naming the method and its arity, which is what the catalogue
   * matches on.
   */
  public static List<String> calleeParamTypesOf(
      List<com.github.javaparser.resolution.types.ResolvedType> paramTypes) {
    if (paramTypes == null) return null;
    List<String> out = new ArrayList<>(paramTypes.size());
    for (com.github.javaparser.resolution.types.ResolvedType pt : paramTypes) {
      TacType mapped = TypeMapper.mapResolved(pt);
      if (mapped == null || mapped == TacType.UNKNOWN) return null;
      out.add(mapped.displayName());
    }
    return out;
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
