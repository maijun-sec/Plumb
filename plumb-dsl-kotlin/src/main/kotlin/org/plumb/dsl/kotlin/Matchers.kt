package org.plumb.dsl.kotlin

import com.github.javaparser.ast.Node

/**
 * The structural matchers -- the upstream `match` / `contain` idioms.
 *
 * <p>Upstream:
 *
 * <pre>
 *   infix fun Node.match(block: Node.() -&gt; Boolean): Boolean = block()
 *   infix fun Node.contain(block: Node.() -&gt; Boolean): Boolean
 * </pre>
 *
 * As with the extension properties, they are exposed as static methods so a
 * JSR-223 script can call them.
 *
 * <p><b>Scope.</b> `match` tests the node itself. `contain` tests every
 * descendant at <em>any</em> depth -- the upstream version walks the
 * descendants too, so the two agree.
 */
object Matchers {

    /** `node match { ... }` -- evaluate the predicate against this node. */
    @JvmStatic
    fun match(node: Node?, predicate: (Node) -> Boolean): Boolean {
        if (node == null) return false
        return try {
            predicate(node)
        } catch (ex: RuntimeException) {
            false
        }
    }

    /**
     * `node contain { ... }` -- true when any descendant satisfies the
     * predicate.
     *
     * <p>The predicate is evaluated once per descendant, and the descendant
     * is passed as the lambda's argument. That is what lets a rule write
     * `contain { simpleName == "ReturnStmt" }` and have it mean "there is
     * a return statement somewhere below".
     */
    @JvmStatic
    fun contain(node: Node?, predicate: (Node) -> Boolean): Boolean {
        if (node == null) return false
        return try {
            node.findAll(Node::class.java).any { descendant ->
                try {
                    predicate(descendant)
                } catch (ex: RuntimeException) {
                    false
                }
            }
        } catch (ex: RuntimeException) {
            false
        }
    }

    /**
     * `node ancestor { ... }` -- true when any ancestor satisfies the
     * predicate.
     *
     * <p>The predicate is evaluated once per ancestor, and the ancestor is
     * passed as the lambda's argument.
     */
    @JvmStatic
    fun ancestor(node: Node?, predicate: (Node) -> Boolean): Boolean {
        if (node == null) return false
        var current = node.parentNode.orElse(null)
        while (current != null) {
            val hit = try {
                predicate(current)
            } catch (ex: RuntimeException) {
                false
            }
            if (hit) return true
            current = current.parentNode.orElse(null)
        }
        return false
    }
}
