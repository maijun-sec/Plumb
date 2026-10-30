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

import com.github.javaparser.ast.Node
import com.github.javaparser.ast.comments.Comment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * The node types the corpus's fixtures actually produce.
 *
 * <p><b>Why this test exists.</b> The engine can TARGET every concrete node type -- that is
 * what [NodeTypeCoverageTest] proves. But targeting a node type is not the same as being able
 * to WRITE a rule about it: a rule is written against a node the parser actually produces, and
 * the parser only produces the node types the language level allows.
 *
 * <p>So this test asks the fixtures a different question: which node types do they produce? A
 * node type the fixtures never produce is a node type the corpus cannot demonstrate, and the
 * gap is invisible -- the corpus stays green, the engine keeps working, and nobody notices that
 * half the language's vocabulary has no example.
 *
 * <p><b>The one deliberate gap.</b> `RecordPatternExpr` needs a record deconstruction pattern,
 * which is Java 21. Plumb parses at [TestParsers.LEVEL] -- the same level the rest of the
 * engine uses -- so no fixture at that level produces the node. It is named here rather than
 * quietly left out, because a gap with a reason is a decision and a gap without one is a bug.
 */
class NodeTypeFixtureCoverageTest {

    @Test
    fun `the fixtures produce every node type they can`() {
        val produced = producedNodeTypes()
        val expected = expectedNodeTypes()

        val missing = expected - produced

        assertThat(missing)
            .describedAs(
                "the node types no fixture produces. A node type the fixtures never produce is " +
                    "a node type the corpus cannot demonstrate -- the corpus stays green, the " +
                    "engine keeps working, and nobody notices that part of the vocabulary has " +
                    "no example."
            )
            .isEmpty()
    }

    @Test
    fun `the one gap is the node type the language level does not reach`() {
        val produced = producedNodeTypes()
        val unreachable = allNodeTypes() - produced

        assertThat(unreachable)
            .describedAs(
                "the node types no fixture produces. The fixtures are read at " +
                    "${TestParsers.LEVEL}, so a node type from a newer language is expected to " +
                    "be missing -- but ONLY such a node type, and only the ones named here."
            )
            .containsExactlyInAnyOrderElementsOf(NODE_TYPES_ABOVE_THE_LANGUAGE_LEVEL)
    }

    @Test
    fun `every fixture parses without problems`() {
        // A fixture that does not parse is a silent hole: the parser drops what it could not
        // read, so the node types it was written to demonstrate are simply absent -- and no
        // example fails, because the rule that would have matched them never fires.
        for (name in FIXTURES) {
            val path = Paths.get("src/test/resources/testcase/$name")
            val result = com.github.javaparser.JavaParser(TestParsers.config()).parse(path.toFile())
            if (name == UNPARSABLE_FIXTURE) {
                // This one is SUPPOSED to fail to parse.  It is the fixture that produces
                // `UnparsableStmt`, which is JavaParser's node for code it could not read.
                assertThat(result.isSuccessful)
                    .describedAs("the unparsable fixture is expected to fail to parse")
                    .isFalse()
            } else {
                assertThat(result.isSuccessful)
                    .describedAs(
                        "the fixture %s does not parse: %s",
                        name,
                        result.problems.joinToString("; ") { it.verboseMessage }
                    )
                    .isTrue()
            }
        }
    }

    companion object {

        private const val UNPARSABLE_FIXTURE = "Unparsable.java"

        private val FIXTURES = listOf("Fixture.java", "module-info.java", UNPARSABLE_FIXTURE)

        /**
         * The node types a Java 21 construct produces.
         *
         * <p>These are the ones the language level does not reach. They are named rather than
         * left implicit, so a new one showing up is a change to this list -- which is a
         * deliberate act, not a silent gap.
         */
        private val NODE_TYPES_ABOVE_THE_LANGUAGE_LEVEL = listOf("RecordPatternExpr")

        /** Every concrete node type the library ships. */
        fun allNodeTypes(): List<String> = NodeTypeCoverageTest.allNodeTypes()

        /**
         * The node types the fixtures produce.
         *
         * <p>Every fixture in the directory is read, because the module directives and the
         * unparsable statement are in files of their own.
         */
        fun producedNodeTypes(): Set<String> {
            val produced = HashSet<String>()
            for (name in FIXTURES) {
                val path = Paths.get("src/test/resources/testcase/$name")
                val result = com.github.javaparser.JavaParser(TestParsers.config()).parse(path.toFile())
                val unit = result.result.get()
                for (node in unit.findAll(Node::class.java)) {
                    produced.add(node.javaClass.simpleName)
                }
                for (comment in unit.getAllComments()) {
                    produced.add(comment.javaClass.simpleName)
                }
            }
            return produced
        }

        /**
         * The node types the fixtures are expected to produce.
         *
         * <p>Derived from the library's own truth, so a new JavaParser release that adds a
         * node type is covered the moment the library is upgraded -- and the fixtures have to
         * catch up.
         */
        fun expectedNodeTypes(): Set<String> =
            allNodeTypes().toSet() - NODE_TYPES_ABOVE_THE_LANGUAGE_LEVEL.toSet()
    }
}
