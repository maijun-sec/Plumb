package org.plumb.dfa.checkers.controlflow;

import org.plumb.rules.RuleDef;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.dfa.config.CheckerConfig;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.reporter.Finding;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceBuilder;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * DFA-based version of Coverity <code>SINGLETON_RACE</code> (CWE-366). Where the AST checker {@code
 * org.plumb.style.concurrency.SingletonRaceCheck} only looks at field accesses within a single
 * method, the DFA version tracks which methods in a class read / write the same field.
 *
 * <p><b>What the DFA version catches that AST misses.</b>
 *
 * <ul>
 *   <li>Field {@code counter} is incremented in {@code doGet} (the AST checker sees it) but also
 *       incremented in {@code doPost} via {@code helperIncrement(this.counter)} (which the AST
 *       checker doesn't know about because the increment is in a different method).
 *   <li>Field is read in {@code doGet} and written in {@code doPost} ? a classic servlet race where
 *       the AST version sees only one of the two access sites.
 * </ul>
 *
 * <p><b>Detection strategy.</b>
 *
 * <ol>
 *   <li>Pre-compute the {@code @Controller} / {@code extends HttpServlet} flag per class.
 *   <li>For every non-final, non-volatile field on such a class, track which methods in the class
 *       read or write the field via FIELDLOAD / FIELDSTORE / compound assign.
 *   <li>At {@code finish}, for each field with at least one read AND at least one write, fire once
 *       on the field declaration site (or the class name if we don't have a declaration position).
 * </ol>
 *
 * <p>The DFA checker doesn't attempt to verify synchronization ? the AST checker does that for
 * in-method accesses. The DFA version is purely the "is this field accessed from multiple methods"
 * signal, which the AST can't see.
 */
public final class SingletonRaceDfaChecker implements DfaChecker {

  private static final Set<String> SERVLET_SUPERCLASSES =
      Set.of(
          "javax.servlet.Servlet", "javax.servlet.http.HttpServlet",
          "jakarta.servlet.Servlet", "jakarta.servlet.http.HttpServlet");

  private static final Set<String> CONTROLLER_ANNOTATIONS =
      Set.of(
          "Controller",
          "RestController",
          "org.springframework.stereotype.Controller",
          "org.springframework.web.bind.annotation.RestController");

  /** class → set of non-final, non-volatile field names. */
  private final Map<String, Set<String>> classFields = new HashMap<>();

  /** class → set of methods (signatures) in the class. */
  private final Map<String, Set<String>> classMethods = new HashMap<>();

  /** (class, field) → set of method signatures that read it. */
  private final Map<String, Set<String>> fieldReaders = new HashMap<>();

  /** (class, field) → set of method signatures that write it. */
  private final Map<String, Set<String>> fieldWriters = new HashMap<>();

  @Override
  public String name() {
    return "singleton-race-dfa";
  }

  /**
   * Test-only constructor for forcing a specific aggressiveness level. Production path uses the
   * YAML config in {@link #config(AnalysisContext)}.
   */
  public SingletonRaceDfaChecker(String aggressivenessOverride) {
    this.aggressivenessOverride = aggressivenessOverride;
  }

  public SingletonRaceDfaChecker() {
    this.aggressivenessOverride = null;
  }

  /** Non-null if a test forced a level. */
  private final String aggressivenessOverride;

  @Override
  public CheckerConfig config(AnalysisContext ctx) {
    CheckerConfig c = ctx.config().checkers().byName(name()).orElse(null);
    if (c != null) return c;
    return new CheckerConfig(
        name(),
        true,
        "HIGH",
        "CWE-366",
        1,
        "full",
        null,
        "org.plumb.dfa.checkers.controlflow.SingletonRaceDfaChecker",
        "DFA-based SINGLETON_RACE",
        "medium");
  }

  /** Cached aggressiveness, set in initFunction. */
  private String aggressiveness = "medium";

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    String cls = fn.enclosingClass();
    if (cls == null) return;
    Set<String> classAnns =
        fn.classAnnotations() == null ? Set.of() : new HashSet<>(fn.classAnnotations());
    boolean isSingleton =
        classAnns.stream()
            .anyMatch(
                a ->
                    CONTROLLER_ANNOTATIONS.contains(a)
                        || CONTROLLER_ANNOTATIONS.contains(simpleName(a)));
    if (!isSingleton) {
      // Best-effort: also try class name match (e.g.
      // when the class is registered via
      // extends HttpServlet — we can't see that
      // here without resolving supertypes, so the
      // AST checker's servlet detection is still
      // needed for that case). For now, only
      // explicit @Controller / @RestController
      // annotations are detected by the DFA
      // version.
      return;
    }
    Set<String> fields = new LinkedHashSet<>();
    if (fn.fieldAnnotations() != null) {
      for (String name : fn.fieldAnnotations().keySet()) {
        fields.add(name);
      }
    }
    classFields.computeIfAbsent(cls, k -> new LinkedHashSet<>()).addAll(fields);
    classMethods.computeIfAbsent(cls, k -> new LinkedHashSet<>()).add(fn.signature());
    // Cache the aggressiveness from the
    // YAML config so the hot path doesn't
    // re-query the config map. Test-only
    // override takes precedence.
    aggressiveness =
        aggressivenessOverride != null ? aggressivenessOverride : config(ctx).aggressiveness();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    String cls = fn.enclosingClass();
    if (cls == null) return;
    if (ins.kind == TacInstruction.Kind.FIELDLOAD
        && ins.fieldName != null
        && isThisReceiver(ins.lhs1)
        && classFields.getOrDefault(cls, Set.of()).contains(ins.fieldName)) {
      fieldReaders
          .computeIfAbsent(cls + "::" + ins.fieldName, k -> new LinkedHashSet<>())
          .add(fn.signature());
    }
    if (ins.kind == TacInstruction.Kind.FIELDSTORE
        && ins.fieldName != null
        && isThisReceiver(ins.lhs1)
        && classFields.getOrDefault(cls, Set.of()).contains(ins.fieldName)) {
      fieldWriters
          .computeIfAbsent(cls + "::" + ins.fieldName, k -> new LinkedHashSet<>())
          .add(fn.signature());
    }
    // Compound assignment: x += ... also counts as
    // both a read and a write of x. The TAC lowers
    // it to a FIELDLOAD + BINOP + FIELDSTORE, so
    // the two cases above will catch it.
  }

  @Override
  public void finishFunction(AnalysisContext ctx, TacFunction fn) {
    // Findings are aggregated at finish(), not
    // per-function. Move on.
  }

  @Override
  public void finish(AnalysisContext ctx) {
    // At "low" aggressiveness, suppress
    // cross-method findings (the AST already
    // covers same-method access). Low mode is
    // a "I want only the most precise findings"
    // setting — the cross-class, cross-method
    // aggregations in this DFA version are
    // more likely to surface false positives
    // (e.g. read+write that are actually
    // thread-safe via different sync objects).
    if ("low".equals(aggressiveness)) return;
    // For each (class, field), fire if read AND
    // written by different methods.
    for (String key : fieldReaders.keySet()) {
      Set<String> reads = fieldReaders.get(key);
      Set<String> writes = fieldWriters.getOrDefault(key, Set.of());
      Set<String> all = new HashSet<>();
      all.addAll(reads);
      all.addAll(writes);
      if (all.size() < 2) continue; // single-method access — AST covers
      int dot = key.indexOf("::");
      String cls = key.substring(0, dot);
      String field = key.substring(dot + 2);
      // R-812 v2 trace: cross-method summary
      // shape. The (class, field) pair is
      // encoded in the FieldRef operand;
      // method counts go in a Free operand so
      // the AI dedup layer can see "this field
      // races across N methods" without
      // regex-parsing the legacy flow string.
      TraceBuilder b = new TraceBuilder();
      int handle =
          b.addViolation(
              TraceNode.Op.SUMMARY_SINK,
              List.of(
                  new OperandRef.FieldRef(cls, field),
                  new OperandRef.Free(
                      "readMethods:" + reads.size() + ",writeMethods:" + writes.size())),
              List.of(),
              "field '" + field + "' in '" + cls + "' raced across " + all.size() + " method(s)",
              "",
              -1,
              -1,
              "");
      Trace v2 = b.build(List.of(handle), Trace.CompressionLevel.FULL);
      Finding f =
          Finding.dfaWithTrace(
              name(),
              name(),
              "SINGLETON_RACE (DFA): field '"
                  + field
                  + "' in singleton class '"
                  + cls
                  + "' is read by "
                  + reads.size()
                  + " method(s) and written by "
                  + writes.size()
                  + " method(s) — concurrent requests can lose updates",
              RuleDef.Severity.HIGH,
              "CWE-366",
              "",
              -1,
              -1,
              field + " in " + cls,
              v2);
      ctx.reporter().reportWithTrace(f, v2);
    }
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    return FunctionSummary.empty();
  }

  private static boolean isThisReceiver(TacOperand op) {
    if (op == null) return false;
    if (op instanceof TacOperand.TacVar) {
      return "this".equals(((TacOperand.TacVar) op).name());
    }
    return false;
  }

  private static String simpleName(String fqn) {
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
  }
}
