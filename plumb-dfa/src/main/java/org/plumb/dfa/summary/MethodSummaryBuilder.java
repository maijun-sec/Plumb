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
package org.plumb.dfa.summary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.dfa.cg.FunctionSignature;
import org.plumb.dfa.resolve.StaticFieldResolver;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * Builds a {@link MethodSummary} from a {@link TacFunction}'s IR. The builder is
 * <i>path-insensitive</i>: it walks the instruction stream in order, maintains a "last-def" map for
 * each {@link TacOperand.TacVar}, and folds every {@code RETURN} instruction into one of the ten
 * {@link ReturnShape.Kind} variants.
 *
 * <p>The algorithm:
 *
 * <ol>
 *   <li>Pre-collect every {@code RETURN} instruction so we can detect the {@link
 *       ReturnShape.Kind#RETURN_CONDITIONAL} pattern (two distinct constant returns along different
 *       paths).
 *   <li>For every instruction that writes a {@code TacVar} dest (MOVE / BINOP / UNARYOP / FIELDLOAD
 *       / CALL / NEW), update the last-def map. Use the map to look through temporary aliases when
 *       classifying a {@code RETURN}.
 *   <li>For every {@code RETURN lhs1}, classify lhs1:
 *       <ul>
 *         <li>Const → {@link ReturnShape.Kind#RETURN_CONST}
 *         <li>TacVar that aliases a parameter → {@link ReturnShape.Kind#RETURN_PARAM}
 *         <li>TacVar whose last def is a {@code FIELDLOAD} on {@code this} → {@link
 *             ReturnShape.Kind#RETURN_THIS_FIELD}
 *         <li>TacVar whose last def is a {@code FIELDLOAD} on a class name → {@link
 *             ReturnShape.Kind#RETURN_STATIC_FIELD} (resolved via {@link StaticFieldResolver} when
 *             available)
 *         <li>TacVar whose last def is a {@code NEW} → {@link ReturnShape.Kind#RETURN_NEW_INSTANCE}
 *         <li>TacVar whose last def is a {@code BINOP} → {@link ReturnShape.Kind#RETURN_BINARY_OP}
 *         <li>TacVar with any other def → {@link ReturnShape.Kind#RETURN_LOCAL_VAR} (the engine can
 *             still chase it if its def is itself constant)
 *         <li>Anything else (no return, multiple returns with mismatched shapes) → {@link
 *             ReturnShape.Kind#UNKNOWN} or {@link ReturnShape.Kind#RETURN_CONDITIONAL} if the only
 *             two returns are boolean constants.
 *       </ul>
 *   <li>Collect side effects: every {@code FIELDSTORE} on {@code this} or a parameter alias, every
 *       {@code FIELDSTORE} on a class name, every {@code CALL}, every {@code THROW}.
 * </ol>
 *
 * <p>The builder is single-threaded. The caller (engine) is expected to wrap it in a per-function
 * critical section if multi-thread analysis is in use.
 */
public final class MethodSummaryBuilder {

  private final StaticFieldResolver staticFields;

  public MethodSummaryBuilder() {
    this(null);
  }

  public MethodSummaryBuilder(StaticFieldResolver staticFields) {
    this.staticFields = staticFields;
  }

  /**
   * Compute the {@link MethodSummary} for a function. The returned summary is safe to share across
   * threads.
   */
  public MethodSummary build(TacFunction fn) {
    if (fn == null) {
      throw new IllegalArgumentException("function is null");
    }
    // Set working params
    // list once so both
    // buildReturnShape
    // (for RETURN_PARAM)
    // and
    // buildSideEffect
    // (for WRITES_PARAM)
    // see the same
    // snapshot.
    this.currentParams = fn.params();
    FunctionSignature sig = parseSignature(fn);
    ReturnShape shape = buildReturnShape(fn);
    SideEffect side = buildSideEffect(fn);
    return new MethodSummary(sig, shape, side);
  }

  private static FunctionSignature parseSignature(TacFunction fn) {
    String sig = fn.signature();
    if (sig == null || sig.isEmpty()) {
      return synthesiseFallback(fn);
    }
    FunctionSignature parsed = null;
    try {
      parsed = FunctionSignature.parse(sig);
    } catch (RuntimeException re) {
      // fall through to fallback
    }
    return parsed != null ? parsed : synthesiseFallback(fn);
  }

  private static FunctionSignature synthesiseFallback(TacFunction fn) {
    String name = fn.name();
    return new FunctionSignature(
        "",
        "<unknown>",
        name == null ? "<unknown>" : name,
        java.util.List.of(),
        fn.returnType() == null ? "void" : fn.returnType().toString());
  }

  private ReturnShape buildReturnShape(TacFunction fn) {
    // 1) Collect every
    // RETURN.
    List<TacInstruction> returns = new ArrayList<>();
    for (TacInstruction ins : fn.instructions()) {
      if (ins.kind == TacInstruction.Kind.RETURN) {
        returns.add(ins);
      }
    }
    if (returns.isEmpty()) {
      // No explicit
      // return — treat
      // as void.
      return ReturnShape.voidShape();
    }
    // 2) Build last-def
    // map for TacVar
    // dests.
    Map<String, TacInstruction> lastDef = new HashMap<>();
    for (TacInstruction ins : fn.instructions()) {
      if (ins.dest instanceof TacOperand.TacVar v) {
        lastDef.put(v.name(), ins);
      }
    }
    // 3) Classify each
    // return.
    List<ReturnShape> shapes = new ArrayList<>(returns.size());
    for (TacInstruction ret : returns) {
      shapes.add(classifyReturn(ret, lastDef));
    }
    // 4) If all returns
    // classify the same
    // way, that is the
    // shape. If they
    // disagree, look for
    // the CONDITIONAL
    // pattern (two
    // returns, both
    // boolean consts,
    // one true and one
    // false).
    ReturnShape first = shapes.get(0);
    boolean allSame = true;
    for (int i = 1; i < shapes.size(); i++) {
      if (shapes.get(i).kind != first.kind) {
        allSame = false;
        break;
      }
    }
    if (allSame) {
      // Special case:
      // two returns, both
      // boolean consts,
      // distinct values
      // → CONDITIONAL.
      // The first
      // constant is the
      // true-arm, the
      // second the
      // false-arm. This
      // captures the
      // `if (b) return
      // true; else return
      // false;` pattern
      // path-insensitively.
      if (shapes.size() == 2
          && first.kind == ReturnShape.Kind.RETURN_CONST
          && first.constValue != null
          && first.constValue.kind() == TacOperand.Const.Kind.BOOL) {
        ReturnShape second = shapes.get(1);
        if (second.kind == ReturnShape.Kind.RETURN_CONST
            && second.constValue != null
            && second.constValue.kind() == TacOperand.Const.Kind.BOOL
            && !first.constValue.equals(second.constValue)) {
          return ReturnShape.condShape(first, second);
        }
      }
      return first;
    }
    if (shapes.size() == 2) {
      ReturnShape a = shapes.get(0);
      ReturnShape b = shapes.get(1);
      if (a.kind == ReturnShape.Kind.RETURN_CONST
          && b.kind == ReturnShape.Kind.RETURN_CONST
          && a.constValue != null
          && b.constValue != null
          && a.constValue.kind() == TacOperand.Const.Kind.BOOL
          && b.constValue.kind() == TacOperand.Const.Kind.BOOL) {
        return ReturnShape.condShape(a, b);
      }
    }
    return ReturnShape.unknown("multiple returns with mismatched shapes");
  }

  private ReturnShape classifyReturn(TacInstruction ret, Map<String, TacInstruction> lastDef) {
    TacOperand retVal = ret.lhs1;
    if (retVal == null) {
      // bare `return;`
      return ReturnShape.voidShape();
    }
    if (retVal instanceof TacOperand.Const c) {
      return ReturnShape.constShape(c);
    }
    if (retVal instanceof TacOperand.TacVar v) {
      return chase(v, lastDef, new java.util.HashSet<>());
    }
    return ReturnShape.unknown("unhandled return operand kind");
  }

  /** Look through a chain of aliasing TacVars (MOVE-to-MOVE) and classify the underlying value. */
  private ReturnShape chase(
      TacOperand.TacVar v, Map<String, TacInstruction> lastDef, Set<String> seen) {
    if (!seen.add(v.name())) {
      // cycle, give up
      return ReturnShape.unknown("cycle in alias chain: " + v.name());
    }
    // Direct parameter
    // return.
    int pIdx = indexOfParam(v, currentParams);
    if (pIdx >= 0) {
      return ReturnShape.paramShape(pIdx);
    }
    TacInstruction def = lastDef.get(v.name());
    if (def == null) {
      // Unknown var, no def
      // visible. Could be
      // a parameter we
      // missed, or an
      // outer-scope var.
      return ReturnShape.localShape(v.name());
    }
    switch (def.kind) {
      case MOVE:
        {
          TacOperand src = def.lhs1;
          if (src instanceof TacOperand.Const c) {
            return ReturnShape.constShape(c);
          }
          if (src instanceof TacOperand.TacVar srcVar) {
            return chase(srcVar, lastDef, seen);
          }
          return ReturnShape.localShape(v.name());
        }
      case FIELDLOAD:
        {
          if (def.lhs1 instanceof TacOperand.TacVar receiver) {
            if (isThisName(receiver.name())) {
              return ReturnShape.thisFieldShape(def.fieldName);
            }
            // Static-field
            // access: the
            // receiver is a
            // class name, not
            // an instance.
            // We classify the
            // shape based on
            // the receiver
            // form alone —
            // the engine
            // consults the
            // StaticFieldResolver
            // separately
            // when it wants
            // the actual
            // constant value.
            // The builder's
            // job is just to
            // identify
            // "this is a
            // static-field
            // access".
            String className = staticReceiverName(receiver);
            if (className != null) {
              String key = className + "." + def.fieldName;
              return ReturnShape.staticFieldShape(key);
            }
          }
          return ReturnShape.unknown("field load on non-this, non-static receiver: " + def);
        }
      case NEW:
        {
          return ReturnShape.newInstanceShape(def.ctorType);
        }
      case BINOP:
        {
          return ReturnShape.binopShape(def.binop, def.lhs1, def.lhs2);
        }
      case UNARYOP:
        {
          // `return -x;` is
          // binop-like for
          // our purposes;
          // we treat it as a
          // local.
          return ReturnShape.localShape(v.name());
        }
      case CALL:
        {
          // Could be a chain
          // like
          //   x = list.size();
          //   return x;
          // The engine would
          // need a recursive
          // summary of
          // `list.size()` to
          // resolve further.
          // For now we
          // return the local
          // — the engine can
          // apply a follow-up
          // summary if the
          // call's signature
          // is in the cache.
          return ReturnShape.localShape(v.name());
        }
      default:
        {
          return ReturnShape.localShape(v.name());
        }
    }
  }

  private SideEffect buildSideEffect(TacFunction fn) {
    SideEffect.Builder b = new SideEffect.Builder();
    // Cache param-name
    // → index for
    // WRITES_PARAM.
    Map<String, Integer> paramIndexByName = new HashMap<>();
    List<TacOperand.TacVar> params = fn.params();
    for (int i = 0; i < params.size(); i++) {
      paramIndexByName.put(params.get(i).name(), i);
    }
    this.currentParams = params;
    for (TacInstruction ins : fn.instructions()) {
      switch (ins.kind) {
        case FIELDSTORE:
          {
            if (ins.lhs1 instanceof TacOperand.TacVar recv) {
              if (isThisName(recv.name())) {
                b.addWriteThisField(ins.fieldName);
              } else {
                // Static-style
                // field store
                // (calleeSig-like
                // receiver).
                String className = staticReceiverName(recv);
                if (className != null && ins.fieldName != null) {
                  b.addWriteStaticField(className + "." + ins.fieldName);
                } else {
                  // Possibly a
                  // write through
                  // a parameter
                  // alias.
                  Integer idx = paramIndexByName.get(recv.name());
                  if (idx != null) {
                    b.addWriteParamIndex(idx);
                  }
                }
              }
            }
            break;
          }
        case ARRAYSTORE:
          {
            // array store
            // through a
            // parameter alias
            // counts as a
            // WRITES_PARAM.
            if (ins.lhs1 instanceof TacOperand.TacVar arr) {
              Integer idx = paramIndexByName.get(arr.name());
              if (idx != null) {
                b.addWriteParamIndex(idx);
              }
            }
            break;
          }
        case CALL:
          {
            if (ins.calleeSig != null) {
              b.addCall(ins.calleeSig);
            }
            break;
          }
        case THROW:
          {
            b.markMayThrow();
            break;
          }
        default:
          break;
      }
    }
    return b.build();
  }

  // Working state
  // shared between
  // classifyReturn
  // and chase.
  // Updated once per
  // build() call
  // before iterating
  // returns.
  private List<TacOperand.TacVar> currentParams = List.of();

  private static int indexOfParam(TacOperand.TacVar v, List<TacOperand.TacVar> params) {
    for (int i = 0; i < params.size(); i++) {
      if (params.get(i).name().equals(v.name())) {
        return i;
      }
    }
    return -1;
  }

  private static boolean isThisName(String name) {
    return "this".equals(name) || "$this".equals(name);
  }

  /**
   * A FIELDLOAD on a static-style class name is encoded in TAC as a TacVar whose name is the class
   * name (e.g. "Helper") followed by a <i>synthetic</i> constant. In practice the receiver of a
   * static-style load is marked as a class identifier by the translator; here we sniff the {@link
   * TacType} and the name (uppercase first letter or contains a dot) to tell static from instance.
   */
  private static String staticReceiverName(TacOperand.TacVar v) {
    String n = v.name();
    if (n == null) return null;
    if (n.contains(".")) return n;
    if (n.isEmpty()) return null;
    char first = n.charAt(0);
    if (Character.isUpperCase(first) && v.type() != null) {
      // Heuristic:
      // uppercase first
      // letter + a class
      // type → likely a
      // class name.
      // Lowercase first
      // letter is an
      // instance variable.
      return n;
    }
    return null;
  }
}
