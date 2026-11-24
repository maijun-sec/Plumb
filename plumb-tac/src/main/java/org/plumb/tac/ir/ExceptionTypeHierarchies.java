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
package org.plumb.tac.ir;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The exception hierarchies of the platforms Plumb analyses, as a catalogue of parent edges.
 *
 * <p><b>What this is.</b> The hierarchy is world knowledge, so it does not live in the TAC
 * instruction stream: the {@code THROW} and the {@code CATCH_HANDLER} each carry a type NAME, and
 * this catalogue answers "is that name a subtype of that name". It is offered to the CFG builder
 * through {@link ExceptionTypeMatcher}, so a caller that knows a different platform can pass its
 * own matcher instead.
 *
 * <p><b>Why the names are fully qualified.</b> A matcher's {@code NEVER_CATCHES} answer drops an
 * exception-flow edge, and a dropped edge is a lost flow. That makes the name comparison a
 * soundness question, not a convenience: if the catalogue matched the SIMPLE name {@code
 * IOException}, then a program that declares its own {@code app.IOException} would be read as the
 * JDK's {@code java.io.IOException}, and the engine would drop a real edge. So the catalogue is
 * keyed by the fully qualified name, and a name it does not know is answered {@link
 * ExceptionTypeMatcher.Relation#UNKNOWN} -- which keeps the edge.
 *
 * <p><b>How a pair is decided.</b> Let {@code T} be the thrown type and {@code C} the handler's
 * type, and let {@code chain(X)} be {@code X} together with all its ancestors.
 *
 * <ul>
 *   <li>{@code C ∈ chain(T)} -- {@code C} is a supertype of {@code T}, so the handler catches it
 *       whatever the runtime type is: {@link ExceptionTypeMatcher.Relation#CATCHES}.
 *   <li>{@code T ∈ chain(C)} -- {@code C} is a subtype of {@code T}, so the handler catches it only
 *       for some runtime types: {@link ExceptionTypeMatcher.Relation#MAY_CATCH}.
 *   <li>Both chains are complete and the two are unrelated -- the handler cannot catch it: {@link
 *       ExceptionTypeMatcher.Relation#NEVER_CATCHES}. Only a language with single class inheritance
 *       may answer this; see {@link #singleInheritance}.
 *   <li>Anything else -- including a name the catalogue does not know, and a chain that stops at a
 *       type the catalogue does not know -- is {@link ExceptionTypeMatcher.Relation#UNKNOWN}.
 * </ul>
 *
 * <p><b>The catalogue is deliberately partial.</b> It lists the exception types that appear in
 * ordinary code, not the whole platform. A type that is missing is not an error: it is answered
 * {@code UNKNOWN}, the edge is kept, and the analysis stays sound. The cost is that a program using
 * an unlisted exception type does not get the sharper catch edges.
 */
public final class ExceptionTypeHierarchies {

  private ExceptionTypeHierarchies() {}

  /**
   * The matcher built from the catalogue below.
   *
   * <p>This is the matcher the CFG builder uses by default, so that every consumer of a CFG sees
   * the same edges. A caller that has better platform knowledge (a classpath it can read, a
   * language the catalogue does not cover) can pass its own matcher to {@code
   * TacCfgBuilder.build(fn, matcher)} instead.
   */
  public static ExceptionTypeMatcher defaultMatcher() {
    return CatalogueMatcher.INSTANCE;
  }

  /**
   * Whether the language's classes have exactly one superclass.
   *
   * <p>This is what makes {@code NEVER_CATCHES} sound: with single inheritance, two unrelated
   * classes cannot have a common subclass, so no runtime value can be an instance of both. A
   * language with multiple inheritance of exception classes (Python) has no such guarantee -- a
   * class can derive from two unrelated exception classes at once -- so its matcher must never
   * answer {@code NEVER_CATCHES}.
   */
  public static boolean singleInheritance(SourceLanguage language) {
    switch (language) {
      case JAVA:
      case KOTLIN:
      case SCALA:
      case GROOVY:
      case CSHARP:
      case PHP:
        return true;
      default:
        return false;
    }
  }

  // ---- The tables ------------------------------------------------------
  //
  // Each entry is "child -> parent". A child whose parent is the empty string is a ROOT: the top
  // of its hierarchy. A chain that reaches a root is complete; a chain that reaches a type the
  // table does not mention is not, and the pair is answered UNKNOWN.

  /** The JVM's exception tree. Also the answer for Kotlin, Scala and Groovy. */
  private static final Map<String, String> JVM =
      buildTable(
          "java.lang.Throwable", //
          "java.lang.Exception=java.lang.Throwable", //
          "java.lang.Error=java.lang.Throwable", //
          "java.lang.RuntimeException=java.lang.Exception", //
          "java.lang.IllegalArgumentException=java.lang.RuntimeException", //
          "java.lang.IllegalStateException=java.lang.RuntimeException", //
          "java.lang.NumberFormatException=java.lang.IllegalArgumentException", //
          "java.lang.NullPointerException=java.lang.RuntimeException", //
          "java.lang.ClassCastException=java.lang.RuntimeException", //
          "java.lang.ArithmeticException=java.lang.RuntimeException", //
          "java.lang.IndexOutOfBoundsException=java.lang.RuntimeException", //
          "java.lang.ArrayIndexOutOfBoundsException=java.lang.IndexOutOfBoundsException", //
          "java.lang.StringIndexOutOfBoundsException=java.lang.IndexOutOfBoundsException", //
          "java.lang.NegativeArraySizeException=java.lang.RuntimeException", //
          "java.lang.UnsupportedOperationException=java.lang.RuntimeException", //
          "java.lang.SecurityException=java.lang.RuntimeException", //
          "java.lang.InterruptedException=java.lang.Exception", //
          "java.lang.CloneNotSupportedException=java.lang.Exception", //
          "java.lang.reflect.InvocationTargetException=java.lang.Exception", //
          "java.lang.reflect.UndeclaredThrowableException=java.lang.RuntimeException", //
          "java.lang.AssertionError=java.lang.Error", //
          "java.lang.StackOverflowError=java.lang.Error", //
          "java.lang.OutOfMemoryError=java.lang.Error", //
          "java.lang.UnsatisfiedLinkError=java.lang.Error", //
          "java.io.IOException=java.lang.Exception", //
          "java.io.FileNotFoundException=java.io.IOException", //
          "java.io.UncheckedIOException=java.lang.RuntimeException", //
          "java.net.SocketException=java.io.IOException", //
          "java.net.UnknownHostException=java.io.IOException", //
          "java.net.ConnectException=java.net.SocketException", //
          "java.net.SocketTimeoutException=java.io.InterruptedIOException", //
          "java.io.InterruptedIOException=java.io.IOException", //
          "java.sql.SQLException=java.lang.Exception", //
          "java.util.NoSuchElementException=java.lang.RuntimeException", //
          "java.util.ConcurrentModificationException=java.lang.RuntimeException", //
          "java.util.InputMismatchException=java.util.NoSuchElementException");

  /** The .NET exception tree. */
  private static final Map<String, String> DOTNET =
      buildTable(
          "System.Exception", //
          "System.SystemException=System.Exception", //
          "System.ApplicationException=System.Exception", //
          "System.ArgumentException=System.SystemException", //
          "System.ArgumentNullException=System.ArgumentException", //
          "System.ArgumentOutOfRangeException=System.ArgumentException", //
          "System.InvalidOperationException=System.SystemException", //
          "System.NotSupportedException=System.SystemException", //
          "System.NotImplementedException=System.SystemException", //
          "System.ObjectDisposedException=System.InvalidOperationException", //
          "System.NullReferenceException=System.SystemException", //
          "System.ArithmeticException=System.SystemException", //
          "System.OverflowException=System.ArithmeticException", //
          "System.IndexOutOfRangeException=System.SystemException", //
          "System.FormatException=System.SystemException", //
          "System.TimeoutException=System.SystemException", //
          "System.UnauthorizedAccessException=System.SystemException", //
          "System.IO.IOException=System.SystemException", //
          "System.IO.FileNotFoundException=System.IO.IOException", //
          "System.IO.DirectoryNotFoundException=System.IO.IOException", //
          "System.Collections.Generic.KeyNotFoundException=System.SystemException");

  /** The PHP exception tree. */
  private static final Map<String, String> PHP =
      buildTable(
          "Exception", //
          "Error=Exception", //
          "ErrorException=Exception", //
          "LogicException=Exception", //
          "RuntimeException=Exception", //
          "BadFunctionCallException=LogicException", //
          "BadMethodCallException=LogicException", //
          "DomainException=LogicException", //
          "InvalidArgumentException=LogicException", //
          "LengthException=LogicException", //
          "OutOfRangeException=LogicException", //
          "OutOfBoundsException=RuntimeException", //
          "OverflowException=RuntimeException", //
          "RangeException=RuntimeException", //
          "UnexpectedValueException=RuntimeException", //
          "UnexpectedTypeException=UnexpectedValueException", //
          "TypeError=Error", //
          "ValueError=Error", //
          "ArithmeticError=Error", //
          "DivisionByZeroError=ArithmeticError", //
          "ArgumentCountError=TypeError", //
          "AssertionError=Error", //
          "UnhandledMatchError=Error", //
          "JsonException=Exception");

  /**
   * The Python exception tree.
   *
   * <p>Python's exception classes may inherit from more than one exception class, so this table is
   * only ever used to answer {@code CATCHES} and {@code MAY_CATCH}; see {@link #singleInheritance}.
   */
  private static final Map<String, String> PYTHON =
      buildTable(
          "BaseException", //
          "Exception=BaseException", //
          "ArithmeticError=Exception", //
          "AssertionError=Exception", //
          "AttributeError=Exception", //
          "BufferError=Exception", //
          "EOFError=Exception", //
          "ImportError=Exception", //
          "LookupError=Exception", //
          "MemoryError=Exception", //
          "NameError=Exception", //
          "OSError=Exception", //
          "ReferenceError=Exception", //
          "RuntimeError=Exception", //
          "StopAsyncIteration=Exception", //
          "StopIteration=Exception", //
          "SyntaxError=Exception", //
          "SystemError=Exception", //
          "TypeError=Exception", //
          "ValueError=Exception", //
          "Warning=Exception", //
          "FloatingPointError=ArithmeticError", //
          "OverflowError=ArithmeticError", //
          "ZeroDivisionError=ArithmeticError", //
          "ModuleNotFoundError=ImportError", //
          "IndexError=LookupError", //
          "KeyError=LookupError", //
          "UnboundLocalError=NameError", //
          "BlockingIOError=OSError", //
          "ChildProcessError=OSError", //
          "ConnectionError=OSError", //
          "FileExistsError=OSError", //
          "FileNotFoundError=OSError", //
          "InterruptedError=OSError", //
          "IsADirectoryError=OSError", //
          "NotADirectoryError=OSError", //
          "PermissionError=OSError", //
          "ProcessLookupError=OSError", //
          "TimeoutError=OSError", //
          "ConnectionAbortedError=ConnectionError", //
          "ConnectionRefusedError=ConnectionError", //
          "ConnectionResetError=ConnectionError", //
          "BrokenPipeError=ConnectionError", //
          "RecursionError=RuntimeError", //
          "IndentationError=SyntaxError", //
          "TabError=IndentationError", //
          "UnicodeError=ValueError", //
          "UnicodeDecodeError=UnicodeError", //
          "UnicodeEncodeError=UnicodeError", //
          "UnicodeTranslateError=UnicodeError");

  /**
   * The table that answers for a language, or {@code null} when the catalogue does not cover it.
   *
   * <p>A language that is not covered is not an error: every pair is answered {@code UNKNOWN}, the
   * edges are all kept, and the analysis stays sound.
   */
  private static Map<String, String> tableFor(SourceLanguage language) {
    if (language == null) {
      return null;
    }
    switch (language) {
      case JAVA:
      case KOTLIN:
      case SCALA:
      case GROOVY:
        return JVM;
      case CSHARP:
        return DOTNET;
      case PHP:
        return PHP;
      case PYTHON3:
        return PYTHON;
      default:
        return null;
    }
  }

  /**
   * Build a table from {@code child=parent} entries plus one root.
   *
   * <p>The root's parent is the empty string, which terminates a chain.
   */
  private static Map<String, String> buildTable(String root, String... entries) {
    Map<String, String> table = new HashMap<>();
    table.put(root, "");
    for (String entry : entries) {
      int eq = entry.indexOf('=');
      table.put(entry.substring(0, eq), entry.substring(eq + 1));
    }
    return Map.copyOf(table);
  }

  /**
   * The type name as the catalogue keys it.
   *
   * <p>Generic arguments and array markers are stripped: a {@code List<IOException>} is not a type
   * the catalogue knows, and an exception type is never an array either.
   */
  private static String normalize(String type) {
    if (type == null) {
      return null;
    }
    String name = type.trim();
    if (name.isEmpty()) {
      return null;
    }
    int lt = name.indexOf('<');
    if (lt >= 0) {
      name = name.substring(0, lt);
    }
    while (name.endsWith("[]")) {
      name = name.substring(0, name.length() - 2);
    }
    name = name.trim();
    return name.isEmpty() ? null : name;
  }

  /**
   * The type and all its ancestors, or {@code null} when the chain is not complete.
   *
   * <p>A chain is complete when it reaches a root of the table. A chain that runs into a type the
   * table does not mention is incomplete: the real hierarchy continues in a direction this
   * catalogue does not know, so nothing may be concluded from it.
   */
  private static Set<String> chain(Map<String, String> table, String type) {
    Set<String> chain = new LinkedHashSet<>();
    String current = type;
    while (current != null && !current.isEmpty()) {
      if (!chain.add(current)) {
        return null; // a cycle: the table is broken
      }
      String parent = table.get(current);
      if (parent == null) {
        return null; // an ancestor the table does not know
      }
      current = parent;
    }
    return chain;
  }

  /** The matcher over the tables above. */
  private enum CatalogueMatcher implements ExceptionTypeMatcher {
    INSTANCE;

    @Override
    public Relation relation(SourceLanguage language, String thrownType, String caughtType) {
      String thrown = normalize(thrownType);
      String caught = normalize(caughtType);
      if (thrown == null || caught == null) {
        return Relation.UNKNOWN;
      }
      if (thrown.equals(caught)) {
        return Relation.CATCHES;
      }
      Map<String, String> table = tableFor(language);
      if (table == null) {
        return Relation.UNKNOWN;
      }
      // Both names must be types the catalogue knows. A name it does not
      // know could be a user-defined subclass of anything, so nothing may
      // be concluded from it.
      if (!table.containsKey(thrown) || !table.containsKey(caught)) {
        return Relation.UNKNOWN;
      }
      Set<String> thrownChain = chain(table, thrown);
      if (thrownChain != null && thrownChain.contains(caught)) {
        return Relation.CATCHES;
      }
      Set<String> caughtChain = chain(table, caught);
      if (caughtChain != null && caughtChain.contains(thrown)) {
        return Relation.MAY_CATCH;
      }
      if (thrownChain == null || caughtChain == null) {
        return Relation.UNKNOWN;
      }
      if (!singleInheritance(language)) {
        return Relation.UNKNOWN;
      }
      return Relation.NEVER_CATCHES;
    }
  }
}
