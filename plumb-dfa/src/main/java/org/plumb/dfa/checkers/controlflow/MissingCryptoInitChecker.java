/*
 * Missing Required Cryptographic Step (CWE-325).
 *
 * <p>DFA-style pattern detector with per-function
 * state. Tracks KeyGenerator / KeyPairGenerator /
 * MessageDigest instances that were obtained via
 * {@code getInstance("...")} but never had
 * {@code init(...)} called on them. Fires on
 * subsequent {@code generateKey()} / {@code digest()} /
 * {@code generateKeyPair()} calls.</p>
 *
 * <p>The bug: the cryptographic provider will use its
 * default initialization (random key size, etc.) which
 * may produce a smaller key than desired, leading to
 * weaker security and interoperability issues.</p>
 *
 * <h2>v2 trace (R-812)</h2>
 *
 * <p>The check is a per-function data flow:
 * factory → optional init → sink. The v2 trace
 * captures that shape:</p>
 * <ol>
 *   <li>{@code MARK_SOURCE} at the factory call
 *       (e.g. {@code KeyGenerator.getInstance("AES")}) —
 *       records the instance was created.</li>
 *   <li>{@code SANITIZER} at the init call
 *       (e.g. {@code kg.init(256)}) ? records the
 *       instance was initialised.</li>
 *   <li>{@code SINK_CRYPTO_INIT} at the sink call
 *       (e.g. {@code kg.generateKey()}) ? fires
 *       only when no SANITIZER was seen for the
 *       same TYPE on this path.</li>
 * </ol>
 *
 * <p>The v1 {@code dataflow_steps} flattens to a
 * SOURCE → (optional SANITIZER) → SINK shape so
 * pre-R-812 reporters see no change.</p>
 */
package org.plumb.dfa.checkers.controlflow;

import org.plumb.rules.RuleDef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

public final class MissingCryptoInitChecker implements DfaChecker {
  private static final String RULE_ID = "dfa-controlflow-missing-crypto-init";

  /**
   * Per-TYPE state: true = needs init (just got from factory), false = init'd. Multiple
   * KeyGenerator instances in a function share the same TYPE, so this is a coarse approximation —
   * but works for the common case where one KeyGenerator is created and used.
   *
   * <p>For v2 trace fidelity we also keep a parallel map of {@code TYPE -> factory-v2-handle} so
   * the sink node can wire a {@code SUMMARY_PROPAGATE} edge back to the factory source. This makes
   * the "data was created by getInstance and never initialised" fact visible to the AI dedup layer
   * without regex-parsing the legacy {@code flow} string.
   */
  private final Map<String, Boolean> cryptoVarsByType = new HashMap<>();

  private final Map<String, Integer> typeFactoryHandle = new HashMap<>();

  /** Set of TYPEs whose init has been observed. */
  private final Set<String> initedTypes = new HashSet<>();

  /** Per-function TraceBuilder for v2 trace fidelity. */
  private TraceBuilder v2 = new TraceBuilder();

  /** Per-function sink handles for {@link Trace#build}. */
  private final List<Integer> sinkHandles = new ArrayList<>();

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "MissingCryptoInit";
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    cryptoVarsByType.clear();
    typeFactoryHandle.clear();
    initedTypes.clear();
    v2 = new TraceBuilder();
    sinkHandles.clear();
    reported.clear();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    if (ins.kind != TacInstruction.Kind.CALL) return;
    if (ins.calleeSig == null) return;
    String sig = ins.calleeSig;

    // 1) Factory: getInstance(...)
    // Mark the dest's TYPE as "needs init" (true)
    // and emit a v2 MARK_SOURCE.
    if (isFactoryCall(sig) && ins.dest instanceof TacOperand.TacVar tv) {
      String type = cryptoTypeName(tv);
      if (type != null) {
        cryptoVarsByType.put(type, true);
        int handle =
            v2.addSource(
                TraceNode.Op.MARK_SOURCE,
                List.of(new OperandRef.CallRef(sig), new OperandRef.Var(tv.name(), 0)),
                "factory: " + type + " = " + sig,
                "",
                ins.line,
                ins.column,
                fn.signature());
        typeFactoryHandle.put(type, handle);
      }
      return;
    }

    // 2) init() / update() / initialize() call.
    // Emit a v2 SANITIZER linked to the factory handle.
    if (isInitCall(sig) && ins.args != null && !ins.args.isEmpty()) {
      if (ins.args.get(0) instanceof TacOperand.TacVar recv) {
        String type = cryptoTypeName(recv);
        if (type != null && cryptoVarsByType.containsKey(type)) {
          cryptoVarsByType.put(type, false);
          initedTypes.add(type);
          Integer parent = typeFactoryHandle.get(type);
          if (parent != null) {
            v2.addSanitizer(
                TraceNode.Op.SANITIZER_INTEGER_RANGE_CHECK,
                List.of(new OperandRef.CallRef(sig)),
                parent,
                "init: " + type + "." + sig,
                "",
                ins.line,
                ins.column,
                fn.signature());
          }
        }
      }
      return;
    }

    // 3) Sink: generateKey() / digest() / generateKeyPair().
    // Fire on the receiver's TYPE state and emit
    // SINK_CRYPTO_INIT with parent edge to factory (and
    // optional init sanitizer if seen).
    if (isGenSink(sig) && ins.args != null && !ins.args.isEmpty()) {
      if (ins.args.get(0) instanceof TacOperand.TacVar recv) {
        String type = cryptoTypeName(recv);
        if (type != null && Boolean.TRUE.equals(cryptoVarsByType.get(type))) {
          String key = fn.signature() + "|" + ins.line + "|325";
          if (!reported.add(key)) return;
          Integer parent = typeFactoryHandle.get(type);
          List<Integer> parents = parent == null ? List.of() : List.of(parent);
          int sinkHandle =
              v2.addSink(
                  TraceNode.Op.SINK_CRYPTO_INIT,
                  List.of(new OperandRef.CallRef(sig), new OperandRef.Var(recv.name(), 0)),
                  parents,
                  sig + " on " + type + " (no init seen)",
                  "",
                  ins.line,
                  ins.column,
                  fn.signature());
          sinkHandles.add(sinkHandle);
          Trace trace = v2.build(sinkHandles, Trace.CompressionLevel.FULL);
          Finding f =
              Finding.dfaWithTrace(
                  name(),
                  RULE_ID,
                  "Missing required cryptographic step: "
                      + sig
                      + " called on "
                      + recv.name()
                      + " without prior init/update() call. "
                      + "The provider's default initialization may "
                      + "produce a smaller/weaker key than intended.",
                  RuleDef.Severity.MEDIUM,
                  "CWE-325",
                  "",
                  ins.line,
                  ins.column,
                  "missing-crypto-init: " + sig + " (var " + recv.name() + " never init'd)",
                  trace);
          ctx.reporter().reportWithTrace(f, trace);
        }
      }
    }
  }

  /**
   * Get the "crypto type" of a TacVar ? canonical short form. Both "javax.crypto.KeyGenerator" and
   * "KeyGenerator" map to the same key "KeyGenerator". Returns null if the type doesn't look like a
   * crypto class.
   */
  private static String cryptoTypeName(TacOperand.TacVar v) {
    if (v.type() == null) return null;
    String t = v.type().toString();
    if (t.contains("KeyGenerator")
        || t.contains("KeyPairGenerator")
        || t.contains("MessageDigest")) {
      int dot = t.lastIndexOf('.');
      return dot < 0 ? t : t.substring(dot + 1);
    }
    return null;
  }

  private static boolean isFactoryCall(String sig) {
    return sig.contains("KeyGenerator.getInstance")
        || sig.contains("KeyPairGenerator.getInstance")
        || sig.contains("MessageDigest.getInstance")
        || sig.endsWith(".getInstance/1")
        || sig.equals("getInstance/1");
  }

  private static boolean isInitCall(String sig) {
    if (sig.endsWith(".init/1")
        || sig.endsWith(".init/2")
        || sig.endsWith(".init/3")
        || sig.endsWith(".initialize/1")
        || sig.endsWith(".initialize/2")
        || sig.endsWith(".update/1")
        || sig.endsWith(".update/3")
        || sig.endsWith(".reset/0")
        || sig.equals("init/1")
        || sig.equals("init/2")
        || sig.equals("initialize/1")
        || sig.equals("initialize/2")
        || sig.equals("update/1")
        || sig.equals("update/3")
        || sig.equals("reset/0")) {
      return true;
    }
    return false;
  }

  private static boolean isGenSink(String sig) {
    return sig.endsWith("KeyGenerator.generateKey/0")
        || sig.endsWith("KeyPairGenerator.generateKeyPair/0")
        || sig.endsWith("MessageDigest.digest/0")
        || sig.equals("generateKey/0")
        || sig.equals("digest/0")
        || sig.equals("generateKeyPair/0");
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    return FunctionSummary.empty();
  }

  private static final String AI_PROMPT =
      """
        RULE: Missing required cryptographic initialization (CWE-325).
        Fires when generateKey()/digest()/generateKeyPair() is
        called on a KeyGenerator/KeyPairGenerator/MessageDigest
        instance that was never init()'d.

        TP when the variable was created by getInstance() and
        generate/digest was called without init().

        FP when:
          * The variable was constructed via a custom factory
            that already initializes internally.
          * init() is called on a different reference of the
            same object (aliasing).

        UNKNOWN when in doubt.
""";

  @Override
  public String getAiPrompt(Finding finding) {
    return AI_PROMPT;
  }
}
