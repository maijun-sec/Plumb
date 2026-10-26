package org.plumb.dsl.kotlin

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.comments.Comment
import org.plumb.rules.RuleDef
import org.plumb.style.AstChecker
import org.plumb.style.StyleContext
import org.slf4j.LoggerFactory

/**
 * The AST checker that runs the DSL rules.
 *
 * <p>Plumb's [AstChecker] SPI dispatches by node type, which suits the
 * built-in checkers: each one declares the node types it cares about. A DSL
 * rule, by contrast, names its node type in the rule file, so this checker
 * does not fit the per-type dispatch model. It therefore runs its own pass
 * over the compilation unit, grouping the rules by the node type they
 * target.
 *
 * <p>The extra traversal is cheap relative to the rest of the scan -- Plumb
 * already walks the tree once for the 600+ built-in checkers -- and it buys
 * support for any node type a rule names, including abstract ones such as
 * `Statement` or `Expression`.
 *
 * <p><b>Why the work happens in {@link #finish(StyleContext)} and not in a
 * {@code visit} method.</b> {@link org.plumb.style.AstDispatcher} dispatches
 * by EXACT node class: it builds a table keyed by the node type a checker
 * declared, and a node is offered only to the checkers registered under that
 * exact class. Two consequences follow.
 *
 * <ol>
 *   <li>The dispatcher never offers the {@link CompilationUnit} to a
 *       checker, because a {@code CompilationUnit} is the tree ROOT, not one
 *       of its nodes -- {@code unit.walk(...)} does not visit the root. A
 *       checker that overrides {@code visit(CompilationUnit, StyleContext)}
 *       therefore NEVER runs.
 *   <li>A checker that overrides {@code visit(CatchClause, StyleContext)}
 *       only sees {@code CatchClause}s. A DSL rule may name any node type,
 *       including abstract ones such as {@code Statement} or
 *       {@code Expression}, which the exact-class table cannot express.
 * </ol>
 *
 * <p>So the DSL rules cannot ride the dispatcher's per-node dispatch at all.
 * They run in {@link #finish(StyleContext)}, which the dispatcher calls once
 * per file after the walk -- the one hook that is guaranteed to see the whole
 * unit.
 */
class DslAstChecker private constructor(
    private val compiled: List<CompiledRule>,
    private val targets: List<Pair<CompiledRule, List<Class<out Node>>>>
) : AstChecker {

    override fun name(): String = "dsl-kotlin"

    /**
     * The rule metadata for every loaded rule.
     *
     * <p>The runner mirrors these into the live [org.plumb.rules.RuleRegistry]
     * so that the rules are visible to the whitelist / blacklist filters and
     * to [org.plumb.style.StyleContext.report]'s enabled gate. Without this,
     * a DSL rule would compile, match, and then be dropped at report time.
     */
    fun ruleDefs(): List<RuleDef> = compiled.map { it.rule.toRuleDef() }

    override fun init(ctx: StyleContext) {
        // The rules are compiled when the checker is built, so there is
        // nothing to set up per file.
    }

    override fun finish(ctx: StyleContext) {
        val unit = ctx.source().unit() ?: return
        if (compiled.isEmpty()) return
        val registry = ctx.rules()
        for ((rule, nodeTypes) in targets) {
            val ruleDef = rule.rule.toRuleDef()
            // Per-rule enable check.
            //
            // The DSL checker stands for MANY rules, so the runner
            // can only gate it as a whole. The per-rule decision
            // belongs here, against the rule's own id: a rule the
            // user disabled must not fire even though its
            // siblings do.
            //
            // The registry entry wins when it exists; the rule
            // file's own `enabled` flag is the fallback for a rule
            // that was not mirrored into the registry.
            val registered = registry.byId(ruleDef.ruleId().id()).orElse(null)
            if (registered != null && !registered.enabled()) continue
            if (registered == null && !rule.rule.enabled) continue
            for (node in nodesOfType(unit, nodeTypes)) {
                if (!rule.matches(node)) continue
                val position = node.begin.map { it.line }.orElse(0)
                val column = node.begin.map { it.column }.orElse(0)
                ctx.report(
                    position,
                    column,
                    ruleDef,
                    rule.rule.description,
                    ruleDef.severity(),
                    "dsl:${rule.rule.normalisedId}"
                )
            }
        }
    }

    /**
     * Every node in the unit that is an instance of one of the given types.
     *
     * <p>A rule that names an ABSTRACT node type -- `Comment`, `Statement`,
     * `Expression` -- matches every subclass of it. That is what the
     * upstream rules expect: the "password in comment" rule names
     * `Comment`, and the comments in a file are `JavadocComment`,
     * `LineComment` and `BlockComment`.
     *
     * <p><b>Comments need special handling.</b> JavaParser attaches a
     * comment to the node it documents, but it does NOT put it in the
     * tree: {@code findAll(Node.class)} never visits a comment. The
     * comments are reachable through {@code getAllContainedComments()},
     * so a rule that targets them needs that explicitly. (The upstream
     * project walks comments through a dedicated visitor; the same effect
     * is achieved here by adding them to the candidate list.)
     */
    private fun nodesOfType(unit: CompilationUnit, types: List<Class<out Node>>): List<Node> {
        val wantsComments = types.any { it.isAssignableFrom(Comment::class.java) }
        if (types.size == 1 && types[0] == Node::class.java) {
            val all = ArrayList<Node>(unit.findAll(Node::class.java))
            if (wantsComments) all.addAll(unit.getAllContainedComments())
            return all
        }
        val out = ArrayList<Node>()
        for (node in unit.findAll(Node::class.java)) {
            for (type in types) {
                if (type.isInstance(node)) {
                    out.add(node)
                    break
                }
            }
        }
        if (wantsComments) {
            for (comment in unit.getAllContainedComments()) {
                for (type in types) {
                    if (type.isInstance(comment)) {
                        out.add(comment)
                        break
                    }
                }
            }
        }
        return out
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(DslAstChecker::class.java)

        /**
         * Builds a checker from the given rule files.
         *
         * <p>A rule that fails to compile is reported and skipped: one broken
         * rule must not take the whole scan down. When [strict] is set, a
         * compilation failure aborts instead.
         */
        fun from(
            ruleFiles: List<java.nio.file.Path>,
            strict: Boolean = false
        ): DslAstChecker? {
            if (ruleFiles.isEmpty()) return null
            val rules = RuleLoader.loadAll(ruleFiles)
            if (rules.isEmpty()) {
                LOG.warn("No DSL rules were loaded from {}", ruleFiles)
                return null
            }
            val compiled = ArrayList<CompiledRule>()
            val targets = ArrayList<Pair<CompiledRule, List<Class<out Node>>>>()
            var failures = 0
            for (rule in rules) {
                if (!rule.enabled) continue
                val nodeTypes = NodeTypes.targetsFor(rule)
                if (nodeTypes.isEmpty()) {
                    LOG.error("Rule '{}' has no usable node type; skipping it", rule.id)
                    failures++
                    if (strict) throw IllegalStateException("Rule '${rule.id}' has no usable node type")
                    continue
                }
                val javaClassName = NodeTypes.qualifiedName(nodeTypes[0])
                val rewritten = RuleMatcher.rewrite(rule.matcher, javaClassName)
                if (rewritten.script.isEmpty()) {
                    LOG.error(
                        "Rule '{}' has an unusable matcher ({}); skipping it",
                        rule.id,
                        rewritten.warnings.joinToString("; ")
                    )
                    failures++
                    if (strict) throw IllegalStateException("Rule '${rule.id}' has an unusable matcher")
                    continue
                }
                for (warning in rewritten.warnings) {
                    LOG.warn("Rule '{}': {}", rule.id, warning)
                }
                val compiledRule = try {
                    CompiledRule(rule, ScriptEngineHolder.compile(rewritten.script, rule.id))
                } catch (ex: ScriptCompilationException) {
                    LOG.error("{}", ex.message)
                    failures++
                    if (strict) throw ex
                    continue
                }
                compiled.add(compiledRule)
                targets.add(compiledRule to nodeTypes)
            }
            if (compiled.isEmpty()) {
                LOG.warn("No DSL rule could be compiled from {}", ruleFiles)
                return null
            }
            if (failures > 0) {
                LOG.warn("{} of {} DSL rule(s) were skipped", failures, rules.size)
            }
            LOG.info("Loaded {} DSL rule(s) from {}", compiled.size, ruleFiles)
            return DslAstChecker(compiled, targets)
        }
    }
}
