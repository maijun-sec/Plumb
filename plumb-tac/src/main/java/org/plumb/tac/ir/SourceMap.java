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
