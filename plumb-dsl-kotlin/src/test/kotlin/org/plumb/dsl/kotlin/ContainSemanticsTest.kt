package org.plumb.dsl.kotlin

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.comments.Comment
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The semantics of `contain`, pinned against the upstream's own definition.
 *
 * <p><b>Why this needs its own test.</b> The example corpus shows that `contain` finds the direct
 * children of a node. It does not show WHY that matters, and a future change that turns `contain`
 * back into a subtree search would pass the corpus -- because a subtree search finds everything a
 * direct-children search finds, and more.
 *
 * <p>So the cases that DISTINGUISH the two readings are pinned here:
 *
 * <ul>
 *   <li>a node does not contain ITSELF;
 *   <li>a node does not contain a GRANDCHILD;
 *   <li>a catch clause contains its own statements, which is what the upstream's rules ask about.
 * </ul>
 *
 * <p>The upstream's definition, from `extend_node.kt`:
 *
 * <pre>
 *   infix fun Node.contain(block: Node.() -&gt; Boolean): Boolean {
 *       if (this is NodeWithBlockStmt&lt;*&gt;) {
 *           this.body.statements.forEach {
 *               if (block(it)) { return true }
 *           }
 *       }
 *       return false
 *   }
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContainSemanticsTest {

    private val fixture: Path = Paths.get("src/test/resources/testcase/Fixture.java")

    private lateinit var unit: CompilationUnit

    @BeforeAll
    fun parse() {
        val config = ParserConfiguration()
            .setSymbolResolver(JavaSymbolSolver(ReflectionTypeSolver()))
            .setAttributeComments(true)
        unit = JavaParser(config).parse(fixture.toFile()).result.get()
    }

    /** The nodes of the given type the matcher matches. */
    private fun matches(matcher: String, nodeType: String): List<Node> {
        val target = NodeTypes.targetsFor(
            DslRule("x", "x", "x", nodeType, matcher)
        ).first()
        val rewritten = RuleMatcher.rewrite(matcher, NodeTypes.qualifiedName(target))
        val compiled = ScriptEngineHolder.compile(rewritten.script, "contain-test")
        val rule = CompiledRule(DslRule("x", "x", "x", nodeType, matcher), compiled)
        val nodes = unit.findAll(Node::class.java).filter { target.isInstance(it) }
        return nodes.filter { rule.matches(it) }
    }

    /**
     * A node does not contain ITSELF.
     *
     * <p>This is the case that a subtree reading of `contain` gets wrong, because JavaParser's
     * `findAll` includes the receiver.
     */
    @Test
    fun `a node does not contain itself`() {
        val hits = matches(
            "CatchClause catchClause: catchClause contain { simpleName == \"CatchClause\" }",
            "CatchClause"
        )
        assertThat(hits).isEmpty()
    }

    /**
     * A node does not contain a GRANDCHILD.
     *
     * <p>The catch's statement is a child; the literal inside that statement is a grandchild. A
     * direct-children search must not reach it.
     */
    @Test
    fun `a node does not contain a grandchild`() {
        val hits = matches(
            "CatchClause catchClause: catchClause contain { simpleName == \"StringLiteralExpr\" }",
            "CatchClause"
        )
        assertThat(hits).isEmpty()
    }

    /**
     * A catch clause contains its own statements.
     *
     * <p>This is the upstream's own use of `contain`, and the one the upstream's rule files rely
     * on: the "return in catch block" rule asks whether the catch contains a return.
     */
    @Test
    fun `a catch clause contains its own statements`() {
        val hits = matches(
            "CatchClause catchClause: catchClause contain { simpleName == \"ReturnStmt\" }",
            "CatchClause"
        )
        assertThat(hits).hasSize(1)
    }

    /**
     * A catch clause contains a throw, which is a sibling of the return.
     *
     * <p>The fixture has one catch with a return and one with a throw, so the two rules are
     * independent.
     */
    @Test
    fun `a catch clause contains a throw`() {
        val hits = matches(
            "CatchClause catchClause: catchClause contain { simpleName == \"ThrowStmt\" }",
            "CatchClause"
        )
        assertThat(hits).hasSize(1)
    }

    /**
     * A method contains its own statements.
     *
     * <p>The upstream's `contain` applies only to a `NodeWithBlockStmt`, which in JavaParser is a
     * catch clause and a constructor -- so upstream's `contain` finds nothing in a method body.
     * The Plumb engine keeps the direct-children rule and widens the set of nodes that HAVE a
     * body, because "does this method contain a call?" is a question the language should answer.
     */
    @Test
    fun `a method contains its own statements`() {
        val hits = matches(
            "MethodDeclaration md: md contain { simpleName == \"ExpressionStmt\" }",
            "MethodDeclaration"
        )
        assertThat(hits).isNotEmpty()
    }

    /**
     * A method does NOT contain the calls inside its statements.
     *
     * <p>This is the case that pins the semantics hardest: a call is a grandchild of the method,
     * so a direct-children search must not reach it. A subtree search would find it.
     */
    @Test
    fun `a method does not contain the calls inside its statements`() {
        val hits = matches(
            "MethodDeclaration md: md contain { simpleName == \"MethodCallExpr\" }",
            "MethodDeclaration"
        )
        assertThat(hits).isEmpty()
    }

    /**
     * The nesting depth is the depth of the question.
     *
     * <p>Two levels of `contain` reach a statement's child; three reach a call's child. The rule
     * that says "the depth of the nesting is the depth of the question" is only true if each level
     * is exactly one level down.
     */
    @Test
    fun `each level of contain is one level down`() {
        val twoLevels = matches(
            "MethodDeclaration md: md contain { contain { simpleName == \"MethodCallExpr\" } }",
            "MethodDeclaration"
        )
        val threeLevels = matches(
            "MethodDeclaration md: md contain { contain { contain { simpleName == \"StringLiteralExpr\" } } }",
            "MethodDeclaration"
        )
        assertThat(twoLevels).isNotEmpty()
        assertThat(threeLevels).isNotEmpty()
        // Three levels reach fewer nodes than two: the deeper question is
        // the more specific one.
        assertThat(threeLevels.size).isLessThan(twoLevels.size)
    }

    /**
     * A node with no body at all contains nothing.
     *
     * <p>A comment has no children, so the upstream's `contain` -- and this one -- finds nothing.
     */
    @Test
    fun `a node with no children contains nothing`() {
        val hits = matches(
            "Comment comment: comment contain { simpleName == \"StringLiteralExpr\" }",
            "Comment"
        )
        assertThat(hits).isEmpty()
    }
}

