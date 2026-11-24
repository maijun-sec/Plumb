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

/**
 * Decides whether an exception of one type can be caught by a handler of another type.
 *
 * <p><b>Why this is a contract and not a table inside the IR.</b> The exception hierarchy is
 * <i>world knowledge</i>, not a fact about the analysed program: "is {@code IOException} a subclass
 * of {@code Exception}" is a fact about the Java platform, and the same program analysed by a tool
 * that knows a different platform gets a different answer. So the TAC carries the two type NAMES
 * ({@link TacInstruction#ctorType} on the {@code THROW} and on the {@code CATCH_HANDLER}) and the
 * engine side supplies the relation. The TAC instruction stream itself gains no field for this --
 * that is the whole point of reusing {@code ctorType}.
 *
 * <p><b>The four answers, and why there are four.</b> A handler catches an exception when the
 * exception's RUNTIME type is an instance of the handler's declared type. The IR only knows the
 * STATIC type of the thrown value, so the answer is not always yes or no:
 *
 * <ul>
 *   <li>{@link Relation#CATCHES} -- the handler's type is a supertype of the thrown type, so the
 *       handler catches it whatever the runtime type is.
 *   <li>{@link Relation#MAY_CATCH} -- the handler's type is a SUBTYPE of the thrown type: the
 *       runtime value may be an instance of it (and then the handler catches it) or may not (and
 *       then it does not). The edge must be kept.
 *   <li>{@link Relation#NEVER_CATCHES} -- the two types are unrelated classes, so no runtime type
 *       can be an instance of both. The edge can be dropped.
 *   <li>{@link Relation#UNKNOWN} -- the relation could not be decided. The edge must be kept.
 * </ul>
 *
 * <p><b>The asymmetry is the point.</b> {@code CATCHES} and {@code MAY_CATCH} both keep an edge, so
 * a matcher that is wrong in the "too generous" direction costs precision and nothing else. {@code
 * NEVER_CATCHES} drops an edge, so a matcher that is wrong there loses a real flow -- a false
 * negative. An implementation must therefore only answer {@code NEVER_CATCHES} when it is certain,
 * and must answer {@code UNKNOWN} whenever it is not.
 *
 * <p><b>The single-inheritance caveat.</b> {@code NEVER_CATCHES} is only sound where a class has
 * exactly one superclass, because then two unrelated classes cannot have a common subclass. A
 * language with multiple inheritance of exception classes (Python) has no such guarantee, so a
 * matcher for it must never answer {@code NEVER_CATCHES}. The same holds for an INTERFACE type: a
 * class may implement an interface and extend another class at the same time, so an unrelated
 * interface and class may still have a common instance.
 */
public interface ExceptionTypeMatcher {

  /** What the matcher can say about one (thrown type, caught type) pair. */
  enum Relation {

    /**
     * The handler's type is a supertype of the thrown type, so the handler catches the exception
     * whatever the runtime type is.
     */
    CATCHES,

    /**
     * The handler's type is a subtype of the thrown type, so the handler catches the exception only
     * if the runtime value happens to be an instance of it.
     */
    MAY_CATCH,

    /**
     * The two types are unrelated classes: no runtime value can be an instance of both, so the
     * handler cannot catch this exception.
     */
    NEVER_CATCHES,

    /** The relation could not be decided. The handler might catch the exception. */
    UNKNOWN
  }

  /**
   * The relation between the thrown type and the caught type.
   *
   * @param language the language the function was written in -- the hierarchy is a property of the
   *     language's platform, not of the IR
   * @param thrownType the thrown type, as the TAC spells it; {@code null} when the front end did
   *     not know it
   * @param caughtType the handler's declared type, as the TAC spells it; {@code null} when the
   *     front end did not know it
   * @return what the matcher can say; never {@code null}
   */
  Relation relation(SourceLanguage language, String thrownType, String caughtType);

  /**
   * A matcher that knows nothing.
   *
   * <p>Every pair is {@link Relation#UNKNOWN}, so every catch handler stays reachable from every
   * try block -- the behaviour of a tool that has no hierarchy at all. It is the default for a
   * caller that has no platform knowledge to offer.
   */
  ExceptionTypeMatcher UNKNOWN_MATCHER = (language, thrownType, caughtType) -> Relation.UNKNOWN;
}
