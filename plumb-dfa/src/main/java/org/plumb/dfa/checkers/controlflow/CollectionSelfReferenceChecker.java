package org.plumb.dfa.checkers.controlflow;

import java.util.HashSet;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.trace.PatternTrace;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.rules.RuleDef;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * CWE-754 (Improper Check for Unusual or Exceptional Conditions) self-reference check, in the
 * unified DFA engine.
 *
 * <p>Detects collection operations where the receiver and the explicit argument are the same
 * variable (or alias of it). These calls are at best useless and at worst catastrophic:
 *
 * <ul>
 *   <li>{@code list.addAll(list)} ? adds the list to itself, which (a) is a no-op for some
 *       implementations, (b) throws CME during the iteration in {@code AbstractCollection.addAll},
 *       (c) silently produces an infinite loop in hand-rolled implementations.
 *   <li>{@code list.removeAll(list)} ? removes every element of the list from itself, which empties
 *       the list (probably not what the caller meant).
 *   <li>{@code list.retainAll(list)} ? keeps every element that's also in the list, i.e. keeps
 *       everything. No-op (a hint the caller meant a different collection).
 *   <li>{@code list.contains(list)} ? a collection always contains itself; the result is always
 *       true. Almost certainly a bug (meant to test for a sub-element).
 * </ul>
 *
 * <p><b>Why DFA, not AST.</b> Detecting {@code list.addAll(list)} syntactically is easy, but the
 * more interesting case is aliasing: {@code a = list; b = list; a.addAll(b);} is the same bug. The
 * DFA's per-function data flow tracks the aliasing fact ? after the two {@code MOVE}s, both {@code
 * a} and {@code b} point to the same collection, and the call to {@code a.addAll(b)} is a
 * self-reference that AST-level pattern matching would miss.
 *
 * <p><b>Limits.</b> Aliasing through field loads ({@code this.list} ↔ {@code that.list}) is not
 * tracked; only local-variable aliasing. CWE-754 in a single function with local aliasing is the
 * common case; cross-function aliasing is left for a later round that would need a points-to model.
 */
public final class CollectionSelfReferenceChecker implements DfaChecker {

  /**
   * Per-function: mapping from a variable to the set of "canonical names" it may point to.
   * Initially each var maps to itself. After {@code a = b}, both {@code a} and {@code b} share a
   * canonical name (the union of their prior sets).
   */
  private final java.util.Map<String, Set<String>> aliasClasses = new java.util.HashMap<>();

  /** Per-function: which calls have already fired (dedup). */
  private final Set<String> alreadyFired = new HashSet<>();

  @Override
  public String name() {
    return "CollectionSelfReference";
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    aliasClasses.clear();
    alreadyFired.clear();
    // Each parameter starts in its own alias class.
    for (TacOperand.TacVar p : fn.params()) {
      aliasClasses.put(p.name(), new HashSet<>(java.util.List.of(p.name())));
    }
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    switch (ins.kind) {
      case MOVE -> {
        if (ins.dest instanceof TacOperand.TacVar dest
            && ins.lhs1 instanceof TacOperand.TacVar src) {
          Set<String> srcClass = aliasClasses.get(src.name());
          Set<String> destClass = aliasClasses.get(dest.name());
          if (srcClass == null) {
            srcClass = new HashSet<>(java.util.List.of(src.name()));
            aliasClasses.put(src.name(), srcClass);
          }
          if (destClass == null) {
            destClass = new HashSet<>(java.util.List.of(dest.name()));
            aliasClasses.put(dest.name(), destClass);
          }
          // Union: dest now points to everything src
          // points to. If src and dest had disjoint
          // classes, the union of both names
          // represents the merged class.
          Set<String> merged = new HashSet<>(srcClass);
          merged.addAll(destClass);
          for (String n : merged) {
            aliasClasses.put(n, merged);
          }
        }
      }
      case CALL -> {
        if (ins.calleeSig == null) return;
        if (!isSelfRefCall(ins.calleeSig)) return;
        if (ins.args.size() < 2) return;
        TacOperand first = ins.args.get(0);
        TacOperand second = ins.args.get(1);
        if (!(first instanceof TacOperand.TacVar recv)) return;
        if (!(second instanceof TacOperand.TacVar arg)) return;
        // self-ref iff recv and arg share an alias class
        Set<String> recvClass = aliasClasses.get(recv.name());
        Set<String> argClass = aliasClasses.get(arg.name());
        if (recvClass == null) {
          recvClass = new HashSet<>(java.util.List.of(recv.name()));
          aliasClasses.put(recv.name(), recvClass);
        }
        if (argClass == null) {
          argClass = new HashSet<>(java.util.List.of(arg.name()));
          aliasClasses.put(arg.name(), argClass);
        }
        if (!java.util.Collections.disjoint(recvClass, argClass)
            && alreadyFired.add(recv.name() + "|" + arg.name())) {
          emit(ctx, ins, fn, recv.name(), arg.name(), ins.calleeSig);
        }
      }
      default -> {
        /* ignore */
      }
    }
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    return FunctionSummary.empty();
  }

  private static void emit(
      AnalysisContext ctx,
      TacInstruction ins,
      TacFunction fn,
      String recv,
      String arg,
      String callee) {
    // v2 trace: single-node VIOLATION
    // with COLLECTION_SELF_REF op + a Free
    // operand encoding the "self-ref: <callee>
    // on <recv> with <arg>" shape so the AI
    // dedup layer can see the receiver-arg
    // equality without regex-parsing the
    // legacy flow string.
    PatternTrace.reportFree(
        ctx,
        fn,
        ins,
        TraceNode.Op.COLLECTION_SELF_REF,
        "self-ref: " + callee + " on " + recv + " with " + arg,
        "CollectionSelfReference",
        "dfa-controlflow-collection-self-reference",
        "Collection "
            + recv
            + " is passed as both the receiver and "
            + "the argument of "
            + callee
            + " — self-reference almost always indicates a bug",
        RuleDef.Severity.MEDIUM,
        "CWE-754",
        "self-ref: " + callee + " on " + recv + " with " + arg);
  }

  private static boolean isSelfRefCall(String sig) {
    if (sig == null) return false;
    String method = methodName(sig);
    switch (method) {
      case "addAll":
      case "removeAll":
      case "retainAll":
      case "containsAll":
      case "contains":
        return true;
      default:
        return false;
    }
  }

  /** See {@code IteratorConcurrentModificationChecker#methodName}. */
  private static String methodName(String sig) {
    int lastDot = sig.lastIndexOf('.');
    int hash = sig.lastIndexOf('#');
    int sep = Math.max(lastDot, hash);
    if (sep < 0) {
      int slash = sig.indexOf('/');
      return slash < 0 ? sig : sig.substring(0, slash);
    }
    String tail = sig.substring(sep + 1);
    int slash = tail.indexOf('/');
    return slash < 0 ? tail : tail.substring(0, slash);
  }
}
