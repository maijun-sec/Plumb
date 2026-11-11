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
 * A construction with an argument, so the bytecode front end has a constructor call to resolve.
 *
 * <p>Jimple splits {@code new Holder(value)} into an allocation and a separate special-invoke that
 * runs the constructor; the argument rides on the invoke. The TAC wants the argument on the
 * allocation, because that is where the dataflow engine reads a construction's arguments from.
 *
 * <p>NB: this file is not compiled by the project -- it is shipped in {@code src/test/resources} so
 * {@code BytecodeConstructorArgsTest} can compile it to a .class on the fly and scan the result.
 */
public class CtorArgDemo {

  /** The constructed class: its constructor takes the value the test is about. */
  public static class Holder {
    private final String value;

    public Holder(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  /** Builds a holder out of the argument it is given. */
  public Holder make(String value) {
    return new Holder(value);
  }
}
