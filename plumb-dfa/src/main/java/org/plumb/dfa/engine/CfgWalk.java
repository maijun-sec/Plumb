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
package org.plumb.dfa.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.ir.TacCfg;

/**
 * One depth-first walk of a control-flow graph, and the loops it finds.
 *
 * <p><b>Why this exists.</b> A loop is identified the way the compiler literature identifies it: an
 * edge {@code u -> v} that closes a cycle -- a <b>back edge</b> -- makes {@code v} the loop's
 * header, and the natural loop of that edge is the header plus every block on the path from the
 * back-edge source back to the header.
 *
 * <p>The engine already computed exactly that, because the analysis order has to put a loop's body
 * before its exit. It computed it inside a method whose job is something else, so the answer was
 * not available to a checker that wants to ask a different question about the same graph: "is this
 * instruction inside a loop?"
 *
 * <p>The alternative -- a second walk, written for the checker -- is the thing this class exists to
 * prevent. Two implementations of "what is a loop" drift: the one that drifts is the one answering
 * a question about the code, and it does so silently.
 *
 * <p>So this class is the ONE implementation. The analysis order reads the loops from it to reorder
 * the successors; a checker reads the loops from it to find the instructions inside a loop. Both
 * answers come from the same walk.
 */
final class CfgWalk {

  /** The depth-first parent of each reachable block: child -&gt; parent. */
  final Map<Integer, Integer> dfsParent;

  /** The blocks in the order the first walk finished them. */
  final List<Integer> postOrder;

  /** The blocks that are the header of some loop: the targets of the back edges. */
  final Set<Integer> loopHeaders;

  /** Every block that is inside some loop: each header, and each block on a path back to it. */
  final Set<Integer> blocksInLoop;

  private CfgWalk(
      Map<Integer, Integer> dfsParent,
      List<Integer> postOrder,
      Set<Integer> loopHeaders,
      Set<Integer> blocksInLoop) {
    this.dfsParent = dfsParent;
    this.postOrder = postOrder;
    this.loopHeaders = loopHeaders;
    this.blocksInLoop = blocksInLoop;
  }

  /**
   * Walks the graph once and reports the loops it closes.
   *
   * @param cfg the control-flow graph
   * @return the walk, with its parent chain, its post-order and its loops
   */
  static CfgWalk of(TacCfg cfg) {
    Map<Integer, Integer> dfsParent = new HashMap<>();
    ArrayList<Integer> postOrder = new ArrayList<>();
    HashSet<Integer> visited = new HashSet<>();
    ArrayDeque<int[]> stack = new ArrayDeque<>();
    stack.push(new int[] {cfg.entryId(), 0});
    while (!stack.isEmpty()) {
      int[] top = stack.peek();
      int blockId = top[0];
      int childIdx = top[1];
      if (childIdx == 0 && !visited.add(blockId)) {
        stack.pop();
        continue;
      }
      List<Integer> blockSuccs = cfg.succs(blockId);
      if (childIdx < blockSuccs.size()) {
        top[1] = childIdx + 1;
        int nextSucc = blockSuccs.get(childIdx);
        if (visited.contains(nextSucc)) continue;
        dfsParent.put(nextSucc, blockId);
        stack.push(new int[] {nextSucc, 0});
        continue;
      }
      stack.pop();
      postOrder.add(blockId);
    }

    // The blocks that are loop headers: the targets of the back edges.
    HashSet<Integer> loopHeaders = new HashSet<>();
    // The blocks that are inside some loop: every block on a path from a loop header to the
    // back-edge source, plus the header itself.
    HashSet<Integer> inLoop = new HashSet<>();
    //
    // A back edge is an edge u -> v where v is an ANCESTOR of u in the depth-first search tree.
    // The DFS tree edges are recorded in `dfsParent` (child -> parent), so the ancestors of a
    // node are the chain obtained by following `dfsParent` upwards. For every block u and every
    // successor v of u, v is a back-edge target when the upward chain from u reaches v.
    for (Map.Entry<Integer, List<Integer>> e : cfg.successors().entrySet()) {
      int u = e.getKey();
      for (int v : e.getValue()) {
        int walker = u;
        int guard = 0;
        boolean isBackEdge = false;
        while (guard++ < dfsParent.size() + 1) {
          if (walker == v) {
            isBackEdge = true;
            break;
          }
          Integer next = dfsParent.get(walker);
          if (next == null) break;
          walker = next;
        }
        if (!isBackEdge) continue;
        loopHeaders.add(v);
        // The natural loop of the back edge v -> u: the header v, every block on the DFS parent
        // chain from u back to v, and v itself.
        inLoop.add(v);
        int p = u;
        int g2 = 0;
        while (g2++ < dfsParent.size() + 1) {
          inLoop.add(p);
          if (p == v) break;
          Integer next = dfsParent.get(p);
          if (next == null) break;
          p = next;
        }
      }
    }
    return new CfgWalk(dfsParent, postOrder, loopHeaders, inLoop);
  }
}
