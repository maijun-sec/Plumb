package org.plumb.dsl.kotlin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The boolean operators a rule may use.
 *
 * <p><b>Why this test exists.</b> The upstream DSL spells the boolean operators in words --
 * `and`, `or`, `not` -- because it defines them as Kotlin extension infix functions. Kotlin
 * itself has {@code Boolean.and} and {@code Boolean.or} as member functions, so the first two
 * happen to work unchanged. But Kotlin has no prefix {@code not} operator: {@code Boolean.not()}
 * is a function call, so {@code not x} is a syntax error and the rule fails to compile.
 *
 * <p>So the rewriter turns the prefix {@code not} into {@code !}.
 */
class BooleanOperatorTest {

    private val catchClause = "com.github.javaparser.ast.stmt.CatchClause"

    /** The predicate the rewriter produced, without the script's boilerplate. */
    private fun rewrite(predicate: String): String {
        val rewritten =
            RuleMatcher.rewrite(
                "CatchClause catchClause: catchClause match { $predicate }",
                catchClause
            )
        return rewritten.script.lines().filter { it.isNotBlank() }.last()
    }

    /** Compile the rewritten predicate; the point is that it compiles at all. */
    private fun compile(predicate: String) {
        val rewritten =
            RuleMatcher.rewrite(
                "CatchClause catchClause: catchClause match { $predicate }",
                catchClause
            )
        ScriptEngineHolder.compile(rewritten.script, "boolean-operator")
    }

    @Test
    fun `and is left alone because Kotlin has it`() {
        assertThat(rewrite("empty and empty")).contains("and")
        compile("empty and empty")
    }

    @Test
    fun `or is left alone because Kotlin has it`() {
        assertThat(rewrite("empty or empty")).contains("or")
        compile("empty or empty")
    }

    @Test
    fun `the symbol operators are left alone`() {
        compile("empty && empty")
        compile("empty || empty")
    }

    @Test
    fun `the prefix not becomes a bang`() {
        assertThat(rewrite("not empty")).contains("!").doesNotContain("not ")
        compile("not empty")
    }

    @Test
    fun `not in front of a chain becomes a bang`() {
        // The operand has to be a boolean for `!` to make sense -- a rule
        // never negates a list.
        val predicate = rewrite("not methodName == \"x\"")
        assertThat(predicate).contains("!")
        compile("not methodName == \"x\"")
    }

    @Test
    fun `not in front of a parenthesised expression becomes a bang`() {
        compile("not (empty and empty)")
    }

    /**
     * A `not` that is part of a longer name is a different word.
     *
     * <p>`nothing` is a name, not a negation, and rewriting it would break the rule.
     */
    @Test
    fun `a longer name that starts with not is left alone`() {
        val predicate = rewrite("methodName == \"nothing\"")
        assertThat(predicate).doesNotContain("! ")
    }
}
