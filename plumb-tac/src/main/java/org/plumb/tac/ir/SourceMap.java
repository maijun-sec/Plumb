package org.plumb.tac.ir;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

// IR-serializable: this class participates in the disk-cache contract.
// Instances are written via IrSerializer (ObjectOutputStream) and read
// back via IrSerializer.read(...). Field additions are backwards
// compatible; renaming or removing fields needs a serialVersionUID bump.
/**
 * Maps {@link TacInstruction} indices in a {@link TacFunction} back to their source location. The
 * dataflow engine reports findings by line number; reporters use this to convert a {@code
 * (function, insnIdx)} back to a usable position.
 *
 * <p>Kept as a simple {@code insnIdx -> (line, column)} map. The CFG builder re-uses it.
 */
public final class SourceMap implements java.io.Serializable {
  private static final long serialVersionUID = 1L;

  public record Loc(int line, int column) implements java.io.Serializable {
    public Loc {
      Objects.requireNonNull(this);
    }

    private static final long serialVersionUID = 1L;
  }

  private final Map<String, Map<Integer, Loc>> perFunction = new LinkedHashMap<>();

  public void record(String functionSig, int insnIdx, int line, int column) {
    perFunction
        .computeIfAbsent(functionSig, k -> new LinkedHashMap<>())
        .put(insnIdx, new Loc(line, column));
  }

  public Loc lookup(String functionSig, int insnIdx) {
    Map<Integer, Loc> m = perFunction.get(functionSig);
    return m == null ? null : m.get(insnIdx);
  }

  public Map<Integer, Loc> instructions(String functionSig) {
    Map<Integer, Loc> m = perFunction.get(functionSig);
    return m == null ? Map.of() : Map.copyOf(m);
  }
}
