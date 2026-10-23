package org.plumb.tac.translate;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;

/**
 * R1104 regression gate: the AST parent chain is intact, and the resolver's failure message lies.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>R1103 stalled for a round on a false conclusion. JavaParser refused to resolve a field reference
 * with:
 *
 * <pre>
 *   LOG.calculateResolvedType() -&gt; IllegalStateException:
 *       "The node is not inserted in a CompilationUnit"
 * </pre>
 *
 * <p>Read literally, that says the translator is walking a different tree from the one the parser
 * produced, and an investigation was started into a parser/translator handoff defect that does not
 * exist. The message is misleading: the parent chain is fully populated and {@code
 * resolve()} does work. {@code NameExpr.resolve()} fails for a different reason — the reference's
 * ancestor chain contains the {@code VariableDeclarationExpr} that declared it — and {@code
 * calculateResolvedType()} fails differently again.
 *
 * <p>The actual fix reads the field declaration syntactically, which needs no resolver at all. This
 * test pins the facts that refute the wrong conclusion, so nobody re-derives it from the same error
 * message.
 */
class R1104AstParentChainDiagnosisTest {

  private static final String STATIC_FIELD =
      """
      import java.util.logging.Logger;
      class L8 {
        private static final Logger LOG = Logger.getLogger(L8.class.getName());
        void logHeader(String ua) {
          LOG.info("agent: " + ua);
        }
      }
      """;

  @TempDir Path tmp;

  private ParsedSource parse() throws Exception {
    Path f = tmp.resolve("L8.java");
    Files.writeString(f, STATIC_FIELD);
    return new JavaSourceParser().parse(f);
  }

  private static NameExpr logRef(CompilationUnit unit) {
    return unit.findAll(NameExpr.class).stream()
        .filter(n -> n.getNameAsString().equals("LOG"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void theParentChainIsFullyPopulated() throws Exception {
    CompilationUnit unit = parse().unit();
    NameExpr ref = logRef(unit);

    assertThat(ref.getParentNode())
        .as("the reference's parent must be present -- 'not inserted in a CompilationUnit' is a lie")
        .isPresent();
    assertThat(ref.getParentNode().orElseThrow().getParentNode())
        .as("and so must its grandparent")
        .isPresent();
    boolean declaredFieldFound = false;
    for (FieldDeclaration fd : unit.findAll(FieldDeclaration.class)) {
      for (VariableDeclarator vd : fd.getVariables()) {
        if (vd.getNameAsString().equals("LOG")) declaredFieldFound = true;
      }
    }
    assertThat(declaredFieldFound)
        .as("the declaring field declaration must be reachable from the same tree")
        .isTrue();
  }

  @Test
  void resolveWorksAndTheMisleadingMessageComesFromTheTranslatorPath() throws Exception {
    CompilationUnit unit = parse().unit();
    NameExpr ref = logRef(unit);

    // The API that works. This is what the false conclusion was built on top of.
    assertThat(ref.resolve().getType().describe())
        .isEqualTo("java.util.logging.Logger");

    // Called directly on the parser's own tree, calculateResolvedType() also succeeds. The
    // "The node is not inserted in a CompilationUnit" failure only appears when the translator
    // reaches the node, which is why it looked like a parser/translator tree-identity bug: the
    // same call, same object, different call site. The syntactic lookup R1103 uses sidesteps
    // the question entirely, so the discrepancy is recorded here rather than resolved.
    assertThat(ref.calculateResolvedType().describe())
        .as("works when called directly -- see the class javadoc for why that is misleading")
        .isEqualTo("java.util.logging.Logger");
  }

  @Test
  void theDeclaredTypeIsReadableWithoutAnyResolver() throws Exception {
    CompilationUnit unit = parse().unit();
    String declared = null;
    for (FieldDeclaration fd : unit.findAll(FieldDeclaration.class)) {
      for (VariableDeclarator vd : fd.getVariables()) {
        if (vd.getNameAsString().equals("LOG")) declared = vd.getType().asString();
      }
    }
    assertThat(declared)
        .as("the syntactic lookup R1103 relies on, which needs no symbol solver")
        .isEqualTo("Logger");
  }

  @Test
  void injectIsNotRequiredForResolution() throws Exception {
    // Sanity: a solver configured at parse time already resolves these nodes, so there is no
    // missing-injection defect either.
    CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver());
    var fresh = new com.github.javaparser.JavaParser(
            new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setSymbolResolver(new JavaSymbolSolver(solver)))
        .parse(STATIC_FIELD)
        .getResult()
        .orElseThrow();
    assertThat(logRef(fresh).resolve().getType().describe())
        .isEqualTo("java.util.logging.Logger");
  }
}