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
package org.plumb.dsl.kotlin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The matcher rewriter: the upstream DSL text becomes a script the Kotlin
 * script engine can compile.
 */
class RuleMatcherTest {

    private fun rewrite(text: String): RuleMatcher.Rewritten =
        RuleMatcher.rewrite(text, "com.github.javaparser.ast.stmt.CatchClause")

    @Test
    fun `the header is split into the node type and the binding`() {
        val result = rewrite("CatchClause catchClause: catchClause match { empty }")
        assertThat(result.nodeType).isEqualTo("CatchClause")
        assertThat(result.bindingName).isEqualTo("catchClause")
    }

    @Test
    fun `a structural matcher becomes a facade call`() {
        val result = rewrite("CatchClause catchClause: catchClause match { empty }")
        assertThat(result.script).contains("Matchers.match(catchClause)")
        assertThat(result.script).contains("NodeAccess.isEmpty(catchClause)")
    }

    @Test
    fun `a contain matcher becomes a facade call`() {
        val result = rewrite("CatchClause catchClause: catchClause contain { simpleName == \"X\" }")
        assertThat(result.script).contains("Matchers.contain(catchClause)")
        assertThat(result.script).contains("NodeAccess.simpleName(it)")
    }

    @Test
    fun `a chained property becomes a nested facade call`() {
        val result = rewrite(
            "MethodCallExpr mce: mce match { method.name == \"printStackTrace\" }"
        )
        assertThat(result.script).contains("NodeAccess.resolvedMethodName(NodeAccess.method(mce)")
    }

    @Test
    fun `an infix string operator becomes a facade call`() {
        val result = rewrite(
            "Comment comment: comment match { content matches \"(?!).*password.*\" }"
        )
        assertThat(result.script).contains("StringOps.matches(NodeAccess.content(comment)")
    }

    @Test
    fun `a list method becomes a facade call`() {
        val result = rewrite(
            "MethodDeclaration md: md match { parameterTypes.size == 1 }"
        )
        assertThat(result.script).contains("Ops.size(NodeAccess.parameterTypes(md)")
    }

    @Test
    fun `an indexed element becomes a facade call`() {
        val result = rewrite(
            "MethodDeclaration md: md match { parameterTypes[0].resolveType matches \"x\" }"
        )
        assertThat(result.script).contains("TypeAccess.typeAt(NodeAccess.parameterTypes(md)")
        assertThat(result.script).contains("TypeAccess.resolveDeclaredTypeName")
    }

    @Test
    fun `the generated script binds the node through the holder`() {
        val result = rewrite("CatchClause catchClause: catchClause match { empty }")
        assertThat(result.script).contains("import org.plumb.dsl.kotlin.CurrentNodeHolder")
        assertThat(result.script)
            .contains("val catchClause = CurrentNodeHolder.get() as com.github.javaparser.ast.stmt.CatchClause")
    }

    @Test
    fun `an unknown property is reported as a warning`() {
        val result = rewrite("CatchClause catchClause: catchClause match { noSuchProperty }")
        assertThat(result.warnings).isNotEmpty
        assertThat(result.warnings[0]).contains("noSuchProperty")
    }

    @Test
    fun `a matcher without a header is rejected`() {
        val result = rewrite("catchClause match { empty }")
        assertThat(result.script).isEmpty()
        assertThat(result.warnings).isNotEmpty
    }

    @Test
    fun `an empty matcher is rejected`() {
        val result = rewrite("   ")
        assertThat(result.script).isEmpty()
        assertThat(result.warnings).isNotEmpty
    }

    @Test
    fun `a colon inside a string does not end the header`() {
        val result = rewrite(
            "Comment comment: comment match { content matches \"a:b\" }"
        )
        assertThat(result.nodeType).isEqualTo("Comment")
        assertThat(result.script).contains("StringOps.matches")
    }
}
