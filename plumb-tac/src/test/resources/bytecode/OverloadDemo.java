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
package bytecode;

/**
 * Two methods that share a name and an arity, and two that share neither.
 *
 * <p>The first pair is the case the TAC key used to lose: {@code pick(String)} and {@code
 * pick(Object)} have the same name and the same number of parameters, so a key built from those two
 * facts alone names them alike and only one of the two bodies survives. The second pair has
 * different arities, and is here to show that the arity a key reports is the one it should.
 */
public class OverloadDemo {

  /** A field, so that the compiled code has a receiver to type. */
  private int counter;

  /** The narrow overload. */
  public String pick(String value) {
    return value;
  }

  /** The wide overload: same name, same number of parameters. */
  public String pick(Object value) {
    return String.valueOf(value);
  }

  /** A method with two parameters. */
  public String join(String left, String right) {
    return left + right;
  }

  /** A method with no parameters. */
  public String nothing() {
    return "";
  }

  /** A method that reads a field, so its bytecode has a receiver. */
  public int bump() {
    return ++counter;
  }

  /**
   * Calls both overloads from one method.
   *
   * <p>Both calls name the same method with the same number of parameters, so the callee signature
   * cannot tell them apart -- only the parameter types of the callee can.
   */
  public String pickBoth(String value) {
    return pick(value) + pick((Object) value);
  }

  /** Calls the overload it is, twice. */
  public String pickTwice(String value) {
    return pick(value) + pick(value);
  }
}
