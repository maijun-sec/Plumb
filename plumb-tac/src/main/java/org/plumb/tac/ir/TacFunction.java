package org.plumb.tac.ir;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// IR-serializable: this class participates in the disk-cache contract.
// Instances are written via IrSerializer (ObjectOutputStream) and read
// back via IrSerializer.read(...). Field additions are backwards
// compatible; renaming or removing fields needs a serialVersionUID bump.
/**
 * One method (or constructor / static initializer) lowered to TAC.
 * A function owns its {@link TacInstruction} stream in source order and
 * the {@link TacVar} parameters / locals.
 *
 * <p>The instruction list is the linear stream emitted by the AST
 * translator. The CFG builder partitions it into basic blocks; the
 * DFA engine reads the CFG.</p>
 *
 * <p><b>Annotation metadata.</b> {@code TacFunction} also
 * carries three lists of annotation names: the
 * <em>class-level</em> annotations (e.g. {@code @RestController},
 * {@code @Configuration}), the <em>method-level</em> annotations
 * (e.g. {@code @PreAuthorize}, {@code @RequestMapping}), a
 * map of <em>field-level</em> annotations
 * ({@code fieldName -> [annotation, ...]}, e.g.
 * {@code password -> [@Value("${app.password}")]), and a map of
 * <em>parameter-level</em> annotations
 * ({@code paramName -> [annotation, ...]}, e.g.
 * {@code id -> [@RequestParam, @Valid]}). These lists are filled
 * by the translator from the JavaParser AST and are consulted by
 * class-aware checkers (MISSING_AUTHZ, MISSING_INPUT_VALIDATION,
 * @Value suppression, servlet entry-point classification, etc.) to
 * make decisions the data-flow engine alone can't make.</p>
 *
 * <p>The annotation names are stored as their simple form
 * (e.g. {@code "PreAuthorize"} rather than
 * {@code "org.springframework.security.access.prepost.PreAuthorize"}).
 * The simple form is what most catalog-style patterns need
 * (e.g. "matches if the class has any of @RestController,
 * @Controller, @Service, @Component"). A small set of aliases
 * is also captured when the source uses the fully-qualified
 * name; the deduplication happens at fill time.</p>
 */
public final class TacFunction implements java.io.Serializable {
  private static final long serialVersionUID = 1L;

  private final String name;
  private final String signature;
  private final TacType returnType;
  private final List<TacOperand.TacVar> params;
  private final List<TacOperand.TacVar> locals;
  private final List<TacInstruction> instructions;
  // Enclosing class short name (e.g. "MyThread"). Filled
  // by the translator; null for functions emitted outside of
  // a type declaration (synthetic <clinit>, lambdas whose
  // scope didn't carry a class). Distinguishes two
  // same-named methods on different classes, so the merged
  // program can keep both instead of collapsing them into one
  // last-writer-wins entry.
  private final String enclosingClass;
  // Where this function's body came from. Default
  // SOURCE for everything the AST translator emits; the
  // bytecode front-end sets LIBRARY for class-file input
  // and SYSTEM_LIBRARY for explicit JDK stubs. The DFA
  // engine consults this along with
  // {@code TuningConfig.analysedOrigins} to decide whether
  // to run a checker on this function.
  private final SourceOrigin origin;
  // Annotation metadata. Class / method annotations are
  // captured as a flat list of simple names; field annotations
  // are a name-keyed map; parameter annotations use the same
  // shape — a name-keyed map indexed by parameter
  // name. The four annotation channels (class / method / field
  // / parameter) collectively let a checker ask "is X annotated
  // with Y?" at any granularity the IR exposes.
  private final List<String> classAnnotations;
  private final List<String> methodAnnotations;
  private final Map<String, List<String>> fieldAnnotations;
  private final Map<String, List<String>> paramAnnotations;
  // Try / catch / finally regions. Each region carries the
  // range of instruction indices that the try block covers, the
  // range covered by the catch handlers (catches; their rethrow
  // exits are *not* in this range — the rethrow jumps straight to
  // the finally region via the exception-flow edges), and the
  // optional finally-block range. The labels of the catch-handler
  // entry points and the finally-block entry point are recorded
  // so the CFG builder can wire up exception-flow edges: any block
  // inside the try range (or catch range) gets extra successors
  // pointing to the matching catch handlers and the finally
  // block. A block inside a catch range also has a normal
  // successor into the finally block (catch handler fall-through
  // after handling). Used by the DFA engine to walk exception
  // paths so taint / null facts propagate through catch handlers
  // instead of being silently dropped when a throw fires.
  private final List<TryRegion> tryRegions;

  public TacFunction(
      String name,
      String signature,
      TacType returnType,
      List<TacOperand.TacVar> params,
      List<TacOperand.TacVar> locals,
      List<TacInstruction> instructions) {
    this(
        name,
        signature,
        returnType,
        params,
        locals,
        instructions,
        List.<String>of(),
        List.<String>of(),
        Map.of(),
        Map.of(),
        null,
        SourceOrigin.SOURCE,
        List.<TryRegion>of());
  }

  public TacFunction(
      String name,
      String signature,
      TacType returnType,
      List<TacOperand.TacVar> params,
      List<TacOperand.TacVar> locals,
      List<TacInstruction> instructions,
      List<String> classAnnotations,
      List<String> methodAnnotations,
      Map<String, List<String>> fieldAnnotations) {
    this(
        name,
        signature,
        returnType,
        params,
        locals,
        instructions,
        classAnnotations,
        methodAnnotations,
        fieldAnnotations,
        Map.of(),
        null,
        SourceOrigin.SOURCE,
        List.<TryRegion>of());
  }

  public TacFunction(
      String name,
      String signature,
      TacType returnType,
      List<TacOperand.TacVar> params,
      List<TacOperand.TacVar> locals,
      List<TacInstruction> instructions,
      List<String> classAnnotations,
      List<String> methodAnnotations,
      Map<String, List<String>> fieldAnnotations,
      Map<String, List<String>> paramAnnotations) {
    this(
        name,
        signature,
        returnType,
        params,
        locals,
        instructions,
        classAnnotations,
        methodAnnotations,
        fieldAnnotations,
        paramAnnotations,
        null,
        SourceOrigin.SOURCE,
        List.<TryRegion>of());
  }

  /**
   * Full constructor. Adds the {@code enclosingClass} parameter that lets the merged program
   * distinguish two same-named methods on different classes.
   */
  public TacFunction(
      String name,
      String signature,
      TacType returnType,
      List<TacOperand.TacVar> params,
      List<TacOperand.TacVar> locals,
      List<TacInstruction> instructions,
      List<String> classAnnotations,
      List<String> methodAnnotations,
      Map<String, List<String>> fieldAnnotations,
      Map<String, List<String>> paramAnnotations,
      String enclosingClass) {
    this(
        name,
        signature,
        returnType,
        params,
        locals,
        instructions,
        classAnnotations,
        methodAnnotations,
        fieldAnnotations,
        paramAnnotations,
        enclosingClass,
        SourceOrigin.SOURCE,
        List.of());
  }

  /**
   * Full constructor. Adds the {@code origin} parameter that distinguishes user-source code from
   * user-supplied library code from JDK runtime code. The DFA engine consults this together with
   * {@code TuningConfig.analysedOrigins} to decide whether to run a checker on this function.
   */
  public TacFunction(
      String name,
      String signature,
      TacType returnType,
      List<TacOperand.TacVar> params,
      List<TacOperand.TacVar> locals,
      List<TacInstruction> instructions,
      List<String> classAnnotations,
      List<String> methodAnnotations,
      Map<String, List<String>> fieldAnnotations,
      Map<String, List<String>> paramAnnotations,
      String enclosingClass,
      SourceOrigin origin) {
    this(
        name,
        signature,
        returnType,
        params,
        locals,
        instructions,
        classAnnotations,
        methodAnnotations,
        fieldAnnotations,
        paramAnnotations,
        enclosingClass,
        origin,
        List.of());
  }

  /**
   * Full constructor. Adds the {@code tryRegions} parameter that records the try / catch / finally
   * structure so the CFG builder can wire up exception-flow edges. Each region covers the try-block
   * range, the catch-handlers range, the catch-handler entry labels, and the optional finally-block
   * range + entry label. See {@link TryRegion} for the semantics of each field.
   */
  public TacFunction(
      String name,
      String signature,
      TacType returnType,
      List<TacOperand.TacVar> params,
      List<TacOperand.TacVar> locals,
      List<TacInstruction> instructions,
      List<String> classAnnotations,
      List<String> methodAnnotations,
      Map<String, List<String>> fieldAnnotations,
      Map<String, List<String>> paramAnnotations,
      String enclosingClass,
      SourceOrigin origin,
      List<TryRegion> tryRegions) {
    this.name = Objects.requireNonNull(name);
    this.signature = signature == null ? name : signature;
    this.returnType = returnType == null ? TacType.VOID : returnType;
    this.params = List.copyOf(params);
    this.locals = List.copyOf(locals);
    this.instructions = List.copyOf(instructions);
    this.classAnnotations = List.copyOf(classAnnotations);
    this.methodAnnotations = List.copyOf(methodAnnotations);
    this.fieldAnnotations =
        fieldAnnotations == null ? Map.of() : Collections.unmodifiableMap(fieldAnnotations);
    this.paramAnnotations =
        paramAnnotations == null ? Map.of() : Collections.unmodifiableMap(paramAnnotations);
    this.enclosingClass = enclosingClass;
    this.origin = origin == null ? SourceOrigin.SOURCE : origin;
    this.tryRegions = tryRegions == null ? List.of() : List.copyOf(tryRegions);
  }

  public String name() {
    return name;
  }

  public String signature() {
    return signature;
  }

  public TacType returnType() {
    return returnType;
  }

  public List<TacOperand.TacVar> params() {
    return params;
  }

  public List<TacOperand.TacVar> locals() {
    return locals;
  }

  public List<TacInstruction> instructions() {
    return instructions;
  }

  /** Class-level annotation names (simple form), e.g. {@code RestController}. */
  public List<String> classAnnotations() {
    return classAnnotations;
  }

  /** Method-level annotation names, e.g. {@code PreAuthorize}. */
  public List<String> methodAnnotations() {
    return methodAnnotations;
  }

  /**
   * Field-level annotation map. The key is the field name as it appears in {@code
   * FIELDSTORE.fieldName}; the value is the list of annotation simple names on that field. Empty
   * map if no field annotations were captured.
   */
  public Map<String, List<String>> fieldAnnotations() {
    return fieldAnnotations;
  }

  /**
   * Parameter-level annotation map. The key is the parameter name as it appears in {@code
   * params()}; the value is the list of annotation simple names on that parameter (e.g. {@code id
   * -> [RequestParam, Valid]}). Empty map if no parameter annotations were captured.
   */
  public Map<String, List<String>> paramAnnotations() {
    return paramAnnotations;
  }

  /**
   * Short name of the enclosing class (e.g. {@code "MyThread"}) for the function's declaring type,
   * or {@code null} if the function was emitted outside of a type declaration (synthetic {@code
   * <clinit>}, free functions, …). Distinguishes two same-named methods on different classes, so
   * the merged program can keep both instead of collapsing them into one entry.
   */
  public String enclosingClass() {
    return enclosingClass;
  }

  /**
   * Where this function's body came from (user source, user-supplied library, or JDK runtime).
   * Default {@link SourceOrigin#SOURCE} for code emitted by the AST translator; the bytecode
   * front-end overrides this for class-file input. The DFA engine consults this together with
   * {@code TuningConfig.analysedOrigins} to decide whether to run a checker on this function.
   */
  public SourceOrigin origin() {
    return origin;
  }

  /**
   * Convenience ? is this function's origin in the given set? Returns true for an empty / null set
   * so callers that don't filter by origin get all functions (matches the earlier behavior).
   */
  public boolean hasOriginIn(java.util.Set<SourceOrigin> allowed) {
    if (allowed == null || allowed.isEmpty()) return true;
    return allowed.contains(this.origin);
  }

  public int instructionCount() {
    return instructions.size();
  }

  public TacInstruction instructionAt(int i) {
    return instructions.get(i);
  }

  /**
   * Try / catch / finally regions emitted by the translator / bytecode front-end. Empty list for
   * functions that contain no try statements. The CFG builder reads this list to wire up
   * exception-flow edges.
   */
  public List<TryRegion> tryRegions() {
    return tryRegions;
  }

  /** Human-readable one-liner used by {@code toString} and printer. */
  public String displayName() {
    return signature;
  }

  @Override
  public String toString() {
    return "function " + signature + " (" + instructionCount() + " insns)";
  }

  /**
   * A single try / catch / finally region within a {@link TacFunction}. Records the instruction
   * index ranges that the try body and catch handlers cover (inclusive of their terminators), the
   * labels of the catch-handler entry blocks (so the CFG builder can wire exception-flow edges to
   * them), and the optional finally-block range + entry label.
   *
   * <p>Indices are into the parent function's {@code instructions()} list. The ranges are
   * <em>inclusive on both ends</em> and match the boundaries of the try / catch bodies in source
   * order (e.g. for a try { a; b; } the {@code tryStart} is the index of {@code a} and {@code
   * tryEnd} is the index of {@code b}; for catch (E e) { c; d; } the {@code catchStart} is the
   * index of {@code c} and {@code catchEnd} is the index of {@code d}).
   *
   * <p>Re-throws inside a catch handler are NOT in the {@code catch} range: a {@code THROW} inside
   * a catch handler jumps straight to the finally block (or escapes the try if no finally). The CFG
   * builder handles that via the {@code THROW} instruction's own exception-flow rules ? not via
   * this region's range.
   */
  public static final class TryRegion {
    /** Index of the first instruction inside the try body (inclusive). */
    public final int tryStart;

    /** Index of the last instruction inside the try body (inclusive). */
    public final int tryEnd;

    /**
     * Index of the first instruction of the first catch handler (inclusive), or {@code -1} if there
     * are no catch clauses.
     */
    public final int catchStart;

    /**
     * Index of the last instruction of the last catch handler (inclusive), or {@code -1} if there
     * are no catch clauses.
     */
    public final int catchEnd;

    /**
     * Labels of the catch-handler entry blocks in source order (e.g. {@code "L_CATCH_IOException"},
     * {@code "L_CATCH_NullPointerException"}). The CFG builder resolves each label to the
     * corresponding block id when wiring exception-flow edges. Empty list for a try with no catches
     * (only a finally block).
     */
    public final List<String> catchLabels;

    /**
     * Label of the finally-block entry block (e.g. {@code "L_FINALLY"}), or {@code null} if the try
     * statement has no finally block. The CFG builder routes every try-region exit (normal try
     * completion, catch completion, and rethrow from a catch) through this block.
     */
    public final String finallyLabel;

    /**
     * Index of the first instruction of the finally block (inclusive), or {@code -1} if there is no
     * finally block.
     */
    public final int finallyStart;

    /**
     * Index of the last instruction of the finally block (inclusive), or {@code -1} if there is no
     * finally block.
     */
    public final int finallyEnd;

    public TryRegion(
        int tryStart,
        int tryEnd,
        int catchStart,
        int catchEnd,
        List<String> catchLabels,
        String finallyLabel,
        int finallyStart,
        int finallyEnd) {
      this.tryStart = tryStart;
      this.tryEnd = tryEnd;
      this.catchStart = catchStart;
      this.catchEnd = catchEnd;
      this.catchLabels = catchLabels == null ? List.of() : List.copyOf(catchLabels);
      this.finallyLabel = finallyLabel;
      this.finallyStart = finallyStart;
      this.finallyEnd = finallyEnd;
    }

    /** {@code true} if this region has a finally block. */
    public boolean hasFinally() {
      return finallyLabel != null;
    }

    /** {@code true} if this region has at least one catch handler. */
    public boolean hasCatch() {
      return catchStart >= 0 && catchEnd >= catchStart;
    }

    @Override
    public String toString() {
      return "TryRegion(try=["
          + tryStart
          + ","
          + tryEnd
          + "]"
          + (hasCatch() ? ", catch=[" + catchStart + "," + catchEnd + "]" : "")
          + (hasFinally() ? ", finally=[" + finallyStart + "," + finallyEnd + "]" : "")
          + ")";
    }
  }
}
