package org.plumb.tac.ir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// IR-serializable: this class participates in the disk-cache contract.
// Instances are written via IrSerializer (ObjectOutputStream) and read
// back via IrSerializer.read(...). Field additions are backwards
// compatible; renaming or removing fields needs a serialVersionUID bump.
/**
 * Control-flow graph of one {@link TacFunction}. A CFG is a list of {@link TacBasicBlock}s in
 * order, plus a successor map (block-id -> list of successor block-ids). The entry block is the
 * first block (always present) and the exit block is the unique block whose last instruction is a
 * {@code RETURN} / {@code THROW}.
 *
 * <p><b>Exception-flow edges.</b> A second successor map, {@code exceptionSuccessors}, holds edges
 * that the dataflow engine walks when a block in a try region can throw: these edges point to the
 * matching catch handler blocks and (if the try statement has a {@code finally} block) to the
 * finally block. The DFA engine consults {@link #exceptionSuccs(int)} alongside {@link #succs(int)}
 * to propagate facts through exception paths.
 *
 * <p>The builder is the {@link TacCfgBuilder}; dataflow engines walk the CFG by iterating blocks +
 * their successors.
 */
public final class TacCfg implements java.io.Serializable {
  private static final long serialVersionUID = 1L;

  private final String functionSig;
  private final List<TacBasicBlock> blocks;
  private final Map<Integer, List<Integer>> successors;

  /**
   * Exception-flow edges. Maps block-id -> list of successor block-ids reachable when the block
   * (which is inside a try region) throws. Each such edge points to a catch handler block or a
   * finally block. Empty if the function has no try statements.
   */
  private final Map<Integer, List<Integer>> exceptionSuccessors;

  private final int entryId;

  public TacCfg(
      String functionSig,
      List<TacBasicBlock> blocks,
      Map<Integer, List<Integer>> successors,
      int entryId) {
    this(functionSig, blocks, successors, entryId, Map.of());
  }

  /**
   * Full constructor. The {@code exceptionSuccessors} map holds the exception-flow edges described
   * in the class javadoc. An empty map means the function has no try / catch / finally structure.
   */
  public TacCfg(
      String functionSig,
      List<TacBasicBlock> blocks,
      Map<Integer, List<Integer>> successors,
      int entryId,
      Map<Integer, List<Integer>> exceptionSuccessors) {
    this.functionSig = functionSig;
    this.blocks = List.copyOf(blocks);
    this.successors = Collections.unmodifiableMap(new LinkedHashMap<>(successors));
    this.exceptionSuccessors =
        Collections.unmodifiableMap(new LinkedHashMap<>(exceptionSuccessors));
    this.entryId = entryId;
  }

  public String functionSig() {
    return functionSig;
  }

  public List<TacBasicBlock> blocks() {
    return blocks;
  }

  public Map<Integer, List<Integer>> successors() {
    return successors;
  }

  /**
   * Exception-flow successor map. See class javadoc for semantics. Empty if the function has no try
   * regions.
   */
  public Map<Integer, List<Integer>> exceptionSuccessors() {
    return exceptionSuccessors;
  }

  public int entryId() {
    return entryId;
  }

  public TacBasicBlock block(int id) {
    for (TacBasicBlock b : blocks) if (b.id() == id) return b;
    return null;
  }

  public List<Integer> succs(int blockId) {
    return successors.getOrDefault(blockId, Collections.emptyList());
  }

  /**
   * Exception-flow successors of a block. Returns an empty list if the block has no exception
   * edges. Dataflow engines that care about exception paths (NullDerefChecker, TaintedCheckerBase,
   * path-sensitive walker) should consult this in addition to {@link #succs(int)}.
   */
  public List<Integer> exceptionSuccs(int blockId) {
    return exceptionSuccessors.getOrDefault(blockId, Collections.emptyList());
  }

  @Override
  public String toString() {
    return "Cfg(" + functionSig + ", " + blocks.size() + " blocks)";
  }
}
