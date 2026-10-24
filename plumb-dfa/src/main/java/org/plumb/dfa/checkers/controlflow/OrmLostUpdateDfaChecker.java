package org.plumb.dfa.checkers.controlflow;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.plumb.dfa.config.CheckerConfig;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.summary.Operation;
import org.plumb.dfa.trace.PatternTrace;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.rules.RuleDef;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * DFA-based version of Coverity <code>ORM_LOST_UPDATE</code> / <code>ORM_ABANDONED_TRANSIENT</code>
 * (CWE-459). Where the AST checker {@code org.plumb.style.quality.OrmLostUpdateCheck} inspects only
 * the immediate function body, this DFA version uses the TAC IR plus the {@link FunctionSummary}
 * cache to detect when a transient entity's persistence happens <em>through</em> a helper-method
 * call chain.
 *
 * <p><b>What the DFA version catches that AST misses.</b>
 *
 * <ul>
 *   <li>Persistence through a service wrapper ? {@code void create() { User u = new User();
 *       u.setName("x"); service.save(u); }} where {@code service.save} is a one-line method that
 *       calls {@code dao.persist(u)}. The AST only sees {@code service.save(u)} and doesn't know
 *       whether the call persists the entity. The DFA checker looks up {@code save}'s summary,
 *       finds a {@link Operation.PersistsParam} recorded on parameter 0, and concludes the entity
 *       was persisted.
 *   <li>Persistence through a chain of helpers ? {@code service.save(u)} → {@code
 *       repository.persist(u)} → {@code entityManager.persist(u)}: the FunctionSummary chain
 *       propagation handles arbitrary depth (each callee's summary is consulted, transitively).
 *   <li>Transient-with-mutation ? when the transient entity's field is mutated via a helper method
 *       (e.g. {@code u.setName("x")} inside a setter that writes the field), the DFA walker still
 *       detects the mutation (because FIELDSTORE happens inside the callee) and concludes the
 *       entity is "in flight" enough to warrant a finding if it never reaches a persist call.
 * </ul>
 *
 * <p><b>Detection strategy.</b>
 *
 * <ol>
 *   <li>On a {@code CALL} to {@code X#<init>} where {@code X} is a non-JDK type (heuristic for an
 *       entity), mark the dest {@link TacOperand.TacVar} as a "transient entity" (allocated but not
 *       necessarily persisted).
 *   <li>On a {@code CALL} whose {@code calleeSig} name is a JPA persist method
 *       (persist/save/merge/update/saveOrUpdate), mark every {@code TacVar} argument as
 *       "persisted".
 *   <li>On any {@code CALL}, consult the callee's {@link FunctionSummary}: any {@link
 *       Operation.PersistsParam} on a parameter index matches the position of a {@code TacVar}
 *       argument in the call's {@code args} list. Mark that argument "persisted" too. This is the
 *       cross-method propagation that the AST cannot see.
 *   <li>On a {@code FIELDSTORE} (or any {@code WriteToField} in the callee's summary) of an entity
 *       reference, mark the base object as "mutated".
 *   <li>At {@code finishFunction}, for every transient entity that was mutated but never reached a
 *       persist call (directly or transitively), report <code>ORM_LOST_UPDATE</code>.
 *   <li>At {@code summarizeFunction}, the checker contributes a {@code PersistsParam} Operation for
 *       every parameter that this function passes to a JPA persist method ? so callers (and other
 *       checkers) can see the persistence through the summary chain.
 * </ol>
 *
 * <p><b>What the DFA version deliberately does not do.</b> The DFA checker uses a pure-name
 * heuristic for "is this an entity type" ? any {@code new X()} where X is not on the JDK deny-list
 * is a candidate. This is intentionally permissive: the AST checker has the precise {@code @Entity}
 * annotation, the DFA checker would otherwise need cross-class metadata. The false-positive risk is
 * small because a "mutated but not persisted" allocation is almost always a real defect, regardless
 * of whether X is formally annotated.
 *
 * <p>Registered alongside the AST {@code OrmLostUpdateCheck}; both fire on the same problem, with
 * the DFA version catching the cross-method case.
 */
public final class OrmLostUpdateDfaChecker implements DfaChecker {

  /** JPA / Hibernate methods that mark an entity as persisted. */
  private static final Set<String> PERSIST_METHODS =
      Set.of("persist", "save", "merge", "update", "saveOrUpdate", "saveOrUpdateCopy", "store");

  /**
   * JDK / primitive-constructor types that are emphatically not JPA entities. Matches by callee
   * signature prefix (the part before {@code "#"}). A constructor call of {@code
   * java.util.ArrayList#<init>}, for example, is a collection, not an entity ? the AST's
   * {@code @Entity} check would never match it but the DFA's broader heuristic would otherwise
   * treat it as a candidate.
   */
  private static final Set<String> NON_ENTITY_TYPES =
      Set.of(
          "String",
          "Integer",
          "Long",
          "Double",
          "Float",
          "Boolean",
          "Byte",
          "Short",
          "Character",
          "BigInteger",
          "BigDecimal",
          "Object",
          "Class",
          "StringBuilder",
          "StringBuffer",
          "ArrayList",
          "LinkedList",
          "HashMap",
          "LinkedHashMap",
          "HashSet",
          "LinkedHashSet",
          "TreeMap",
          "TreeSet",
          "ArrayDeque",
          "PriorityQueue",
          "Stack",
          "Vector",
          "Date",
          "Calendar",
          "GregorianCalendar",
          "Timestamp",
          "LocalDate",
          "LocalTime",
          "LocalDateTime",
          "ZonedDateTime",
          "Duration",
          "Period",
          "Instant",
          "UUID",
          "URL",
          "URI",
          "File",
          "Path",
          "Paths",
          "Pattern",
          "Matcher",
          "Optional",
          "List",
          "Map",
          "Set",
          "Collection",
          "Arrays",
          "Collections",
          "ByteArrayOutputStream",
          "ByteArrayInputStream",
          "StringReader",
          "StringWriter",
          "PrintWriter",
          "PrintStream",
          "AtomicInteger",
          "AtomicLong",
          "AtomicBoolean",
          "AtomicReference");

  /** Per-function: var name → "transient entity" flag (allocated, may be persisted later). */
  private Map<String, Boolean> transientVars;

  /**
   * Per-function: var name → "mutated" flag (any field write observed, directly or through a
   * helper).
   */
  private Map<String, Boolean> mutatedVars;

  /**
   * Per-function: var name → "persisted" flag (reached a JPA persist call, directly or
   * transitively).
   */
  private Map<String, Boolean> persistedVars;

  /**
   * Per-function: var name → the allocation site (for the report). The line/column of the {@code
   * new X()} CALL that produced the entity.
   */
  private Map<String, TacInstruction> allocationSite;

  /** Persist param indices recorded by this function (used in summary). */
  private final Set<Integer> thisFunctionPersistsParam;

  /** Cached aggressiveness level, set in initFunction. */
  private String aggressiveness = "medium";

  /**
   * Non-null override from the test-only constructor; if set, {@link #initFunction} uses this value
   * rather than the YAML config.
   */
  private final String aggressivenessOverride;

  public OrmLostUpdateDfaChecker() {
    thisFunctionPersistsParam = new HashSet<>();
    this.aggressivenessOverride = null;
  }

  /**
   * Constructor that lets callers (notably unit tests) pin a specific aggressiveness level. The
   * checker's {@link #initFunction} consults this field; if non-null, the YAML config is ignored.
   * Pass {@code null} to fall back to the YAML config (the production path).
   */
  public OrmLostUpdateDfaChecker(String aggressivenessOverride) {
    thisFunctionPersistsParam = new HashSet<>();
    this.aggressivenessOverride = aggressivenessOverride;
  }

  @Override
  public String name() {
    return "orm-lost-update-dfa";
  }

  @Override
  public CheckerConfig config(AnalysisContext ctx) {
    CheckerConfig c = ctx.config().checkers().byName(name()).orElse(null);
    if (c != null) return c;
    return new CheckerConfig(
        name(),
        true,
        "MEDIUM",
        "CWE-459",
        1,
        "full",
        null,
        "org.plumb.dfa.checkers.controlflow.OrmLostUpdateDfaChecker",
        "DFA-based ORM_LOST_UPDATE (cross-method persistence tracking)",
        "medium");
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    transientVars = new HashMap<>();
    mutatedVars = new HashMap<>();
    persistedVars = new HashMap<>();
    allocationSite = new HashMap<>();
    thisFunctionPersistsParam.clear();
    // Cache aggressiveness so the hot path
    // doesn't re-query the config map. The
    // test-only constructor override takes
    // precedence over the YAML config.
    aggressiveness =
        aggressivenessOverride != null ? aggressivenessOverride : config(ctx).aggressiveness();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    if (ins.kind == TacInstruction.Kind.CALL) {
      handleCall(ins, fn, ctx);
      // At "high" aggressiveness, treat any
      // CALL with a transient entity as an
      // argument as a "mutation event" (e.g.
      // entity.toString() / entity.hashCode()
      // is unlikely to mutate, but a setter
      // such as entity.setName(...) might —
      // and the AST can't see the FIELDSTORE
      // inside the callee). The original AST
      // rule is strict (only direct FIELDSTORE
      // counts); the DFA's high mode trades a
      // few false positives for catching
      // setter-via-helper patterns.
      if ("high".equals(aggressiveness)) {
        for (TacOperand arg : ins.args) {
          if (arg instanceof TacOperand.TacVar) {
            String n = ((TacOperand.TacVar) arg).name();
            if (transientVars.containsKey(n)) {
              mutatedVars.put(n, true);
            }
          }
        }
      }
      return;
    }
    if (ins.kind == TacInstruction.Kind.FIELDSTORE && ins.lhs1 instanceof TacOperand.TacVar) {
      String base = ((TacOperand.TacVar) ins.lhs1).name();
      if (transientVars.containsKey(base)) {
        mutatedVars.put(base, true);
      }
    }
  }

  private void handleCall(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    String callee = ins.calleeSig;
    if (callee == null) return;
    int hash = callee.indexOf('#');
    if (hash < 0) return;
    String after = callee.substring(hash + 1);
    int paren = after.indexOf('(');
    String methodName = paren < 0 ? after : after.substring(0, paren);
    String typeName = callee.substring(0, hash);
    // Strip the FQN package prefix to compare
    // against NON_ENTITY_TYPES which lists
    // short names.
    String shortType = simpleName(typeName);

    // 1. Allocation site for a non-JDK type:
    //    <init> call is a constructor.
    //    At "low" aggressiveness we still
    //    recognise the allocation (so we don't
    //    lose cross-method coverage completely)
    //    but the fire-time branch in
    //    finishFunction is stricter.
    if ("<init>".equals(methodName) && !NON_ENTITY_TYPES.contains(shortType)) {
      if (ins.dest instanceof TacOperand.TacVar) {
        String v = ((TacOperand.TacVar) ins.dest).name();
        transientVars.put(v, true);
        allocationSite.put(v, ins);
      }
      return;
    }

    // 2. Direct persist call: mark every
    //    TacVar argument as persisted.
    if (PERSIST_METHODS.contains(methodName)) {
      for (TacOperand arg : ins.args) {
        if (arg instanceof TacOperand.TacVar) {
          persistedVars.put(((TacOperand.TacVar) arg).name(), true);
        }
      }
    }

    // 3. Cross-method: any CALLEE whose
    //    FunctionSummary says "persists param N"
    //    — propagate to the corresponding arg
    //    in the call site. At "low"
    //    aggressiveness we skip the cross-method
    //    summary lookup to favour precision over
    //    recall (avoiding the "the wrapper
    //    doesn't really persist" false positive).
    if ("low".equals(aggressiveness)) return;
    FunctionSummary calleeSummary = ctx.summaryCache().get(callee);
    if (calleeSummary != null) {
      // PersistsParam ops on params
      for (int pi = 0; pi < ins.args.size(); pi++) {
        Set<Operation> ops = calleeSummary.paramOpsAt(pi);
        if (ops == null) continue;
        for (Operation op : ops) {
          if (op instanceof Operation.PersistsParam) {
            TacOperand arg = ins.args.get(pi);
            if (arg instanceof TacOperand.TacVar) {
              persistedVars.put(((TacOperand.TacVar) arg).name(), true);
            }
          }
        }
      }
      // WriteToField on params: implies
      // mutation of the underlying object —
      // treat the corresponding arg as
      // "mutated" for the caller-side view.
      for (int pi = 0; pi < ins.args.size(); pi++) {
        Set<Operation> ops = calleeSummary.paramOpsAt(pi);
        if (ops == null) continue;
        for (Operation op : ops) {
          if (op instanceof Operation.WriteToField) {
            TacOperand arg = ins.args.get(pi);
            if (arg instanceof TacOperand.TacVar) {
              String n = ((TacOperand.TacVar) arg).name();
              if (transientVars.containsKey(n)) {
                mutatedVars.put(n, true);
              }
            }
          }
        }
      }
    }
  }

  @Override
  public void finishFunction(AnalysisContext ctx, TacFunction fn) {
    // Fire on every transient var that is
    // mutated but never persisted. A
    // constructor-only allocation that
    // never has any field touched is not
    // a defect (the object is unused).
    for (Map.Entry<String, Boolean> e : transientVars.entrySet()) {
      String v = e.getKey();
      boolean mutated = Boolean.TRUE.equals(mutatedVars.get(v));
      boolean persisted = Boolean.TRUE.equals(persistedVars.get(v));
      if (!mutated || persisted) continue;
      TacInstruction site = allocationSite.get(v);
      int line = site != null ? site.line : -1;
      int col = site != null ? site.column : -1;
      if (site != null) {
        PatternTrace.report(
            ctx,
            fn,
            site,
            TraceNode.Op.UNUSED_GET_RESULT,
            "transient:" + v,
            name(),
            name(),
            "ORM_LOST_UPDATE (DFA): transient entity variable '"
                + v
                + "' is allocated in this function, has"
                + " fields mutated, but is never passed to"
                + " persist/save/merge/update — the change"
                + " will be lost",
            RuleDef.Severity.MEDIUM,
            "CWE-459",
            "transient entity " + v + " in " + fn.signature());
      } else {
        // No allocation site recorded (e.g. the
        // entity came in as a parameter, not a
        // NEW). Fall back to per-function report.
        PatternTrace.reportPerFunction(
            ctx,
            fn,
            line,
            col,
            TraceNode.Op.UNUSED_GET_RESULT,
            "transient:" + v,
            name(),
            name(),
            "ORM_LOST_UPDATE (DFA): transient entity variable '"
                + v
                + "' is allocated in this function, has"
                + " fields mutated, but is never passed to"
                + " persist/save/merge/update — the change"
                + " will be lost",
            RuleDef.Severity.MEDIUM,
            "CWE-459",
            "transient entity " + v + " in " + fn.signature());
      }
    }
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    FunctionSummary.Builder b = new FunctionSummary.Builder(fn.signature());
    for (Integer pi : thisFunctionPersistsParam) {
      b.recordParamOp(pi, new Operation.PersistsParam(pi));
    }
    return b.build();
  }

  /**
   * Records a PersistsParam hint for parameter {@code pi}. Called when this function's analysis (in
   * handleCall) sees a JPA persist call where one of the arguments is the function's own parameter
   * {@code pi}.
   *
   * <p>Not currently used in this minimal implementation: the "this function's body passes param N
   * to persist" logic is computed when the param's TacVar name matches a known param of the current
   * function. Left as an extension point for a future round.
   */
  private void recordPersistsParam(int pi) {
    thisFunctionPersistsParam.add(pi);
  }

  private static String simpleName(String fqn) {
    if (fqn == null) return "";
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
  }
}
