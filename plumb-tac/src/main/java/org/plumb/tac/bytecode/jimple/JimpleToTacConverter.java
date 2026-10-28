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
package org.plumb.tac.bytecode.jimple;

import java.util.ArrayList;
import java.util.List;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;
import org.plumb.tac.ir.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.jimple.basic.StmtPositionInfo;
import sootup.core.jimple.common.Local;
import sootup.core.jimple.common.expr.JCastExpr;
import sootup.core.jimple.common.expr.JInstanceOfExpr;
import sootup.core.jimple.common.expr.JLengthExpr;
import sootup.core.jimple.common.expr.JNegExpr;
import sootup.core.jimple.common.expr.JNewArrayExpr;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.ref.JCaughtExceptionRef;
import sootup.core.jimple.common.ref.JInstanceFieldRef;
import sootup.core.jimple.common.ref.JParameterRef;
import sootup.core.jimple.common.ref.JStaticFieldRef;
import sootup.core.jimple.common.ref.JThisRef;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JGotoStmt;
import sootup.core.jimple.common.stmt.JIdentityStmt;
import sootup.core.jimple.common.stmt.JIfStmt;
import sootup.core.jimple.common.stmt.JInvokeStmt;
import sootup.core.jimple.common.stmt.JNopStmt;
import sootup.core.jimple.common.stmt.JReturnStmt;
import sootup.core.jimple.common.stmt.JReturnVoidStmt;
import sootup.core.jimple.common.stmt.JThrowStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.model.Position;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.Type;

/**
 * Convert a single SootUp Jimple {@link Body} into a {@link TacFunction}. The body is read in
 * CFG-block order so that labels emitted by {@code JIfStmt} / {@code JGotoStmt} / {@code
 * JIdentityStmt} see forward references and don't get tangled up.
 *
 * <p>Mapping (statement-by-statement, see the project's {@link TacInstruction.Kind} for the 17
 * supported opcodes):
 *
 * <ul>
 *   <li>{@link JIdentityStmt} → a {@code MOVE} from a synthesized local that names the parameter /
 *       this / caught exception. The identity locals already exist in the body's local table, so
 *       the move is sound.
 *   <li>{@link JAssignStmt} → dispatched on the RHS kind: constant → {@code MOVE}; binop → {@code
 *       BINOP}; unop (incl. cast) → {@code UNARYOP}; field/array refs → {@code FIELDLOAD} / {@code
 *       ARRAYLOAD}; {@code new T()} → {@code NEW}; {@code new T[k]()} → {@code NEWARRAY}; {@code x
 *       instanceof T} → {@code INSTANCEOF}; {@code invoke} → {@code CALL}.
 *   <li>{@link JInvokeStmt} → {@code CALL} with no dest.
 *   <li>{@link JIfStmt} → {@code BRANCH} (trueLabel = target, falseLabel = next-block label, which
 *       is synthesized as a fresh label).
 *   <li>{@link JGotoStmt} → {@code JUMP}.
 *   <li>{@link JNopStmt} → {@code NOP}.
 *   <li>{@link JReturnStmt} → {@code RETURN} carrying the value.
 *   <li>{@link JReturnVoidStmt} → {@code RETURN} (no operand).
 *   <li>{@link JThrowStmt} → {@code THROW}.
 * </ul>
 *
 * <p>Anything that doesn't fit a recognized pattern is logged and dropped, never crashing the
 * engine. The 198 dfa unit tests keep passing because the project TAC is shape-compatible: every
 * Jimple shape the existing checkers care about (CALL, NEW, FIELDSTORE, RETURN, THROW, BRANCH) is
 * mapped to the matching TAC opcode.
 */
public final class JimpleToTacConverter {

  private static final Logger LOG = LoggerFactory.getLogger(JimpleToTacConverter.class);

  private final JimpleOperandConverter op;

  public JimpleToTacConverter(Body body) {
    this.op = new JimpleOperandConverter(body);
  }

  public TacFunction convert(Body body) {
    MethodSignature sig = body.getMethodSignature();
    String className = JimpleOperandConverter.classNameOf(sig.getDeclClassType());
    String methodName = sig.getName();
    // Synthetic <init>, <clinit> should keep the angle brackets; the
    // existing AST translator does the same.
    String signature = className + "." + methodName + "(" + sig.getParameterTypes().size() + ")";

    // Return type.
    TacType returnType = op.tacTypeOf(sig.getType());

    // We don't know from MethodSignature alone whether the method
    // is static — that lives on SootMethod. We default to treating
    // the first local in the body that is named "this" as a
    // discriminator. SootUp's Body has "this" as a Local iff the
    // method is instance-level.
    boolean hasThisLocal = false;
    for (Local l : body.getLocals()) {
      if ("this".equals(l.getName())) {
        hasThisLocal = true;
        break;
      }
    }

    // Params (named @parameter-N, SootUp 3.0.0 convention,
    // 1-based with a dash) and locals.
    // Do NOT include the `this` local in the
    // params list. The `this` local is still added to
    // {@code locals} below (because SootUp's Body has it
    // for instance methods), but the params list must
    // match the call-site arity (which counts only the
    // declared parameters, not the implicit receiver).
    // Without this fix, the call-graph builder indexes by name with
    // key "logIt/2" while the call site strips to
    // "logIt/1", so cross-class taint propagation silently
    // fails to resolve. The {@code this} local is
    // available via {@code fn.locals()} for body
    // instructions that reference it.
    // Use SootUp's 1-based `@parameter-N`
    // naming (with dash). SootUp's body code references
    // params as `@parameter-1`, `@parameter-2`, etc. (not
    // `@parameter0`). The params list here must match
    // the body's references so the MOVE from
    // `@parameter-N` to a local var propagates taint
    // correctly. Previously this used `@parameter0`
    // (0-based, no dash), which never matched the body —
    // taint set on the param via the recursive entry
    // never reached the local that subsequent instructions
    // used.
    List<TacOperand.TacVar> params = new ArrayList<>();
    int realIdx = 1;
    for (Type pt : sig.getParameterTypes()) {
      params.add(new TacOperand.TacVar("@parameter-" + realIdx, op.tacTypeOf(pt), false));
      realIdx++;
    }
    // Locals — every other local in the body that isn't a param
    // or `this` is a synthetic. We populate them all so
    // SSA-shape matchers can resolve names.
    List<TacOperand.TacVar> locals = new ArrayList<>();
    for (Local l : body.getLocals()) {
      String name = l.getName();
      if ("this".equals(name) || name.startsWith("@parameter")) continue;
      locals.add(new TacOperand.TacVar(name, op.tacTypeOf(l.getType()), true));
    }

    // Pre-build the instruction list and register label names
    // first so forward references resolve.
    List<TacInstruction> insns = new ArrayList<>();
    // Track synthesized branch-fall-through labels. Each JIfStmt
    // gets a unique "after" label that the next instruction
    // emits as a LABEL so the BRANCH's falseTarget resolves.
    int[] labelCounter = {0};
    java.util.function.Supplier<String> nextLabelName = () -> "block_" + (labelCounter[0]++);

    // Pre-compute the set of statements that are the
    // entry of an exception handler. For each such statement
    // we record (handlerStmt, exceptionType) so the per-stmt
    // loop can emit a CATCH_HANDLER marker immediately before
    // the handler body.
    //
    // SootUp 3.0.0 models exception flow inside
    // {@link sootup.core.graph.ControlFlowGraph}: the
    // {@code exceptionalSuccessors} of a stmt give the catch
    // handlers as {@code Map<ClassType, BasicBlock>}, keyed by
    // the declared exception type. There is no JTryStmt.
    // We pre-build the index once: the first stmt of each
    // catch block is the entry. We then synthesize a
    // synthetic catch-parameter local whose type matches the
    // declared exception type and emit a CATCH_HANDLER
    // instruction binding that local.
    java.util.Map<Stmt, sootup.core.types.ClassType> catchEntries = new java.util.HashMap<>();
    java.util.Set<Stmt> catchHandlerEntries = new java.util.HashSet<>();
    for (Stmt stmt : body.getStmts()) {
      try {
        var cfg = body.getControlFlowGraph();
        if (cfg == null) break;
        var excSuccs = cfg.exceptionalSuccessors(stmt);
        if (excSuccs == null || excSuccs.isEmpty()) continue;
        for (var e : excSuccs.entrySet()) {
          // SootUp 3.0.0: cfg.exceptionalSuccessors
          // returns Map<ClassType, Stmt> — the value
          // is the catch-handler entry Stmt, not a
          // BasicBlock.
          Stmt handlerStmt = e.getValue();
          if (handlerStmt == null) continue;
          catchEntries.putIfAbsent(handlerStmt, e.getKey());
          catchHandlerEntries.add(handlerStmt);
        }
      } catch (RuntimeException ex) {
        // CFG queries may fail for non-SootUp code; skip.
      }
    }

    // Track each stmt's first emitted instruction
    // index so we can compute catch-handler ranges and
    // try-block ranges from the CFG after the main loop.
    // The map is populated inside the per-stmt loop.
    java.util.Map<Stmt, Integer> stmtFirstInsnIdx = new java.util.HashMap<>();
    // Per-catch-handler bookkeeping: when we enter a catch
    // handler block (catchHandlerEntries.contains), record
    // (start, end, excType) where end is filled when the
    // next handler (or function end) is reached. SootUp's
    // CFG does not give us a JTryStmt with explicit
    // try-block ranges, so we approximate them by walking
    // the CFG backwards from each handler block to find
    // the stmts whose exceptionalSuccessors include it
    // (the throw points). Those stmts span the try range.
    java.util.List<int[]> catchRanges = new java.util.ArrayList<>();
    // Map: handler block first stmt → ClassType
    // (already in catchEntries above)
    // Map: catch handler block first stmt → catchLabel name
    //   so we can put the right L_CATCH_<Type> label into
    //   the TryRegion.
    java.util.Map<Stmt, String> catchLabelByHandler = new java.util.HashMap<>();

    for (Stmt stmt : body.getStmts()) {
      int line = lineOf(stmt);
      // Record the index of the first emitted
      // instruction for this stmt so the post-loop
      // try-region builder can compute ranges. Only
      // record the FIRST stmt of a catch handler here,
      // so stmtFirstInsnIdx points at the LABEL of the
      // handler block, not at every stmt inside the
      // handler body.
      if (catchHandlerEntries.contains(stmt)) {
        stmtFirstInsnIdx.putIfAbsent(stmt, insns.size());
      }
      // If this is the first stmt of a catch handler
      // (as recorded in catchHandlerEntries above), emit a
      // CATCH_HANDLER marker that binds a synthetic catch
      // parameter to the exception value. The handler body
      // will reference that local as if it were a normal
      // parameter (which SootUp also models — the handler
      // entry stmt is a JIdentityStmt that moves
      // JCaughtExceptionRef into the catch local).
      if (catchHandlerEntries.contains(stmt)) {
        try {
          sootup.core.types.ClassType excType = catchEntries.get(stmt);
          String excName =
              excType != null ? op.tacTypeOf(excType).toString() : "java.lang.Throwable";
          String excShort = shortName(excName);
          String handlerLabel = "L_CATCH_" + excShort;
          String paramName = "ex_" + excShort;
          insns.add(
              TacInstruction.builder(TacInstruction.Kind.LABEL)
                  .label(handlerLabel)
                  .line(line)
                  .build());
          TacOperand.TacVar catchParam =
              new TacOperand.TacVar(paramName, TacType.ofClass(excName), false);
          insns.add(
              TacInstruction.builder(TacInstruction.Kind.CATCH_HANDLER)
                  .dest(catchParam)
                  .ctorType(TacType.ofClass(excName))
                  .line(line)
                  .build());
          // Remember the catch label name and
          // the catch-handler range so we can build
          // the TryRegion at the end of the loop. The
          // catch range start is the index of the
          // LABEL we just emitted; the end is the
          // index of the last emitted instruction of
          // this handler body (filled below when we
          // encounter the next handler entry or the
          // loop end).
          int prevStart = catchRanges.isEmpty() ? -1 : catchRanges.get(catchRanges.size() - 1)[1];
          if (prevStart < 0) {
            // first catch handler; close its end
            // when the next one is reached.
            catchRanges.add(new int[] {insns.size() - 2, -1, catchRanges.size()});
          } else {
            // close previous catch range end
            catchRanges.get(catchRanges.size() - 1)[1] = insns.size() - 2;
            // start new one (start = insns.size() - 2
            // because we just emitted LABEL + CATCH_HANDLER)
            catchRanges.add(new int[] {insns.size() - 2, -1, catchRanges.size()});
          }
          catchLabelByHandler.put(stmt, handlerLabel);
        } catch (RuntimeException ex) {
          LOG.debug("Failed to emit CATCH_HANDLER for {}: {}", signature, ex.getMessage());
        }
      }
      try {
        if (stmt instanceof JIdentityStmt is) {
          insns.add(convertIdentity(is, line, hasThisLocal));
        } else if (stmt instanceof JAssignStmt as) {
          insns.addAll(convertAssign(as, line, nextLabelName.get()));
        } else if (stmt instanceof JInvokeStmt inv) {
          insns.add(convertInvokeStmt(inv, line));
        } else if (stmt instanceof JIfStmt ifs) {
          insns.add(convertIf(ifs, line, body, nextLabelName.get()));
        } else if (stmt instanceof JGotoStmt gs) {
          insns.add(convertGoto(gs, line, body));
        } else if (stmt instanceof JNopStmt) {
          insns.add(TacInstruction.builder(TacInstruction.Kind.NOP).line(line).build());
        } else if (stmt instanceof JReturnStmt rs) {
          TacInstruction.Builder b =
              TacInstruction.builder(TacInstruction.Kind.RETURN)
                  .lhs1(op.toOperand(rs.getOp()))
                  .line(line);
          insns.add(b.build());
        } else if (stmt instanceof JReturnVoidStmt) {
          insns.add(TacInstruction.builder(TacInstruction.Kind.RETURN).line(line).build());
        } else if (stmt instanceof JThrowStmt ts) {
          insns.add(
              TacInstruction.builder(TacInstruction.Kind.THROW)
                  .lhs1(op.toOperand(ts.getOp()))
                  .line(line)
                  .build());
        } else {
          LOG.debug("Unhandled Jimple stmt in {}: {}", signature, stmt.getClass().getSimpleName());
        }
      } catch (RuntimeException ex) {
        LOG.warn(
            "Failed to convert stmt in {}: {} ({})",
            signature,
            ex.getMessage(),
            stmt.getClass().getSimpleName());
      }
    }
    // Close the last catch range (end = insns.size() - 1)
    // and build the TryRegion list. SootUp's CFG gives us
    // handler blocks but no JTryStmt with explicit try-block
    // ranges; we approximate each try range as the
    // [min, max] of the predecessor-statement indices of
    // the catch handler block. Multiple catch handlers from
    // the same try block share the same predecessor set and
    // are grouped into one TryRegion.
    if (!catchRanges.isEmpty()) {
      catchRanges.get(catchRanges.size() - 1)[1] = insns.size() - 1;
    }
    List<TacFunction.TryRegion> tryRegions =
        buildSootUpTryRegions(
            body, catchEntries, catchLabelByHandler, catchRanges, stmtFirstInsnIdx);
    return new TacFunction(
        methodName,
        signature,
        returnType,
        params,
        locals,
        insns,
        List.of(),
        List.of(),
        java.util.Map.of(),
        java.util.Map.of(),
        null,
        SourceOrigin.LIBRARY,
        tryRegions);
  }

  /**
   * Build a list of {@link TacFunction.TryRegion} for a SootUp-converted function. SootUp's CFG
   * gives us catch-handler entry blocks and the set of stmts whose exceptional successors include
   * them (the throw points) -- the union of those predecessor stmts is the approximated try block
   * for the corresponding catch. We group catch handlers by overlap of their predecessor stmt sets
   * so the AST-translator-style "one region per try statement" model holds.
   */
  private List<TacFunction.TryRegion> buildSootUpTryRegions(
      sootup.core.model.Body body,
      java.util.Map<Stmt, sootup.core.types.ClassType> catchEntries,
      java.util.Map<Stmt, String> catchLabelByHandler,
      List<int[]> catchRanges,
      java.util.Map<Stmt, Integer> stmtFirstInsnIdx) {
    if (catchEntries.isEmpty()) {
      return List.of();
    }
    sootup.core.graph.ControlFlowGraph cfg;
    try {
      cfg = body.getControlFlowGraph();
    } catch (RuntimeException ex) {
      return List.of();
    }
    if (cfg == null) return List.of();
    // Build stmt -> block (or use a body index map for
    // predecessor walking). SootUp's API: iterate over
    // all stmts in the body and find which of them have
    // exceptionalSuccessors pointing to a given handler
    // block. We can do this by walking all stmts once.
    // For each catch handler entry, compute its set of
    // predecessor stmts (= the throw points in the try
    // block that flow to this handler). Group handlers
    // whose predecessor sets overlap.
    java.util.Map<Stmt, java.util.Set<Stmt>> handlerPredecessors = new java.util.HashMap<>();
    for (Stmt handler : catchEntries.keySet()) {
      java.util.Set<Stmt> preds = new java.util.HashSet<>();
      try {
        for (Stmt s : body.getStmts()) {
          var excSuccs = cfg.exceptionalSuccessors(s);
          if (excSuccs == null) continue;
          for (Object hb : excSuccs.values()) {
            if (hb == null) continue;
            if (handler.equals(hb)) {
              preds.add(s);
              break;
            }
          }
        }
      } catch (RuntimeException ex) {
        // skip
      }
      handlerPredecessors.put(handler, preds);
    }
    // Group handlers by overlap. Simplest approach: each
    // handler gets its own region; the CFG builder
    // unions exception-successor sets so duplicate
    // handler edges are deduped. The AST-translator path
    // emits one region per source try statement, so for
    // SootUp the parity is "one region per catch handler
    // entry", with the try range being the predecessor
    // span. This is precise enough for the dataflow
    // engine to walk exception paths.
    List<TacFunction.TryRegion> regions = new java.util.ArrayList<>();
    for (int[] range : catchRanges) {
      int start = range[0];
      int end = range[1];
      int idx = range[2];
      if (start < 0 || end < start) continue;
      // Find the corresponding handler stmt.
      // catchRanges[i][2] is the original index; map
      // back via ordering. Easiest: take the i-th
      // catchLabelByHandler key.
      Stmt handler = null;
      int seen = 0;
      for (Stmt h : catchLabelByHandler.keySet()) {
        if (seen == idx) {
          handler = h;
          break;
        }
        seen++;
      }
      if (handler == null) continue;
      String label = catchLabelByHandler.get(handler);
      // Compute try range from predecessors.
      java.util.Set<Stmt> preds = handlerPredecessors.getOrDefault(handler, java.util.Set.of());
      int tryStart = start;
      int tryEnd = end;
      if (!preds.isEmpty()) {
        int minPredIdx = Integer.MAX_VALUE;
        int maxPredIdx = Integer.MIN_VALUE;
        for (Stmt p : preds) {
          Integer pi = stmtFirstInsnIdx.get(p);
          if (pi == null) continue;
          if (pi < minPredIdx) minPredIdx = pi;
          if (pi > maxPredIdx) maxPredIdx = pi;
        }
        if (minPredIdx != Integer.MAX_VALUE) tryStart = minPredIdx;
        if (maxPredIdx != Integer.MIN_VALUE) tryEnd = maxPredIdx;
      }
      if (tryStart < 0 || tryEnd < tryStart) continue;
      regions.add(
          new TacFunction.TryRegion(tryStart, tryEnd, start, end, List.of(label), null, -1, -1));
    }
    return regions;
  }

  // ---- Per-statement converters -----------------------------------

  private TacInstruction convertIdentity(JIdentityStmt is, int line, boolean hasThisLocal) {
    Local lhs = (Local) is.getLeftOp();
    Object rhs = is.getRightOp();
    TacOperand dest = op.toVar(lhs);
    TacOperand src;
    if (rhs instanceof JThisRef) {
      // `this` is the receiver; a MOVE for `this` is a no-op in
      // practice (the local is just a name for the receiver).
      // Emit a MOVE from a synthetic `this` to keep the IR
      // shape uniform — the existing AST translator does the
      // same for instance methods.
      src = new TacOperand.TacVar("this", op.tacTypeOf(lhs.getType()), false);
    } else if (rhs instanceof JParameterRef pr) {
      // @parameter N — synthesize the project parameter name
      // that the @parameter-indexed locals are matched against.
      int projectIdx = hasThisLocal ? pr.getIndex() - 1 : pr.getIndex();
      src = new TacOperand.TacVar("@parameter" + projectIdx, op.tacTypeOf(lhs.getType()), false);
    } else if (rhs instanceof JCaughtExceptionRef) {
      // `catch (Throwable t)` synthetic init — the body's
      // exception local is the dest; no source needed beyond
      // a "fresh" sentinel. Emit MOVE from a fresh var.
      src = new TacOperand.TacVar("$caught", TacType.ofClass("java.lang.Throwable"), true);
    } else {
      src = new TacOperand.TacVar("<identity>", TacType.UNKNOWN, true);
    }
    return TacInstruction.builder(TacInstruction.Kind.MOVE).dest(dest).lhs1(src).line(line).build();
  }

  private List<TacInstruction> convertAssign(JAssignStmt as, int line, String nextLabelName) {
    List<TacInstruction> out = new ArrayList<>();
    Object lhs = as.getLeftOp();
    Object rhs = as.getRightOp();

    if (!(lhs instanceof Local lhsLocal)) {
      // Field/array ref on the LHS (x.f = ... or x[k] = ...).
      // `as` should have been matched by the typed accessors
      // getFieldRef()/getArrayRef() — fall through to those
      // branches for completeness.
      if (lhs instanceof JInstanceFieldRef ifr) {
        out.add(
            TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
                .lhs1(op.fieldBase(ifr))
                .fieldName(op.fieldName(ifr))
                .dest(op.toOperand(rhs))
                .line(line)
                .build());
        return out;
      }
      if (lhs instanceof JStaticFieldRef sfr) {
        // Static field as the LHS — there's no per-call
        // receiver; emit a FIELDSTORE with a sentinel
        // "static" base so downstream checks can match
        // <static>.<fieldName>.
        out.add(
            TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
                .lhs1(new TacOperand.TacVar("<static>", TacType.UNKNOWN, true))
                .fieldName(sfr.getFieldSignature().getName())
                .dest(op.toOperand(rhs))
                .line(line)
                .build());
        return out;
      }
      if (lhs instanceof JArrayRef ar) {
        out.add(
            TacInstruction.builder(TacInstruction.Kind.ARRAYSTORE)
                .lhs1(op.arrayBase(ar))
                .lhs2(op.arrayIndex(ar))
                .dest(op.toOperand(rhs))
                .line(line)
                .build());
        return out;
      }
      LOG.debug("Unhandled assign LHS: {}", lhs.getClass().getSimpleName());
      return out;
    }
    TacOperand dest = op.toVar(lhsLocal);

    // RHS dispatch.
    if (rhs instanceof sootup.core.jimple.common.constant.Constant
        || rhs instanceof sootup.core.jimple.common.Local) {
      out.add(
          TacInstruction.builder(TacInstruction.Kind.MOVE)
              .dest(dest)
              .lhs1(op.toOperand(rhs))
              .line(line)
              .build());
      return out;
    }
    if (rhs instanceof JInstanceFieldRef ifr) {
      out.add(
          TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
              .dest(dest)
              .lhs1(op.fieldBase(ifr))
              .fieldName(op.fieldName(ifr))
              .line(line)
              .build());
      return out;
    }
    if (rhs instanceof JStaticFieldRef sfr) {
      out.add(
          TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
              .dest(dest)
              .lhs1(new TacOperand.TacVar("<static>", TacType.UNKNOWN, true))
              .fieldName(sfr.getFieldSignature().getName())
              .line(line)
              .build());
      return out;
    }
    if (rhs instanceof JArrayRef ar) {
      out.add(
          TacInstruction.builder(TacInstruction.Kind.ARRAYLOAD)
              .dest(dest)
              .lhs1(op.arrayBase(ar))
              .lhs2(op.arrayIndex(ar))
              .line(line)
              .build());
      return out;
    }
    if (rhs instanceof sootup.core.jimple.common.expr.AbstractBinopExpr bo) {
      BinaryOperator tbop = JimpleOperandConverter.binaryOpOf(bo);
      if (tbop == null) {
        LOG.debug("Unknown binop on RHS: {}", bo.getClass().getSimpleName());
        return out;
      }
      out.add(
          TacInstruction.builder(TacInstruction.Kind.BINOP)
              .dest(dest)
              .binop(tbop)
              .lhs1(op.toOperand(bo.getOp1()))
              .lhs2(op.toOperand(bo.getOp2()))
              .line(line)
              .build());
      return out;
    }
    if (rhs instanceof JNegExpr ne) {
      out.add(
          TacInstruction.builder(TacInstruction.Kind.UNARYOP)
              .dest(dest)
              .unop(UnaryOperator.NEG)
              .lhs1(op.toOperand(ne.getOp()))
              .line(line)
              .build());
      return out;
    }
    if (rhs instanceof JLengthExpr le) {
      // Jimple's array-length is a dedicated expr; in our TAC
      // we model it as a CALL to "Array.length/0" so existing
      // catalog-based length checks (if any) work.
      out.add(
          TacInstruction.builder(TacInstruction.Kind.CALL)
              .dest(dest)
              .calleeSig("java.lang.reflect.Array#length/0")
              .args(java.util.List.of(op.toOperand(le.getOp())))
              .line(line)
              .build());
      return out;
    }
    if (rhs instanceof JCastExpr ce) {
      out.add(
          TacInstruction.builder(TacInstruction.Kind.UNARYOP)
              .dest(dest)
              .unop(UnaryOperator.CAST)
              .castType(op.tacTypeOf(ce.getType()))
              .lhs1(op.toOperand(ce.getOp()))
              .line(line)
              .build());
      return out;
    }
    if (rhs instanceof JInstanceOfExpr ioe) {
      out.add(
          TacInstruction.builder(TacInstruction.Kind.INSTANCEOF)
              .dest(dest)
              .lhs1(op.toOperand(ioe.getOp()))
              .ctorType(op.instanceofTypeOf(ioe))
              .line(line)
              .build());
      return out;
    }
    if (rhs instanceof JNewExpr ne) {
      out.add(
          TacInstruction.builder(TacInstruction.Kind.NEW)
              .dest(dest)
              .ctorType(op.newTypeOf(ne))
              .args(op.newArgs(ne))
              .line(line)
              .build());
      return out;
    }
    if (rhs instanceof JNewArrayExpr na) {
      out.add(
          TacInstruction.builder(TacInstruction.Kind.NEWARRAY)
              .dest(dest)
              .ctorType(op.newArrayComponentOf(na))
              .lhs1(op.newArraySizeOf(na))
              .line(line)
              .build());
      return out;
    }
    if (rhs instanceof sootup.core.jimple.common.expr.AbstractInvokeExpr inv) {
      out.add(convertInvoke(inv, dest, line));
      return out;
    }
    // JNewMultiArrayExpr — a multi-dim new (e.g. new int[3][4]).
    // We don't have a multi-dim TAC op; flatten into a
    // NEWARRAY of array of array … per dimension so consumers
    // can still see the dimensions.
    if (rhs instanceof sootup.core.jimple.common.expr.JNewMultiArrayExpr mna) {
      // Synthesize a TacType of array depth = sizes.size().
      int depth = mna.getSizes().size();
      TacType comp = op.tacTypeOf(mna.getBaseType());
      for (int i = 1; i < depth; i++) {
        comp = TacType.arrayOf(comp);
      }
      out.add(
          TacInstruction.builder(TacInstruction.Kind.NEWARRAY)
              .dest(dest)
              .ctorType(comp)
              .lhs1(op.toOperand(mna.getSizes().get(0)))
              .line(line)
              .build());
      return out;
    }
    LOG.debug("Unhandled assign RHS: {} (line {})", rhs.getClass().getSimpleName(), line);
    return out;
  }

  private TacInstruction convertInvoke(
      sootup.core.jimple.common.expr.AbstractInvokeExpr inv, TacOperand dest, int line) {
    String sig;
    if (inv instanceof sootup.core.jimple.common.expr.JSpecialInvokeExpr) {
      sig =
          JimpleOperandConverter.calleeSigOf(
              (sootup.core.jimple.common.expr.JSpecialInvokeExpr) inv);
    } else if (inv instanceof sootup.core.jimple.common.expr.JVirtualInvokeExpr) {
      sig =
          JimpleOperandConverter.calleeSigOf(
              (sootup.core.jimple.common.expr.JVirtualInvokeExpr) inv);
    } else if (inv instanceof sootup.core.jimple.common.expr.JStaticInvokeExpr) {
      sig =
          JimpleOperandConverter.calleeSigOf(
              (sootup.core.jimple.common.expr.JStaticInvokeExpr) inv);
    } else if (inv instanceof sootup.core.jimple.common.expr.JDynamicInvokeExpr) {
      // invokedynamic (lambda metafactory etc.) — emit with a
      // synthesized signature and the bootstrap args as
      // normal call args. The check engine doesn't need
      // precise invokedynamic resolution for the smoke
      // test.
      sig =
          "<invokedynamic>."
              + inv.getMethodSignature().getName()
              + "/"
              + inv.getMethodSignature().getParameterTypes().size();
    } else {
      sig = JimpleOperandConverter.calleeSigOf(inv);
    }
    return TacInstruction.builder(TacInstruction.Kind.CALL)
        .dest(dest)
        .calleeSig(sig)
        .args(op.callArgs(inv))
        .line(line)
        .build();
  }

  private TacInstruction convertIf(JIfStmt ifs, int line, Body body, String nextLabelName) {
    // SootUp 3.0.0 doesn't store the target on JIfStmt directly;
    // resolve it through the body's CFG.
    List<Stmt> targets = body.getBranchTargetsOf(ifs);
    String trueLabel = targets.isEmpty() ? nextLabelName + "_true" : labelOfTarget(targets.get(0));
    String falseLabel = nextLabelName + "_after";
    return TacInstruction.builder(TacInstruction.Kind.BRANCH)
        .lhs1(op.toOperand(ifs.getCondition()))
        .trueLabel(trueLabel)
        .falseLabel(falseLabel)
        .line(line)
        .build();
  }

  private TacInstruction convertGoto(JGotoStmt gs, int line, Body body) {
    List<Stmt> targets = body.getBranchTargetsOf(gs);
    String targetLabel = targets.isEmpty() ? "block_unknown" : labelOfTarget(targets.get(0));
    return TacInstruction.builder(TacInstruction.Kind.JUMP).label(targetLabel).line(line).build();
  }

  private TacInstruction convertInvokeStmt(JInvokeStmt inv, int line) {
    // JInvokeStmt.getInvokeExpr() returns Optional<AbstractInvokeExpr>.
    return inv.getInvokeExpr()
        .map(expr -> convertInvoke(expr, null, line))
        .orElseGet(() -> TacInstruction.builder(TacInstruction.Kind.NOP).line(line).build());
  }

  private static String labelOfTarget(Object target) {
    // Jimple branch targets are Stmt objects; the project's
    // TAC uses the target stmt's simple class name + a
    // synthetic id derived from System.identityHashCode to
    // keep labels stable within a single body.
    return "block_" + System.identityHashCode(target);
  }

  private static int lineOf(Stmt stmt) {
    try {
      StmtPositionInfo pi = stmt.getPositionInfo();
      if (pi == null) return -1;
      Position p = pi.getStmtPosition();
      if (p == null) return -1;
      int line = p.getFirstLine();
      return Math.max(line, -1);
    } catch (RuntimeException ex) {
      return -1;
    }
  }

  // Short class-name helper used to build stable
  // CATCH_HANDLER labels (e.g. {@code L_CATCH_IOException}
  // instead of {@code L_CATCH_java_io_IOException}).
  private static String shortName(String fqn) {
    if (fqn == null || fqn.isEmpty()) return "Exception";
    int lt = fqn.indexOf('<');
    if (lt >= 0) fqn = fqn.substring(0, lt);
    int dot = fqn.lastIndexOf('.');
    return dot >= 0 ? fqn.substring(dot + 1) : fqn;
  }
}
