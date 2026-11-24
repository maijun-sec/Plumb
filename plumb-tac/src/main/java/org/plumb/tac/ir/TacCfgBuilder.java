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
package org.plumb.tac.ir;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Build a {@link TacCfg} from a {@link TacFunction}'s linear instruction stream. Standard leader /
 * partition rules:
 *
 * <ul>
 *   <li>The first instruction is a leader.
 *   <li>The target of any {@code BRANCH} / {@code JUMP} is a leader.
 *   <li>The instruction immediately after a {@code BRANCH} / {@code JUMP} / {@code RETURN} / {@code
 *       THROW} is a leader.
 * </ul>
 *
 * Successors are computed by walking each block's terminator:
 *
 * <ul>
 *   <li>{@code RETURN}, {@code THROW}: no successors.
 *   <li>{@code JUMP}: 1 successor (the target).
 *   <li>{@code BRANCH}: 2 successors (true-label, false-label).
 *   <li>Anything else: 1 successor (the next instruction's block, or none if the block is last).
 * </ul>
 *
 * <p><b>Exception-flow edges.</b> In addition to the normal successors, the builder adds
 * exception-flow edges for blocks inside try / catch / finally regions. Any block whose instruction
 * range falls inside a try region (per {@link TacFunction#tryRegions()}) gets extra successors
 * pointing to the catch handler blocks of that region, so the dataflow engine can propagate facts
 * through catch paths. Which handlers a block reaches is decided by the TYPE of the exception it
 * raises: a handler whose declared type ({@code CATCH_HANDLER.ctorType}) cannot be an instance of
 * the thrown type ({@code THROW.ctorType}) is not reached, and a handler that catches the exception
 * shadows the handlers after it. The type relation itself is world knowledge, so it comes from an
 * {@link ExceptionTypeMatcher} rather than from the TAC; the default matcher is {@link
 * ExceptionTypeHierarchies#defaultMatcher()}, and a caller that knows a different platform passes
 * its own. Blocks inside a catch range whose last instruction is a {@code THROW} (rethrow) get an
 * extra successor pointing to the region's finally block (if any). The normal successors
 * (try-completion fall-through into finally, catch-completion fall-through into finally) are the
 * translator's responsibility -- the translator emits explicit {@code JUMP} instructions to
 * L_FINALLY labels. This builder only wires the <em>non-normal</em> paths.
 */
public final class TacCfgBuilder {

  private TacCfgBuilder() {}

  public static TacCfg build(TacFunction fn) {
    return build(fn, ExceptionTypeHierarchies.defaultMatcher());
  }

  /**
   * Build the CFG, deciding the exception-flow edges with the given type matcher.
   *
   * <p><b>Why the matcher is a parameter.</b> Whether an exception of one type reaches a handler of
   * another type is a question about the platform's exception hierarchy, not about the analysed
   * program, so the caller that knows the platform supplies the answer. The default matcher is
   * {@link ExceptionTypeHierarchies#defaultMatcher()}, the catalogue that ships with the IR; a
   * caller with a better source of truth (a classpath it can read) can pass its own.
   *
   * <p>Every consumer must use the SAME matcher, because the exception edges are part of the CFG
   * the dataflow engine walks: two CFGs built from one function with different matchers would
   * disagree about which catch handler a throw reaches.
   */
  public static TacCfg build(TacFunction fn, ExceptionTypeMatcher matcher) {
    Objects.requireNonNull(fn, "fn");
    Objects.requireNonNull(matcher, "matcher");
    List<TacInstruction> insns = fn.instructions();
    if (insns.isEmpty()) {
      return new TacCfg(fn.signature(), List.of(), Map.of(), -1, Map.of());
    }

    // 1) Find leaders
    boolean[] isLeader = new boolean[insns.size()];
    isLeader[0] = true;
    for (int i = 0; i < insns.size(); i++) {
      TacInstruction ins = insns.get(i);
      switch (ins.kind) {
        case BRANCH, JUMP -> {
          // mark the targets as leaders (resolved below)
          if (ins.kind == TacInstruction.Kind.BRANCH) {
            // true / false targets
            if (ins.trueLabel != null) markLeaderByLabel(isLeader, insns, ins.trueLabel);
            if (ins.falseLabel != null) markLeaderByLabel(isLeader, insns, ins.falseLabel);
          } else {
            if (ins.label != null) markLeaderByLabel(isLeader, insns, ins.label);
          }
          // fall-through is also a leader
          if (i + 1 < insns.size()) isLeader[i + 1] = true;
        }
        case RETURN, THROW -> {
          if (i + 1 < insns.size()) isLeader[i + 1] = true;
        }
        // A LABEL is itself a block boundary because
        // it represents a new join point — a catch handler
        // entry, a finally block, or the target of an
        // explicit JUMP from another part of the function.
        // Without this, the LABEL ends up at the tail of the
        // preceding block and the catch handler block isn't
        // a separate CFG node, so the exception-flow edges
        // can't target it. Mark every LABEL as a leader.
        case LABEL -> {
          isLeader[i] = true;
        }
        default -> {
          /* normal fall-through */
        }
      }
    }

    // 2) Partition by leaders
    List<TacBasicBlock> blocks = new ArrayList<>();
    List<Integer> current = new ArrayList<>();
    Map<String, Integer> labelToBlock = new HashMap<>();
    for (int i = 0; i < insns.size(); i++) {
      if (isLeader[i] && !current.isEmpty()) {
        int id = blocks.size();
        blocks.add(new TacBasicBlock(id, current));
        current = new ArrayList<>();
      }
      // Record LABEL insns to a block id.
      current.add(i);
      if (insns.get(i).kind == TacInstruction.Kind.LABEL) {
        labelToBlock.put(insns.get(i).label, blocks.size()); // id of the block about to be added
      }
    }
    if (!current.isEmpty()) {
      blocks.add(new TacBasicBlock(blocks.size(), current));
    }
    // Resolve label->block mapping. We just added blocks in order; the
    // label encountered at instruction i is "owned" by the block that
    // starts at i. Walk again to fix that.
    labelToBlock.clear();
    for (TacBasicBlock b : blocks) {
      int first = b.firstIndex();
      if (insns.get(first).kind == TacInstruction.Kind.LABEL) {
        labelToBlock.put(insns.get(first).label, b.id());
      }
    }

    // 3) Compute successors
    Map<Integer, List<Integer>> succs = new LinkedHashMap<>();
    for (TacBasicBlock b : blocks) {
      TacInstruction last = insns.get(b.lastIndex());
      List<Integer> s = new ArrayList<>();
      switch (last.kind) {
        case RETURN, THROW -> {
          /* none */
        }
        case JUMP -> {
          Integer target = labelToBlock.get(last.label);
          if (target != null) s.add(target);
        }
        case BRANCH -> {
          Integer t = last.trueLabel == null ? null : labelToBlock.get(last.trueLabel);
          Integer f = last.falseLabel == null ? null : labelToBlock.get(last.falseLabel);
          if (t != null) s.add(t);
          if (f != null && (t == null || !t.equals(f))) s.add(f);
        }
        default -> {
          // fall through to the next block
          int idxInList = blocks.indexOf(b);
          if (idxInList + 1 < blocks.size()) s.add(blocks.get(idxInList + 1).id());
        }
      }
      succs.put(b.id(), List.copyOf(s));
    }

    // Try-completion edges. The translator's
    // translateTry emits try body, then immediately
    // emits catch handler LABEL+CATCH_HANDLER (no
    // explicit JUMP from end of try body to "after
    // try-catch"). The natural fall-through from the
    // last block of the try body therefore lands in
    // the catch block — but that's only valid if the
    // try body threw. The normal "try completed
    // successfully" path is missing entirely. Previously
    // this silently dropped any post-try code
    // (e.g. CWE-601's `response.sendRedirect(data)`
    // which sits between try and the function exit).
    //
    // We add an explicit edge from the last block of
    // the try body to the first block of the post-try
    // code (= the block starting at catchEnd + 1, or
    // tryEnd + 1 if no catch). This is sound over-
    // approx: the dataflow engine union-merges
    // facts from both paths (try-completion and
    // exception → catch), so post-try code sees the
    // union of in-taints. The natural fall-through
    // to catch remains (covers the "catch doesn't
    // return and execution continues to post-try"
    // case where the catch's RETURN is absent).
    addTryCompletionEdges(succs, blocks, insns, labelToBlock, fn.tryRegions());

    // Resume edges. A SUSPEND instruction is where the execution leaves the
    // function; a resume point is where it may come back to. The edge between
    // them is a normal control-flow edge, so it is added here rather than left
    // implicit.
    //
    // Why it is added even though the linear stream usually already connects
    // the two: the linear stream connects them as a FALL-THROUGH, which says
    // "the code continues" and not "the execution comes back here". A resume
    // point that no fall-through reaches would otherwise be unreachable -- a
    // front end that models a generator's states by re-entering the body at a
    // state dispatch has exactly that shape -- and the code behind it would
    // lose its taint.
    //
    // The front ends in this repository do NOT have that shape: they emit the
    // resume label right after the suspend jump, so with a single yield the
    // edge coincides with the jump's own target. It is the LATER suspend
    // points that get edges back to the EARLIER resume points, and those are
    // the ones no fall-through reaches.
    addResumeEdges(succs, blocks, insns, labelToBlock, fn.resumePoints());

    // Exception-flow edges. For each block that lies inside
    // a try region, add edges to the catch handler blocks (so a
    // throw inside the try body propagates facts to the catch
    // handler). For each block that lies inside a catch range
    // and ends in THROW (rethrow), add an edge to the finally
    // block (if any) so the rethrow can be tracked through the
    // finally region. Finally-region blocks do not gain
    // additional exception edges here — the DFA engine walks
    // them via normal successors.
    Map<Integer, List<Integer>> excSuccs =
        buildExceptionSuccessors(
            blocks, insns, labelToBlock, fn.tryRegions(), matcher, fn.sourceLanguage());

    int entry = blocks.isEmpty() ? -1 : blocks.get(0).id();
    return new TacCfg(fn.signature(), blocks, succs, entry, excSuccs);
  }

  /**
   * For each try region, add a "try-completion" edge from the block containing the last instruction
   * of the try body to the block containing the first instruction after the catch range. The
   * post-try block is {@code catchEnd + 1} if the region has catches, or {@code tryEnd + 1} if it's
   * a try-finally with no catch. The edge is added (not replacing the natural fall-through) so both
   * paths coexist: the dataflow engine unions the in-taints from "try completed" and "catch handled
   * exception and fell through".
   */
  private static void addTryCompletionEdges(
      Map<Integer, List<Integer>> succs,
      List<TacBasicBlock> blocks,
      List<TacInstruction> insns,
      Map<String, Integer> labelToBlock,
      List<TacFunction.TryRegion> regions) {
    if (regions == null || regions.isEmpty()) return;
    for (TacFunction.TryRegion region : regions) {
      if (region.tryStart < 0 || region.tryEnd < region.tryStart) continue;
      // The post-try instruction index: catchEnd+1 if
      // there's a catch, else tryEnd+1.
      int postTryIdx;
      if (region.hasCatch() && region.catchEnd >= region.catchStart) {
        postTryIdx = region.catchEnd + 1;
      } else {
        postTryIdx = region.tryEnd + 1;
      }
      if (postTryIdx >= insns.size()) continue;
      // Find the block containing the last try-body
      // instruction (tryEnd). If that block has a
      // terminator other than RETURN/THROW/JUMP/BRANCH
      // (i.e. fall-through), the natural successor is
      // the catch label — but the try body "completes
      // normally" should land at post-try. We add a
      // new edge rather than replacing, so the
      // existing fall-through is preserved (it covers
      // the "no exception but somehow we still reach
      // the catch label" case which is sound over-
      // approx).
      Integer tryEndBlockId = blockIdContainingIndex(blocks, region.tryEnd);
      Integer postTryBlockId = blockIdContainingIndex(blocks, postTryIdx);
      if (tryEndBlockId == null || postTryBlockId == null) continue;
      if (tryEndBlockId.equals(postTryBlockId)) continue;
      List<Integer> existing = succs.get(tryEndBlockId);
      if (existing != null && existing.contains(postTryBlockId)) continue;
      List<Integer> augmented = new ArrayList<>();
      if (existing != null) augmented.addAll(existing);
      augmented.add(postTryBlockId);
      succs.put(tryEndBlockId, List.copyOf(augmented));
    }
  }

  private static Integer blockIdContainingIndex(List<TacBasicBlock> blocks, int idx) {
    for (TacBasicBlock b : blocks) {
      if (idx >= b.firstIndex() && idx <= b.lastIndex()) return b.id();
    }
    return null;
  }

  /**
   * Add the edges a suspended execution takes when it comes back.
   *
   * <p>Every block that holds a {@code SUSPEND} instruction gets an edge to every resume point of
   * the function. The edge is added, not substituted, so the block's normal successors stay: the
   * code after the suspend point is reachable both by falling through and by being resumed into.
   *
   * <p><b>Why every suspend point reaches every resume point.</b> Which state a suspended execution
   * is resumed in is the CALLER's decision, and the caller is not in the TAC. Merging all of them
   * is the honest over-approximation: it may connect states that cannot actually follow one
   * another, and it never misses a pair that can.
   *
   * <p>Only {@link TacResumePoint.Kind#SUSPEND} points are wired this way. An {@link
   * TacResumePoint.Kind#EXCEPTION} point is reached by an exception unwinding to it, which is the
   * exception flow's business, not an ordinary edge.
   */
  private static void addResumeEdges(
      Map<Integer, List<Integer>> succs,
      List<TacBasicBlock> blocks,
      List<TacInstruction> insns,
      Map<String, Integer> labelToBlock,
      List<TacResumePoint> resumePoints) {
    if (resumePoints == null || resumePoints.isEmpty()) {
      return;
    }
    List<Integer> resumeBlockIds = new ArrayList<>();
    for (TacResumePoint point : resumePoints) {
      if (point == null || point.kind() != TacResumePoint.Kind.SUSPEND) {
        continue;
      }
      Integer blockId = labelToBlock.get(point.label());
      if (blockId != null && !resumeBlockIds.contains(blockId)) {
        resumeBlockIds.add(blockId);
      }
    }
    if (resumeBlockIds.isEmpty()) {
      return;
    }
    for (TacBasicBlock b : blocks) {
      boolean suspends = false;
      for (int i = b.firstIndex(); i <= b.lastIndex(); i++) {
        if (insns.get(i).isSuspending()) {
          suspends = true;
          break;
        }
      }
      if (!suspends) {
        continue;
      }
      List<Integer> existing = succs.get(b.id());
      List<Integer> augmented = new ArrayList<>();
      if (existing != null) {
        augmented.addAll(existing);
      }
      for (Integer resumeBlockId : resumeBlockIds) {
        if (!augmented.contains(resumeBlockId)) {
          augmented.add(resumeBlockId);
        }
      }
      succs.put(b.id(), List.copyOf(augmented));
    }
  }

  /**
   * Compute exception-flow edges from the function's try regions. For each region, every block
   * whose range falls inside {@code [tryStart, tryEnd]} gets an edge to the catch handler blocks it
   * can reach. Every block in {@code [catchStart, catchEnd]} that ends in a {@code THROW} gets an
   * edge to the finally block (if any). The result preserves insertion order so callers that care
   * about determinism (test assertions) get a stable edge order: catch handlers in source order,
   * finally before/after as the region's structure dictates.
   *
   * <p><b>Which handlers a block reaches.</b> A handler is reachable when the exception that leaves
   * the try body can be an instance of the handler's declared type. The block's exception sources
   * decide that:
   *
   * <ul>
   *   <li>A {@code THROW} whose {@code ctorType} names a type contributes that type.
   *   <li>Any instruction that may raise an exception of a type nobody knows -- a call, a
   *       constructor, an array or field access, a division, a cast -- contributes "unknown".
   *   <li>Nothing else contributes.
   * </ul>
   *
   * <p>When the block's sources are not fully known, EVERY handler stays reachable: that is the
   * over-approximation that cannot lose a flow. When they are known, the handler that catches the
   * exception is the FIRST one, in source order, whose type is a supertype of the thrown type --
   * the handlers after it are unreachable for that throw, and the ones whose type is unrelated
   * never see it.
   *
   * <p>Overlapping regions (e.g. a try-with-catch where the same try block has multiple catch
   * handlers registered as separate regions by the SootUp front-end) are unioned: if a block falls
   * inside two regions, the exception successors are the union of both regions' catch / finally
   * sets, deduplicated by block id.
   */
  private static Map<Integer, List<Integer>> buildExceptionSuccessors(
      List<TacBasicBlock> blocks,
      List<TacInstruction> insns,
      Map<String, Integer> labelToBlock,
      List<TacFunction.TryRegion> regions,
      ExceptionTypeMatcher matcher,
      SourceLanguage language) {
    if (regions == null || regions.isEmpty()) {
      return Map.of();
    }
    Map<String, Integer> labelToBlockRO = labelToBlock;
    Map<Integer, java.util.LinkedHashSet<Integer>> excSuccSets = new LinkedHashMap<>();
    List<TacFunction.TryRegion> sorted = new ArrayList<>(regions);
    sorted.sort(Comparator.comparingInt(r -> r.tryStart));
    for (TacFunction.TryRegion region : sorted) {
      // The handlers of this region, in SOURCE ORDER. The order decides
      // which handler a throw reaches first, and the first matching one
      // is the one that runs.
      List<CatchHandler> handlers = new ArrayList<>();
      for (String cl : region.catchLabels) {
        Integer bid = labelToBlockRO.get(cl);
        if (bid != null) {
          handlers.add(new CatchHandler(bid, handlerType(blocks, insns, bid)));
        }
      }
      Integer finallyBlockId =
          region.finallyLabel == null ? null : labelToBlockRO.get(region.finallyLabel);
      for (TacBasicBlock b : blocks) {
        int first = b.firstIndex();
        int last = b.lastIndex();
        // In try range?
        if (region.tryStart >= 0
            && region.tryEnd >= region.tryStart
            && first >= region.tryStart
            && last <= region.tryEnd) {
          List<Integer> targets = catchTargets(b, insns, handlers, matcher, language);
          if (!targets.isEmpty()) {
            excSuccSets
                .computeIfAbsent(b.id(), k -> new java.util.LinkedHashSet<>())
                .addAll(targets);
          }
        }
        // In catch range, ends in THROW (rethrow)?
        if (region.hasCatch() && first >= region.catchStart && last <= region.catchEnd) {
          TacInstruction lastIns = insns.get(last);
          if (lastIns.kind == TacInstruction.Kind.THROW && finallyBlockId != null) {
            excSuccSets
                .computeIfAbsent(b.id(), k -> new java.util.LinkedHashSet<>())
                .add(finallyBlockId);
          }
        }
      }
    }
    Map<Integer, List<Integer>> result = new LinkedHashMap<>();
    for (Map.Entry<Integer, java.util.LinkedHashSet<Integer>> e : excSuccSets.entrySet()) {
      result.put(e.getKey(), List.copyOf(e.getValue()));
    }
    return result;
  }

  /**
   * The catch handler blocks a block's exception can reach.
   *
   * <p>The conservative answer is "every handler of the region": it is the over-approximation that
   * cannot lose a flow, and it is what a block whose exception sources are not fully known gets. A
   * block whose sources are all typed throws gets the sharper answer.
   */
  private static List<Integer> catchTargets(
      TacBasicBlock block,
      List<TacInstruction> insns,
      List<CatchHandler> handlers,
      ExceptionTypeMatcher matcher,
      SourceLanguage language) {
    if (handlers.isEmpty()) {
      return List.of();
    }
    Set<String> thrownTypes = new LinkedHashSet<>();
    boolean unknownSource = false;
    for (int i = block.firstIndex(); i <= block.lastIndex(); i++) {
      TacInstruction ins = insns.get(i);
      if (ins.kind == TacInstruction.Kind.THROW) {
        String thrown = typeName(ins.ctorType);
        if (thrown == null) {
          // A throw whose type nobody knows reaches every handler.
          unknownSource = true;
        } else {
          thrownTypes.add(thrown);
        }
        continue;
      }
      if (mayRaiseUnknownException(ins)) {
        unknownSource = true;
      }
    }
    if (unknownSource || thrownTypes.isEmpty()) {
      // Either something in the block may raise an exception of an unknown
      // type, or the block raises no exception the IR knows about. Both
      // cases keep every handler reachable.
      List<Integer> all = new ArrayList<>(handlers.size());
      for (CatchHandler handler : handlers) {
        all.add(handler.blockId);
      }
      return all;
    }
    Set<Integer> targets = new LinkedHashSet<>();
    for (String thrown : thrownTypes) {
      targets.addAll(targetsForThrownType(thrown, handlers, matcher, language));
    }
    return List.copyOf(targets);
  }

  /**
   * The handlers a throw of one type reaches.
   *
   * <p>The handlers are walked in source order. The first one whose type is a supertype of the
   * thrown type catches the exception whatever the runtime type is, so the handlers after it are
   * unreachable for this throw. A handler whose type is unrelated never sees it. A handler that
   * might see it -- because its type is a subtype of the thrown type, or because the relation could
   * not be decided -- keeps its edge, and the handlers after it keep theirs too.
   */
  private static Set<Integer> targetsForThrownType(
      String thrown,
      List<CatchHandler> handlers,
      ExceptionTypeMatcher matcher,
      SourceLanguage language) {
    Set<Integer> targets = new LinkedHashSet<>();
    for (CatchHandler handler : handlers) {
      ExceptionTypeMatcher.Relation relation = matcher.relation(language, thrown, handler.type);
      if (relation == ExceptionTypeMatcher.Relation.NEVER_CATCHES) {
        // The handler's type cannot be an instance of the thrown type, so
        // the exception cannot reach it.
        continue;
      }
      targets.add(handler.blockId);
      if (relation == ExceptionTypeMatcher.Relation.CATCHES) {
        // This handler catches the exception whatever the runtime type is,
        // so it is the one that runs and the handlers after it are
        // unreachable for this throw.
        break;
      }
    }
    return targets;
  }

  /**
   * The declared type of a catch handler, or {@code null} when the front end did not record one.
   *
   * <p>The handler's type is the {@code ctorType} of the {@code CATCH_HANDLER} instruction at the
   * head of the handler's block.
   */
  private static String handlerType(
      List<TacBasicBlock> blocks, List<TacInstruction> insns, int blockId) {
    for (TacBasicBlock b : blocks) {
      if (b.id() != blockId) {
        continue;
      }
      for (int i = b.firstIndex(); i <= b.lastIndex(); i++) {
        TacInstruction ins = insns.get(i);
        if (ins.kind == TacInstruction.Kind.CATCH_HANDLER) {
          return typeName(ins.ctorType);
        }
      }
      return null;
    }
    return null;
  }

  /**
   * Whether an instruction may raise an exception whose type the IR does not know.
   *
   * <p>This is the conservative list. A call may raise anything its callee raises; a constructor
   * may raise anything; an array or field access may raise a null or a bounds error; a division may
   * raise an arithmetic error; a cast may raise a class-cast error. Every one of them makes the
   * block's exception sources unknown, which keeps every catch handler reachable.
   */
  private static boolean mayRaiseUnknownException(TacInstruction ins) {
    switch (ins.kind) {
      case CALL:
      case NEW:
      case NEWARRAY:
      case ARRAYLOAD:
      case ARRAYSTORE:
      case FIELDLOAD:
      case FIELDSTORE:
      case BINOP:
      case UNARYOP:
      case NULL_CHECK:
      case UNINTERPRETED:
        return true;
      default:
        return false;
    }
  }

  /**
   * The name of a class type, or {@code null} for anything that is not a named class.
   *
   * <p>A type that is not a class cannot be matched against a catch handler: a handler's declared
   * type is always a class, so an array or a primitive type says nothing about which handler would
   * catch it.
   */
  private static String typeName(TacType type) {
    if (type == null || type.kind() != TacType.Kind.CLASS) {
      return null;
    }
    String name = type.displayName();
    return name == null || name.isEmpty() ? null : name;
  }

  /** A catch handler: the block it starts, and the type it catches. */
  private static final class CatchHandler {
    private final int blockId;
    private final String type;

    CatchHandler(int blockId, String type) {
      this.blockId = blockId;
      this.type = type;
    }
  }

  private static void markLeaderByLabel(
      boolean[] isLeader, List<TacInstruction> insns, String label) {
    for (int j = 0; j < insns.size(); j++) {
      TacInstruction ins = insns.get(j);
      if (ins.kind == TacInstruction.Kind.LABEL && label.equals(ins.label)) {
        isLeader[j] = true;
        return;
      }
    }
  }
}
