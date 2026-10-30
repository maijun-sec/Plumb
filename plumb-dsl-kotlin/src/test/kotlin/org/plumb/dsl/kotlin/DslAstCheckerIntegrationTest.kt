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

import com.github.javaparser.JavaParser
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.plumb.parser.ParsedSource
import org.plumb.rules.RuleRegistry
import org.plumb.style.AstDispatcher
import org.plumb.style.StyleContext

/**
 * Integration test for the checker as the runner uses it.
 *
 * <p>The point of these tests is the SEAM between the DSL engine and
 * Plumb's own machinery, not the matcher language (that is covered by
 * [UpstreamParityTest] and [RuleMatcherTest]). Two seams are easy to get
 * wrong and neither shows up in a matcher-level test:
 *
 * <ol>
 *   <li><b>How the checker gets invoked.</b> [DslAstChecker] does its work
 *       in [org.plumb.style.AstChecker.finish], not in a {@code visit}
 *       method, because Plumb's [AstDispatcher] dispatches by exact node
 *       class and never offers a CompilationUnit to a checker. A test that
 *       called {@code checker.visit(unit, ctx)} directly would pass even if
 *       the checker were completely dead in production.
 *   <li><b>Whether the rule reaches the registry.</b> A rule that is not
 *       registered is dropped by [StyleContext]'s enabled gate before it is
 *       ever reported.
 * </ol>
 */
class DslAstCheckerIntegrationTest {

    private fun ruleFile(dir: Path, matcher: String): Path {
        val file = dir.resolve("rules.yaml")
        Files.writeString(
            file,
            """
            rules:
              - id: plumb.dsl.it-empty-catch
                name: it empty catch block
                description: A catch block with no statements
                severity: MEDIUM
                type: BAD_PRACTICE
                cwe: [CWE-390]
                nodeType: CatchClause
                matcher: |
                  $matcher
            """.trimIndent()
        )
        return file
    }

    private fun source(dir: Path): Pair<Path, ParsedSource> {
        val file = dir.resolve("Sample.java")
        Files.writeString(
            file,
            """
            public class Sample {
                public void run() {
                    try {
                        int x = 1 / 0;
                    } catch (Exception e) {
                    }
                }
            }
            """.trimIndent()
        )
        val unit = JavaParser().parse(file.toFile()).result.get()
        return file to ParsedSource(file, "", unit, emptyList(), null)
    }

    @Test
    fun `the dispatcher invokes the checker and the rule is reported`(@TempDir dir: Path) {
        val rules = ruleFile(dir, "CatchClause catchClause: catchClause match { empty }")
        val checker = DslAstChecker.from(listOf(rules), false)
        assertNotNull(checker, "the checker should have been built")

        val (_, parsed) = source(dir)

        // The rule must be in the registry, or StyleContext.report()
        // drops it.
        val registry = RuleRegistry()
        for (def in checker!!.ruleDefs()) {
            registry.register(def)
        }
        assertTrue(registry.byId("plumb.dsl.it-empty-catch").isPresent())

        val ctx = StyleContext(parsed, registry)
        val dispatcher = AstDispatcher()
        dispatcher.register(checker)
        dispatcher.run(ctx)

        assertEquals(1, ctx.findings().size, "expected exactly one DSL finding")
        val finding = ctx.findings()[0]
        assertEquals("plumb.dsl.it-empty-catch", finding.rule().ruleId().id())
        assertEquals(5, finding.line(), "the catch clause is on line 5")
        assertEquals(
            org.plumb.rules.RuleDef.Severity.MEDIUM,
            finding.rule().severity()
        )
    }

    @Test
    fun `a disabled rule in the registry does not report`(@TempDir dir: Path) {
        val rules = ruleFile(dir, "CatchClause catchClause: catchClause match { empty }")
        val checker = DslAstChecker.from(listOf(rules), false)
        assertNotNull(checker)

        val (_, parsed) = source(dir)

        // Register the rule, but disabled -- the same state a
        // `rules.disabled` entry in the user's YAML produces.
        val registry = RuleRegistry()
        for (def in checker!!.ruleDefs()) {
            registry.register(def.withEnabled(false))
        }

        val ctx = StyleContext(parsed, registry)
        val dispatcher = AstDispatcher()
        dispatcher.register(checker)
        dispatcher.run(ctx)

        assertEquals(0, ctx.findings().size, "a disabled rule must not report")
    }

    @Test
    fun `a rule that matches nothing reports nothing`(@TempDir dir: Path) {
        val rules = ruleFile(dir, "CatchClause catchClause: catchClause match { empty }")
        val checker = DslAstChecker.from(listOf(rules), false)
        assertNotNull(checker)

        // A file with no catch clause at all.
        val file = dir.resolve("NoCatch.java")
        Files.writeString(
            file,
            """
            public class NoCatch {
                public void run() {
                    int x = 1 + 1;
                }
            }
            """.trimIndent()
        )
        val unit = JavaParser().parse(file.toFile()).result.get()
        val parsed = ParsedSource(file, "", unit, emptyList(), null)

        val registry = RuleRegistry()
        for (def in checker!!.ruleDefs()) {
            registry.register(def)
        }

        val ctx = StyleContext(parsed, registry)
        val dispatcher = AstDispatcher()
        dispatcher.register(checker)
        dispatcher.run(ctx)

        assertEquals(0, ctx.findings().size)
    }
}
