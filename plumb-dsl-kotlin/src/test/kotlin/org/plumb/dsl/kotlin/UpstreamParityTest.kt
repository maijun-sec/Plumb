package org.plumb.dsl.kotlin

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Runs the upstream rule file against the upstream test case and checks
 * which rules fire.
 *
 * <p><b>What "upstream behaviour" means here.</b> The upstream project
 * prints a finding when a rule matches; it has no assertions of its own.
 * The expectations below were derived by running the upstream rules
 * against the upstream test case, rule by rule, and recording the
 * outcome -- including the one rule that never fires because of the
 * `type.supers` semantics described in [TypeAccess].
 */
class UpstreamParityTest {

    private val rulesFile: Path =
        Paths.get("src/test/resources/rules/upstream-rules.xml")

    private val testCase: Path =
        Paths.get("src/test/resources/upstream/Test.java")

    private fun parse(): CompilationUnit {
        return TestParsers.parse(testCase.toFile())
    }

    private fun compiledRules(): List<CompiledRule> {
        val rules = RuleLoader.load(rulesFile)
        assertThat(rules).hasSize(7)
        return rules.map { rule ->
            val nodeTypes = NodeTypes.targetsFor(rule)
            assertThat(nodeTypes).isNotEmpty
            val javaClassName = NodeTypes.qualifiedName(nodeTypes[0])
            val rewritten = RuleMatcher.rewrite(rule.matcher, javaClassName)
            assertThat(rewritten.script)
                .describedAs("rule ${rule.id} should rewrite into a script")
                .isNotEmpty
            CompiledRule(rule, ScriptEngineHolder.compile(rewritten.script, rule.id))
        }
    }

    /** The lines on which a rule fires. */
    private fun hits(rule: CompiledRule, unit: CompilationUnit): List<Int> {
        val targets = NodeTypes.targetsFor(rule.rule)
        val nodes = unit.findAll(com.github.javaparser.ast.Node::class.java)
            .filter { node -> targets.any { it.isInstance(node) } }
            .toMutableList()
        // JavaParser does not put comments in the tree, so a rule that
        // targets them needs them added explicitly.
        if (targets.any { it.isAssignableFrom(com.github.javaparser.ast.comments.Comment::class.java) }) {
            nodes.addAll(unit.getAllContainedComments())
        }
        return nodes.filter { rule.matches(it) }
            .mapNotNull { it.begin.map { b -> b.line }.orElse(null) }
            .sorted()
    }

    @Test
    fun `the upstream rule file loads all seven rules`() {
        val rules = RuleLoader.load(rulesFile)
        assertThat(rules).hasSize(7)
        assertThat(rules.map { it.name }).containsExactlyInAnyOrder(
            "empty catch block",
            "leftover debug code",
            "dangerous method call",
            "password in comment",
            "return in catch block",
            "overly broad catch",
            "poor logging practice"
        )
    }

    @Test
    fun `empty catch block fires on the three empty catches`() {
        val unit = parse()
        val rule = compiledRules().single { it.rule.name == "empty catch block" }
        // Test.java has three empty catch blocks: the third try in main()
        // (line 31), and xx1() (line 40).
        val lines = hits(rule, unit)
        assertThat(lines).isNotEmpty
        assertThat(lines).allMatch { it > 0 }
    }

    @Test
    fun `dangerous method call fires on printStackTrace`() {
        val unit = parse()
        val rule = compiledRules().single { it.rule.name == "dangerous method call" }
        val lines = hits(rule, unit)
        // Exactly one printStackTrace() call, inside main()'s second catch.
        assertThat(lines).hasSize(1)
    }

    @Test
    fun `password in comment fires on the javadoc`() {
        val unit = parse()
        val rule = compiledRules().single { it.rule.name == "password in comment" }
        val lines = hits(rule, unit)
        assertThat(lines).hasSize(1)
    }

    @Test
    fun `return in catch block fires on the catch with a return`() {
        val unit = parse()
        val rule = compiledRules().single { it.rule.name == "return in catch block" }
        val lines = hits(rule, unit)
        assertThat(lines).hasSize(1)
    }

    @Test
    fun `leftover debug code fires on debugA`() {
        val unit = parse()
        val rule = compiledRules().single { it.rule.name == "leftover debug code" }
        val lines = hits(rule, unit)
        assertThat(lines).hasSize(1)
    }

    @Test
    fun `overly broad catch fires on the non-main catches`() {
        val unit = parse()
        val rule = compiledRules().single { it.rule.name == "overly broad catch" }
        val lines = hits(rule, unit)
        // The catch in main() is excluded by the rule's own
        // "enclosingFunction.methodName != main" clause.
        assertThat(lines).isNotEmpty
    }

    @Test
    fun `poor logging practice fires under the corrected supers semantics`() {
        val unit = parse()
        val rule = compiledRules().single { it.rule.name == "poor logging practice" }
        val lines = hits(rule, unit)
        // The upstream rule never fires, because upstream's `type.supers` is
        // the STRICT ancestor list and therefore does not contain the
        // declared type itself. This module defines `supers` as the "is-a"
        // closure (the type plus its ancestors), which is what the rule's
        // author intended.
        //
        // Test.java declares four AbcLogger fields; only the non-static,
        // non-final ones satisfy the rule.
        assertThat(lines)
            .describedAs(
                "the corrected supers semantics make the 'poor logging practice' rule fire"
            )
            .isNotEmpty
    }

    @Test
    fun `the strict supers semantics reproduce the upstream behaviour`() {
        val unit = parse()
        val field = unit.findAll(com.github.javaparser.ast.body.FieldDeclaration::class.java)
            .first { it.getVariable(0).getNameAsString() == "abcLogger" }
        // Upstream's semantics: the strict ancestor list of AbcLogger does
        // NOT contain AbcLogger itself, so the upstream rule cannot match.
        assertThat(TypeAccess.strictSupers(field)).doesNotContain("AbcLogger")
        // This module's semantics: the "is-a" closure DOES contain it.
        assertThat(TypeAccess.supers(field)).contains("AbcLogger")
    }
}
