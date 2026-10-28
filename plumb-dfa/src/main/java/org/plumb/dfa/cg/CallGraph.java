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
package org.plumb.dfa.cg;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A cross-TU call graph.
 *
 * <p>Built by {@link CallGraphBuilder} from a {@link org.plumb.tac.ir.TacProgram}. Holds:
 *
 * <ul>
 *   <li>all function nodes (functions discovered in the program)
 *   <li>forward map: caller → list of call sites
 *   <li>reverse map: callee → list of incoming call sites
 *   <li>entry points: functions with no incoming calls (e.g. {@code main}, request handlers, test
 *       methods)
 *   <li>reverse post-order (RPO): a topological ordering suitable for bottom-up summary computation
 * </ul>
 *
 * <p>Thread-safe. The graph is built once and then queried by the analysis engine; concurrent
 * queries are allowed but no mutation is expected after {@link #build}.
 */
public final class CallGraph {

  private final Set<FunctionNode> nodes;
  private final Map<FunctionNode, List<CallSite>> forward;
  private final Map<FunctionNode, List<CallSite>> reverse;
  private final List<FunctionNode> rpo;
  private final Set<FunctionNode> entryPoints;

  public CallGraph(
      Set<FunctionNode> nodes,
      Map<FunctionNode, List<CallSite>> forward,
      Map<FunctionNode, List<CallSite>> reverse,
      List<FunctionNode> rpo,
      Set<FunctionNode> entryPoints) {
    this.nodes = Collections.unmodifiableSet(new LinkedHashSet<>(nodes));
    this.forward = Collections.unmodifiableMap(copy(forward));
    this.reverse = Collections.unmodifiableMap(copy(reverse));
    this.rpo = Collections.unmodifiableList(rpo);
    this.entryPoints = Collections.unmodifiableSet(new LinkedHashSet<>(entryPoints));
  }

  private static Map<FunctionNode, List<CallSite>> copy(Map<FunctionNode, List<CallSite>> m) {
    Map<FunctionNode, List<CallSite>> out = new LinkedHashMap<>();
    for (Map.Entry<FunctionNode, List<CallSite>> e : m.entrySet()) {
      out.put(e.getKey(), List.copyOf(e.getValue()));
    }
    return out;
  }

  public Set<FunctionNode> nodes() {
    return nodes;
  }

  public List<CallSite> callsFrom(FunctionNode caller) {
    return forward.getOrDefault(caller, List.of());
  }

  public List<CallSite> callsTo(FunctionNode callee) {
    return reverse.getOrDefault(callee, List.of());
  }

  public List<FunctionNode> reversePostOrder() {
    return rpo;
  }

  public Set<FunctionNode> entryPoints() {
    return entryPoints;
  }

  /** All call sites in the graph, in iteration order. */
  public List<CallSite> allCallSites() {
    java.util.List<CallSite> out = new java.util.ArrayList<>();
    for (List<CallSite> cs : forward.values()) out.addAll(cs);
    return Collections.unmodifiableList(out);
  }
}
