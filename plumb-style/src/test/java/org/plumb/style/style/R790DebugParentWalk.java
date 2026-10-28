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
/* Debug: print AST parent chain for InetAddress.getByName. */
package org.plumb.style.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class R790DebugParentWalk {

  @Test
  void debugGetByName() {
    String src =
        "import java.net.InetAddress;\n"
            + "class T {\n"
            + "  void bad() throws Exception {\n"
            + "    InetAddress addr = InetAddress.getByName(\"localhost\");\n"
            + "  }\n"
            + "}\n";
    JavaParser parser = new JavaParser();
    ParseResult<CompilationUnit> result = parser.parse(src);
    assertThat(result.isSuccessful()).isTrue();
    CompilationUnit cu = result.getResult().get();
    cu.walk(
        MethodCallExpr.class,
        mc -> {
          if ("getByName".equals(mc.getNameAsString())) {
            System.out.println(
                "FOUND getByName at line " + mc.getBegin().map(p -> p.line).orElse(-1));
            com.github.javaparser.ast.Node cur = mc;
            for (int d = 0; d < 6; d++) {
              System.out.println("  d=" + d + " class=" + cur.getClass().getSimpleName());
              if (cur instanceof com.github.javaparser.ast.body.VariableDeclarator vd) {
                System.out.println(
                    "    typeAsString=" + vd.getTypeAsString() + " name=" + vd.getNameAsString());
              }
              if (cur instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr vde) {
                System.out.println("    elementType=" + vde.getElementType().asString());
              }
              Optional<com.github.javaparser.ast.Node> p = cur.getParentNode();
              if (p.isEmpty()) break;
              cur = p.get();
            }
          }
        });
  }
}
