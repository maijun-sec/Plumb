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
package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.Comment;
import java.util.List;
import java.util.Optional;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Default access modifier should be commented. */
public final class CommentDefaultAccessCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-comment-default-access", "COMMENT_DEFAULT_ACCESS"),
          "Default access modifier should be commented",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CommentDefaultAccessCheck",
          true,
          List.of("ast-style-comment-default-access", "comment-default-access"));

  @Override
  public String name() {
    return "comment-default-access";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    // Skip the class itself. // CommentDefaultAccessModifier applies to fields
    // and methods; the class default access is
    // usually explicit. We narrow to members only.
    for (MethodDeclaration m : n.getMethods()) {
      checkAndReport(m.getModifiers(), m.getNameAsString(), m.getComment(), ctx);
    }
    for (FieldDeclaration f : n.getFields()) {
      checkAndReport(
          f.getModifiers(), f.getVariables().get(0).getNameAsString(), f.getComment(), ctx);
    }
  }

  private static void checkAndReport(
      com.github.javaparser.ast.NodeList<Modifier> mods,
      String name,
      Optional<Comment> comment,
      StyleContext ctx) {
    // Fire only when there is NO access modifier
    // (default/package-private). If any modifier is
    // present, the access is already explicit.
    if (hasAccessModifier(mods)) return;
    // Check if any attached comment says "default".
    boolean hasDefaultComment = false;
    if (comment.isPresent()) {
      String text = comment.get().getContent().toLowerCase();
      if (text.contains("default")) hasDefaultComment = true;
    }
    if (!hasDefaultComment) {
      ctx.report(
          -1,
          -1,
          DEFAULT_RULE,
          "Default (package-private) access on '" + name + "' should have a /* default */ comment",
          RuleDef.Severity.LOW,
          name);
    }
  }

  private static boolean hasAccessModifier(com.github.javaparser.ast.NodeList<Modifier> mods) {
    for (Modifier m : mods) {
      Modifier.Keyword kw = m.getKeyword();
      if (kw == Modifier.Keyword.PUBLIC
          || kw == Modifier.Keyword.PROTECTED
          || kw == Modifier.Keyword.PRIVATE) return true;
    }
    return false;
  }
}
