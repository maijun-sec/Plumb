package org.plumb.tac.ir;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * through catch paths. Blocks inside a catch range whose last instruction is a {@code THROW}
 * (rethrow) get an extra successor pointing to the region's finally block (if any). The normal
 * successors (try-completion fall-through into finally, catch-completion fall-through into finally)
 * are the translator's responsibility ? the translator emits explicit {@code JUMP} instructions to
 * L_FINALLY labels. This builder only wires the <em>non-normal</em> paths.
 */
public final class TacCfgBuilder {

  private TacCfgBuilder() {}

  public static TacCfg build(TacFunction fn) {
    Objects.requireNonNull(fn, "fn");
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
        buildExceptionSuccessors(blocks, insns, labelToBlock, fn.tryRegions());

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
   * Compute exception-flow edges from the function's try regions. For each region, every block
   * whose range falls inside {@code [tryStart, tryEnd]} gets an edge to every catch handler block.
   * Every block in {@code [catchStart, catchEnd]} that ends in a {@code THROW} gets an edge to the
   * finally block (if any). The result preserves insertion order so callers that care about
   * determinism (test assertions) get a stable edge order: catch handlers in source order, finally
   * before/after as the region's structure dictates.
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
      List<TacFunction.TryRegion> regions) {
    if (regions == null || regions.isEmpty()) {
      return Map.of();
    }
    Map<String, Integer> labelToBlockRO = labelToBlock;
    Map<Integer, java.util.LinkedHashSet<Integer>> excSuccSets = new LinkedHashMap<>();
    List<TacFunction.TryRegion> sorted = new ArrayList<>(regions);
    sorted.sort(Comparator.comparingInt(r -> r.tryStart));
    for (TacFunction.TryRegion region : sorted) {
      List<Integer> catchBlockIds = new ArrayList<>();
      for (String cl : region.catchLabels) {
        Integer bid = labelToBlockRO.get(cl);
        if (bid != null) catchBlockIds.add(bid);
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
          if (!catchBlockIds.isEmpty()) {
            excSuccSets
                .computeIfAbsent(b.id(), k -> new java.util.LinkedHashSet<>())
                .addAll(catchBlockIds);
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
