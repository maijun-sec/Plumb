package org.plumb.dfa.cg;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a {@link CallGraph} from a {@link TacProgram}.
 *
 * <h2>Algorithm</h2>
 *
 * <ol>
 *   <li>Walk every function; emit one {@link CallSite} per CALL / INVOKE instruction.
 *   <li>Resolve the callee signature from the instruction's {@code calleeSig} field (already set by
 *       the TAC translator).
 *   <li>Compute the reverse post-order (RPO) for bottom-up summary computation.
 *   <li>Compute entry points: functions with no incoming calls (assuming they are reachable from
 *       "outside").
 * </ol>
 *
 * <p>Library calls (e.g. {@code java.lang.String#length}) are NOT registered as {@link CallSite}s
 * by default ? they live in the JRE which the analysis treats as opaque. Callers wanting a
 * library-aware analysis can override the library filter via {@link
 * #withIncludeLibraries(boolean)}.
 */
public final class CallGraphBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(CallGraphBuilder.class);

  /**
   * Library function signatures to exclude from the call graph (treated as opaque by the analysis).
   */
  private static final Set<String> DEFAULT_LIBRARY_PREFIXES =
      Set.of(
          "java.lang.Object#",
          "java.lang.String#",
          "java.lang.Math#",
          "java.lang.Integer#",
          "java.lang.Long#",
          "java.lang.Double#",
          "java.lang.Float#",
          "java.lang.Boolean#",
          "java.lang.Character#",
          "java.lang.Number#",
          "java.lang.System#identityHashCode",
          "java.lang.Throwable#",
          "java.lang.Comparable#",
          "java.util.Objects#",
          "java.util.ArrayList#",
          "java.util.LinkedList#",
          "java.util.HashMap#",
          "java.util.HashSet#",
          "java.util.LinkedHashMap#",
          "java.util.LinkedHashSet#",
          "java.util.TreeMap#",
          "java.util.TreeSet#",
          "java.util.Collections#",
          "java.util.Iterator#",
          "java.util.List#",
          "java.util.Map#",
          "java.util.Set#",
          "java.util.Collection#");

  private final TacProgram program;
  private boolean includeLibraries = false;

  public CallGraphBuilder(TacProgram program) {
    this.program = program;
  }

  public CallGraphBuilder withIncludeLibraries(boolean v) {
    this.includeLibraries = v;
    return this;
  }

  public CallGraph build() {
    if (program == null) {
      // Used by strategies that already
      // pre-built forward/reverse/nodes.
      throw new IllegalStateException(
          "CallGraphBuilder with null program: use "
              + "CallGraphBuilder.buildFromMaps(...) instead");
    }
    return buildFromProgram(program);
  }

  /**
   * Build a {@link CallGraph} from pre-populated forward / reverse / nodes maps. Used by the
   * various strategies that build their own graph internally (CHA, RTA, stub) and want to reuse
   * this builder's RPO / entry-point computation.
   */
  public static CallGraph buildFromMaps(
      Set<FunctionNode> nodes,
      Map<FunctionNode, List<CallSite>> forward,
      Map<FunctionNode, List<CallSite>> reverse) {
    List<FunctionNode> rpo = computeRpo(forward, nodes);
    Set<FunctionNode> entryPoints = computeEntryPoints(reverse, nodes);
    return new CallGraph(nodes, forward, reverse, rpo, entryPoints);
  }

  private CallGraph buildFromProgram(TacProgram prog) {
    final Map<FunctionNode, List<CallSite>> forward = new LinkedHashMap<>();
    final Map<FunctionNode, List<CallSite>> reverse = new LinkedHashMap<>();
    final Set<FunctionNode> nodes = new LinkedHashSet<>();

    // Sig normalization map. The IR
    // builder records CALLs with a
    // bare-name calleeSig (e.g.
    // {@code "buildQuery/1"}) for
    // same-class calls, but the
    // function's canonical
    // {@code TacFunction.signature()}
    // (e.g.
    // {@code "buildQuery(String):String"})
    // is what {@code program.functions()}
    // uses. Without normalization, the
    // call graph has two different
    // {@code FunctionNode}s for the
    // same target, and the RPO puts the
    // caller (bad) before the callee
    // (buildQuery) because they look
    // like different nodes.
    //
    // The map is keyed by
    // {@code bare-name/arity} and
    // records the canonical sig. When
    // we see a calleeSig like
    // {@code "buildQuery/1"} and the
    // map has an entry, we use the
    // canonical sig as the
    // {@code FunctionNode} key so
    // the summary-fold path and the
    // per-function analysis order both
    // work correctly.
    // Multi-value map for overload
    // disambiguation. The single-value
    // variant used {@code putIfAbsent},
    // which silently dropped all but
    // the first function with a given
    // bare-name+arity. For overloaded
    // methods (e.g. {@code foo(int)} and
    // {@code foo(String)}), the IR
    // builder may record the bare-name
    // {@code "foo/1"} for a same-class
    // call, but the resolution would
    // pick whichever function the
    // {@code putIfAbsent} saw first —
    // not necessarily the right one.
    // The multi-value map records ALL
    // candidate functions; the
    // resolution still picks the
    // first, but the map is unambiguous
    // about how many candidates exist.
    final java.util.Map<String, java.util.List<String>> bareNameToCanonical = new HashMap<>();
    for (TacFunction fn : program.functions()) {
      String bare = bareNameAndArity(fn.signature());
      if (bare != null) {
        bareNameToCanonical.computeIfAbsent(bare, k -> new ArrayList<>()).add(fn.signature());
      }
    }

    for (TacFunction fn : program.functions()) {
      FunctionNode caller = new FunctionNode(fn.signature());
      nodes.add(caller);
      forward.putIfAbsent(caller, new ArrayList<>());

      final int n = fn.instructions().size();
      for (int i = 0; i < n; i++) {
        TacInstruction ins = fn.instructionAt(i);
        if (!isCall(ins)) continue;
        String calleeSig = ins.calleeSig;
        if (calleeSig == null || calleeSig.isEmpty()) continue;
        if (!includeLibraries && isLibrary(calleeSig)) continue;
        // If the calleeSig is a
        // bare-name (e.g.
        // {@code "buildQuery/1"}) that
        // doesn't match a function in
        // the program, look it up in
        // the bare-name map and use
        // the canonical sig as the
        // FunctionNode key. The
        // canonical sig is the same
        // key that {@code
        // program.function(...)} uses
        // in
        // {@code DfaEngine.orderedFunctions},
        // so the RPO will resolve
        // correctly.
        // Overload disambiguation:
        // when there are multiple
        // candidate functions for a
        // bare-name (e.g. {@code
        // foo/1} matches both
        // {@code foo(String):void}
        // and
        // {@code foo(int):void}),
        // the multi-value map picks
        // the first candidate
        // blindly. We use the actual
        // call arg types to pick the
        // right candidate. For
        // single-candidate bare-names,
        // behavior is unchanged.
        List<TacOperand> argList = ins.args;
        String nodeSig =
            r48ResolveCalleeSigWithArgs(program, calleeSig, bareNameToCanonical, argList);
        FunctionNode callee = new FunctionNode(nodeSig);
        nodes.add(callee);
        forward.putIfAbsent(callee, new ArrayList<>());
        reverse.putIfAbsent(callee, new ArrayList<>());
        // For now, all calls default to DIRECT.
        // Virtual-dispatch resolution (CHA) is handled by a
        // separate call-graph strategy.
        CallSite.Kind kind = CallSite.Kind.DIRECT;
        CallSite cs = new CallSite(caller, callee, i, ins.line, kind);
        forward.get(caller).add(cs);
        reverse.get(callee).add(cs);
      }
    }

    CallGraph cg = buildFromMaps(nodes, forward, reverse);
    LOG.info(
        "CallGraph built: {} nodes, {} call sites, {} entry points",
        nodes.size(),
        sumSizes(forward),
        cg.entryPoints().size());
    return cg;
  }

  /**
   * Extract a {@code bare-name/arity} alias from a canonical function signature, e.g. {@code
   * "buildQuery(String):String"} → {@code "buildQuery/1"}. Returns {@code null} if the sig can't be
   * parsed (e.g. malformed or empty).
   */
  public static String bareNameAndArity(String sig) {
    if (sig == null || sig.isEmpty()) return null;
    int paren = sig.indexOf('(');
    // The legacy {@code G#use} / {@code G#use()} Jimple-style
    // FQN forms had no slash and no arity, so they round-tripped
    // through {@code bareNameAndArity} as {@code "G#use"} /
    // {@code "G#use/0"} — different keys for the same method.
    // Normalise the {@code #} form to the {@code .} form so both
    // shapes yield the same bare-name output.
    String normalized = sig;
    if (normalized.indexOf('#') >= 0 && normalized.indexOf('(') < 0) {
      // No-parens hash form
      // (e.g. {@code "G#use"}).
      // Convert to dot form so
      // the rest of the parser
      // (which looks for {@code .}
      // before {@code (}) works.
      normalized = normalized.replaceFirst("#", ".");
    }
    paren = normalized.indexOf('(');
    if (paren < 0) {
      // Already a bare-name form
      // like
      // {@code "QueryBuilder.setQuery/1"}
      // or
      // {@code "setQuery/1"}.
      // Return as-is after stripping
      // the optional class prefix.
      int slash = normalized.lastIndexOf('/');
      if (slash < 0) return normalized;
      // Ensure it's
      // actually a bare-name
      // (e.g. "name/1"), not
      // a FQN like
      // "java/lang/String"
      // — but for our
      // purposes, the
      // slash-separated
      // "name/arity" form is
      // the bare name.
      return normalized;
    }
    if (paren == 0) return null;
    String methodName = normalized.substring(0, paren);
    int dot = methodName.lastIndexOf('.');
    if (dot >= 0) {
      methodName = methodName.substring(dot + 1);
    }
    int close = normalized.indexOf(')', paren);
    if (close < 0) return null;
    String paramList = normalized.substring(paren + 1, close);
    int arity = 0;
    if (!paramList.isEmpty()) {
      int depth = 0;
      int last = 0;
      for (int i = 0; i < paramList.length(); i++) {
        char c = paramList.charAt(i);
        if (c == '<') depth++;
        else if (c == '>') depth--;
        else if (c == ',' && depth == 0) {
          if (i > last) arity++;
          last = i + 1;
        }
      }
      if (paramList.length() > last) arity++;
    }
    return methodName + "/" + arity;
  }

  /**
   * Resolve a CALL's {@code calleeSig} to a {@code FunctionNode} key with overload disambiguation
   * based on the actual call arg types.
   *
   * <p>Three-tier resolution:
   *
   * <ol>
   *   <li>If the raw {@code calleeSig} already matches a function in the program, return it as-is.
   *   <li>Otherwise, look up the bare-name+arity in the normalization map:
   *       <ul>
   *         <li>If single candidate, return it.
   *         <li>If multiple candidates, use the actual call arg types to pick the right one
   *             (overload disambiguation). "First wins" is the fallback when no type match is
   *             found.
   *       </ul>
   *   <li>Otherwise, return the raw {@code calleeSig} as-is (becomes a placeholder FunctionNode).
   * </ol>
   *
   * <p>The overload disambiguation compares the actual arg types (extracted from {@code args}) with
   * the param types of each candidate function (parsed from the canonical sig via {@link
   * FunctionSignature#parse}). A match is exact-by-FQN. If multiple candidates match, the first one
   * wins. If no candidates match, the first one wins as the fallback.
   *
   * <p>For instance methods, the first arg is the receiver ? the param types start from the SECOND
   * arg. The candidate function's param types are compared with {@code args[1..]} (skipping
   * args[0]). For static methods, the first arg is the first user param.
   *
   * <p>Whether the call is instance or static is detected by checking if any candidate has a "this"
   * prefix in its param list. The heuristic: for each candidate, if its first param is named "this"
   * (or matches the enclosing class), treat the call as instance and skip args[0]; otherwise treat
   * as static. Keep it simple by using the {@code args} size to check both: the candidate with the
   * matching arity (after skipping receiver for instance methods) is preferred.
   */
  public static String r48ResolveCalleeSigWithArgs(
      TacProgram program,
      String calleeSig,
      java.util.Map<String, java.util.List<String>> bareNameToCanonical,
      List<TacOperand> args) {
    // Delegate to the shared core
    // that takes a function-existence
    // predicate instead of a
    // TacProgram. Per-TU uses
    // {@code program.function(sig)
    // != null} as the predicate;
    // Global uses
    // {@code store.functionBySignature(sig).isPresent()}.
    return r48ResolveCalleeSigCore(
        sig -> program.function(sig) != null, calleeSig, bareNameToCanonical, args);
  }

  /**
   * Core overload of {@link #r48ResolveCalleeSigWithArgs} that takes a function-existence predicate
   * instead of a {@link TacProgram}. This lets {@link GlobalCallGraphBuilder} (which has no single
   * {@code TacProgram} ? it has a {@link GlobalTACStore}) share the exact same arg-type-aware
   * overload disambiguation logic without re-implementing it.
   *
   * <p>Per-TU code path uses {@code sig -> program.function(sig) != null} as the predicate. Global
   * uses {@code sig -> store.functionBySignature(sig).isPresent()}. The behaviour is otherwise
   * identical to {@code r48ResolveCalleeSigWithArgs}.
   */
  public static String r48ResolveCalleeSigCore(
      java.util.function.Predicate<String> existsFn,
      String calleeSig,
      java.util.Map<String, java.util.List<String>> bareNameToCanonical,
      List<TacOperand> args) {
    if (existsFn.test(calleeSig)) {
      return calleeSig;
    }
    String bare = bareNameAndArity(calleeSig);
    // Strip the class prefix (if
    // any) to get the bare name for
    // lookup. e.g. for
    // "QueryBuilder.setQuery/1"
    // (the form emitted by the
    // IR builder for cross-class
    // calls), strip "QueryBuilder."
    // to get "setQuery/1".
    if (bare != null) {
      int slash = bare.indexOf('/');
      if (slash > 0) {
        int dot = bare.lastIndexOf('.', slash);
        if (dot > 0) {
          bare = bare.substring(dot + 1);
        }
      }
    }
    if (bare == null) {
      return calleeSig;
    }
    java.util.List<String> candidates = bareNameToCanonical.get(bare);
    if (candidates == null || candidates.isEmpty()) {
      return calleeSig;
    }
    if (candidates.size() == 1) {
      return candidates.get(0);
    }
    // Overload disambiguation.
    // Build the list of actual
    // arg types (FQN strings) from
    // the call args. For a
    // non-static call, skip
    // args[0] (the receiver). For
    // a static call, all args are
    // user args.
    if (args == null || args.isEmpty()) {
      // No arg type info; fall
      // back to first candidate.
      return candidates.get(0);
    }
    // Try each candidate, picking
    // the one whose parsed param
    // types best match the actual
    // arg types. "Best match" =
    // exact FQN match for every
    // param.
    // Parse param types directly
    // from the canonical sig
    // string
    // {@code name(p1,p2,...):ret}
    // — FunctionSignature.parse
    // doesn't handle this format
    // (it expects Class#name or
    // JNI-style Lpkg/Class;.name),
    // so we extract params
    // manually.
    String firstMatch = null;
    for (String candSig : candidates) {
      java.util.List<String> paramTypes = extractParamTypes(candSig);
      if (paramTypes == null) continue;
      // Determine if this
      // candidate is an instance
      // method (first param is
      // "this" or matches the
      // enclosing class). For
      // instance methods, skip
      // args[0].
      int startIdx = 0;
      // Heuristic: if args.size()
      // > paramTypes.size() and
      // the leading arg has a
      // class type, treat the
      // call as instance and
      // skip args[0].
      if (args.size() > paramTypes.size()
          && args.get(0) instanceof TacOperand.TacVar recvVar
          && recvVar.type() != null
          && recvVar.type().kind() == TacType.Kind.CLASS) {
        startIdx = 1;
      }
      int expected = paramTypes.size();
      int actual = args.size() - startIdx;
      if (expected != actual) {
        // Arity mismatch — try
        // the next candidate
        // (could be a different
        // overload).
        continue;
      }
      // Check that every actual
      // arg type matches the
      // corresponding param
      // type.
      boolean allMatch = true;
      for (int i = 0; i < expected; i++) {
        TacOperand actualArg = args.get(startIdx + i);
        String actualType = tacOperandTypeName(actualArg);
        String expectedType = paramTypes.get(i);
        if (actualType == null) {
          allMatch = false;
          break;
        }
        if (!typesMatch(actualType, expectedType)) {
          allMatch = false;
          break;
        }
      }
      if (allMatch) {
        if (firstMatch == null) {
          firstMatch = candSig;
        }
        // We could break here
        // for "first match wins",
        // but iterating all
        // candidates lets us
        // detect ambiguity. For
        // now, first match wins.
        return candSig;
      }
    }
    if (firstMatch != null) return firstMatch;
    // No type match. Fall back to
    // the first candidate.
    return candidates.get(0);
  }

  /**
   * Extract param-type strings from a canonical sig of the form {@code name(p1,p2,...):ret}.
   * Returns the FQN-normalized list of param types. The normalization converts short type names
   * like {@code "String"} to {@code "java.lang.String"} so they match the FQN form produced by
   * {@link #tacOperandTypeName}. Returns {@code null} if the sig can't be parsed.
   */
  static java.util.List<String> extractParamTypes(String sig) {
    if (sig == null) return null;
    int l = sig.indexOf('(');
    int r = sig.indexOf(')');
    if (l < 0 || r < 0 || r < l) return null;
    String params = sig.substring(l + 1, r);
    java.util.List<String> out = new java.util.ArrayList<>();
    if (params.isEmpty()) return out;
    for (String p : params.split(",")) {
      String t = p.trim();
      if (t.isEmpty()) continue;
      out.add(normalizeTypeName(t));
    }
    return out;
  }

  /**
   * Normalize a type-name string to FQN form. Plumb's IR builder uses JavaParser's {@code
   * Type.asString()} which returns short names like {@code "String"} for {@code java.lang.String}.
   * The actual arg types (extracted from {@code TacVar.type()}) are also short for built-in types
   * like {@code String} but FQN for user classes. To make matching work uniformly, we normalize
   * both sides to FQN.
   */
  static String normalizeTypeName(String t) {
    if (t == null || t.isEmpty()) return t;
    switch (t) {
      case "String":
        return "java.lang.String";
      case "Object":
        return "java.lang.Object";
      case "Boolean":
        return "java.lang.Boolean";
      case "Byte":
        return "java.lang.Byte";
      case "Short":
        return "java.lang.Short";
      case "Character":
        return "java.lang.Character";
      case "Integer":
        return "java.lang.Integer";
      case "Long":
        return "java.lang.Long";
      case "Float":
        return "java.lang.Float";
      case "Double":
        return "java.lang.Double";
      case "Number":
        return "java.lang.Number";
      default:
        return t;
    }
  }

  /** Extract a type-name string (FQN or primitive wrapper name) from a {@link TacOperand}. */
  static String tacOperandTypeName(TacOperand op) {
    if (op == null) return null;
    if (op instanceof TacOperand.TacVar v) {
      TacType t = v.type();
      if (t == null) return null;
      switch (t.kind()) {
        case STRING:
          return "java.lang.String";
        case CLASS:
          return t.className();
        case BOOLEAN:
          return "boolean";
        case BYTE:
          return "byte";
        case SHORT:
          return "short";
        case CHAR:
          return "char";
        case INT:
          return "int";
        case LONG:
          return "long";
        case FLOAT:
          return "float";
        case DOUBLE:
          return "double";
        case ARRAY:
          {
            TacType comp = t.arrayComponent();
            return comp.kind() == TacType.Kind.CLASS
                ? comp.className() + "[]"
                : comp.toString() + "[]";
          }
        default:
          return t.toString();
      }
    }
    if (op instanceof TacOperand.Const c) {
      TacType ct = c.type();
      return tacOperandTypeNameFromType(ct);
    }
    return null;
  }

  /**
   * Internal: extract a type-name string from a {@link TacType}. Split out from {@link
   * #tacOperandTypeName} to handle the {@code Const} case where we already have a {@code TacType}
   * (not a {@code TacOperand}).
   */
  static String tacOperandTypeNameFromType(TacType t) {
    if (t == null) return null;
    switch (t.kind()) {
      case STRING:
        return "java.lang.String";
      case CLASS:
        return t.className();
      case BOOLEAN:
        return "boolean";
      case BYTE:
        return "byte";
      case SHORT:
        return "short";
      case CHAR:
        return "char";
      case INT:
        return "int";
      case LONG:
        return "long";
      case FLOAT:
        return "float";
      case DOUBLE:
        return "double";
      case ARRAY:
        {
          TacType comp = t.arrayComponent();
          return comp.kind() == TacType.Kind.CLASS
              ? comp.className() + "[]"
              : comp.toString() + "[]";
        }
      default:
        return t.toString();
    }
  }

  /**
   * Do two type-name strings match? Both sides should be FQN-normalized (via {@link
   * #normalizeTypeName}). The current implementation does exact string match; a future enhancement
   * could add primitive widening.
   */
  static boolean typesMatch(String actual, String expected) {
    if (actual == null || expected == null) return false;
    return actual.equals(expected);
  }

  /**
   * Resolve a CALL's {@code calleeSig} to a {@code FunctionNode} key. If the raw {@code calleeSig}
   * is already in the program (i.e. a function with that exact canonical sig exists), return it
   * as-is. Otherwise, look up the bare-name+arity in the normalization map and return the canonical
   * sig (if any). This bridges the gap between {@code "buildQuery/1"} (raw same-class) and {@code
   * "buildQuery(String):String"} (canonical).
   */
  private String r41ResolveCalleeSig(
      String calleeSig, java.util.Map<String, java.util.List<String>> bareNameToCanonical) {
    if (program.function(calleeSig) != null) {
      // Raw calleeSig matches a
      // function in the program.
      return calleeSig;
    }
    // Try the bare-name+arity alias.
    // If multiple functions share a
    // bare-name+arity (overloads),
    // pick the first candidate.
    // Without the actual param types
    // at the call site (which the IR
    // builder doesn't always record
    // for same-class calls), we can't
    // disambiguate further. The
    // earlier "first wins" was
    // hidden by the
    // {@code putIfAbsent}; the
    // multi-value map makes it
    // explicit.
    String bare = bareNameAndArity(calleeSig);
    if (bare == null) {
      return calleeSig;
    }
    java.util.List<String> candidates = bareNameToCanonical.get(bare);
    if (candidates == null || candidates.isEmpty()) {
      return calleeSig;
    }
    return candidates.get(0);
  }

  /**
   * Shared sig normalization helper for use by other call-graph strategies (RTA, VTA, OneCFA,
   * Global). Builds the {@code bareNameToCanonical} map from the program's functions. The map is
   * keyed by {@code bare-name/arity} and contains a list of candidate canonical sigs (multi-value
   * for overloads).
   */
  public static java.util.Map<String, java.util.List<String>> buildBareNameMap(TacProgram program) {
    java.util.Map<String, java.util.List<String>> map = new HashMap<>();
    for (TacFunction fn : program.functions()) {
      String bare = bareNameAndArity(fn.signature());
      if (bare != null) {
        map.computeIfAbsent(bare, k -> new ArrayList<>()).add(fn.signature());
      }
    }
    return map;
  }

  /**
   * Shared callee-sig resolution helper. Resolves a bare-name calleeSig to its canonical form using
   * the pre-built {@code bareNameToCanonical} map.
   */
  public static String resolveCalleeSigStatic(
      TacProgram program,
      String calleeSig,
      java.util.Map<String, java.util.List<String>> bareNameToCanonical) {
    if (program.function(calleeSig) != null) {
      return calleeSig;
    }
    String bare = bareNameAndArity(calleeSig);
    if (bare == null) {
      return calleeSig;
    }
    java.util.List<String> candidates = bareNameToCanonical.get(bare);
    if (candidates == null || candidates.isEmpty()) {
      return calleeSig;
    }
    return candidates.get(0);
  }

  private static boolean isCall(TacInstruction ins) {
    return ins.kind == TacInstruction.Kind.CALL;
  }

  private static boolean isLibrary(String sig) {
    for (String p : DEFAULT_LIBRARY_PREFIXES) {
      if (sig.startsWith(p)) return true;
    }
    return false;
  }

  /**
   * Iterative depth-first reverse post-order. Returns a list of all nodes such that every callee
   * appears before its caller (suitable for bottom-up summary computation).
   */
  public static List<FunctionNode> computeRpo(
      Map<FunctionNode, List<CallSite>> forward, Set<FunctionNode> nodes) {
    List<FunctionNode> order = new ArrayList<>();
    Set<FunctionNode> visited = new HashSet<>();
    Set<FunctionNode> onStack = new HashSet<>();
    for (FunctionNode root : nodes) {
      if (visited.contains(root)) continue;
      Deque<FunctionNode> stack = new ArrayDeque<>();
      stack.push(root);
      while (!stack.isEmpty()) {
        FunctionNode cur = stack.peek();
        if (visited.contains(cur)) {
          stack.pop();
          continue;
        }
        if (onStack.add(cur)) {
          boolean pushed = false;
          List<CallSite> cs = forward.get(cur);
          if (cs != null) {
            for (CallSite s : cs) {
              if (!visited.contains(s.callee())) {
                stack.push(s.callee());
                pushed = true;
              }
            }
          }
          if (!pushed) {
            visited.add(cur);
            onStack.remove(cur);
            stack.pop();
            order.add(cur);
          }
        } else {
          visited.add(cur);
          onStack.remove(cur);
          stack.pop();
          order.add(cur);
        }
      }
    }
    return order;
  }

  public static Set<FunctionNode> computeEntryPoints(
      Map<FunctionNode, List<CallSite>> reverse, Set<FunctionNode> nodes) {
    Set<FunctionNode> entries = new LinkedHashSet<>();
    for (FunctionNode n : nodes) {
      if (reverse.getOrDefault(n, List.of()).isEmpty()) {
        entries.add(n);
      }
    }
    return entries;
  }

  private static int sumSizes(Map<FunctionNode, List<CallSite>> m) {
    int s = 0;
    for (List<CallSite> l : m.values()) s += l.size();
    return s;
  }
}
