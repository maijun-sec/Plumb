package org.plumb.dsl.kotlin

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.MethodDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * `qualifiedName` and `ownQualifiedName`, pinned against the upstream.
 *
 * <p><b>Why the two are separate.</b> The upstream defines:
 *
 * <pre>
 *   val Node.qualifiedName: String
 *       get() = this.javaClass.name
 * </pre>
 *
 * so `qualifiedName` is the node's CLASS name -- the fully qualified name of the JavaParser class.
 * It is the node's *shape*, and it is the same for every method in every file.
 *
 * <p>A rule author who wants the node's own name -- the declaring type plus the member -- needs a
 * different property, because the upstream has none. That is `ownQualifiedName`.
 *
 * <p>Conflating the two would break every rule written against the upstream, so the split is
 * pinned here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QualifiedNameTest {
    private lateinit var unit: CompilationUnit

    @BeforeAll
    fun parse() {
        unit = TestParsers.parse(Paths.get("src/test/resources/testcase/Fixture.java").toFile())
    }

    private fun aMethod(): MethodDeclaration =
        unit.findAll(MethodDeclaration::class.java)
            .first { it.nameAsString == "noParameters" }

    /**
     * `qualifiedName` is the node's class name -- the upstream's meaning.
     */
    @Test
    fun `qualifiedName is the node's class name`() {
        assertThat(NodeAccess.qualifiedName(aMethod()))
            .isEqualTo("com.github.javaparser.ast.body.MethodDeclaration")
    }

    /**
     * `ownQualifiedName` is the node's OWN name -- the declaring type plus the member.
     */
    @Test
    fun `ownQualifiedName is the declaring type plus the member`() {
        assertThat(NodeAccess.ownQualifiedName(aMethod()))
            .isEqualTo("com.example.dsl.Fixture.noParameters")
    }

    /**
     * The two are different, and that is the point.
     *
     * <p>Every method in the fixture has the SAME `qualifiedName` -- the class name -- and a
     * DIFFERENT `ownQualifiedName`. A rule that wants to identify one specific method has to use
     * the latter.
     */
    @Test
    fun `the class name is the same for every method and the own name is not`() {
        val methods = unit.findAll(MethodDeclaration::class.java)
        assertThat(methods.map { NodeAccess.qualifiedName(it) }.distinct()).hasSize(1)
        assertThat(methods.map { NodeAccess.ownQualifiedName(it) }.distinct())
            .hasSize(methods.size)
    }

    /**
     * A node that is not a member of a named type falls back to the class name.
     */
    @Test
    fun `a node outside a class falls back to the class name`() {
        val orphan = com.github.javaparser.ast.expr.NameExpr("x")
        assertThat(NodeAccess.ownQualifiedName(orphan))
            .isEqualTo("com.github.javaparser.ast.expr.NameExpr")
    }
}

