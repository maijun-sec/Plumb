package org.plumb.dsl.kotlin

import com.github.javaparser.ast.Node
import org.slf4j.LoggerFactory

/**
 * Resolves a rule's node type name to a JavaParser node class.
 *
 * <p>A rule names its node type the way JavaParser names it -- `CatchClause`,
 * `MethodCallExpr`, `Comment`. The class lives in one of JavaParser's `ast`
 * sub-packages, so the name is resolved by trying the known packages.
 *
 * <p>JavaParser ships a `NodeWithRange`/`Node` hierarchy in these packages:
 * `ast`, `ast.body`, `ast.comments`, `ast.expr`, `ast.modifiers`, `ast.nodeTypes`,
 * `ast.stmt`, `ast.type`. The list below is the set the resolver searches.
 */
object NodeTypes {

    private val LOG = LoggerFactory.getLogger(NodeTypes::class.java)

    /** The JavaParser packages a node class may live in. */
    private val PACKAGES = listOf(
        "com.github.javaparser.ast",
        "com.github.javaparser.ast.body",
        "com.github.javaparser.ast.comments",
        "com.github.javaparser.ast.expr",
        "com.github.javaparser.ast.modifiers",
        "com.github.javaparser.ast.nodeTypes",
        "com.github.javaparser.ast.stmt",
        "com.github.javaparser.ast.type"
    )

    private val cache = HashMap<String, Class<out Node>>()

    /**
     * Resolves a node type name.
     *
     * @return the node class, or null when the name is not a JavaParser node
     */
    @Synchronized
    fun resolve(typeName: String): Class<out Node>? {
        val name = typeName.trim()
        if (name.isEmpty()) return null
        cache[name]?.let { return it }
        for (pkg in PACKAGES) {
            val candidate = "$pkg.$name"
            try {
                val cls = Class.forName(candidate)
                if (Node::class.java.isAssignableFrom(cls) && !cls.isInterface) {
                    @Suppress("UNCHECKED_CAST")
                    val typed = cls as Class<out Node>
                    cache[name] = typed
                    return typed
                }
            } catch (ex: ClassNotFoundException) {
                // try the next package
            }
        }
        return null
    }

    /** The fully qualified name of a node class, for the generated cast. */
    fun qualifiedName(cls: Class<out Node>): String = cls.name

    /**
     * The node types a rule applies to.
     *
     * <p>When a rule does not name a node type, it applies to every node.
     */
    fun targetsFor(rule: DslRule): List<Class<out Node>> {
        val named = rule.nodeType.trim()
        if (named.isEmpty()) return listOf(Node::class.java)
        val resolved = resolve(named)
        if (resolved == null) {
            LOG.warn("Rule '{}' names an unknown node type '{}'", rule.id, named)
            return emptyList()
        }
        return listOf(resolved)
    }
}
