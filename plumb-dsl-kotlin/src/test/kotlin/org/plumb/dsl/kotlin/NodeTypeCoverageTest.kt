package org.plumb.dsl.kotlin

import com.github.javaparser.ast.Node
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarFile

/**
 * Every concrete node type the engine can target.
 *
 * <p><b>Why this test exists.</b> A rule names its node type the way JavaParser names it, and
 * the engine resolves the name to a class by trying JavaParser's packages. A package that is
 * missing from that list makes its node types <em>unnameable</em>: the rule is rejected as an
 * "unknown node type", and the author has no way to find out why.
 *
 * <p>The `ast.modules` package was missing -- it holds the nodes of a `module-info.java` -- so
 * every rule about a module was silently unusable. The list is now verified against the JAR: this
 * test asks the engine to resolve every concrete node class the JVM reports, and a package that
 * is not on the list fails the build.
 *
 * <p>The list is read out of the JAR at runtime, so it is the library's own truth rather than a
 * remembered one. A new JavaParser release that adds a node type is covered the moment the
 * library is upgraded.
 */
class NodeTypeCoverageTest {

    @Test
    fun `every concrete node type can be resolved`() {
        val names = allNodeTypes()
        assertThat(names).describedAs("the concrete node types").isNotEmpty

        val unresolved = names.filter { NodeTypes.resolve(it) == null }

        assertThat(unresolved)
            .describedAs(
                "the node types the engine cannot resolve. A node type lives in a JavaParser " +
                    "package, and a package missing from NodeTypes.PACKAGES makes its node " +
                    "types unnameable -- so a rule about one is rejected as an unknown node " +
                    "type with no explanation."
            )
            .isEmpty()
    }

    @Test
    fun `every concrete node type can be cast by a generated script`() {
        val failures = ArrayList<String>()
        for (name in allNodeTypes()) {
            val cls = NodeTypes.resolve(name) ?: continue
            val matcher = "$name n: n match { simpleName == \"$name\" }"
            val rewritten = RuleMatcher.rewrite(matcher, NodeTypes.qualifiedName(cls))
            try {
                ScriptEngineHolder.compile(rewritten.script, name)
            } catch (ex: Exception) {
                failures.add("$name: ${ex.message?.take(160)}")
            }
        }
        assertThat(failures)
            .describedAs("the node types whose matcher does not compile")
            .isEmpty()
    }

    @Test
    fun `the resolver finds a node type in each of JavaParser's packages`() {
        // One per package, so a package that is dropped from the list is
        // caught even when the package holds no concrete node type of its
        // own -- the module directives, for instance, are all concrete.
        val expected = listOf(
            "com.github.javaparser.ast" to "Modifier",
            "com.github.javaparser.ast.body" to "MethodDeclaration",
            "com.github.javaparser.ast.comments" to "JavadocComment",
            "com.github.javaparser.ast.expr" to "MethodCallExpr",
            "com.github.javaparser.ast.modules" to "ModuleDeclaration",
            "com.github.javaparser.ast.stmt" to "BlockStmt",
            "com.github.javaparser.ast.type" to "ClassOrInterfaceType"
        )
        for ((pkg, name) in expected) {
            val cls = NodeTypes.resolve(name)
            assertThat(cls)
                .describedAs("the node type %s in %s", name, pkg)
                .isNotNull
            assertThat(cls!!.name)
                .describedAs("the package of %s", name)
                .startsWith(pkg)
        }
    }

    companion object {

        /**
         * Every concrete [Node] subclass the library ships.
         *
         * <p>Read out of the JAR, so it is the library's own truth. A class that is abstract, an
         * interface, an enum or an inner class is not a node type a rule can name: a rule's node
         * type is cast in the generated script, and only a concrete class can be cast to.
         */
        fun allNodeTypes(): List<String> {
            val out = ArrayList<String>()
            val location = Node::class.java.protectionDomain.codeSource.location
            JarFile(File(location.toURI())).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (!name.startsWith("com/github/javaparser/ast/")) continue
                    if (!name.endsWith(".class")) continue
                    val simple = name.substringAfterLast('/').removeSuffix(".class")
                    if (simple.contains('$')) continue
                    val cls = try {
                        Class.forName(
                            name.removeSuffix(".class").replace('/', '.'),
                            false,
                            Node::class.java.classLoader
                        )
                    } catch (ex: Throwable) {
                        null
                    } ?: continue
                    if (cls.isInterface || cls.isEnum || cls.isAnnotation) continue
                    if (java.lang.reflect.Modifier.isAbstract(cls.modifiers)) continue
                    if (!Node::class.java.isAssignableFrom(cls)) continue
                    out.add(cls.simpleName)
                }
            }
            return out.sorted()
        }
    }
}
