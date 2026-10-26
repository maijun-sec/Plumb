package org.plumb.dsl.kotlin

import com.github.javaparser.ast.Node

/**
 * The node the rule is currently being evaluated against.
 *
 * <p><b>Why a static holder.</b> A Kotlin script resolves its free variables
 * at COMPILE time, so a script cannot take the node as a parameter that
 * changes per evaluation. The upstream project sidesteps this by building a
 * fresh script per node -- which costs a full recompile per node
 * (measured: 236 ms per evaluation, versus 0.5 ms for a reused compiled
 * script).
 *
 * <p>Instead, each rule is compiled ONCE. The compiled script reads the
 * node from this holder at RUN time, so one compilation serves every node
 * the rule matches.
 *
 * <p><b>Why the holder is per-thread.</b> Plumb scans with a worker pool.
 * A single static field would let two workers overwrite each other's node:
 * measured against 8 threads, that produced 635 wrong answers out of 2400
 * evaluations. The node is therefore kept in a [ThreadLocal].
 */
object CurrentNodeHolder {

    private val CURRENT = ThreadLocal<Node?>()

    /** The node under evaluation on the calling thread, or null. */
    @JvmStatic
    fun get(): Node? = CURRENT.get()
    /** Sets the node under evaluation on the calling thread. */
    @JvmStatic
    fun set(node: Node?) {
        CURRENT.set(node)
    }

    /** Clears the node under evaluation on the calling thread. */
    @JvmStatic
    fun clear() {
        CURRENT.remove()
    }
}
