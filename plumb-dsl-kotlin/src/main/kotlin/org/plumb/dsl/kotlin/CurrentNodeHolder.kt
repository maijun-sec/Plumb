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
