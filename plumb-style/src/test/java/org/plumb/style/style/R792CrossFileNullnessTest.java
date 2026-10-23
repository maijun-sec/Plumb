/* R792 unit test for NullDerefFromReturnCheck cross-file static-helper nullness. */
package org.plumb.style.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.StyleContext;

/**
 * R792 (Oct 2026): {@link NullDerefFromReturnCheck} reads the runner-built cross-file static-method
 * cache ({@link StyleContext#lookupStaticMethod(String, String)}) to determine whether a {@code
 * data = Helper.foo()} call's RHS is definitely-non-null. These tests cover the {@code goodG2B}
 * Juliet pattern (which produced 68 R791 FPs): a same-module static helper returns a literal or a
 * freshly constructed object, the call's LHS is then dereferenced without an explicit null check,
 * and the rule must NOT fire because the LHS is non-null by construction.
 */
class R792CrossFileNullnessTest {

  private static StyleContext ctxForUser(String userSrc, String helperSrc) {
    // Two source files in the "same module": one is the
    // helper that the user calls, the other is the user
    // source that does the assignment. The cache is
    // keyed by `<FQCN>#<methodName>` and the user source
    // doesn't actually need to import the helper — we
    // wire both into the cache via the
    // pre-parsed MethodDeclaration.
    Map<String, MethodDeclaration> cache = new HashMap<>();
    if (helperSrc != null) {
      // Cache every method declared in the helper.
      for (Map.Entry<String, MethodDeclaration> e : parseAllMethods(helperSrc).entrySet()) {
        cache.put("Helper#" + e.getKey(), e.getValue());
      }
    }
    JavaParser parser = new JavaParser();
    ParseResult<CompilationUnit> result = parser.parse(userSrc);
    assertThat(result.isSuccessful()).as("user parse").isTrue();
    ParsedSource ps =
        new ParsedSource(
            java.nio.file.Paths.get("Test.java"),
            "Test.java",
            result.getResult().get(),
            new ArrayList<>());
    return new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true), Map.of(), cache);
  }

  /** Parse a single-file source and return every method by name. Used for the helper side. */
  private static Map<String, MethodDeclaration> parseAllMethods(String src) {
    JavaParser p = new JavaParser();
    ParseResult<CompilationUnit> r = p.parse(src);
    assertThat(r.isSuccessful()).as("parse helper").isTrue();
    Map<String, MethodDeclaration> out = new HashMap<>();
    r.getResult().get().walk(MethodDeclaration.class, md -> out.put(md.getNameAsString(), md));
    return out;
  }

  private static List<Finding> run(StyleContext ctx) {
    try {
      // R-816: use AstDispatcher so per-node visit overloads fire.
      new org.plumb.style.AstDispatcher()
          .register(NullDerefFromReturnCheck.class.getDeclaredConstructor().newInstance())
          .run(ctx);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return ctx.findings();
  }

  @Test
  void staticHelperReturnsLiteral_suppressesFire() {
    String helper = "class Helper { static String safeLiteral() { return \"ok\"; } }";
    String user =
        "class Test {\n"
            + "  void bad() {\n"
            + "    String data = Helper.safeLiteral();\n"
            + "    data.trim();\n"
            + "  }\n"
            + "}\n";
    StyleContext ctx = ctxForUser(user, helper);
    // Expectation: NOT fired (R790c baseline fires this;
    // R792 suppresses because Helper.safeLiteral() returns
    // a string literal).
    assertThat(run(ctx)).isEmpty();
  }

  @Test
  void staticHelperReturnsNew_suppressesFire() {
    String helper =
        "class Helper { static StringBuilder safeNew() { return new StringBuilder(\"ok\"); } }";
    String user =
        "class Test {\n"
            + "  void bad() {\n"
            + "    StringBuilder data = Helper.safeNew();\n"
            + "    data.append(\"x\");\n"
            + "  }\n"
            + "}\n";
    StyleContext ctx = ctxForUser(user, helper);
    assertThat(run(ctx)).isEmpty();
  }

  @Test
  void staticHelperReturnsNull_stillFires() {
    // The "bad" counterpart: the helper may return null,
    // so the dereference is still a real null-deref
    // risk. The rule must still fire.
    String helper = "class Helper { static String unsafeNull() { return null; } }";
    String user =
        "class Test {\n"
            + "  void bad() {\n"
            + "    String data = Helper.unsafeNull();\n"
            + "    data.trim();\n"
            + "  }\n"
            + "}\n";
    StyleContext ctx = ctxForUser(user, helper);
    assertThat(run(ctx)).hasSize(1);
  }

  @Test
  void staticHelperAmbiguousReturn_stillFires() {
    // The helper's return value depends on a parameter,
    // so we cannot prove non-null. The rule must fire
    // (conservative). This is the "mixed GoodG2B / B2G"
    // pattern that we explicitly choose not to suppress.
    String helper =
        "class Helper { static String ambiguous(boolean flag) { if (flag) return \"ok\"; return"
            + " null; } }";
    String user =
        "class Test {\n"
            + "  void bad() {\n"
            + "    String data = Helper.ambiguous(true);\n"
            + "    data.trim();\n"
            + "  }\n"
            + "}\n";
    StyleContext ctx = ctxForUser(user, helper);
    assertThat(run(ctx)).hasSize(1);
  }

  @Test
  void emptyStaticMethodCache_keepsR790cBaseline() {
    // No cross-file cache (R791 back-compat): the rule
    // fires on a plain `data = X(); data.use()` shape
    // where X is unknown. We pick a case where R790c's
    // notNull set is empty (init is a no-arg constructor
    // call returning a custom type that isn't recognised
    // by R790c's `new Xxx()` rule — but the test only
    // needs to prove that without the cross-file cache
    // the rule still fires). To make this clean, we
    // build a case with an unrecognised init that R790c
    // doesn't add to notNull.
    String user =
        "class Test {\n"
            + "  Object source() { return null; }\n"
            + "  void bad() {\n"
            + "    Object data = source();\n"
            + "    data.toString();\n"
            + "  }\n"
            + "}\n";
    StyleContext ctx = ctxForUser(user, /* helper */ null);
    assertThat(run(ctx)).hasSize(1);
  }
}
