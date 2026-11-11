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
 * <p><b>Default analysis policy.</b> The engine analyses a function body when its origin is in the
 * set of analysed origins ({@code TuningConfig.analysedOrigins()}), and that set holds {@code
 * SOURCE} and nothing else by default. {@code LIBRARY} is opt-in: the caller has to put it in that
 * set. {@code SYSTEM_LIBRARY} is opt-in as well AND requires an explicit flag ({@code
 * TuningConfig.analyseSystemLibraries}); skipping JDK code by default keeps the analysis focused
 * and the per-program work bounded.
 *
 * <p>The {@code origin} field is on {@link TacFunction} rather than {@link TacProgram} so that a
 * single merged program (see {@link MergedProgram}) can mix sources: most TUs are {@code SOURCE}, a
 * few attached JARs are {@code LIBRARY}, and a hypothetical JDK stub is {@code SYSTEM_LIBRARY}.
 */
public enum SourceOrigin {

  /** User-provided {@code .java} source file. Default; always analysed. */
  SOURCE,

  /**
   * User-provided {@code .jar} / {@code .class} bytecode.
   *
   * <p>Analysed only when the engine's set of analysed origins ({@code
   * TuningConfig.analysedOrigins()}) holds it. The default set does not, so a scan has to ask for
   * the JAR's bodies explicitly.
   */
  LIBRARY,

  /**
   * JDK / JRE runtime code. Analysed only when the engine's set of analysed origins holds it AND
   * {@code TuningConfig.analyseSystemLibraries} is true. Default: skipped.
   */
  SYSTEM_LIBRARY,

  /**
   * Code this project generated -- a Kotlin {@code data class}'s accessors, a Java {@code record}'s
   * accessors, a Lombok-generated method.
   *
   * <p><b>Why generated code needs its own origin.</b> Generated code is neither the user's
   * hand-written code nor a third-party library, and it deserves a different summary policy from
   * both:
   *
   * <ul>
   *   <li>A generated accessor that returns a field carries the field's taint, and the engine
   *       should know that without re-analysing it.
   *   <li>But the generated code is not in the source the user wrote, so a finding reported
   *       <em>inside</em> it points at a line the user cannot see.
   * </ul>
   *
   * <p>With this origin the engine can summarise a generated accessor once and apply it at every
   * call site, instead of analysing it as if it were ordinary source.
   *
   * <p><b>It is still analysed by default.</b> A generated accessor that carries taint is exactly
   * the kind of thing that must not be skipped -- skipping it would drop the taint at every
   * property access.
   */
  GENERATED,

  /**
   * Code generated <em>outside</em> this project -- a DTO from another team's build, a deserialised
   * class from a dependency.
   *
   * <p>Distinct from {@link #GENERATED} because it is less trustworthy: the engine did not see it
   * being generated, and its shape may not match what the generator produces. A summary derived
   * from it is a guess.
   */
  EXTERNAL_GENERATED;

  /**
   * Whether this origin is on by default. Only {@link #SOURCE} returns true; the other origins
   * require explicit opt-in from the user via the engine config.
   *
   * <p><b>Except the generated ones.</b> {@link #GENERATED} and {@link #EXTERNAL_GENERATED} are
   * analysed by default: a generated accessor that carries taint is precisely what must not be
   * skipped, because skipping it drops the taint at every property access.
   */
  public boolean analysedByDefault() {
    return this == SOURCE || this == GENERATED || this == EXTERNAL_GENERATED;
  }

  /** Whether this origin describes code that a generator produced. */
  public boolean isGenerated() {
    return this == GENERATED || this == EXTERNAL_GENERATED;
  }
}
