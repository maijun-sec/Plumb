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
 * A covariant override, which makes javac emit a synthetic bridge method.
 *
 * <p>{@link Child} narrows {@code produce()} from {@code Object} to {@code String}. The compiler
 * then adds a second {@code produce()} to {@code Child} -- a bridge whose whole body is a call to
 * the method it forwards to -- so that a {@code Child} can still be used wherever a {@code
 * BridgeDemo} is expected.
 *
 * <p>The bridge and the method it forwards to share a name and an arity, and differ only in the
 * return type, which the TAC function key does not carry. That is what {@code
 * BytecodeBridgeMethodTest} pins down.
 *
 * <p>NB: this file is not compiled by the project -- it is shipped in {@code src/test/resources} so
 * {@code BytecodeBridgeMethodTest} can compile it to a .class on the fly and scan the result.
 */
public class BridgeDemo {

  /** The inherited shape: returns an object, so a subclass may narrow it to a string. */
  public Object produce() {
    return new Object();
  }

  /** The narrowing override; the compiler adds a bridge beside it. */
  public static class Child extends BridgeDemo {
    @Override
    public String produce() {
      return "child";
    }
  }
}
