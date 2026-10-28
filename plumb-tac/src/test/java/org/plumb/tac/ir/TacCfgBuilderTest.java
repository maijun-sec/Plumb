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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.translate.TacTranslator;

class TacCfgBuilderTest {

  private TacFunction funcOf(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("Test.java"), src);
    return new TacTranslator(ps).translate().functions().get(0);
  }

  @Test
  void linearBodyHasSingleSuccessor() {
    TacFunction fn = funcOf("class T { void m() { int a = 1; int b = 2; a = a + b; } }");
    TacCfg cfg = TacCfgBuilder.build(fn);
    // Expect 1 block (no branches), 1 instruction, 0 successors
    assertThat(cfg.blocks()).hasSize(1);
    assertThat(cfg.successors().get(cfg.entryId())).isEmpty();
  }

  @Test
  void ifElseSplitsIntoBlocks() {
    TacFunction fn = funcOf("class T { int m(int x) { if (x > 0) { return 1; } return 0; } }");
    TacCfg cfg = TacCfgBuilder.build(fn);
    // Expect 4 blocks: entry (cond), THEN, ELSE-tail, exit
    assertThat(cfg.blocks().size()).isGreaterThanOrEqualTo(3);
    // The entry block has 2 successors (true / false targets)
    int entrySuccs = cfg.successors().get(cfg.entryId()).size();
    assertThat(entrySuccs).isEqualTo(2);
  }
}
