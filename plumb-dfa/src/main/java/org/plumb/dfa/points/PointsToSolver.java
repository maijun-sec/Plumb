package org.plumb.dfa.points;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.dfa.points.AbstractObject.AbstractArrayIndex;
import org.plumb.dfa.points.AbstractObject.AbstractField;
import org.plumb.dfa.points.AbstractObject.Context;
import org.plumb.dfa.points.AbstractObject.Obj;
import org.plumb.dfa.points.AbstractObject.Policy;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * A domain-sensitive points-to analysis for Java, built as shared DFA infrastructure rather than
 * as a rule.
 *
 * <h2>Why infrastructure</h2>
 *
 * <p>Alias information is not a finding; it is an input. Every checker that asks "is this the same
 * object I already validated?", "can this field be null?", or "does this write reach the reference
 * I sanitised?" needs the same answer. Keeping that in one place — {@link
 * org.plumb.dfa.engine.AnalysisContext#pointsTo()} — is what makes the answers consistent. The
 * previous arrangement attached a points-to map to a single checker
 * ({@code UseAfterFreeChecker}, since removed) so no other rule could consult it, and the map it
 * used was keyed by allocation site with no field or array path at all.
 *
 * <h2>Shape</h2>
 *
 * <p>Follows Qilin's structure (ECOOP'22): a pointer-assignment graph solved by an incremental
 * worklist, with the heap abstraction and the context-sensitivity policy as parameters rather than
 * hard-coded choices. The difference in scale is deliberate — Qilin runs on bytecode with IFDS/IDE;
 * Plumb has a {@link TacProgram} and a whole-program-per-file model, so this is a direct
 * constraint-propagation solver rather than a tabulation solver.
 *
 * <p>The propagation rules are the standard ones:
 *
 * <pre>
 *   NEW  / NEWARRAY : v = { alloc(v) }
 *   MOVE            : pts(v) ⊇ pts(v')          (copy propagation)
 *   FIELDSTORE      : for each o in pts(v) : field[o, f] ⊇ pts(src)
 *   FIELDLOAD       : for each o in pts(v) : pts(dest) ⊇ field[o, f]
 *   ARRAYSTORE      : for each o in pts(v) : arrayCell[o, i] ⊇ pts(src)
 *   ARRAYLOAD       : for each o in pts(v) : pts(dest) ⊇ arrayCell[o, i]
 *   CALL            : formal(actual) ⊇ pts(actual)          (parameters, both ways)
 *                     pts(dest)    ⊇ returnOf(callee)          (return values)
 *   RETURN          : returnOf(fn) ⊇ pts(v)
 * </pre>
 *
 * <p>Every rule is monotone and incremental: adding an object to a points-to set enqueues whatever
 * reads that set, so the solver reaches a fixpoint without rescanning.
 *
 * <h2>Context sensitivity</h2>
 *
 * <p>Under {@link Policy#CALL_SITE} and {@link Policy#OBJECT} each method is analysed once per
 * distinct context, and a local variable's key is qualified by that context. That is what keeps
 * {@code id()} called from two sites with two different receivers from having its locals merged.
 * The default is {@link Policy#CALL_SITE} (1-CFA over the heap), as decided by {@link
 * org.plumb.dfa.points.AbstractObject#defaultPolicy()} and overridable per run through {@code
 * TuningConfig.effectivePointsToPolicy()}. Precision can be lowered to {@link
 * Policy#CONTEXT_INSENSITIVE} per run once a caller wants the cheaper solve.
 *
 * <h2>Field sensitivity</h2>
 *
 * <p>A field is {@code (receiver, name)}, so {@code a.child} and {@code b.child} are distinct
 * whenever {@code a} and {@code b} are, and {@code a.child} and {@code a.name} are always distinct.
 * This is what lets "two roots holding different children" stay distinguished — the case a
 * field-insensitive analysis conflates and then over-reports on.
 */
public final class PointsToSolver {

  /** Per-variable points-to set, qualified by context when the policy is context-sensitive. */
  private final Map<VarKey, Set<Obj>> pts = new LinkedHashMap<>();

  /** {@code (receiver, field) -> objects}. */
  private final Map<AbstractField, Set<Obj>> fields = new LinkedHashMap<>();

  /** {@code (array, index) -> objects}. Index {@link AbstractArrayIndex#ANY} means "unknown". */
  private final Map<AbstractArrayIndex, Set<Obj>> arrayCells = new LinkedHashMap<>();

  /**
   * R1112: {@code (container object, key) -> objects}, for Map and List cells.
   *
   * <p>Separate from {@link #arrayCells} because a Java container is reached through a CALL, not
   * through an indexed instruction, so it needs its own accessor path rather than an overload of the
   * array one. Keeping them apart also keeps the array abstraction unchanged for real arrays.
   */
  private final Map<ContainerCell, Set<Obj>> containerCells = new LinkedHashMap<>();

  /**
   * Per-context points-to for a method's return value.
   *
   * <p>Keyed by (signature, context) rather than by signature alone: that is what lets one method
   * return a different object per caller under a context-sensitive policy. Keying by signature is
   * the single fact that made {@link Policy#CALL_SITE} inert -- every call site's destination was
   * seeded from one merged return set.
   */
  private final Map<ContextKey, Set<Obj>> returns = new LinkedHashMap<>();

  // ── R1114: the null direction ─────────────────────────────────────────────────────
  //
  // The five stores above answer "which objects". They cannot answer "which of these slots hold
  // NULL", because `Obj` is an allocation-site identity: two null references are not the same
  // object, and giving null an `Obj` would make every null in the program compare equal. That
  // single sentinel would then flow into `mayAlias` and into the guard-coverage test, where
  // `covered.containsAll(held)` would read "the guard covers this null" -- exactly the false
  // negative R1113 removed two rounds ago.
  //
  // So nullness is a SEPARATE channel, and every slot is tri-valued by construction:
  //
  //   objects non-empty, null not present   -> definitely an object
  //   objects empty,       null present     -> definitely null
  //   objects empty,       null absent      -> UNKNOWN (never "null")
  //
  // The third row is why `nullFields` cannot be collapsed into an empty-set signal: absence of a
  // fact must keep meaning "the analysis does not know", or every unmodelled field read in the
  // corpus would become a CWE-476 finding.

  /** {@code (receiver, field) -> "this slot was assigned the null literal"}. */
  private final java.util.Set<AbstractField> nullFields = new java.util.LinkedHashSet<>();

  /** {@code (array, index) -> "this cell was assigned the null literal"}. */
  private final java.util.Set<AbstractArrayIndex> nullArrayCells = new java.util.LinkedHashSet<>();

  /** {@code (container object, key) -> "this cell holds the null literal". */
  private final java.util.Set<ContainerCell> nullContainerCells = new java.util.LinkedHashSet<>();

  /** {@code (signature, context) -> "this method returns null on some path"}. */
  private final java.util.Set<ContextKey> nullReturns = new java.util.LinkedHashSet<>();

  /** Per-variable "assigned the null literal", mirroring {@link #pts} but for nullness. */
  private final java.util.Set<VarKey> nullVars = new java.util.LinkedHashSet<>();

  /**
   * Call sites that consume a (method, context) return value.
   *
   * <p>The reverse edge of {@link #returns}: when a return object is discovered, every call site
   * that invoked the method <em>in that context</em> must see it. Without this the return flow is
   * one-way and a value discovered after the call site was visited never reaches it.
   */
  private final Map<ContextKey, Set<VarKey>> returnReaders = new LinkedHashMap<>();

  /** (method, context) the caller discovered for each call site, so contexts are not rebuilt. */
  private final Set<String> contextEdgesRegistered = new LinkedHashSet<>();

  /** (method, context) pairs already seeded with their allocations, so seeding happens once. */
  private final Set<ContextKey> seededContexts = new LinkedHashSet<>();

  /** Method signature -> the contexts in which it has been (or must be) analysed. */
  private final Map<String, Set<Context>> methodContexts = new LinkedHashMap<>();

  /** Every function seen, so the solver can iterate to a fixpoint without re-walking the program. */
  private final Map<String, TacFunction> functions = new LinkedHashMap<>();

  /**
   * {@code name/arity -> declared signature}, so a CALL's {@code make/0} resolves to the function
   * declared as {@code make():Object}. Built during {@link #solve}.
   */
  private final Map<String, String> byNameArity = new LinkedHashMap<>();

  private final Policy policy;

  /** Parameters of a method, indexed positionally; the argument constraints target these. */
  private final Map<String, List<TacOperand.TacVar>> paramsBySignature = new LinkedHashMap<>();

  /** Sites known to allocate a resource, for consumers that care about that specifically. */
  private final Set<String> resourceAllocSites = new LinkedHashSet<>();

  /** Objects known to be released (close/dispose), kept separate from the alias answer. */
  private final Set<Obj> released = new LinkedHashSet<>();

  public PointsToSolver(Policy policy) {
    this.policy = policy == null ? Policy.CONTEXT_INSENSITIVE : policy;
  }

  public Policy policy() {
    return policy;
  }

  /** A variable identity, optionally qualified by the context it was declared in. */
  public record VarKey(String signature, String varName, Context context) {}

  /**
   * A method analysed under one specific context.
   *
   * <p>Needed as a map key for the return flow: two invocations of the same method under 1-CFA have
   * the same signature and different contexts, so the signature alone cannot address a return value.
   */
  private record ContextKey(String signature, Context context) {}

  // ── entry point ───────────────────────────────────────────────────────

  /**
   * Analyse a whole program to a fixpoint.
   *
   * <p>Seeds every context-insensitive allocation and every method's entry parameters, then drains
   * the worklist. Under a context-sensitive policy the caller/callee edges are followed as the
   * callgraph allows, so a second context for a method is discovered while draining rather than
   * enumerated up front.
   */
  public void solve(Collection<TacFunction> program) {
    for (TacFunction fn : program) {
      if (fn == null) continue;
      functions.put(fn.signature(), fn);
      paramsBySignature.put(fn.signature(), List.copyOf(fn.params()));
      methodContexts.computeIfAbsent(fn.signature(), k -> new LinkedHashSet<>())
          .add(Context.empty());
      String name = fn.name();
      if (name != null && !name.isEmpty()) {
        int params = fn.params().size();
        String key = name + "/" + params;
        // Prefer the first declaration so overloads resolve deterministically; the
        // callgraph, not the points-to solver, is where overload selection belongs.
        byNameArity.putIfAbsent(key, fn.signature());
      }
    }
    // Seed every method's trivial context. Contexts discovered later are seeded by ensureContext
    // at the moment the call edge that reveals them is followed.
    for (String sig : new ArrayList<>(functions.keySet())) {
      ensureContext(sig, Context.empty());
    }
    drain();
  }

  /**
   * Fixpoint loop.
   *
   * <p>Re-seeds and re-propagates until nothing changes. A plain worklist over "what changed" would
   * be faster, but the program sizes Plumb sees make a bounded rescan the clearer trade: it is
   * obviously correct and the cost is linear in functions times program size.
   *
   * <p>The context list is snapshotted before iterating because a context-sensitive pass calls
   * {@link #ensureContext}, which appends to {@link #methodContexts} while this loop is walking it.
   * Iterating the live map would raise a ConcurrentModificationException on exactly the runs that
   * discover a second context.
   */
  private void drain() {
    int rounds = 0;
    boolean changed = true;
    while (changed && rounds++ < 64) {
      changed = false;
      List<String> sigs = new ArrayList<>(functions.keySet());
      for (String sig : sigs) {
        for (Context ctx : new ArrayList<>(methodContexts.getOrDefault(sig, Set.of()))) {
          changed |= propagate(sig, ctx);
        }
      }
    }
  }

  /** One propagation pass over one method in one context. Returns true when anything grew. */
  private boolean propagate(String signature, Context ctx) {
    TacFunction fn = functions.get(signature);
    if (fn == null) return false;
    boolean changed = false;
    int i = 0;
    for (TacInstruction ins : fn.instructions()) {
      changed |= step(signature, ctx, ins, i);
      i++;
    }
    return changed;
  }

  private boolean step(String signature, Context ctx, TacInstruction ins, int index) {
    switch (ins.kind) {
      case MOVE -> {
        if (!(ins.dest instanceof TacOperand.TacVar d)) return false;
        // R1114: a null literal on either side of the copy propagates nullness. Handling this as a
        // plain TacVar copy would miss `b = null`, which is the single most common way a reference
        // becomes null in the corpus.
        if (ins.lhs1 instanceof TacOperand.Const c
            && c.kind() == TacOperand.Const.Kind.NULL) {
          return nullVars.add(key(signature, d, ctx));
        }
        if (ins.lhs1 instanceof TacOperand.TacVar s) {
          boolean ch = add(key(signature, d, ctx), points(signature, s, ctx));
          // Copy the null direction alongside the object direction. This is NOT a may-analysis
          // "remove on reassignment": a name is never proven non-null here, so the copied fact
          // stays a may-fact about the alias, exactly like the object sets.
          if (nullVars.contains(key(signature, s, ctx))) {
            ch |= nullVars.add(key(signature, d, ctx));
          }
          return ch;
        }
        return false;
      }
      case FIELDSTORE -> {
        if (!(ins.lhs1 instanceof TacOperand.TacVar recv)) return false;
        boolean ch = false;
        boolean storeNull = ins.dest instanceof TacOperand.Const c
            && c.kind() == TacOperand.Const.Kind.NULL;
        for (Obj o : points(signature, recv, ctx)) {
          AbstractField slot = new AbstractField(o, fieldNameOf(ins));
          if (storeNull) {
            // R1114: record the NULL direction, not just the object direction. This is the fact
            // that lets a consumer tell "assigned null" apart from "never assigned".
            ch |= nullFields.add(slot);
          } else {
            ch |= addField(slot, points(signature, asVar(ins.dest), ctx));
          }
        }
        return ch;
      }
      case FIELDLOAD -> {
        if (!(ins.dest instanceof TacOperand.TacVar d)) return false;
        if (!(ins.lhs1 instanceof TacOperand.TacVar recv)) return false;
        boolean ch = false;
        for (Obj o : points(signature, recv, ctx)) {
          AbstractField slot = new AbstractField(o, fieldNameOf(ins));
          ch |= add(key(signature, d, ctx), fieldObjects(slot));
          // A load of a slot that may hold null makes the destination nullable. The ANY analogue
          // is intentionally absent: unlike an unknown array index, an unknown FIELD is not
          // modelled here, so this does not over-approximate across unrelated fields.
          if (nullFields.contains(slot)) {
            ch |= nullVars.add(key(signature, d, ctx));
          }
        }
        return ch;
      }
      case ARRAYSTORE -> {
        // ARRAYSTORE carries the array in lhs1, the index in lhs2, and the STORED VALUE in
        // dest -- not in lhs2. Getting this backwards silently loses every array write.
        if (!(ins.lhs1 instanceof TacOperand.TacVar arr)) return false;
        TacOperand.TacVar value = asVar(ins.dest);
        boolean storeNull = value == null && ins.dest instanceof TacOperand.Const c
            && c.kind() == TacOperand.Const.Kind.NULL;
        if (value == null && !storeNull) return false;
        boolean ch = false;
        for (Obj o : points(signature, arr, ctx)) {
          AbstractArrayIndex cell = AbstractArrayIndex.of(o, literalIndex(ins));
          ch |= storeNull
              ? nullArrayCells.add(cell)
              : addArray(cell, points(signature, value, ctx));
        }
        return ch;
      }
      case ARRAYLOAD -> {
        if (!(ins.dest instanceof TacOperand.TacVar d)) return false;
        if (!(ins.lhs1 instanceof TacOperand.TacVar arr)) return false;
        boolean ch = false;
        for (Obj o : points(signature, arr, ctx)) {
          ch |= add(key(signature, d, ctx),
              arrayObjects(AbstractArrayIndex.of(o, literalIndex(ins))));
          ch |= add(key(signature, d, ctx), arrayObjects(AbstractArrayIndex.any(o)));
          // R1114: a known index must also see null written at an unknown index, mirroring the
          // ANY read above. Without this a `new String[n]` default-initialised cell -- which is
          // null by language definition -- would be invisible.
          if (nullArrayCells.contains(AbstractArrayIndex.of(o, literalIndex(ins)))
              || nullArrayCells.contains(AbstractArrayIndex.any(o))) {
            ch |= nullVars.add(key(signature, d, ctx));
          }
        }
        return ch;
      }
      case CALL -> {
        boolean ch = false;
        // R1112: container store/load BEFORE callee resolution. `map.get` resolves to no function
        // in this program, so handling it after resolution would make it unreachable -- the same
        // structural defect that left the CWE-502 rule registered but unfireable.
        // R1112: a container access returns a container cell, not the result of a call into this
        // program. Letting it fall through to the callee path as well is how a second object
        // reached the destination: the caller-side return binding contributed whatever the
        // resolved signature happened to yield, and the cell's value was unioned with it.
        //
        // So a recognised container operation is handled here and returns. Whether the signature
        // ALSO resolves to a local function does not matter -- `Map.get` is library code in both
        // readings, and its result is the cell.
        if (applyContainerOp(signature, ctx, ins)) {
          return true;
        }
        // Arguments bind to the callee's formals, in both directions.
        String callee = resolveCallee(ins);
        if (callee == null) return false;
        Context calleeCtx = calleeContext(signature, ctx, ins, index);
        ensureContext(callee, calleeCtx);
        List<TacOperand.TacVar> formals = paramsBySignature.get(callee);
        if (formals != null) {
          // The TAC keeps the receiver at args[0]; user arguments start at args[1].
          for (int a = 1; a < ins.args.size(); a++) {
            if (!(ins.args.get(a) instanceof TacOperand.TacVar actual)) continue;
            int p = a - 1;
            if (p < formals.size()) {
              // Bound into the CALLEE's context, not the empty one: under a context-sensitive
              // policy this is what keeps two invocations' parameters disjoint.
              ch |= add(new VarKey(callee, formals.get(p).name(), qual(calleeCtx)),
                  points(signature, actual, ctx));
            }
          }
          // The receiver binds to the `this` / first formal, which is params[0] for an
          // instance method in this IR's convention when there is one.
          if (!ins.args.isEmpty() && ins.args.get(0) instanceof TacOperand.TacVar recv
              && !formals.isEmpty()) {
            ch |= add(new VarKey(callee, formals.get(0).name(), qual(calleeCtx)),
                points(signature, recv, ctx));
          }
        }
        ch |= bindReturn(callee, calleeCtx, signature, ctx, ins);
        // R1114: the null half of the return summary. `StringBuilder b = mayReturnNull();` was
        // previously indistinguishable from one returning a fresh object, because the return
        // store had no way to record null.
        if (ins.dest instanceof TacOperand.TacVar dst
            && nullReturns.contains(new ContextKey(callee, qual(calleeCtx)))) {
          ch |= nullVars.add(key(signature, dst, ctx));
        }
        return ch;
      }
      case RETURN -> {
        ContextKey ck = new ContextKey(signature, qual(ctx));
        if (ins.lhs1 instanceof TacOperand.Const c
            && c.kind() == TacOperand.Const.Kind.NULL) {
          // R1114: `return null` is the same shape as any other return fact.
          return nullReturns.add(ck);
        }
        if (ins.lhs1 instanceof TacOperand.TacVar r) {
          // Keyed by the returning method's own context, so a value returned under one call
          // site's context is never visible to another's.
          boolean ch = addReturn(ck, points(signature, r, ctx));
          if (nullVars.contains(key(signature, r, ctx))) {
            ch |= nullReturns.add(ck);
          }
          return ch;
        }
        return false;
      }
      default -> {
        return false;
      }
    }
  }

  // ── container cells (R1112) ───────────────────────────────────────────

  /**
   * R1112: one cell of a Java container.
   *
   * <p>{@code key} is the literal string or index when the access site used one, and {@link #ANY_KEY}
   * otherwise. Distinguishing keys is what keeps two map entries separate: with a single merged cell
   * per container, a guard on one entry would silence a dereference of a different entry, which is a
   * wrong "safe" of exactly the kind a guard-transfer rule must not produce.
   *
   * <p>{@code ANY_KEY} aliases every concrete key of the same container. An access with a computed
   * key could be reading any entry, so claiming otherwise would be unsound in the other direction.
   */
  /**
   * R1112: one cell of a Java container: {@code container[key]}.
   *
   * <p>Keys match <em>exactly</em>, the opposite of {@link AbstractArrayIndex}, and the difference is
   * load-bearing. An array's ANY cell exists because a computed index genuinely cannot say which
   * element it touches. A container's literal key is known: {@code store.get("b")} is about exactly
   * one entry, and letting its cell alias {@code put("a", ..)}'s cell would report two unrelated
   * entries as one object. For a guard-transfer rule that is a false "safe" manufactured by
   * imprecision rather than by a real alias.
   *
   * <p>The conservative direction is confined to the access sites: an unrecognised key stores under
   * {@link #ANY_KEY} and reads consult every cell of the container, so no fact is ever lost, while a
   * recognised key stays exact.
   */
  private record ContainerCell(Obj container, String key, boolean mapCell) {
    static final String ANY_KEY = "\u0000*";

    boolean isAnyKey() {
      return ANY_KEY.equals(key);
    }
  }

  /**
   * R1112: container operations modelled as store and load edges.
   *
   * <p>Modelled by callee signature, with the receiver at {@code args[0]} per the TAC convention:
   *
   * <pre>
   *   map.put(receiver, key, value)   store receiver[key] = pts(value)
   *   map.get(receiver, key)          pts(dest) ⊇ receiver[key]
   *   map.remove(receiver, key)       clears receiver[key] -- accepted but not subtracted, since
   *                                   this solver is may-analysis and cannot retract facts
   *   list.add(receiver, value)       store receiver[#size] = pts(value); the index is unknown
   *   list.get(receiver, i)           pts(dest) ⊇ receiver[i]
   *   list.set(receiver, i, value)    store receiver[i] = pts(value)
   * </pre>
   *
   * <p>Only signatures ending in the bare method name are matched, so {@code Map.put} and a user
   * {@code Foo.put} do not collide. An explicit {@code java.util.Map.put} style signature is matched
   * exactly.
   *
   * <p>{@code list.add} cannot know the index it will occupy, so it writes {@link
   * ContainerCell#ANY_KEY}. A {@code list.get(0)} then observes it. The consequence is that two
   * elements added to the same list are <em>not</em> distinguished: {@code get(0)} may see either.
   * That is the conservative direction and is documented rather than papered over.
   *
   * @return the kind of cell touched, or {@code null} when this call is not a container operation.
   */
  private String containerOp(TacInstruction ins) {
    String sig = ins.calleeSig;
    if (sig == null || sig.isEmpty()) return null;
    List<TacOperand> args = ins.args;
    if (args == null || args.size() < 2) return null;
    String name = bareName(sig);
    if (name == null) return null;
    return switch (name) {
      case "put", "remove" ->
          args.size() >= 3 && keyOf(args.get(1)) != null ? "map:" + keyOf(args.get(1)) : null;
      case "get" -> keyOf(args.get(1)) != null ? "map:" + keyOf(args.get(1)) : null;
      case "set" ->
          args.size() >= 3 && indexOf(args.get(1)) != null ? "list:" + indexOf(args.get(1)) : null;
      case "add", "offer", "push" -> ContainerCell.ANY_KEY;
      default -> null;
    };
  }

  /** The bare method name of a callee signature, or {@code null} when it has none. */
  private static String bareName(String sig) {
    int slash = sig.lastIndexOf('/');
    if (slash <= 0) return null;
    String head = sig.substring(0, slash);
    int dot = head.lastIndexOf('.');
    String name = dot >= 0 ? head.substring(dot + 1) : head;
    // A resolved signature such as `java.util.Map.put/2` carries a package qualifier; a bare
    // `put/2` from the translator does not. Both are accepted, but a package-qualified name must
    // actually be a JDK container so a user-defined `put` cannot hijack the rule.
    if (dot >= 0) {
      String qualifier = head.substring(0, dot);
      if (!isJdkContainer(qualifier)) return null;
    }
    return name.isEmpty() ? null : name;
  }

  private static boolean isJdkContainer(String qualifier) {
    return qualifier.startsWith("java.util.") || qualifier.startsWith("java.lang.")
        || qualifier.contains("Map") || qualifier.contains("List")
        || qualifier.contains("Set") || qualifier.contains("Queue")
        || qualifier.contains("Deque") || qualifier.contains("Collection");
  }

  /**
   * The key text of a map access, or {@code null} when it is not a literal.
   *
   * <p>Only literals are recognised on purpose. A computed key could be any entry, so admitting it
   * with its text would claim a precision the IR does not have; {@link ContainerCell#ANY_KEY} is the
   * honest answer, and a read through it still sees every literal-keyed write.
   */
  private static String keyOf(TacOperand op) {
    if (op instanceof TacOperand.Const c && c.kind() == TacOperand.Const.Kind.STRING) {
      Object v = c.value();
      return v == null ? ContainerCell.ANY_KEY : "\"" + v + "\"";
    }
    if (op instanceof TacOperand.Const c && c.kind() == TacOperand.Const.Kind.INT) {
      Integer v = (Integer) c.value();
      return v == null ? ContainerCell.ANY_KEY : "i" + v;
    }
    return ContainerCell.ANY_KEY;
  }

  /** The index text of a list access, or {@code null} when it is not an integer literal. */
  private static String indexOf(TacOperand op) {
    if (op instanceof TacOperand.Const c && c.kind() == TacOperand.Const.Kind.INT) {
      Integer v = (Integer) c.value();
      return v == null ? ContainerCell.ANY_KEY : "i" + v;
    }
    return ContainerCell.ANY_KEY;
  }

  // ── set operations ────────────────────────────────────────────────────

  /**
   * Qualify a context for use as a map key.
   *
   * <p>Under {@link Policy#CONTEXT_INSENSITIVE} every variable shares one context, so any context
   * collapses to {@link Context#empty()} here. Naming this separately from {@link
   * #key(String, TacOperand.TacVar, Context)} matters: the two look almost identical at a call site
   * and mixing them up compiles into a key with the wrong arity.
   */
  private Context qual(Context ctx) {
    return policy.isContextSensitive() ? ctx : Context.empty();
  }

  private VarKey key(String signature, TacOperand.TacVar v, Context ctx) {
    return new VarKey(signature, v.name(), qual(ctx));
  }

  private Set<Obj> points(String signature, TacOperand.TacVar v, Context ctx) {
    if (v == null) return Set.of();
    Set<Obj> s = pts.get(key(signature, v, ctx));
    return s == null ? Set.of() : s;
  }

  private Set<Obj> fieldObjects(AbstractField slot) {
    Set<Obj> s = fields.get(slot);
    return s == null ? Set.of() : s;
  }

  /** Every cell recorded for one container object, used when the access key is unknown. */
  private java.util.List<ContainerCell> containerCellsOf(Obj container, boolean mapCell) {
    java.util.List<ContainerCell> out = new ArrayList<>();
    for (ContainerCell cell : containerCells.keySet()) {
      if (cell.container().equals(container) && cell.mapCell() == mapCell) {
        out.add(cell);
      }
    }
    return out;
  }

  private Set<Obj> containerObjects(ContainerCell cell) {
    Set<Obj> s = containerCells.get(cell);
    return s == null ? Set.of() : s;
  }

  private boolean addContainer(ContainerCell cell, Collection<Obj> objects) {
    if (cell == null || objects == null || objects.isEmpty()) return false;
    return containerCells.computeIfAbsent(cell, x -> new LinkedHashSet<>()).addAll(objects);
  }

  private Set<Obj> arrayObjects(AbstractArrayIndex cell) {
    // The ANY cell is a catch-all: a known-index read must also see whatever an
    // unknown-index write could have stored.
    Set<Obj> s = arrayCells.get(cell);
    return s == null ? Set.of() : s;
  }

  private Set<Obj> returnObjects(ContextKey ck) {
    Set<Obj> s = returns.get(ck);
    return s == null ? Set.of() : s;
  }

  private boolean add(VarKey k, Obj object) {
    return pts.computeIfAbsent(k, x -> new LinkedHashSet<>()).add(object);
  }

  private boolean add(VarKey k, Collection<Obj> objects) {
    if (objects.isEmpty()) return false;
    Set<Obj> target = pts.computeIfAbsent(k, x -> new LinkedHashSet<>());
    return target.addAll(objects);
  }

  private boolean addField(AbstractField slot, Collection<Obj> objects) {
    if (objects.isEmpty()) return false;
    return fields.computeIfAbsent(slot, x -> new LinkedHashSet<>()).addAll(objects);
  }

  private boolean addArray(AbstractArrayIndex cell, Collection<Obj> objects) {
    if (objects.isEmpty()) return false;
    return arrayCells.computeIfAbsent(cell, x -> new LinkedHashSet<>()).addAll(objects);
  }

  private boolean addReturn(ContextKey ck, Collection<Obj> objects) {
    if (objects.isEmpty()) return false;
    boolean changed = returns.computeIfAbsent(ck, x -> new LinkedHashSet<>()).addAll(objects);
    // A return object found after the call site was already visited still has to reach every
    // reader of this (method, context). Re-firing here is what makes the flow bidirectional.
    if (changed) {
      for (VarKey reader : returnReaders.getOrDefault(ck, Set.of())) {
        changed |= add(reader, objects);
      }
    }
    return changed;
  }

  // ── context edges ─────────────────────────────────────────────────────

  /**
   * The context a callee is analysed under, given the caller's context and this call site.
   *
   * <p>1-CFA: the caller's context extended with a call-site identity. The element is
   * {@code callerSignature#instructionIndex}, which is unique inside the caller, so two distinct
   * call sites in the same method yield two distinct callee contexts and the callee's locals never
   * merge. That is exactly the property that stops taint on one call path from appearing on another.
   *
   * <p>k-OBJ extends the same element set with the receiver's abstract object, so even two different
   * call sites dispatching to the same method on different receivers stay apart. The receiver is
   * looked up in the caller's context, which is the context the call site is actually in.
   */
  private Context calleeContext(String callerSig, Context callerCtx, TacInstruction ins,
      int index) {
    if (policy == Policy.CONTEXT_INSENSITIVE) {
      return Context.empty();
    }
    Context base = callerCtx == null ? Context.empty() : callerCtx;
    if (policy == Policy.CALL_SITE) {
      return base.append(callerSig + "#" + index);
    }
    // Policy.OBJECT
    TacOperand.TacVar recv = ins.args == null || ins.args.isEmpty()
        ? null
        : asVar(ins.args.get(0));
    List<String> elements = new ArrayList<>(base.elements());
    elements.add(callerSig + "#" + index);
    if (recv != null) {
      Set<Obj> receivers = points(callerSig, recv, base);
      elements.add("recv:" + receivers.stream().map(Obj::site).sorted().toList());
    }
    return Context.of(elements.toArray(new String[0]));
  }

  /**
   * Register a method under a newly discovered context and seed its allocations.
   *
   * <p>Seeding is separate from context discovery on purpose: a context created halfway through the
   * drain still has to contribute its {@code new} sites, and doing that immediately is what keeps
   * the fixpoint monotone instead of needing a second pass.
   */
  private void ensureContext(String signature, Context ctx) {
    ContextKey ck = new ContextKey(signature, ctx);
    if (!seededContexts.add(ck)) return;
    methodContexts.computeIfAbsent(signature, k -> new LinkedHashSet<>()).add(ctx);
    TacFunction fn = functions.get(signature);
    if (fn == null) return;
    int i = 0;
    for (TacInstruction ins : fn.instructions()) {
      if (ins.kind == TacInstruction.Kind.NEW || ins.kind == TacInstruction.Kind.NEWARRAY) {
        if (ins.dest instanceof TacOperand.TacVar d) {
          String type = ins.ctorType == null ? "java.lang.Object" : ins.ctorType.toString();
          Obj o = Obj.at(signature, i, type, ctx);
          add(key(signature, d, ctx), o);
          if (isResourceType(type)) resourceAllocSites.add(o.site());
        }
      } else if (ins.kind == TacInstruction.Kind.MOVE
          && ins.dest instanceof TacOperand.TacVar d
          && ins.lhs1 instanceof TacOperand.TacVar s) {
        add(key(signature, d, ctx), points(signature, s, ctx));
      }
      i++;
    }
  }

  /**
   * Connect a call site's destination to the callee's return value in that callee's context.
   *
   * <p>Two things are recorded: the destination becomes a reader of {@code (callee, calleeCtx)}, and
   * it is seeded with whatever is known so far. The registration is idempotent per call site because
   * {@code drain} revisits instructions until the fixpoint is reached.
   */
  private boolean bindReturn(String callee, Context calleeCtx, String callerSig, Context callerCtx,
      TacInstruction ins) {
    TacOperand.TacVar dest = asVar(ins.dest);
    if (dest == null) return false;
    ContextKey ck = new ContextKey(callee, qual(calleeCtx));
    VarKey reader = new VarKey(callerSig, dest.name(), qual(callerCtx));
    if (returnReaders.computeIfAbsent(ck, x -> new LinkedHashSet<>()).add(reader)) {
      contextEdgesRegistered.add(callerSig + "#" + reader.varName() + "@" + ck);
    }
    return add(reader, returnObjects(ck));
  }

  /**
   * R1112: apply one container store or load. Returns true when a fact grew.
   *
   * <p>Each concrete-keyed cell is read together with the {@link ContainerCell#ANY_KEY} cell, in
   * both directions: a read through an unknown key must see every literal-keyed write, and a write
   * through an unknown key must be visible to every literal-keyed read. Reading only the literal cell
   * would be unsound, and writing only the literal cell would lose the entry entirely.
   */
  /**
   * R1112: apply one container store or load.
   *
   * @return {@code true} when this instruction was a container access and has been fully handled
   *     here, whether or not a fact grew. An empty-cell write is still a handled access, and running
   *     the callee path on it as well is what previously let a second object reach the destination.
   * @return {@code false} when this is an ordinary call and the caller should continue.
   */
  private boolean applyContainerOp(String signature, Context ctx, TacInstruction ins) {
    String op = containerOp(ins);
    if (op == null) return false;
    applyContainerFacts(signature, ctx, ins, op);
    return true;
  }

  private void applyContainerFacts(
      String signature, Context ctx, TacInstruction ins, String op) {
    List<TacOperand> args = ins.args;
    if (args == null || args.size() < 2) return;
    if (!(args.get(0) instanceof TacOperand.TacVar recv)) return;
    boolean mapCell = !op.startsWith("list:");
    String key = op.startsWith("map:") || op.startsWith("list:")
        ? op.substring(op.indexOf(':') + 1)
        : ContainerCell.ANY_KEY;
    // The stored value is the LAST argument for put/set, the only argument for add/offer/push.
    boolean isLoad = "get".equals(bareName(ins.calleeSig));
    if (!isLoad && args.size() >= 2) {
      int valueIdx = "set".equals(bareName(ins.calleeSig)) ? 2 : args.size() - 1;
      // R1114: `store.put(k, null)` and `list.add(null)` store the null literal. The operand is a
      // Const rather than a TacVar, so it has to be recognised before the TacVar branch below --
      // otherwise the store is silently dropped, which is the whole gap being closed.
      boolean storeNull = valueIdx < args.size()
          && args.get(valueIdx) instanceof TacOperand.Const kc
          && kc.kind() == TacOperand.Const.Kind.NULL;
      if (storeNull) {
        for (Obj o : points(signature, recv, ctx)) {
          nullContainerCells.add(new ContainerCell(o, key, mapCell));
        }
      } else if (valueIdx < args.size() && args.get(valueIdx) instanceof TacOperand.TacVar value) {
        Set<Obj> stored = points(signature, value, ctx);
        for (Obj o : points(signature, recv, ctx)) {
          // R1112: a literal key populates ONLY its own cell.
          //
          // Writing the ANY_KEY cell as well is the "conservative" choice and it is wrong here:
          // every literal-keyed read consults ANY_KEY, so one extra write under a literal makes
          // every entry of the container appear to hold every object ever stored. `get("b")`
          // would then return the object put under "a" -- and a guard on one entry would silence a
          // dereference of an unrelated one.
          //
          // The unsound direction is covered on the read side instead: an unrecognised key
          // consults every cell, and an unrecognised write populates ANY_KEY.
          addContainer(new ContainerCell(o, key, mapCell), stored);
        }
      }
    }
    TacOperand.TacVar dest = asVar(ins.dest);
    if (isLoad && dest != null) {
      for (Obj o : points(signature, recv, ctx)) {
        if (ContainerCell.ANY_KEY.equals(key)) {
          // An unrecognised key could be any entry, so every recorded cell of this container is a
          // candidate. This is where the conservatism lives: the write side stays exact, and the
          // imprecision is confined to accesses the IR could not pin down.
          for (ContainerCell cell : containerCellsOf(o, mapCell)) {
            add(key(signature, dest, ctx), containerObjects(cell));
          }
          for (ContainerCell cell : nullContainerCellsOf(o, mapCell)) {
            nullVars.add(key(signature, dest, ctx));
          }
        } else {
          // A literal key is exact: its own cell, plus the ANY cell holding facts written under an
          // unrecognised key. No other entry can leak in.
          add(key(signature, dest, ctx), containerObjects(new ContainerCell(o, key, mapCell)));
          add(
              key(signature, dest, ctx),
              containerObjects(new ContainerCell(o, ContainerCell.ANY_KEY, mapCell)));
          if (nullContainerCells.contains(new ContainerCell(o, key, mapCell))
              || nullContainerCells.contains(new ContainerCell(o, ContainerCell.ANY_KEY, mapCell))) {
            nullVars.add(key(signature, dest, ctx));
          }
        }
      }
    }
  }

  /** Every null-marked cell of one container object, used when the access key is unknown. */
  private java.util.List<ContainerCell> nullContainerCellsOf(Obj container, boolean mapCell) {
    java.util.List<ContainerCell> out = new ArrayList<>();
    for (ContainerCell cell : nullContainerCells) {
      if (cell.container().equals(container) && cell.mapCell() == mapCell) {
        out.add(cell);
      }
    }
    return out;
  }

  // ── R1114: the null direction, as a query ──────────────────────────────────────────

  /**
   * R1114: may this local hold the null literal?
   *
   * <p>Tri-valued, and the third state is the one that matters. {@code false} means the analysis
   * found no null path; it does <em>not</em> mean the reference is proven non-null, because an
   * unmodelled write (through a parameter, across a TU boundary, or via a callee the solver could
   * not resolve) leaves no trace here at all.
   *
   * <p>A consumer must therefore treat {@code false} as "nothing known", never as "safe". That is
   * the same rule that governs the empty points-to set, and it is why this is a may-query and not a
   * proof obligation.
   */
  public boolean mayBeNull(String signature, TacOperand.TacVar v, Context ctx) {
    return v != null && nullVars.contains(key(signature, v, ctx));
  }

  public boolean mayBeNull(String signature, TacOperand.TacVar v) {
    return mayBeNull(signature, v, Context.empty());
  }

  /** R1114: may this field slot hold null? Same "unknown is not false" contract. */
  public boolean fieldMayBeNull(AbstractField slot) {
    return slot != null && nullFields.contains(slot);
  }

  /** R1114: may this array cell hold null? */
  public boolean arrayMayBeNull(AbstractArrayIndex cell) {
    return cell != null && nullArrayCells.contains(cell);
  }

  /** R1114: may this container cell hold null? */
  public boolean containerMayBeNull(Obj container, String key, boolean mapCell) {
    return container != null
        && (nullContainerCells.contains(new ContainerCell(container, key, mapCell))
            || nullContainerCells.contains(
                new ContainerCell(container, ContainerCell.ANY_KEY, mapCell)));
  }

  /** R1114: may this method return null in this context? */
  public boolean returnMayBeNull(String signature, Context ctx) {
    return signature != null && nullReturns.contains(new ContextKey(signature, qual(ctx)));
  }

  /**
   * R1114: every local of {@code signature} that may hold null.
   *
   * <p>Exists so a consumer does not have to iterate locals itself when it needs a suppression set.
   */
  public Set<TacOperand.TacVar> nullableVars(String signature, Context ctx) {
    Set<TacOperand.TacVar> out = new java.util.LinkedHashSet<>();
    TacFunction fn = functions.get(signature);
    if (fn == null) return out;
    for (VarKey vk : nullVars) {
      if (!vk.signature().equals(signature)) continue;
      if (!policy.isContextSensitive() || vk.context().equals(qual(ctx))) {
        TacOperand.TacVar v = varOf(signature, vk.varName());
        if (v != null) out.add(v);
      }
    }
    return out;
  }

  /** The declared TacVar for a name in a function, or null when the function is unknown. */
  private TacOperand.TacVar varOf(String signature, String name) {
    TacFunction fn = functions.get(signature);
    if (fn == null) return null;
    for (TacOperand.TacVar v : fn.locals()) {
      if (v.name().equals(name)) return v;
    }
    for (TacOperand.TacVar v : fn.params()) {
      if (v.name().equals(name)) return v;
    }
    return null;
  }

  // ── queries for checkers ──────────────────────────────────────────────

  /** The objects a variable may point at. Never null; empty means "unknown / nothing tracked". */
  public Set<Obj> pointsTo(String signature, TacOperand.TacVar v, Context ctx) {
    return Set.copyOf(points(signature, v, ctx));
  }

  /** Context-free convenience for a single-context analysis. */
  public Set<Obj> pointsTo(String signature, TacOperand.TacVar v) {
    return pointsTo(signature, v, Context.empty());
  }

  /**
   * May these two references be the same object? Unknown references answer {@code false}.
   *
   * <p>Both variables must belong to {@code signature}: the key is {@code (function, name)}, so
   * passing two locals from different methods under one signature would silently compare the same
   * entry against itself and answer {@code true} for any non-empty set. That is exactly the shape
   * of bug a caller writes when it forgets the method boundary, so the query takes one signature
   * and the contract is "both of these are locals of this function".
   */
  public boolean mayAlias(String signature, TacOperand.TacVar a, TacOperand.TacVar b, Context ctx) {
    if (signature == null || a == null || b == null) return false;
    Set<Obj> sa = points(signature, a, ctx);
    Set<Obj> sb = points(signature, b, ctx);
    if (sa.isEmpty() || sb.isEmpty()) return false;
    for (Obj o : sa) {
      if (sb.contains(o)) return true;
    }
    return false;
  }

  public boolean mayAlias(String signature, TacOperand.TacVar a, TacOperand.TacVar b) {
    return mayAlias(signature, a, b, Context.empty());
  }

  /**
   * Cross-method alias query: may a local of {@code sigA} and a local of {@code sigB} be the same
   * object?
   *
   * <p>Needed because same-named locals in different methods are different variables -- asking
   * {@link #mayAlias} with one signature would compare one entry against itself. This is the query
   * a caller wants when a value crosses a boundary, e.g. "does the object the caller passed in
   * alias the one the callee stashed in a field".
   */
  public boolean mayAliasAcross(
      String sigA, TacOperand.TacVar a, String sigB, TacOperand.TacVar b, Context ctx) {
    if (sigA == null || sigB == null || a == null || b == null) return false;
    Set<Obj> sa = points(sigA, a, ctx);
    Set<Obj> sb = points(sigB, b, ctx);
    if (sa.isEmpty() || sb.isEmpty()) return false;
    for (Obj o : sa) {
      if (sb.contains(o)) return true;
    }
    return false;
  }

  /** Every object reachable from any reference this analysis knows about. */
  public Set<Obj> allObjects() {
    Set<Obj> all = new LinkedHashSet<>();
    for (Set<Obj> s : pts.values()) {
      all.addAll(s);
    }
    return all;
  }

  /**
   * R1108: the objects {@code receiver} may point at, restricted to one context.
   *
   * <p>Exposed so an alias-aware checker can start from a suspect reference and ask "which other
   * references in this function could be the same object?", rather than having to enumerate the
   * function's locals itself and issue a pairwise {@link #mayAlias} per combination.
   *
   * <p>Returns an empty set for an unknown reference. Callers must treat empty as "no alias
   * information" and fall back to their previous behaviour -- never as "definitely not aliased
   * with anything", because that reading would turn missing information into a suppressed finding.
   */
  public Set<Obj> objectsOf(String signature, TacOperand.TacVar receiver, Context ctx) {
    if (signature == null || receiver == null) return Set.of();
    return Set.copyOf(points(signature, receiver, ctx));
  }

  /**
   * R1108: every local of {@code signature} that may hold an object also reachable from
   * {@code receiver}.
   *
   * <p>This is the "alias class" of a reference, and it is what makes a null-ness guard transferable
   * between names: if {@code a} is proven non-null on this path and {@code b} is in {@code a}'s alias
   * class, then {@code b} denotes the same object and is non-null here too.
   *
   * <p>The result always contains {@code receiver} itself when its points-to set is non-empty, so
   * callers can use membership without special-casing the seed. Empty when the receiver is unknown.
   */
  public java.util.Set<TacOperand.TacVar> aliasesOf(
      String signature, TacOperand.TacVar receiver, Context ctx) {
    java.util.Set<TacOperand.TacVar> out = new java.util.LinkedHashSet<>();
    Set<Obj> seed = objectsOf(signature, receiver, ctx);
    if (seed.isEmpty()) return out;
    TacFunction fn = functions.get(signature);
    if (fn == null) return out;
    java.util.List<TacOperand.TacVar> candidates = new ArrayList<>(fn.locals());
    candidates.addAll(fn.params());
    for (TacOperand.TacVar candidate : candidates) {
      if (mayAlias(signature, receiver, candidate, ctx)) {
        out.add(candidate);
      }
    }
    return out;
  }

  /**
   * R1112: does this reference reach {@code candidate} through a container cell?
   *
   * <p>Separate from {@link #mayAlias} because the answer may hold even when neither variable has a
   * points-to set of its own: the value lives in a map cell, and the two references are the writer
   * and the reader of that cell. Points-to answers that question for fields and arrays; this is the
   * same reachability stated over container cells.
   */
  public boolean linkedViaContainer(
      String signature, TacOperand.TacVar writer, TacOperand.TacVar reader, Context ctx) {
    if (signature == null || writer == null || reader == null) return false;
    Set<Obj> written = points(signature, writer, ctx);
    Set<Obj> read = points(signature, reader, ctx);
    if (written.isEmpty() || read.isEmpty()) return false;
    for (ContainerCell cell : containerCells.keySet()) {
      Set<Obj> inCell = containerCells.get(cell);
      if (inCell == null) continue;
      boolean writerTouches = written.stream().anyMatch(inCell::contains);
      boolean readerTouches = read.stream().anyMatch(inCell::contains);
      if (writerTouches && readerTouches) return true;
    }
    return false;
  }

  public Map<String, TacFunction> functions() {
    return Map.copyOf(functions);
  }

  public Set<Obj> released() {
    return Set.copyOf(released);
  }

  // ── helpers ───────────────────────────────────────────────────────────

  private static TacOperand.TacVar asVar(Object operand) {
    return operand instanceof TacOperand.TacVar v ? v : null;
  }

  private static String fieldNameOf(TacInstruction ins) {
    return ins.fieldName == null || ins.fieldName.isEmpty() ? "<field>" : ins.fieldName;
  }

  private static int literalIndex(TacInstruction ins) {
    Object raw = ins.lhs2;
    if (raw instanceof TacOperand.Const c && c.kind() == TacOperand.Const.Kind.INT) {
      Integer v = (Integer) c.value();
      return v == null ? 0 : v;
    }
    return 0;
  }

  /**
   * The declared signature of a CALL's callee, or null when it cannot be resolved.
   *
   * <p>The IR spells a call two ways and they do not match each other: a same-class call is
   * {@code make/0} or {@code Class.method/arity}, while a declared function is
   * {@code make():Object} or {@code Class.method(Type):Ret}. Both namespaces have to be reduced to
   * {@code name/arity} before a callee can be found, which is the same normalisation the callgraph
   * strategies perform.
   */
  private String resolveCallee(TacInstruction ins) {
    String sig = ins.calleeSig;
    if (sig == null || sig.isEmpty()) return null;
    if (functions.containsKey(sig)) return sig;
    int slash = sig.lastIndexOf('/');
    if (slash <= 0) return null;
    String name = sig.substring(0, slash);
    int arity = parseArity(sig.substring(slash + 1));
    if (arity < 0) return null;
    String key = name + "/" + arity;
    if (byNameArity.containsKey(key)) return byNameArity.get(key);
    return null;
  }

  /** `2`, `+2`, `?2` -> 2; anything else -> -1. */
  private static int parseArity(String suffix) {
    StringBuilder digits = new StringBuilder();
    for (int i = 0; i < suffix.length(); i++) {
      char c = suffix.charAt(i);
      if (Character.isDigit(c)) digits.append(c);
    }
    return digits.length() == 0 ? -1 : Integer.parseInt(digits.toString());
  }

  private static boolean isResourceType(String type) {
    if (type == null) return false;
    return type.contains("InputStream")
        || type.contains("OutputStream")
        || type.contains("Reader")
        || type.contains("Writer")
        || type.contains("Connection")
        || type.contains("Statement")
        || type.contains("ResultSet")
        || type.contains("Socket")
        || type.contains("Channel");
  }

  /** Recorded for consumers that distinguish resources from other allocations. */
  public Set<String> resourceAllocSites() {
    return Set.copyOf(resourceAllocSites);
  }

  /** The declared type of a variable, for consumers that need it alongside the points-to set. */
  public static String typeOf(TacOperand.TacVar v) {
    TacType t = v == null ? null : v.type();
    if (t == null || t.kind() != TacType.Kind.CLASS) return null;
    return t.className();
  }
}