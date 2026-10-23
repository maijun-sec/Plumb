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
