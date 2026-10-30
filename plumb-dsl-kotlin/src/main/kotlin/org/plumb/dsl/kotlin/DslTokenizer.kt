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

/**
 * The lexical tokens of a matcher predicate.
 *
 * <p>Split out from [RuleMatcher] so the tokeniser can be unit-tested
 * directly -- the rewriter's correctness depends on how the text is cut
 * into pieces, and a bug here shows up as a silently wrong rewrite.
 */
internal sealed class DslToken {
    /** An identifier or a keyword. */
    data class Word(val text: String) : DslToken()

    /** A string literal, quotes included. */
    data class Str(val text: String) : DslToken()

    /** A punctuation mark or an operator. */
    data class Punct(val text: String) : DslToken()
}

/**
 * Splits a matcher predicate into [DslToken]s.
 *
 * <p>String literals are kept whole, so a `{` or a `:` inside a string
 * does not confuse the parser.
 */
internal object DslTokenizer {

    /** The multi-character operators, longest first. */
    private val TWO_CHAR_OPERATORS = setOf("==", "!=", "&&", "||", ">=", "<=", "->")

    fun tokenize(text: String): List<DslToken> {
        val out = ArrayList<DslToken>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++

                c == '"' -> {
                    val sb = StringBuilder()
                    sb.append(c)
                    i++
                    while (i < text.length) {
                        val d = text[i]
                        sb.append(d)
                        i++
                        if (d == '\\' && i < text.length) {
                            sb.append(text[i])
                            i++
                            continue
                        }
                        if (d == '"') break
                    }
                    out.add(DslToken.Str(sb.toString()))
                }

                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    out.add(DslToken.Word(text.substring(start, i)))
                }

                else -> {
                    val two = if (i + 1 < text.length) text.substring(i, i + 2) else ""
                    if (two in TWO_CHAR_OPERATORS) {
                        out.add(DslToken.Punct(two))
                        i += 2
                    } else {
                        out.add(DslToken.Punct(c.toString()))
                        i++
                    }
                }
            }
        }
        return out
    }
}
