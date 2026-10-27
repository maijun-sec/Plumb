package org.plumb.dsl.kotlin

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The DSL example corpus, executed.
 *
 * <p><b>Why the examples are a corpus and not a list.</b> A file of example matchers is
 * documentation, and documentation rots: nothing notices when the engine stops supporting a
 * construct the examples use. So the examples live in a data file, and this test runs every one
 * of them against a fixture and checks how many nodes it matched.
 *
 * <p>That makes the corpus <em>self-verifying</em> in both directions:
 *
 * <ul>
 *   <li>an example that stops compiling fails the build, so the corpus cannot claim a
 *       construct the engine does not have;
 *   <li>an example whose count drifts fails the build, so the corpus cannot quietly disagree
 *       with the fixture.
 * </ul>
 *
 * <p>A count of zero is a legitimate expectation: a rule that must NOT fire is as much a part of
 * the language's contract as one that must.
 */
class DslExampleCorpusTest {

    private data class Example(
        val name: String,
        val nodeType: String,
        val matcher: String,
        val expect: Int,
        val shows: String
    )

    companion object {
        private val CORPUS: Path = Paths.get("src/test/resources/testcase/examples.yaml")

        /**
         * The fixtures the corpus runs against.
         *
         * <p>They are three files, because some node types cannot share a file with a class:
         * a `module-info.java` is a compilation unit of its own, and the unparsable statement
         * needs a file that does not parse. A rule that targets a module directive has to be
         * demonstrated against the module file, or the corpus would claim a vocabulary it does
         * not have.
         */
        private val FIXTURES: List<String> =
            listOf("Fixture.java", "module-info.java", "Unparsable.java")

        private var examples: List<Example> = emptyList()
        private var units: List<CompilationUnit> = emptyList()

        @JvmStatic
        @BeforeAll
        fun load() {
            examples = readExamples()
            units = parseFixtures()
        }

        /** The examples, read from the corpus file. */
        private fun readExamples(): List<Example> {
            val text = Files.readString(CORPUS)
            @Suppress("UNCHECKED_CAST")
            val parsed = Yaml().load<Any?>(text) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val entries = parsed["examples"] as List<Map<String, Any?>>
            return entries.map { entry ->
                Example(
                    name = entry["name"] as String,
                    nodeType = entry["nodeType"] as String,
                    matcher = (entry["matcher"] as String).trim(),
                    expect = (entry["expect"] as Number).toInt(),
                    shows = (entry["shows"] as String).trim()
                )
            }
        }

        /**
         * The fixtures, parsed.
         *
         * <p>The unparsable fixture is parsed too -- it is the one that produces
         * `UnparsableStmt`, so it has to be in the corpus's world for a rule about unparsable
         * code to have something to match.
         */
        private fun parseFixtures(): List<CompilationUnit> =
            FIXTURES.map { name ->
                val path = Paths.get("src/test/resources/testcase/$name")
                val result = com.github.javaparser.JavaParser(TestParsers.config()).parse(path.toFile())
                result.result.get()
            }
    }

    /**
     * Every example in the corpus matches the number of nodes it says it does.
     *
     * <p>This is the whole corpus in one test on purpose: an example that fails should be
     * reported by name, and the failure message says what the example was demonstrating.
     */
    @Test
    fun `every example in the corpus behaves as documented`() {
        val failures = ArrayList<String>()

        for (example in examples) {
            val rewritten = RuleMatcher.rewrite(example.matcher, qualifiedNameOf(example.nodeType))
            if (rewritten.script.isEmpty()) {
                failures.add("${example.name}: the matcher did not rewrite: ${rewritten.warnings}")
                continue
            }
            val compiled = try {
                ScriptEngineHolder.compile(rewritten.script, example.name)
            } catch (ex: Exception) {
                failures.add("${example.name}: the script did not compile: ${ex.message}")
                continue
            }
            val actual = countMatches(compiled, example)
            if (actual != example.expect) {
                failures.add(
                    "${example.name}: expected ${example.expect} match(es) but got $actual " +
                        "-- ${example.shows}"
                )
            }
        }

        assertThat(failures).describedAs("the DSL example corpus").isEmpty()
    }

    /**
     * How many nodes of the example's node type the compiled rule matches.
     *
     * <p>The rule is evaluated against EVERY fixture, because the node types live in different
     * files: a module directive is in the module file, and the unparsable statement is in the
     * file that does not parse.
     *
     * <p><b>Why the comments are collected separately.</b> JavaParser keeps most comments OUT of
     * the tree -- a node's comment is an attribute, not a child -- so on the corpus fixture the
     * tree walk visits 17 of the 59 comments. A rule that targets a comment has to be given the
     * comments explicitly, and the ones the tree walk found have to be removed, or the rule
     * would count them twice.
     *
     * <p><b>Why the check reads the other way round.</b> "Does this rule want comments?" is
     * asked as {@code Comment::class.java.isAssignableFrom(target)} -- is the target a comment
     * type, or a supertype of one. The other way round is true only for {@code Comment} itself,
     * so a rule that named {@code LineComment} would silently lose the comments that are not in
     * the tree.
     */
    private fun countMatches(compiled: javax.script.CompiledScript, example: Example): Int {
        val target = targetClassOf(example.nodeType)
        val wantsComments =
            com.github.javaparser.ast.comments.Comment::class.java.isAssignableFrom(target)
        val nodes = ArrayList<Node>()
        for (unit in units) {
            val inTree = unit.findAll(Node::class.java).filter { target.isInstance(it) }
            if (wantsComments) {
                // The comments the tree walk does not reach.
                val reached = HashSet<Node>(inTree)
                nodes.addAll(
                    unit.getAllContainedComments().filter { target.isInstance(it) && !reached.contains(it) }
                )
            }
            nodes.addAll(inTree)
        }
        val rule =
            DslRule(
                id = example.name,
                name = example.name,
                description = example.shows,
                nodeType = example.nodeType,
                matcher = example.matcher
            )
        val compiledRule = CompiledRule(rule, compiled)
        return nodes.count { compiledRule.matches(it) }
    }

    /** The JavaParser class the node type names. */
    private fun targetClassOf(nodeType: String): Class<out Node> {
        val rule =
            DslRule(
                id = "x",
                name = "x",
                description = "x",
                nodeType = nodeType,
                matcher = ""
            )
        val targets = NodeTypes.targetsFor(rule)
        assertThat(targets).describedAs("the node type %s", nodeType).isNotEmpty
        return targets[0]
    }

    /** The fully qualified name of the node type's class. */
    private fun qualifiedNameOf(nodeType: String): String =
        NodeTypes.qualifiedName(targetClassOf(nodeType))

    /**
     * The corpus is not empty, and every example says what it demonstrates.
     *
     * <p>An example without prose is a test case with no explanation, and the corpus exists to
     * be read as much as to be run.
     */
    @Test
    fun `the corpus is populated and documented`() {
        assertThat(examples).describedAs("the example corpus").isNotEmpty
        assertThat(examples.map { it.name }).doesNotHaveDuplicates()
        for (example in examples) {
            assertThat(example.shows)
                .describedAs("the example %s", example.name)
                .isNotBlank
            assertThat(example.matcher)
                .describedAs("the example %s", example.name)
                .contains(":")
        }
    }

    /**
     * Every example's name is a usable rule id.
     *
     * <p>The name becomes the rule's id, so it has to survive the normalisation the registry
     * applies: lowercase, dashes instead of spaces, no punctuation that would need escaping.
     */
    @Test
    fun `every example name is a usable rule id`() {
        for (example in examples) {
            assertThat(example.name)
                .describedAs("the example name")
                .matches("[a-z0-9][a-z0-9-]*")
        }
    }
}
