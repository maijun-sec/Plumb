package org.plumb.dsl.kotlin

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.resolution.TypeSolver
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import java.io.File

/**
 * The parser configuration the DSL tests read their fixtures with.
 *
 * <p><b>Why this exists.</b> JavaParser's default language level is an old one, and a source
 * file that uses a newer construct does not PARSE at that level -- it parses partially, and
 * the parts that failed are simply missing from the tree. The symptom is confusing: a
 * comment rule suddenly matches nothing, a type rule matches nothing, and the counts drift,
 * with no error anywhere.
 *
 * <p>That is exactly what happened here. The fixture grew constructs from Java 12 through
 * Java 17 -- records, text blocks, switch expressions, `yield`, `instanceof` patterns -- and
 * the corpus's comment and type examples all went to zero, because the fixture was parsed at
 * the wrong level and the tree was incomplete.
 *
 * <p>So the level is stated ONCE, here, and every test uses it. And it is the level the rest
 * of Plumb uses: `JavaSourceParser` parses with `JAVA_17`, so a DSL rule sees the same tree
 * the other checkers see.
 */
object TestParsers {

    /** The language level Plumb parses with everywhere else. */
    val LEVEL: ParserConfiguration.LanguageLevel = ParserConfiguration.LanguageLevel.JAVA_17

    /**
     * A configuration that matches the one the engine uses.
     *
     * <p>The symbol solver is included because the type rules need it, and the comments are
     * attached because the comment rules need them.
     */
    fun config(): ParserConfiguration {
        val solver: TypeSolver = ReflectionTypeSolver()
        return ParserConfiguration()
            .setLanguageLevel(LEVEL)
            .setSymbolResolver(JavaSymbolSolver(solver))
            .setAttributeComments(true)
    }

    /** Parses a file, failing loudly when it does not parse. */
    fun parse(file: File): CompilationUnit {
        val result = JavaParser(config()).parse(file)
        check(result.isSuccessful) {
            "the fixture did not parse: " +
                result.getProblems().joinToString("; ") { it.getVerboseMessage() }
        }
        return result.getResult().get()
    }
}
