package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.EmptyStatementUsageCheck;

/** S1116 EmptyStatementUsageCheck mirror. */
class R177SonarEmptyStatementUsageTest {

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new EmptyStatementUsageCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnStraySemicolon() {
    String src = "class N { void m() {; } }";
    assertThat(run(src)).as("R177: stray semicolon in method body fires").isNotEmpty();
  }

  @Test
  void firesOnEmptyIfStatement() {
    // `if (cond);` : the then-branch is the empty
    // statement, and the parent is IfStmt (not a loop).
    String src = "class N { void m(int x) { if (x > 0); } }";
    assertThat(run(src)).as("R177: if (x > 0); fires").isNotEmpty();
  }

  @Test
  void noFindingForEmptyWhileBody() {
    // The whole point of empty loops is the empty
    // statement as the body. must skip.
    String src = "class N { void m() { while (true); } }";
    assertThat(run(src)).as("R177: while(true); is intentional").isEmpty();
  }

  @Test
  void noFindingForEmptyForBody() {
    String src = "class N { void m() { for (int i = 0; i < 10; i++); } }";
    assertThat(run(src)).as("R177: for (...); is intentional").isEmpty();
  }
}
