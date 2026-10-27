package org.plumb.dsl.kotlin

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * The two parameter lists, and what may be read off an element of each.
 *
 * <p><b>Why they need pinning.</b> A method has two lists that look alike and are not:
 * `parameterTypes` holds the declared TYPES, and `parameters` holds the PARAMETERS themselves.
 * Indexing them produces different values, so a rule that reads `.name` off
 * `parameterTypes[0]` is asking for something that does not exist.
 *
 * <p>The rewriter therefore has to know which list it is indexing -- and it used to get it wrong
 * in a way that failed with a message about an unresolved reference rather than about the real
 * mistake.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParameterListTest {
    private lateinit var unit: CompilationUnit

    @BeforeAll
    fun parse() {
        val config = ParserConfiguration()
            .setSymbolResolver(JavaSymbolSolver(ReflectionTypeSolver()))
            .setAttributeComments(true)
        unit = JavaParser(config)
            .parse(Paths.get("src/test/resources/testcase/Fixture.java").toFile())
            .result.get()
    }

    private fun aMethod(name: String): MethodDeclaration =
        unit.findAll(MethodDeclaration::class.java).first { it.nameAsString == name }

    /**
     * The element of `parameterTypes` is a resolved type, so its type name is readable.
     */
    @Test
    fun `an element of parameterTypes is a resolved type`() {
        val method = aMethod("oneParameter")
        val types = NodeAccess.parameterTypes(method)
        assertThat(types).hasSize(1)
        assertThat(TypeAccess.resolveDeclaredTypeName(TypeAccess.typeAt(types, 0)))
            .isEqualTo("java.util.List<java.lang.String>")
    }

    /**
     * The element of `parameters` is a parameter, so its NAME is readable.
     */
    @Test
    fun `an element of parameters is a parameter`() {
        val method = aMethod("oneParameter")
        val parameters = NodeAccess.parameters(method)
        assertThat(parameters).hasSize(1)
        assertThat(NodeAccess.parameterName(NodeAccess.parameterAt(parameters, 0)))
            .isEqualTo("args")
    }

    /**
     * An index outside the list is null, not an exception.
     *
     * <p>A rule that indexes past the end of a list must not bring the scan down.
     */
    @Test
    fun `an index outside the list is null`() {
        val method = aMethod("noParameters")
        assertThat(NodeAccess.parameterAt(NodeAccess.parameters(method), 0)).isNull()
        assertThat(TypeAccess.typeAt(NodeAccess.parameterTypes(method), 0)).isNull()
    }

    /**
     * The two accessors produce different values, which is why the rewriter has to know which
     * list it is indexing.
     */
    @Test
    fun `the two accessors produce different values`() {
        val method = aMethod("oneParameter")
        val fromTypes = TypeAccess.typeNameOf(
            TypeAccess.typeAt(NodeAccess.parameterTypes(method), 0)
        )
        val fromParameters = NodeAccess.parameterType(
            NodeAccess.parameterAt(NodeAccess.parameters(method), 0)
        )
        assertThat(fromTypes).isEqualTo("java.util.List<java.lang.String>")
        assertThat(fromParameters?.typeName).isEqualTo("java.util.List<java.lang.String>")
    }
}

