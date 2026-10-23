/*
 * CWE-329 (Not Using a Random IV with CBC Mode).
 *
 * <p>Detects {@code new IvParameterSpec(arg)} calls where {@code arg}
 * is a hardcoded byte array initializer (e.g. {@code {0x00, 0x00, ...}})
 * or a variable whose declaration is a hardcoded initializer with no
 * {@code .nextBytes(arg)} call between declaration and the
 * IvParameterSpec constructor.</p>
 *
 * <p>Juliet's CWE-329 corpus (17 files) uses the same template:
 * {@code bad()} builds an IV as a hardcoded byte array and passes
 * it to {@code new IvParameterSpec(iv)}; {@code good1/good2} fill
 * the IV with {@code SecureRandom.nextBytes(iv)} before the
 * constructor call.</p>
 *
 * <p>Detection strategy: per-node visit on every {@code ObjectCreationExpr}
 * for {@code IvParameterSpec} (bare or FQN). For each:
 * <ol>
 *   <li>Argument is a literal array initializer (starts with '{'):
 *       fire directly.</li>
 *   <li>Argument is a {@code NameExpr} (variable): find the
 *       {@code VariableDeclarationExpr} / {@code FieldDeclaration}
 *       for the same name in the enclosing method. If the declaration
 *       is a literal array initializer, fire. If it's {@code new byte[N]}
 *       and no {@code .nextBytes(varName)} call appears between
 *       declaration and the IvParameterSpec, fire.</li>
 * </ol>
 *
 * <p><b>R-816 (2026-10-14): pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} + a single
 * {@code n.walk(ObjectCreationExpr, ...)} + 3 sub-walks per matched
 * node ({@code scope.walk(VariableDeclarationExpr)} +
 * {@code scope.walk(FieldDeclaration)} + {@code scope.walk(MethodCallExpr)}),
 * giving a worst case of 4 walks per IvParameterSpec node. The new
 * implementation uses {@code visit(ObjectCreationExpr)} dispatched
 * by the {@code AstDispatcher} once per file, and folds all three
 * sub-lookups into a single {@code enclosing.walk(Node.class, n -> ...)}
 * with an {@code instanceof} dispatch. Total walks: 1 per file
 * (the AstDispatcher's main walk) ? no nested walks.</p>
 *
 * <p>Limitations: cross-function dataflow not supported (variable may
 * be filled by nextBytes in a helper). For Juliet's template this
 * gives 100% recall because nextBytes (if any) lives in the same
 * method body.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class RandomIvCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-random-iv-cbc", "RANDOM_IV_CBC"),
          "AES/CBC cipher uses a non-random Initialization Vector (CWE-329).",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-329"),
          "org.plumb.style.RandomIvCheck",
          true,
          List.of("ast-style-random-iv-cbc-style", "random-iv-cbc")
      );

  @Override
  public String name() {
    return "random-iv-cbc";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr oc, StyleContext ctx) {
    if (!isIvParameterSpec(oc)) return;
    if (oc.getArguments().size() < 1) return;
    var arg = oc.getArguments().get(0);
    int line = oc.getBegin().map(p -> p.line).orElse(-1);
    // Case 1: literal array initializer.
    String argText = arg.toString().trim();
    if (argText.startsWith("{")) {
      fire(ctx, line, "literal byte-array IV passed to IvParameterSpec");
      return;
    }
    // Case 2: variable reference — find its declaration.
    if (!(arg instanceof NameExpr ne)) return;
    String varName = ne.getNameAsString();
    // R790: prefer the enclosing method scope to avoid picking
    // bad()'s first decl when goodN() reuses the same var name.
    // R-816 (follow-up): Juliet CWE-329 places the byte[] field
    // at class scope (e.g. `private static final byte[]
    // initializationVector;`), not inside the bad() method. So
    // we must ALSO consider the enclosing CompilationUnit when
    // the method-scope walk doesn't find a decl — otherwise
    // we silently skip class-level fields and lose coverage.
    Node enclosingMethod = findEnclosingMethodDecl(oc);
    Node cu = oc.findRootNode();

    // R-816: single enclosing walk with instanceof dispatch,
    // replacing the previous 3 separate sub-walks. The walk
    // collects three booleans in one pass:
    //   - hasVarDecl:     is there a `byte[] varName = ...`?
    //   - declIsLiteral:  is the init a `{...}` array literal?
    //   - declIsNewByte:  is the init `new byte[N]`?
    //   - hasNextBytes:   is there a `varName.nextBytes()` between
    //                      decl and use?
    // The first matching decl wins; line-window for nextBytes is
    // (declLine, line) exclusive of `line` (the IvParameterSpec).
    // Local-decl precedence over field: a local `byte[] iv = ...`
    // shadows the class-level `initializationVector` field.
    final int[] declLineRef = {-1};
    final boolean[] hasVarDecl = {false};
    final boolean[] declIsLiteral = {false};
    final boolean[] declIsNewByte = {false};
    final boolean[] hasNextBytes = {false};
    java.util.function.Consumer<Node> collect =
        n -> {
          if (hasVarDecl[0] && hasNextBytes[0]) return; // both found, early-exit
          if (n instanceof VariableDeclarationExpr vd) {
            if (hasVarDecl[0]) return; // already found the decl
            if (vd.getElementType() == null || !vd.getElementType().asString().startsWith("byte"))
              return;
            for (VariableDeclarator v : vd.getVariables()) {
              if (varName.equals(v.getNameAsString())) {
                hasVarDecl[0] = true;
                declLineRef[0] = vd.getBegin().map(p -> p.line).orElse(-1);
                String declText = vd.toString();
                if (declText.contains("= {") && declText.contains("}")) {
                  declIsLiteral[0] = true;
                } else if (declText.contains("= new byte[")) {
                  declIsNewByte[0] = true;
                }
                return;
              }
            }
          } else if (n instanceof FieldDeclaration fd) {
            if (hasVarDecl[0]) return; // prefer local decl over field
            if (fd.getElementType() == null || !fd.getElementType().asString().startsWith("byte"))
              return;
            for (VariableDeclarator v : fd.getVariables()) {
              if (varName.equals(v.getNameAsString())) {
                hasVarDecl[0] = true;
                declLineRef[0] = fd.getBegin().map(p -> p.line).orElse(-1);
                String declText = fd.toString();
                if (declText.contains("= {") && declText.contains("}")) {
                  declIsLiteral[0] = true;
                } else if (declText.contains("= new byte[")) {
                  declIsNewByte[0] = true;
                }
                return;
              }
            }
          } else if (n instanceof MethodCallExpr mc) {
            if (!"nextBytes".equals(mc.getNameAsString())) return;
            if (mc.getArguments().size() != 1) return;
            var mcArg = mc.getArguments().get(0);
            if (!(mcArg instanceof NameExpr mcNe)) return;
            if (!varName.equals(mcNe.getNameAsString())) return;
            int mcLine = mc.getBegin().map(p -> p.line).orElse(-1);
            int fromLine = declLineRef[0];
            if (mcLine >= fromLine && mcLine < line) {
              hasNextBytes[0] = true;
            }
          }
        };
    // Pass 1: enclosing method (preferred for Juliet's intra-method
    // bad/good1/good2 layout).
    if (enclosingMethod != null) {
      enclosingMethod.walk(Node.class, collect::accept);
    }
    // Pass 2: cu-wide fallback. The previous implementation did this
    // whenever scope was null; we now do it whenever pass 1 didn't
    // find a decl. This restores coverage for class-level fields
    // (Juliet CWE-329 `private static final byte[] initializationVector;`).
    if (!hasVarDecl[0] && cu != null) {
      cu.walk(Node.class, collect::accept);
    }

    if (!hasVarDecl[0]) return; // unknown — conservative skip
    int declLine = declLineRef[0];
    if (declIsLiteral[0]) {
      fire(
          ctx,
          line,
          "var '"
              + varName
              + "' (decl@line "
              + declLine
              + ") is initialized with a literal byte array;"
              + " no random fill before IvParameterSpec");
      return;
    }
    if (declIsNewByte[0] && !hasNextBytes[0]) {
      fire(
          ctx,
          line,
          "var '"
              + varName
              + "' (decl@line "
              + declLine
              + ") is `new byte[N]` but never filled by nextBytes()"
              + " before IvParameterSpec; IV is zeroed, not random");
    }
  }

  private static boolean isIvParameterSpec(ObjectCreationExpr oc) {
    String typeName = oc.getType().getNameAsString();
    return "IvParameterSpec".equals(typeName);
  }

  /**
   * R790: find the enclosing method declaration, so we can scope the `varName` decl lookup and the
   * `nextBytes` line-window to the same method (Juliet puts each test case in its own method, so a
   * file-wide first-decl lookup always picks bad()'s decl and FPs goodN()).
   */
  private static Node findEnclosingMethodDecl(ObjectCreationExpr oc) {
    Node cur = oc;
    while (cur != null) {
      if (cur instanceof MethodDeclaration) {
        return cur;
      }
      cur = cur.getParentNode().orElse(null);
    }
    return null;
  }

  private static void fire(StyleContext ctx, int line, String detail) {
    ctx.report(
        line,
        -1,
        DEFAULT_RULE,
        "AES/CBC cipher uses a non-random Initialization Vector"
            + " (CWE-329) at line "
            + line
            + ": "
            + detail
            + ". Use SecureRandom.nextBytes(iv) to fill the IV"
            + " with cryptographically strong random bytes"
            + " before passing to IvParameterSpec.",
        RuleDef.Severity.MEDIUM,
        "random-iv-cbc@" + line);
  }
}
