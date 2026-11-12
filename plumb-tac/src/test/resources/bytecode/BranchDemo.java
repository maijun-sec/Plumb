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
 * A demo with one branch, used by the bytecode front end's control-flow test.
 *
 * <p>The two returns are the two paths of the branch: {@code 11} is the THEN path (the source's
 * {@code if (x > 0)}) and {@code 22} is the ELSE path. The test tells them apart by the constant
 * each one returns.
 */
public class BranchDemo {

  public static int pick(int x) {
    if (x > 0) {
      return 11;
    }
    return 22;
  }

  /**
   * A shape where the compiler keeps the source's comparison instead of inverting it: for {@code x
   * > 0 || y > 0} the first branch jumps to the THEN path on {@code x > 0}.
   */
  public static int pickOr(int x, int y) {
    if (x > 0 || y > 0) {
      return 11;
    }
    return 22;
  }
}
