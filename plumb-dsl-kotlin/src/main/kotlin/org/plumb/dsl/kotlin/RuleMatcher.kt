package org.plumb.dsl.kotlin

import org.slf4j.LoggerFactory

/**
 * Rewrites the upstream matcher text into a Kotlin script.
 *
 * <p>The upstream matcher text looks like:
 *
 * <pre>
 *   CatchClause catchClause: catchClause match { empty }
 * </pre>
 *
 * The part before the `:` names the node type and the binding; the part
 * after it is the predicate.
 *
 * <p><b>Why a rewrite is needed.</b> The upstream DSL uses Kotlin
 * extension properties and functions on the node (`n.simpleName`,
 * `n match { ... }`). Those are not resolvable from a JSR-223 script -- the
 * script compiler reads extension metadata from the script's compile
 * classpath, not from the module's own runtime classpath (verified against
 * Kotlin 2.2.0: `n.simpleName` fails with "Unresolved reference", while
 * `NodeAccess.simpleName(n)` compiles and runs).
 *
 * <p>So the rewriter turns the extension call syntax into explicit static
 * calls:
 *
 * <pre>
 *   catchClause match { empty }
 *   -&gt; Matchers.match(catchClause) { NodeAccess.isEmpty(it) }
 *
 *   method.name == "printStackTrace"
 *   -&gt; NodeAccess.methodName(NodeAccess.method(mce)) == "printStackTrace"
 * </pre>
 *
 * <p><b>How the rewriting works.</b> The predicate is tokenised and then
 * rebuilt. A dotted chain is rewritten from its innermost link outwards, so
 * `a.b.c` becomes `Facade.c(Facade.b(a))` and each step is a plain static
 * call the script compiler understands.
 *
 * <p>The rewriter never evaluates the predicate and never interprets its
 * meaning. Anything it does not recognise is passed through untouched and
 * reported as a warning; the rule then fails to COMPILE with a Kotlin
 * error naming the unknown symbol, rather than silently never matching.
 */
object RuleMatcher {

    private val LOG = LoggerFactory.getLogger(RuleMatcher::class.java)

    /**
     * The node properties that become [NodeAccess] calls, keyed by the
     * property name the rule writes.
     */
    private val NODE_PROPERTIES: Map<String, String> = mapOf(
        "simpleName" to "NodeAccess.simpleName",
        "qualifiedName" to "NodeAccess.qualifiedName",
        "methodName" to "NodeAccess.methodName",
        "parameterTypes" to "NodeAccess.parameterTypes",
        "parameters" to "NodeAccess.parameters",
        "exception" to "NodeAccess.exception",
        "enclosingFunction" to "NodeAccess.enclosingFunction",
        "enclosingClass" to "NodeAccess.enclosingClass",
        "static" to "NodeAccess.isStatic",
        "final" to "NodeAccess.isFinal",
        "type" to "NodeAccess.type",
        "content" to "NodeAccess.content",
        "method" to "NodeAccess.method",
        "empty" to "NodeAccess.isEmpty"
    )

    /**
     * The properties of the wrappers the facades return.
     *
     * <p>These are the chained accesses -- `method.name`, `type.supers` --
     * that the upstream rules use. They are read off a wrapper object, so
     * the rewrite keeps the chain but turns the wrapper into an explicit
     * call.
     *
     * <p>The key is the WRAPPER TYPE the property belongs to, so the
     * rewriter knows which facade method to use: `name` off a
     * [org.plumb.dsl.kotlin.MethodRef] is a different method from `name`
     * off a node.
     */
    private val WRAPPER_PROPERTIES: Map<String, WrapperProperty> = mapOf(
        "name" to WrapperProperty("MethodRef", "NodeAccess.resolvedMethodName"),
        "enclosingClass" to
            WrapperProperty("MethodRef", "NodeAccess.resolvedMethodEnclosingClass"),
        "supers" to WrapperProperty("TypeRef", "TypeAccess.supersOf"),
        "strictSupers" to WrapperProperty("TypeRef", "TypeAccess.strictSupersOf"),
        "typeName" to WrapperProperty("TypeRef", "TypeAccess.typeNameOf"),
        "resolveType" to WrapperProperty("TypeRef", "TypeAccess.resolveTypeName")
    )

    /**
     * A property read off a wrapper value.
     *
     * @param owner the wrapper type the property belongs to
     * @param facade the facade method that reads it
     */
    private data class WrapperProperty(val owner: String, val facade: String)

    /**
     * The wrapper type each node property yields.
     *
     * <p>This is what lets the rewriter know which properties may be read
     * off the result: `method` yields a [org.plumb.dsl.kotlin.MethodRef],
     * so `method.name` is a method-reference property, while `type` yields
     * a [org.plumb.dsl.kotlin.TypeRef], so `type.supers` is a type
     * property.
     */
    private val WRAPPER_RETURN_TYPE: Map<String, String> = mapOf(
        "method" to "MethodRef",
        "type" to "TypeRef",
        "exception" to "ParameterRef"
    )

    /**
     * The properties read off an ELEMENT of a list.
     *
     * <p>`parameterTypes[0].resolveType` reads the resolved name of a
     * declared type. The list element is a JavaParser `Type`, so the
     * element-level helper resolves it.
     */
    private val ELEMENT_PROPERTIES: Map<String, String> = mapOf(
        "resolveType" to "TypeAccess.resolveDeclaredTypeName"
    )

    /**
     * The methods called on a list.
     *
     * <p>`parameterTypes.size()` is the upstream's way of asking how many
     * parameters a method has.
     */
    private val LIST_METHODS: Map<String, String> = mapOf(
        "size" to "Ops.size"
    )

    /**
     * The node properties that are read as a METHOD CALL rather than a
     * plain property.
     *
     * <p>`parameterTypes.size()` -- the upstream rule writes it as a call.
     */
    private val LIST_METHOD_PROPERTIES: Set<String> = setOf("size")

    /**
     * The node properties that may appear WITHOUT a receiver.
     *
     * <p>Inside a matcher block the receiver is implicit -- `match { empty }`
     * means "the node under test has an empty body" -- so these names are
     * resolved against the block's subject.
     */
    private val BARE_NODE_PROPERTIES: Map<String, String> = mapOf(
        "simpleName" to "NodeAccess.simpleName",
        "qualifiedName" to "NodeAccess.qualifiedName",
        "methodName" to "NodeAccess.methodName",
        "parameterTypes" to "NodeAccess.parameterTypes",
        "parameters" to "NodeAccess.parameters",
        "exception" to "NodeAccess.exception",
        "enclosingFunction" to "NodeAccess.enclosingFunction",
        "enclosingClass" to "NodeAccess.enclosingClass",
        "static" to "NodeAccess.isStatic",
        "final" to "NodeAccess.isFinal",
        "type" to "NodeAccess.type",
        "content" to "NodeAccess.content",
        "method" to "NodeAccess.method",
        "empty" to "NodeAccess.isEmpty"
    )

    /**
     * The facade names the rewriter itself introduces.
     *
     * <p>A word from this set in the rewritten output came from the
     * rewriter, not from the rule, so it is not worth warning about.
     */
    private val REWRITER_NAMES: Set<String> = setOf(
        "Matchers.match", "Matchers.contain", "Matchers.ancestor",
        "NodeAccess.simpleName", "NodeAccess.qualifiedName", "NodeAccess.methodName",
        "NodeAccess.parameterTypes", "NodeAccess.parameters", "NodeAccess.exception",
        "NodeAccess.enclosingFunction", "NodeAccess.enclosingClass", "NodeAccess.isStatic",
        "NodeAccess.isFinal", "NodeAccess.type", "NodeAccess.content", "NodeAccess.method",
        "NodeAccess.isEmpty", "NodeAccess.resolvedMethodName",
        "NodeAccess.resolvedMethodEnclosingClass", "NodeAccess.exceptionType",
        "NodeAccess.exceptionName", "TypeAccess.supers", "TypeAccess.strictSupers",
        "TypeAccess.typeName", "TypeAccess.resolveTypeName", "TypeAccess.supersOf",
        "TypeAccess.strictSupersOf", "TypeAccess.typeNameOf", "TypeAccess.typeAt",
        "TypeAccess.resolveDeclaredTypeName", "StringOps.startsWith", "StringOps.endsWith",
        "StringOps.matches", "StringOps.contains", "Ops.contains", "Ops.containsText", "Ops.size"
    )

    /**
     * True when the word is one the rewriter produced, or the rule's own
     * binding.
     */
    private fun producedByRewriter(word: String, binding: String): Boolean =
        word in REWRITER_NAMES || word == "it" || word == binding

    /** The infix string / collection operators. */
    private val INFIX_OPERATORS: Map<String, String> = mapOf(
        "startWith" to "StringOps.startsWith",
        "startsWith" to "StringOps.startsWith",
        "endsWith" to "StringOps.endsWith",
        "matches" to "StringOps.matches",
        "contain" to "Ops.contains",
        "contains" to "Ops.contains"
    )

    /** The structural matchers and the facade that replaces them. */
    private val STRUCTURAL_MATCHERS: Map<String, String> = mapOf(
        "match" to "Matchers.match",
        "contain" to "Matchers.contain",
        "ancestor" to "Matchers.ancestor"
    )
    /**
     * The words that are legitimately NOT node properties.
     *
     * <p>Kotlin's own keywords and the boolean operators, plus the names
     * the upstream rules use for their own locals. The DSL's own operator
     * keywords are in the list too: they are consumed by the infix and
     * structural passes, so the chain pass must not warn about them.
     */
    private val LOCAL_NAMES: Set<String> = setOf(
        "and", "or", "not", "true", "false", "null", "it", "val", "var", "fun"
    ) + INFIX_OPERATORS.keys + STRUCTURAL_MATCHERS.keys


    /**
     * The result of rewriting one rule's matcher.
     *
     * @param nodeType the node type the rule applies to
     * @param bindingName the name the node is bound to inside the script
     * @param script the generated script
     * @param warnings human-readable notes about constructs that were not
     *     recognised and were passed through unchanged
     */
    data class Rewritten(
        val nodeType: String,
        val bindingName: String,
        val script: String,
        val warnings: List<String>
    )

    /**
     * Rewrites one rule's matcher text into a compilable script.
     *
     * @param matcher the matcher text, e.g. `CatchClause catchClause: catchClause match { empty }`
     * @param javaClassName the fully qualified name of the node type, used
     *     for the cast in the generated script
     */
    fun rewrite(matcher: String, javaClassName: String): Rewritten {
        val warnings = ArrayList<String>()
        val text = matcher.trim()
        if (text.isEmpty()) {
            return Rewritten("", "", "", listOf("the matcher is empty"))
        }

        val colon = findHeaderColon(text)
        if (colon < 0) {
            return Rewritten(
                "", "",
                "",
                listOf("the matcher has no '<NodeType> <binding>:' header")
            )
        }
        val header = text.substring(0, colon).trim()
        val predicate = text.substring(colon + 1).trim()

        val headerParts = header.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val nodeType = headerParts.getOrNull(0) ?: ""
        val binding = headerParts.getOrNull(1) ?: "node"
        if (nodeType.isEmpty()) {
            return Rewritten("", binding, "", listOf("the matcher header names no node type"))
        }
        if (headerParts.size < 2) {
            warnings.add("the matcher header '$header' names no binding; using '$binding'")
        }

        val rewrittenPredicate = rewritePredicate(predicate, binding, warnings)
        val script = buildScript(binding, javaClassName, rewrittenPredicate)
        return Rewritten(nodeType, binding, script, warnings)
    }

    /** The generated script for a rewritten predicate. */
    private fun buildScript(binding: String, javaClassName: String, predicate: String): String =
        """
        |import org.plumb.dsl.kotlin.CurrentNodeHolder
        |import org.plumb.dsl.kotlin.NodeAccess
        |import org.plumb.dsl.kotlin.TypeAccess
        |import org.plumb.dsl.kotlin.StringOps
        |import org.plumb.dsl.kotlin.Ops
        |import org.plumb.dsl.kotlin.Matchers
        |
        |val $binding = CurrentNodeHolder.get() as $javaClassName
        |$predicate
        |""".trimMargin()

    // ─── tokenising ─────────────────────────────────────────────────────

    /** One lexical token of the predicate. */

    /**
     * Rewrites the predicate body.
     *
     * <p>The passes are: (1) the structural matcher calls, (2) the
     * property chains, (3) the infix operators.
     *
     * <p>The order matters. The chain pass must run BEFORE the infix pass:
     * an infix operator's left-hand side is a chain (`parameterTypes[0]
     * .resolveType matches "..."`), and the infix pass copies that side
     * verbatim. If the chain ran afterwards, the copied side would be
     * rewritten in the wrong context.
     */
    private fun rewritePredicate(
        predicate: String,
        binding: String,
        warnings: MutableList<String>
    ): String {
        var tokens = DslTokenizer.tokenize(predicate)
        tokens = rewriteNegation(tokens)
        tokens = rewriteStructural(tokens, binding, warnings)
        tokens = rewriteChains(tokens, binding, warnings)
        tokens = rewriteInfix(tokens, warnings)
        return render(tokens)
    }

    /**
     * Rewrites the prefix `not` into Kotlin's `!`.
     *
     * <p><b>Why this is needed.</b> `not` is a name in the upstream DSL's vocabulary -- the
     * rewriter already lists it among the words a rule may use -- but Kotlin has no prefix
     * `not` operator. `Boolean.not()` exists, but only as a function call, so `not empty` is a
     * syntax error and the rule fails to compile. `!empty` is the same thing written the way
     * Kotlin spells it.
     *
     * <p><b>Why the operand is parenthesised.</b> `!` binds tighter than the comparison
     * operators, so `!a == b` parses as `(!a) == b` -- which compares a Boolean with a String,
     * and does not mean what the rule said. The upstream's `not` is a prefix operator over the
     * whole operand, so the operand is wrapped: `not a == b` becomes `!(a == b)`.
     *
     * <p><b>Why the pass runs first.</b> The operand is usually a chain (`not a.b`), and the
     * chain pass rewrites chains. Running the negation first keeps each pass responsible for
     * one thing: the negation decides the extent of its operand, and the chain pass then
     * rewrites what is inside.
     *
     * <p>Only a `not` that stands alone as a word is rewritten. A `not` that is part of a longer
     * name (`nothing`, `annotate`) is a different word, and the tokenizer keeps them apart.
     */
    internal fun rewriteNegation(tokens: List<DslToken>): List<DslToken> {
        val out = ArrayList<DslToken>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token is DslToken.Word && token.text == "not" && !isOperandOfInfix(tokens, i)) {
                out.add(DslToken.Punct("!"))
                out.add(DslToken.Punct("("))
                // The operand runs to the end of the expression, or to the
                // closing bracket that encloses this `not`.
                val operandEnd = operandEnd(tokens, i + 1)
                out.addAll(tokens.subList(i + 1, operandEnd))
                out.add(DslToken.Punct(")"))
                i = operandEnd
                continue
            }
            out.add(token)
            i++
        }
        return out
    }

    /**
     * True when the `not` at {@code index} is an operand rather than a negation.
     *
     * <p>A `not` that follows a value is part of an infix expression -- `a not b` -- and
     * rewriting it would produce nonsense.
     */
    private fun isOperandOfInfix(tokens: List<DslToken>, index: Int): Boolean {
        val previous = if (index > 0) tokens[index - 1] else null
        return previous is DslToken.Word &&
            previous.text != "not" &&
            previous.text !in LOCAL_NAMES
    }

    /**
     * The end of the operand that starts at {@code from}.
     *
     * <p>The operand ends at the closing bracket that matches the bracket the `not` sits in, or
     * at the end of the token list. A comparison inside the operand does NOT end it: `not a == b`
     * negates the whole comparison, which is why the operand is parenthesised.
     */
    private fun operandEnd(tokens: List<DslToken>, from: Int): Int {
        var depth = 0
        var i = from
        while (i < tokens.size) {
            val token = tokens[i]
            if (token is DslToken.Punct) {
                when (token.text) {
                    "(", "[", "{" -> depth++
                    ")", "]", "}" -> if (depth == 0) return i else depth--
                }
            }
            i++
        }
        return tokens.size
    }

    /** Renders a token list back into source text. */
    internal fun render(tokens: List<DslToken>): String {
        val sb = StringBuilder()
        for ((index, token) in tokens.withIndex()) {
            val text = when (token) {
                is DslToken.Word -> token.text
                is DslToken.Str -> token.text
                is DslToken.Punct -> token.text
            }
            if (index > 0) {
                val prev = tokens[index - 1]
                val needSpace = when {
                    // A word followed by '(' is a call: no space.
                    prev is DslToken.Word && token is DslToken.Punct && token.text == "(" -> false
                    // A prefix operator is tight on both sides: `!x`, not `! x`.
                    // Kotlin parses `! x` as an unresolved reference to `!`,
                    // because the space makes the `!` look like a name.
                    token is DslToken.Punct && token.text in PREFIX_OPERATORS -> false
                    prev is DslToken.Punct && prev.text in PREFIX_OPERATORS -> false
                    prev is DslToken.Punct && prev.text !in setOf("(", "[", ".") -> true
                    token is DslToken.Punct && token.text !in setOf(")", "]", ",", ".", ";") -> true
                    prev is DslToken.Word && token is DslToken.Word -> true
                    prev is DslToken.Word && token is DslToken.Str -> true
                    prev is DslToken.Str && token is DslToken.Word -> true
                    else -> false
                }
                if (needSpace) sb.append(' ')
            }
            sb.append(text)
        }
        return sb.toString()
    }

    /**
     * The operators that bind to what follows them.
     *
     * <p>Kotlin writes a prefix operator tight against its operand: {@code !x}. A space makes
     * the {@code !} look like a name, and the compiler reports "Unresolved reference: !".
     */
    private val PREFIX_OPERATORS: Set<String> = setOf("!")

    /**
     * Rewrites `receiver match { body }` into
     * `Matchers.match(receiver) { body }`.
     */
    internal fun rewriteStructural(
        tokens: List<DslToken>,
        binding: String,
        warnings: MutableList<String>
    ): List<DslToken> {
        val out = ArrayList<DslToken>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token is DslToken.Word && STRUCTURAL_MATCHERS.containsKey(token.text)) {
                val facade = STRUCTURAL_MATCHERS.getValue(token.text)
                val nextToken = if (i + 1 < tokens.size) tokens[i + 1] else null
                val nextIsBrace = nextToken is DslToken.Punct && nextToken.text == "{"
                val receiverStart = chainStart(out)
                if (nextIsBrace && receiverStart < out.size) {
                    val receiver = out.subList(receiverStart, out.size).toList()
                    out.subList(receiverStart, out.size).clear()
                    out.add(DslToken.Word(facade))
                    out.add(DslToken.Punct("("))
                    out.addAll(receiver)
                    out.add(DslToken.Punct(")"))
                    // Skip the keyword; the block that follows is copied as-is.
                    i++
                    continue
                }
            }
            out.add(token)
            i++
        }
        return out
    }

    /**
     * The index in {@code out} where the trailing expression starts.
     *
     * <p>The expression is either a dotted chain (`a.b.c`) or a call
     * (`Facade.a(x)`) -- the infix operators can have either on their left
     * side once the chain pass has run.
     */
    internal fun chainStart(out: List<DslToken>): Int {
        var i = out.size
        var expectWord = true
        var start = out.size
        while (i > 0) {
            val token = out[i - 1]
            if (expectWord) {
                if (token is DslToken.Word) {
                    start = i - 1
                    expectWord = false
                    i--
                    continue
                }
                break
            }
            if (token is DslToken.Punct && token.text == ".") {
                expectWord = true
                i--
                continue
            }
            break
        }
        if (start < out.size) return start
        // Not a dotted chain: maybe a call -- `Facade.a(x)`.
        val callStart = callStart(out)
        if (callStart >= 0) return callStart
        return out.size
    }

    /**
     * The index where the trailing call expression starts, or -1.
     *
     * <p>A call is `Word ( ... )`; the index returned points at the
     * {@code Word}.
     */
    internal fun callStart(out: List<DslToken>): Int {
        if (out.size < 3) return -1
        val last = out[out.size - 1]
        if (last !is DslToken.Punct || last.text != ")") return -1
        // Walk back to the matching '('.
        var depth = 0
        var i = out.size - 1
        while (i >= 0) {
            val token = out[i]
            if (token is DslToken.Punct) {
                if (token.text == ")") depth++
                if (token.text == "(") {
                    depth--
                    if (depth == 0) {
                        val name = out[i - 1]
                        return if (name is DslToken.Word) i - 1 else -1
                    }
                }
            }
            i--
        }
        return -1
    }

    /**
     * Rewrites `a startWith "x"` into `StringOps.startsWith(a, "x")`.
     */
    internal fun rewriteInfix(tokens: List<DslToken>, warnings: MutableList<String>): List<DslToken> {
        val out = ArrayList<DslToken>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token is DslToken.Word && INFIX_OPERATORS.containsKey(token.text)) {
                val facade = INFIX_OPERATORS.getValue(token.text)
                val receiverStart = chainStart(out)
                val rhsEnd = expressionEnd(tokens, i + 1)
                if (receiverStart < out.size && rhsEnd > i + 1) {
                    val receiver = out.subList(receiverStart, out.size).toList()
                    out.subList(receiverStart, out.size).clear()
                    out.add(DslToken.Word(facade))
                    out.add(DslToken.Punct("("))
                    out.addAll(receiver)
                    out.add(DslToken.Punct(","))
                    out.addAll(tokens.subList(i + 1, rhsEnd))
                    out.add(DslToken.Punct(")"))
                    i = rhsEnd
                    continue
                }
            }
            out.add(token)
            i++
        }
        return out
    }

    /**
     * The end index of the expression that starts at {@code from}.
     */
    internal fun expressionEnd(tokens: List<DslToken>, from: Int): Int {
        var i = from
        var depth = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token is DslToken.Punct) {
                when (token.text) {
                    "(", "[" -> depth++
                    ")" -> if (depth == 0) break else depth--
                    "]" -> if (depth == 0) break else depth--
                    "}" -> if (depth == 0) break else depth--
                    "," -> if (depth == 0) break
                }
            }
            if (depth == 0 && token is DslToken.Word) {
                if (token.text == "and" || token.text == "or") break
            }
            if (depth == 0 && token is DslToken.Punct &&
                (token.text == "&&" || token.text == "||")
            ) break
            i++
        }
        return i
    }

    /**
     * Rewrites the dotted property chains.
     *
     * <p>`a.b.c` becomes `Facade.c(Facade.b(a))`. The chain is rewritten
     * from the innermost link outwards, so each link's receiver is
     * already a call by the time the next link is applied.
     *
     * <p>Only the rule's own binding, the implicit `it` inside a matcher
     * block, and the wrapper values the facades return are recognised. A
     * property on anything else is left alone with a warning, so the rule
     * fails to compile with a message naming the unknown symbol.
     */
    internal fun rewriteChains(
        tokens: List<DslToken>,
        binding: String,
        warnings: MutableList<String>
    ): List<DslToken> {
        val out = ArrayList<DslToken>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            // A chain is: word ( . word )*
            if (token is DslToken.Word) {
                val chainEnd = chainEndAt(tokens, i)
                if (chainEnd > i) {
                    val links = chainLinks(tokens, i, chainEnd)
                    val rewritten = rewriteChain(links, binding, warnings)
                    if (rewritten != null) {
                        out.addAll(rewritten)
                        i = chainEnd
                        continue
                    }
                    // The chain's head may be a BARE node property -- the
                    // upstream rules write `parameterTypes[0].resolveType`
                    // without a receiver. Handle that shape here.
                    val headName = links[0].substringBefore('[')
                    val bareHead = BARE_NODE_PROPERTIES[headName]
                    if (bareHead != null) {
                        val subject = currentSubject(out, binding)
                        val bareChain = rewriteBareChain(
                            links, bareHead, subject, binding, warnings
                        )
                        if (bareChain != null) {
                            out.addAll(bareChain)
                            i = chainEnd
                            continue
                        }
                    }
                }
                // A bare word that names a node property stands for the
                // property of the node under test. Inside a matcher block
                // that is the implicit `it`; at the top level it is the
                // rule's binding.
                val bare = BARE_NODE_PROPERTIES[token.text]
                if (bare != null) {
                    val subject = currentSubject(out, binding)
                    out.add(DslToken.Word(bare))
                    out.add(DslToken.Punct("("))
                    out.add(DslToken.Word(subject))
                    out.add(DslToken.Punct(")"))
                    i++
                    continue
                }
                // A bare word that is NOT a known property is either a
                // local name the rule defines itself, or a mistake.
                // Kotlin rejects it at compile time, so the rule fails
                // loudly rather than silently never matching -- but the
                // warning tells the rule author what went wrong.
                //
                // Only words that came from the RULE are checked: the
                // rewriter's own output (facade names, the rule's binding)
                // is not the rule author's business.
                if (token.text !in LOCAL_NAMES && !producedByRewriter(token.text, binding)) {
                    if (!warnings.any { it.contains("'${token.text}'") }) {
                        warnings.add(
                            "'${token.text}' is not a node property; the DSL knows these: " +
                                BARE_NODE_PROPERTIES.keys.joinToString(", ")
                        )
                    }
                }
            }
            out.add(token)
            i++
        }
        return out
    }

    /**
     * The node a bare property applies to.
     *
     * <p>Inside a `match { ... }` block the subject is the node itself.
     * Inside a `contain { ... }` block the subject is each descendant in
     * turn -- the block is evaluated once per descendant, and the property
     * reads that descendant. Inside an `ancestor { ... }` block it is each
     * ancestor.
     *
     * <p>Outside any block the subject is the rule's binding.
     */
    private fun currentSubject(out: List<DslToken>, binding: String): String {
        // Find the innermost matcher call we are inside.
        for (index in out.indices.reversed()) {
            val token = out[index]
            if (token is DslToken.Word && token.text.startsWith("Matchers.")) {
                val j = index + 1
                if (j < out.size) {
                    val paren = out[j]
                    if (paren is DslToken.Punct && paren.text == "(" && j + 1 < out.size) {
                        val receiver = out[j + 1]
                        if (receiver is DslToken.Word) {
                            // For `contain` and `ancestor` the block's subject
                            // is the descendant / ancestor, not the receiver.
                            val facade = token.text
                            if (facade == "Matchers.contain" ||
                                facade == "Matchers.ancestor"
                            ) {
                                return "it"
                            }
                            return receiver.text
                        }
                    }
                }
            }
        }
        return binding
    }

    /** The index just past the end of a bracket group starting at {@code from}. */
    internal fun bracketEndAt(tokens: List<DslToken>, from: Int): Int {
        var i = from
        var depth = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token is DslToken.Punct) {
                if (token.text == "[") depth++
                if (token.text == "]") {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return from
    }

    /** The index just past the end of the dotted chain starting at {@code from}. */
    internal fun chainEndAt(tokens: List<DslToken>, from: Int): Int {
        var i = from
        if (i >= tokens.size || tokens[i] !is DslToken.Word) return from
        i++
        while (i < tokens.size) {
            // An index segment comes first: `parameterTypes[0].resolveType`.
            val bracket = tokens[i]
            if (bracket is DslToken.Punct && bracket.text == "[") {
                var depth = 0
                while (i < tokens.size) {
                    val t = tokens[i]
                    if (t is DslToken.Punct && t.text == "[") depth++
                    if (t is DslToken.Punct && t.text == "]") {
                        depth--
                        if (depth == 0) {
                            i++
                            break
                        }
                    }
                    i++
                }
                continue
            }
            // Then a dotted link: `.resolveType`.
            if (i + 1 >= tokens.size) break
            val dot = tokens[i]
            val next = tokens[i + 1]
            if (dot !is DslToken.Punct || dot.text != "." || next !is DslToken.Word) break
            i += 2
        }
        return i
    }

    /**
     * The links in the chain.
     *
     * <p>A link is a property name; an index segment is attached to the
     * link it follows, so `parameterTypes[0].resolveType` has the links
     * `parameterTypes[0]` and `resolveType`.
     */
    internal fun chainLinks(tokens: List<DslToken>, from: Int, to: Int): List<String> {
        val out = ArrayList<String>()
        var i = from
        while (i < to) {
            val token = tokens[i]
            if (token is DslToken.Word) {
                val name = StringBuilder(token.text)
                i++
                // Attach an index segment, if any.
                val bracket = if (i < to) tokens[i] else null
                if (bracket is DslToken.Punct && bracket.text == "[") {
                    val start = i
                    var depth = 0
                    var close = -1
                    while (i < to) {
                        val t = tokens[i]
                        if (t is DslToken.Punct && t.text == "[") depth++
                        if (t is DslToken.Punct && t.text == "]") {
                            depth--
                            if (depth == 0) {
                                close = i
                                i++
                                break
                            }
                        }
                        i++
                    }
                    // The index is rendered WITHOUT the brackets: the
                    // brackets are Kotlin's array-literal syntax, and
                    // `[ 0 ]` would not compile as an index argument.
                    // The '[' marker is kept so the caller can tell an
                    // indexed link from a plain one.
                    name.append('[')
                    if (close > start) {
                        name.append(render(tokens.subList(start + 1, close)))
                    }
                    name.append(']')
                }
                out.add(name.toString())
                continue
            }
            i++
        }
        return out
    }

    /**
     * Rewrites a chain whose head is a bare node property.
     *
     * <p>The upstream rules write `parameterTypes[0].resolveType` -- the
     * property stands for the node under test, the index picks an element
     * of the resulting list, and the trailing property is read off that
     * element.
     *
     * @return the rewritten tokens, or null when the chain is not one the
     *     rewriter knows how to handle
     */
    internal fun rewriteBareChain(
        links: List<String>,
        bareFacade: String,
        subject: String,
        binding: String,
        warnings: MutableList<String>
    ): List<DslToken>? {
        if (links.size < 2) return null
        val head = links[0]
        val indexed = head.contains('[')
        // The index is carried in brackets -- see chainLinks -- and the
        // brackets are stripped here: they are Kotlin's array-literal
        // syntax, not an index.
        val index = if (indexed) head.substring(head.indexOf('[') + 1, head.length - 1) else ""

        // `parameterTypes` -> the list.
        var current: List<DslToken> = listOf(
            DslToken.Word(bareFacade),
            DslToken.Punct("("),
            DslToken.Word(subject),
            DslToken.Punct(")")
        )

        if (indexed) {
            // `parameterTypes[0]` -> the element's resolved type.
            current = listOf(
                DslToken.Word("TypeAccess.typeAt"),
                DslToken.Punct("("),
                DslToken.Word(bareFacade),
                DslToken.Punct("("),
                DslToken.Word(subject),
                DslToken.Punct(")"),
                DslToken.Punct(","),
                DslToken.Punct(index),
                DslToken.Punct(")")
            )
        }

        for (position in 1 until links.size) {
            val property = links[position]
            // A property read off an INDEXED element is an element
            // property; a property read off the list is a list method.
            val facade = if (indexed) {
                ELEMENT_PROPERTIES[property] ?: LIST_METHODS[property]
            } else {
                LIST_METHODS[property] ?: ELEMENT_PROPERTIES[property]
            }
            if (facade == null) {
                if (!warnings.any { it.contains("'.$property'") }) {
                    warnings.add(
                        "'.$property' was left unchanged; the DSL knows these " +
                            "node properties: ${NODE_PROPERTIES.keys.joinToString(", ")}"
                    )
                }
                return null
            }
            current = wrap(current, facade)
        }
        return current
    }

    /**
     * Rewrites one dotted chain.
     *
     * <p>The chain may mix two kinds of link:
     *
     * <ul>
     *   <li>a <em>node property</em> -- `simpleName`, `method`, `type`.
     *       These turn the receiver into a facade call.
     *   <li>a <em>wrapper property</em> -- `name`, `enclosingClass`,
     *       `supers`. These are read off the value the previous link
     *       produced, so they turn it into a facade call as well.
     * </ul>
     *
     * <p>The chain's head must be the rule's binding or the implicit `it`.
     *
     * @return the rewritten tokens, or null when the chain is not one the
     *     rewriter knows how to handle
     */
    internal fun rewriteChain(
        links: List<String>,
        binding: String,
        warnings: MutableList<String>
    ): List<DslToken>? {
        if (links.size < 2) return null
        val head = links[0]

        // The head is either the rule's binding / the implicit `it`, or a
        // node property that is reached without a receiver -- `method` and
        // `type` in the upstream rules stand for the binding's own method
        // and type.
        var current: List<DslToken>
        var currentType: String
        if (head == binding || head == "it") {
            current = listOf(DslToken.Word(head))
            currentType = "Node"
        } else {
            val headFacade = NODE_PROPERTIES[head]
            if (headFacade == null) return null
            current = listOf(
                DslToken.Word(headFacade),
                DslToken.Punct("("),
                DslToken.Word(binding),
                DslToken.Punct(")")
            )
            currentType = WRAPPER_RETURN_TYPE[head] ?: "Node"
        }

        for (index in 1 until links.size) {
            val property = links[index]
            // An indexed link -- `parameterTypes[0]` -- is a list, so a
            // property read off it is an element-level property.
            val indexed = property.contains('[')
            val bare = if (indexed) property.substringBefore('[') else property

            if (currentType == "Node") {
                // A list method: `parameterTypes.size()`.
                if (LIST_METHOD_PROPERTIES.contains(bare)) {
                    val listFacade = LIST_METHODS[bare]
                    if (listFacade == null) return null
                    current = wrap(current, listFacade)
                    currentType = "Int"
                    continue
                }
                val nodeFacade = NODE_PROPERTIES[bare]
                if (nodeFacade == null) {
                    if (!warnings.any { it.contains("'.$bare'") }) {
                        warnings.add(
                            "'.$bare' was left unchanged; the DSL knows these " +
                                "node properties: ${NODE_PROPERTIES.keys.joinToString(", ")}"
                        )
                    }
                    return null
                }
                if (indexed) {
                    // `parameterTypes[0]` -- the list, then one element.
                    current = listOf(
                        DslToken.Word("TypeAccess.typeAt"),
                        DslToken.Punct("("),
                        DslToken.Word(nodeFacade),
                        DslToken.Punct("("),
                        DslToken.Word(binding),
                        DslToken.Punct(")"),
                        DslToken.Punct(","),
                        DslToken.Punct(property.substring(property.indexOf('[') + 1, property.length - 1)),
                        DslToken.Punct(")")
                    )
                    currentType = "TypeRef"
                } else {
                    current = wrap(current, nodeFacade)
                    // The facade's return type decides what the NEXT link
                    // may read: `method` yields a MethodRef, `type` a
                    // TypeRef.
                    currentType = WRAPPER_RETURN_TYPE[bare] ?: "Node"
                }
                continue
            }

            if (currentType == "ParameterRef") {
                // A property read off a catch clause's exception parameter.
                if (bare == "type") {
                    current = wrap(current, "NodeAccess.exceptionType")
                    currentType = "TypeRef"
                    continue
                }
                if (bare == "name") {
                    current = wrap(current, "NodeAccess.exceptionName")
                    currentType = "String"
                    continue
                }
                if (!warnings.any { it.contains("'$bare'") }) {
                    warnings.add(
                        "'$bare' was left unchanged; the DSL knows these " +
                            "properties on a caught exception: type, name"
                    )
                }
                return null
            }

            if (currentType == "TypeRef") {
                // A property read off a resolved type.
                val wrapper = WRAPPER_PROPERTIES[bare]
                if (wrapper == null || wrapper.owner != "TypeRef") {
                    if (!warnings.any { it.contains("'.$bare'") }) {
                        warnings.add(
                            "'.$bare' was left unchanged; the DSL knows these " +
                                "properties on a resolved type: " +
                                WRAPPER_PROPERTIES.filter { it.value.owner == "TypeRef" }
                                    .keys.joinToString(", ")
                        )
                    }
                    return null
                }
                current = wrap(current, wrapper.facade)
                currentType = "String"
                continue
            }

            if (currentType == "MethodRef") {
                // A property read off a resolved method.
                val wrapper = WRAPPER_PROPERTIES[bare]
                if (wrapper == null || wrapper.owner != "MethodRef") {
                    if (!warnings.any { it.contains("'$bare'") }) {
                        warnings.add(
                            "'.$bare' was left unchanged; the DSL knows these " +
                                "properties on a resolved method: " +
                                WRAPPER_PROPERTIES.filter { it.value.owner == "MethodRef" }
                                    .keys.joinToString(", ")
                        )
                    }
                    return null
                }
                current = wrap(current, wrapper.facade)
                currentType = "String"
                continue
            }

            // A list: `parameterTypes.size()`.
            val listFacade = LIST_METHODS[bare]
            if (listFacade == null) {
                if (!warnings.any { it.contains("'$bare'") }) {
                    warnings.add(
                        "'$bare' was left unchanged; the DSL knows these " +
                            "list methods: ${LIST_METHODS.keys.joinToString(", ")}"
                    )
                }
                return null
            }
            current = wrap(current, listFacade)
            currentType = "Int"
        }
        return current
    }

    /** Wraps the given token sequence in a facade call. */
    private fun wrap(receiver: List<DslToken>, facade: String): List<DslToken> {
        val out = ArrayList<DslToken>()
        out.add(DslToken.Word(facade))
        out.add(DslToken.Punct("("))
        out.addAll(receiver)
        out.add(DslToken.Punct(")"))
        return out
    }

    /**
     * The index of the header's colon: the first colon that is not inside a
     * string literal.
     */
    private fun findHeaderColon(text: String): Int {
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"' && (i == 0 || text[i - 1] != '\\')) inString = !inString
            if (c == ':' && !inString) return i
            i++
        }
        return -1
    }
}
