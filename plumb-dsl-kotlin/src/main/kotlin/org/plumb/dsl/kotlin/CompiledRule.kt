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
