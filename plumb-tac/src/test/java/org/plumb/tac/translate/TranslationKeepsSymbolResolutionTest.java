package org.plumb.tac.translate;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.types.ResolvedType;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;

/**
 * Translating to TAC must not break symbol resolution.
 *
 * <p>The scan pipeline TRANSLATES a file to TAC and only then runs the AST checkers on the same
 * tree. So a translation that damages the tree damages every AST checker that runs afterwards --
 * silently, because a checker that cannot resolve a type has no way to say "I do not know".
 *
 * <p>This used to happen. The translator built a synthetic {@code VariableDeclarationExpr} around a
 * field's original {@code VariableDeclarator} with {@code NodeList.nodeList(v)}, and that
 * RE-PARENTS the original: the declarator is detached from its {@code FieldDeclaration} and
 * attached to a node that hangs off no {@code CompilationUnit}. JavaParser's {@code
 * getSymbolResolver()} walks the parent chain to find the unit, so every {@code resolve()} on that
 * declarator and everything under it threw {@code IllegalStateException:
 * com.github.javaparser.ast.expr.VariableDeclarationExpr}.
 *
 * <p>The visible symptom was a checker that needed to know whether a method returns {@code void}
 * answering "I do not know" for every field initializer it had passed through -- and then reporting
 * a false positive, because "unknown" was being treated as "has a return value".
 */
class TranslationKeepsSymbolResolutionTest {

  @TempDir Path tmp;

  private static final String SOURCE =
      """
      package app;

      import java.io.File;
      import java.io.InputStream;
      import java.sql.Connection;

      public class Holder {
        private final File file = new File("x");
        private final InputStream stream = null;
        private final Connection connection = null;

        public void work() throws Exception {
          stream.close();
          connection.commit();
          file.delete();
        }
      }
      """;

  private ParsedSource parse() throws Exception {
    Path file = tmp.resolve("Holder.java");
    Files.writeString(file, SOURCE);
    return new JavaSourceParser().parse(file);
  }

  /** The return type of the named call, after the TAC translation. */
  private String returnTypeAfterTranslation(ParsedSource ps, String callText) {
    new TacTranslator(ps).translate();
    for (MethodCallExpr call : ps.unit().findAll(MethodCallExpr.class)) {
      if (call.toString().equals(callText)) {
        try {
          ResolvedType type = call.resolve().getReturnType();
          return type == null ? "null" : type.describe();
        } catch (RuntimeException ex) {
          return "THREW " + ex.getClass().getSimpleName();
        }
      }
    }
    return "not found";
  }

  /**
   * A call on a FIELD still resolves after the translation.
   *
   * <p>This is the case the bug broke: the field's declarator was the node that got re-parented,
   * and every call on that field lost its resolution.
   */
  @Test
  void aCallOnAFieldStillResolvesAfterTheTranslation() throws Exception {
    ParsedSource ps = parse();
    assertThat(returnTypeAfterTranslation(ps, "stream.close()"))
        .as("close() must still resolve to void after the translation")
        .isEqualTo("void");
  }

  /** The boolean / void distinction survives, so a checker can tell them apart. */
  @Test
  void theBooleanAndVoidDistinctionSurvivesTheTranslation() throws Exception {
    ParsedSource ps = parse();
    assertThat(returnTypeAfterTranslation(ps, "file.delete()"))
        .as("delete() must still resolve to boolean after the translation")
        .isEqualTo("boolean");
  }

  /** A call on a field of a THIRD-PARTY type survives too. */
  @Test
  void aCallOnAThirdPartyFieldStillResolvesAfterTheTranslation() throws Exception {
    ParsedSource ps = parse();
    assertThat(returnTypeAfterTranslation(ps, "connection.commit()"))
        .as("commit() must still resolve to void after the translation")
        .isEqualTo("void");
  }

  /**
   * The original tree is INTACT: the declarator still has its {@code FieldDeclaration} parent.
   *
   * <p>Cloning the declarator, rather than re-parenting it, is what keeps this true. Without this
   * assertion the fix could regress into "re-parent again", and the symptom would only reappear in
   * a checker far away from here.
   */
  @Test
  void theOriginalDeclaratorKeepsItsFieldParent() throws Exception {
    ParsedSource ps = parse();
    new TacTranslator(ps).translate();

    com.github.javaparser.ast.body.FieldDeclaration field =
        ps.unit().findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream()
            .filter(f -> f.getVariable(0).getNameAsString().equals("file"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the field must still be in the tree"));

    com.github.javaparser.ast.body.VariableDeclarator declarator = field.getVariable(0);
    assertThat(declarator.getParentNode().orElse(null))
        .as("the declarator must still be attached to its FieldDeclaration")
        .isSameAs(field);
  }

  /**
   * The INITIALIZER still resolves after the translation.
   *
   * <p>This is the deeper half of the fix. A shallow copy -- one that re-parents the initializer
   * expression instead of copying it -- would leave the declarator's parent chain intact while
   * detaching the expression under it, so the expression could no longer be resolved. A {@code new
   * File("x")} initializer needs its {@code java.io.File} type, so it discriminates.
   */
  @Test
  void theFieldInitializerStillResolvesAfterTheTranslation() throws Exception {
    ParsedSource ps = parse();
    new TacTranslator(ps).translate();

    com.github.javaparser.ast.body.VariableDeclarator declarator =
        ps.unit().findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
            .filter(v -> v.getNameAsString().equals("file"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the declarator must still be in the tree"));

    com.github.javaparser.ast.expr.Expression initializer =
        declarator
            .getInitializer()
            .orElseThrow(() -> new AssertionError("the initializer is gone"));

    assertThat(initializer.getParentNode().orElse(null))
        .as("the initializer must still be attached to its declarator")
        .isSameAs(declarator);

    try {
      com.github.javaparser.resolution.types.ResolvedType type =
          initializer.calculateResolvedType();
      assertThat(type.describe()).as("the initializer's type must still resolve").contains("File");
    } catch (RuntimeException ex) {
      throw new AssertionError("the initializer must still resolve, but it threw " + ex, ex);
    }
  }
}
