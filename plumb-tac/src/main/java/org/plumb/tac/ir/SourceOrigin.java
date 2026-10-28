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

// IR-serializable: this class participates in the disk-cache contract.
// Instances are written via IrSerializer (ObjectOutputStream) and read
// back via IrSerializer.read(...). Field additions are backwards
// compatible; renaming or removing fields needs a serialVersionUID bump.

/**
 * Where a {@link TacFunction}'s body came from.
 *
 * <p>Plumb can lower Java code from three sources:
 *
 * <ol>
 *   <li>{@link #SOURCE}: a {@code .java} file the user gave us. Body is fully lowered; every
 *       instruction carries a real {@link SourceMap} location.
 *   <li>{@link #LIBRARY}: a {@code .jar} the user passed via {@code --input}. The class is loaded
 *       via {@link org.plumb.tac.bytecode.BytecodeAnalyzer} (SootUp / Jimple → TAC). The body is
 *       complete but no source locations are available; findings report the input JAR as the file.
 *   <li>{@link #SYSTEM_LIBRARY}: code from the JDK / JRE runtime (everything under {@code java.*},
 *       {@code javax.*}, {@code jdk.*}, {@code sun.*}, {@code com.sun.*}). We never get bodies for
 *       these from the user; they are known to the engine through {@link
 *       org.plumb.dfa.cg.CallGraph} edges but {@link TacFunction} instances do not exist for them
 *       in normal operation. A body appears only if a JDK stub is explicitly loaded (e.g. via
 *       {@code -Xjavadoc-stub} or a future {@code --input jrt:/...}). The {@code origin} field
 *       tells the engine to skip these unless the user explicitly opted in.
 * </ol>
 *
 * <p><b>Default analysis policy.</b> The engine analyses {@code SOURCE} functions by default.
 * {@code LIBRARY} is opt-in ({@code TuningConfig.libraryAnalysisEnabled}). {@code SYSTEM_LIBRARY}
 * is opt-in AND requires an explicit flag ({@code TuningConfig.analyseSystemLibraries}); skipping
 * JDK code by default keeps the analysis focused and the per-program work bounded.
 *
 * <p>The {@code origin} field is on {@link TacFunction} rather than {@link TacProgram} so that a
 * single merged program (see {@link MergedProgram}) can mix sources: most TUs are {@code SOURCE}, a
 * few attached JARs are {@code LIBRARY}, and a hypothetical JDK stub is {@code SYSTEM_LIBRARY}.
 */
public enum SourceOrigin {

  /** User-provided {@code .java} source file. Default; always analysed. */
  SOURCE,

  /**
   * User-provided {@code .jar} / {@code .class} bytecode. Analysed only when {@code
   * TuningConfig.libraryAnalysisEnabled} is true.
   */
  LIBRARY,

  /**
   * JDK / JRE runtime code. Analysed only when both {@code TuningConfig.libraryAnalysisEnabled} AND
   * {@code TuningConfig.analyseSystemLibraries} are true. Default: skipped.
   */
  SYSTEM_LIBRARY;

  /**
   * Whether this origin is on by default. Only {@link #SOURCE} returns true; the other two require
   * explicit opt-in from the user via the engine config.
   */
  public boolean analysedByDefault() {
    return this == SOURCE;
  }
}
