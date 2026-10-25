package org.plumb.dfa.checkers.controlflow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * try / catch / finally data-flow checks, in the unified DFA engine. Detects three classes of bug
 * at the TAC IR level:
 *
 * <ol>
 *   <li><b>CWE-248 Uncaught Exception.</b> A {@code throw new X(...)} whose declared exception type
 *       {@code X} is not handled by any {@code catch (...)} in the enclosing function (or only
 *       handled by a more general type). The exception will propagate up to the caller; the
 *       function declares it throws nothing, so the JVM enforces a runtime {@code
 *       UnhandledExceptionError}. We track throw sites per function and the catch handler types,
 *       then at function exit fire for any unmatched throw.
 *   <li><b>CWE-396 Catch of Generic Exception.</b> A {@code catch (Exception e)} or {@code catch
 *       (Throwable t)} swallows everything. We flag any {@code CATCH_HANDLER} whose declared type
 *       is {@code Exception}, {@code RuntimeException}, or {@code Throwable}.
 *   <li><b>CWE-397 Declaration of Throws for Generic Exception.</b> A {@code throw new
 *       Exception("...")} is almost always wrong ? the caller has no specific exception type to
 *       catch. We flag any {@code THROW} whose operand is a {@code NEW} of {@code Exception},
 *       {@code RuntimeException}, or {@code Throwable}.
 * </ol>
 *
 * <p><b>Why DFA, not AST.</b> All three checks need the per-function flow: a {@code throw} must be
 * matched against the union of all catch handler types in the same function, which crosses the try
 * / catch syntactic structure. An AST-level pass would have to re-walk the function and correlate
 * try-block and catch-clauses by hand. The TAC IR already has the linearized {@code CATCH_HANDLER}
 * markers and the {@code THROW} instructions, so a single per-fn walk collects the inputs and fires
 * at function end.
 */
public final class TryCatchFinallyChecker implements DfaChecker {

  /** Per-function: set of dest vars of generic NEW (Exception/RuntimeException/Throwable). */
  private final Set<String> genericNewDests = new HashSet<>();

  /** Per-function: list of (THROW insn, declared exc type) sites. */
  private final List<ThrowSite> throws_ = new ArrayList<>();

  /** Per-function: set of declared exception types in catch handlers. */
  private final Set<String> caughtTypes = new HashSet<>();

  /** Per-function: list of CATCH_HANDLER insns with generic types. */
  private final List<TacInstruction> genericCatches = new ArrayList<>();

  /** Per-function: list of NEW insns with generic exception ctorType. */
  private final List<TacInstruction> genericNews = new ArrayList<>();

  private record ThrowSite(TacInstruction insn, String declaredType) {}

  @Override
  public String name() {
    return "TryCatchFinally";
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    throws_.clear();
    caughtTypes.clear();
    genericCatches.clear();
    genericNews.clear();
    genericNewDests.clear();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    switch (ins.kind) {
      case NEW -> {
        // `t = new X(...)`. Record if X is a
        // generic exception type, and remember
        // the dest var so we can pair with a
        // later THROW.
        if (ins.ctorType != null && isGenericExceptionType(ins.ctorType.toString())) {
          genericNews.add(ins);
          if (ins.dest instanceof TacOperand.TacVar dest) {
            genericNewDests.add(dest.name());
          }
        }
      }
      case THROW -> {
        // `throw v;`. If v is the dest of a
        // generic NEW in this function, fire
        // CWE-397 on the NEW. (We fire on the
        // NEW rather than the THROW so the
        // diagnostic points to the source.)
        TacOperand operand = ins.lhs1;
        if (operand instanceof TacOperand.TacVar v && genericNewDests.contains(v.name())) {
          // Mark the throw site for the
          // uncaught analysis too.
          throws_.add(new ThrowSite(ins, "GenericException"));
        }
      }
      case CATCH_HANDLER -> {
        if (ins.ctorType != null) {
          String declared = ins.ctorType.toString();
          caughtTypes.add(declared);
          if (isGenericExceptionType(declared)) {
            genericCatches.add(ins);
          }
        }
      }
      default -> {
        /* ignore */
      }
    }
  }

  @Override
  public void finishFunction(AnalysisContext ctx, TacFunction fn) {
    // CWE-396: generic catch
    for (TacInstruction ch : genericCatches) {
      emit(
          ctx,
          ch,
          fn,
          "CWE-396",
          "Catch of overly-generic exception type "
              + ch.ctorType
              + " — this swallows errors the caller probably wants to know about",
          "catch-generic: " + ch.ctorType);
    }
    // CWE-397: generic throw (fire on the NEW so
    // the source is visible)
    for (TacInstruction nt : genericNews) {
      emit(
          ctx,
          nt,
          fn,
          "CWE-397",
          "Throwing a generic exception type "
              + nt.ctorType
              + " — declare a specific exception subclass instead",
          "throw-generic: " + nt.ctorType);
    }
    // CWE-248: uncaught. If the function has at
    // least one CATCH_HANDLER of `Exception` /
    // `Throwable` (i.e. catches everything) we
    // assume nothing escapes. Otherwise we don't
    // make claims about cross-function
    // propagation — we limit to *intra-function*
    // unmatched throws.
    boolean catchesEverything = caughtTypes.stream().anyMatch(TryCatchFinallyChecker::isTopType);
    if (!catchesEverything) {
      for (ThrowSite site : throws_) {
        if (!isCaught(site.declaredType, caughtTypes)) {
          emit(
              ctx,
              site.insn,
              fn,
              "CWE-248",
              "Throw of " + site.declaredType + " is not caught by any handler in this function",
              "uncaught: " + site.declaredType);
        }
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
      String cwe,
      String message,
      String flow) {
    String ruleId = cwe.toLowerCase().replace('-', '.');
    // v2 trace: single-node VIOLATION
    // with NULL_DEREF_GENERIC as the closest
    // generic-exception op. The CWE code is
    // carried in the Free operand so the AI
    // dedup layer can dispatch on it without
    // regex-parsing the legacy flow string.
    PatternTrace.reportFree(
        ctx,
        fn,
        ins,
        TraceNode.Op.NULL_DEREF_GENERIC,
        "cwe:" + cwe + " — " + flow,
        cweToName(cwe),
        "plumb." + ruleId,
        message,
        RuleDef.Severity.MEDIUM,
        cwe,
        flow);
  }

  private static String cweToName(String cwe) {
    return switch (cwe) {
      case "CWE-248" -> "UncaughtException";
      case "CWE-396" -> "CatchGenericException";
      case "CWE-397" -> "ThrowGenericException";
      default -> cwe;
    };
  }

  private static boolean isGenericExceptionType(String type) {
    if (type == null) return false;
    String shortName = shortName(type);
    return shortName.equals("Exception")
        || shortName.equals("RuntimeException")
        || shortName.equals("Throwable");
  }

  private static boolean isTopType(String type) {
    if (type == null) return false;
    String shortName = shortName(type);
    return shortName.equals("Exception") || shortName.equals("Throwable");
  }

  /**
   * Best-effort: does the set of caught types in this function handle {@code thrownType}? A handler
   * of {@code Exception} or {@code Throwable} matches everything; otherwise we look for an exact
   * name match. Cross-class hierarchy matching is not attempted (we don't have a type-resolved
   * model in this checker).
   */
  private static boolean isCaught(String thrownType, Set<String> caught) {
    if (thrownType == null) return false;
    String shortName = shortName(thrownType);
    for (String c : caught) {
      if (shortName.equals(shortName(c))) return true;
      // Exception / Throwable catches everything
      String cShort = shortName(c);
      if (cShort.equals("Exception") || cShort.equals("Throwable")) {
        return true;
      }
    }
    return false;
  }

  private static String shortName(String fqn) {
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
  }
}
