package org.plumb.tac.ir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// IR-serializable: this class participates in the disk-cache contract.
// Instances are written via IrSerializer (ObjectOutputStream) and read
// back via IrSerializer.read(...). Field additions are backwards
// compatible; renaming or removing fields needs a serialVersionUID bump.
/**
 * Top-level TAC container: one per Java compilation unit. Holds (synthetic) global state and the
 * list of {@link TacFunction}s.
 *
 * <p>For Java, "globals" are essentially {@code static} fields; we keep the door open for them but
 * the current translator does not emit any (Java puts class-level state behind accessor calls,
 * which already go through {@code FIELDLOAD} / {@code FIELDSTORE}).
 */
public final class TacProgram implements java.io.Serializable {
  private static final long serialVersionUID = 1L;

  public record FunctionProblem(
      String functionName,
      String kind, // PARSE_PROBLEM | TRANSLATION_ERROR | UNHANDLED_NODE
      String message,
      String nodeClass,
      int line,
      int column) {
    public FunctionProblem {
      Objects.requireNonNull(kind, "kind");
    }

    @Override
    public String toString() {
      String loc = line > 0 ? " (" + line + ":" + column + ")" : "";
      String fn = (functionName == null || functionName.isEmpty()) ? "<global>" : functionName;
      return "["
          + kind
          + "] "
          + fn
          + loc
          + " : "
          + message
          + (nodeClass == null ? "" : " <" + nodeClass + ">");
    }
  }

  /**
   * {@link Path} does not implement {@link java.io.Serializable} on any JDK we target. We mark the
   * field {@code transient} and use a custom {@link #writeObject} / {@link #readObject} to
   * round-trip the path as a {@link String}. The cache file's <em>name</em> ({@code
   * <sha1-of-path>.tac}) is the source of truth on disk; this field is just the in-memory pointer
   * used for finding per-source outputs.
   *
   * <p>Note: this field is deliberately <b>not</b> {@code final} (despite the rest of the class
   * being effectively immutable): {@code final} + {@code transient} + {@code readObject} is a known
   * JDK-17+ footgun, and the value should round-trip to the same effective path anyway.
   */
  private transient Path sourcePath;

  private final String typeName;
  private final Map<String, TacFunction> functions = new LinkedHashMap<>();
  private final List<FunctionProblem> programProblems = new ArrayList<>();
  private final SourceMap sourceMap = new SourceMap();

  /**
   * Simple class-hierarchy table for the {@link org.plumb.dfa.cg.CallResolver} CHA-style {@code
   * resolveVirtual} walk. Key is the short class name as it appears in {@link
   * TacFunction#enclosingClass()} (e.g. {@code "MyBase"}, {@code "MyDerived"}); value is the
   * immediate parent class's short name (also looked up in this map recursively), or {@code null}
   * for the class at the top of the hierarchy (e.g. {@code Object}). Empty map by default.
   */
  private final Map<String, String> classToParent = new java.util.HashMap<>();

  /**
   * Reverse class-hierarchy table ? for each parent, the list of direct children that {@code
   * extends} it. Maintained alongside {@link #classToParent} inside {@link #registerClassParent} so
   * callers can ask "what classes implement this abstract method?" without scanning the whole
   * program. Keyed by short class name exactly like {@link #classToParent}. Empty by default.
   */
  private final Map<String, List<String>> classToChildren = new java.util.HashMap<>();

  /**
   * Lambda binding table: maps a user-visible var name to the synthetic {@code lambda$N} function
   * signature that the var holds. Filled by the translator when a lambda is assigned to a local /
   * field. The inter-procedural taint engine uses this to follow {@code f.apply(...)} into the
   * lambda body.
   */
  private final Map<String, String> lambdaBindings = new java.util.HashMap<>();

  public TacProgram(Path sourcePath, String typeName) {
    this.sourcePath = sourcePath;
    this.typeName = typeName;
  }

  public Path sourcePath() {
    return sourcePath;
  }

  public String typeName() {
    return typeName;
  }

  public void addFunction(TacFunction fn) {
    functions.put(fn.signature(), fn);
  }

  /**
   * Add a function under a caller-supplied key, bypassing the {@link TacFunction#signature()}-based
   * last-writer-wins. The merged-program view uses this to keep two functions with the same
   * signature but different enclosing classes (e.g. two concrete subclasses of an abstract base,
   * both implementing the abstract {@code action(String)} method) in the same synthetic program.
   * The default {@link #addFunction} collapses them to a single entry, which loses the "bad"
   * variant when the "goodG2B" variant is added second.
   */
  public void addFunctionByKey(String key, TacFunction fn) {
    functions.put(key, fn);
  }

  public TacFunction function(String signature) {
    return functions.get(signature);
  }

  public List<TacFunction> functions() {
    return Collections.unmodifiableList(new ArrayList<>(functions.values()));
  }

  /**
   * Register a binding: the user var {@code varName} holds the function value of the synthetic
   * {@code lambdaSignature}.
   */
  public void bindLambda(String varName, String lambdaSignature) {
    if (varName != null && lambdaSignature != null) {
      lambdaBindings.put(varName, lambdaSignature);
    }
  }

  /**
   * Look up the lambda signature bound to {@code varName}, or {@code null} if the var is not a
   * known lambda.
   */
  public String lambdaFor(String varName) {
    return lambdaBindings.get(varName);
  }

  /** All lambda bindings (for debugging / testing). */
  public Map<String, String> lambdaBindings() {
    return Collections.unmodifiableMap(lambdaBindings);
  }

  public SourceMap sourceMap() {
    return sourceMap;
  }

  /**
   * Register that {@code child} extends (directly) {@code parent}. Both names are short class names
   * as they appear in {@link TacFunction#enclosingClass()}. Calling this twice for the same {@code
   * child} keeps the first call (the first parent declared is the "real" parent; Java doesn't
   * support multiple inheritance of classes).
   *
   * <p>Also maintains {@link #classToChildren} in lock-step so {@link
   * org.plumb.dfa.cg.CallResolver} can answer the reverse question "what classes implement this
   * abstract method?" without scanning the program.
   */
  public void registerClassParent(String child, String parent) {
    if (child == null || parent == null) return;
    if (child.equals(parent)) return;
    if (classToParent.putIfAbsent(child, parent) == null) {
      // First registration of this child — add to
      // the parent's child list. Using a list
      // (not a set) preserves registration order
      // so output is deterministic in tests.
      classToChildren.computeIfAbsent(parent, k -> new java.util.ArrayList<>()).add(child);
    }
  }

  /**
   * The simple class-hierarchy table. See {@link #registerClassParent}. The map is unmodifiable.
   */
  public Map<String, String> classToParent() {
    return java.util.Collections.unmodifiableMap(classToParent);
  }

  /**
   * Reverse class-hierarchy table. Key is the parent short class name, value is the list of direct
   * children (in registration order). The map is unmodifiable. Returns an empty map if no {@link
   * #registerClassParent} call has populated a given parent.
   */
  public Map<String, List<String>> classToChildren() {
    return java.util.Collections.unmodifiableMap(classToChildren);
  }

  public void addProgramProblem(FunctionProblem p) {
    Objects.requireNonNull(p, "FunctionProblem");
    programProblems.add(p);
  }

  public List<FunctionProblem> programProblems() {
    return Collections.unmodifiableList(programProblems);
  }

  public boolean hasProblems() {
    return !programProblems.isEmpty();
  }

  /**
   * Custom {@link java.io.Serializable} hook. The {@code Path} field is {@code transient}; we write
   * its string form (or {@code null}) and read it back via {@link Path#of(String, String...)}.
   * Anything else serialises via the default mechanism.
   */
  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    out.defaultWriteObject();
    out.writeObject(sourcePath == null ? null : sourcePath.toString());
  }

  /**
   * See {@link #writeObject}. Restores {@code sourcePath} from its string form. {@code null} is
   * allowed (e.g. for synthetic programs the translator builds without a real source file) ? the
   * field stays {@code null} in that case and the cache file name is the source of truth on disk.
   */
  private void readObject(java.io.ObjectInputStream in)
      throws java.io.IOException, ClassNotFoundException {
    in.defaultReadObject();
    Object raw = in.readObject();
    if (raw == null) {
      this.sourcePath = null;
      return;
    }
    this.sourcePath = Path.of((String) raw);
  }

  @Override
  public String toString() {
    return "TacProgram(" + typeName + ", " + functions.size() + " fns)";
  }
}
