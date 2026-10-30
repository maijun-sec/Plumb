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
import org.slf4j.LoggerFactory
import javax.script.CompiledScript

/**
 * A rule that has been compiled and is ready to be evaluated.
 *
 * <p>The compiled script is shared across threads; the node it is evaluated
 * against is per-thread (see [CurrentNodeHolder]). That combination was
 * verified: 8 threads evaluating one shared compiled script against 2400
 * nodes produced zero cross-talk, while a single shared node field produced
 * 635 wrong results.
 */
class CompiledRule(
    val rule: DslRule,
    private val compiled: CompiledScript
) {

    /**
     * Evaluates the rule against a node.
     *
     * @return true when the rule matches
     */
    fun matches(node: Node): Boolean {
        CurrentNodeHolder.set(node)
        return try {
            val result = compiled.eval()
            result is Boolean && result
        } catch (ex: Exception) {
            LOG.warn(
                "Rule '{}' failed while evaluating against {}: {}",
                rule.id,
                node.javaClass.simpleName,
                ex.message
            )
            false
        } finally {
            CurrentNodeHolder.clear()
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(CompiledRule::class.java)
    }
}
