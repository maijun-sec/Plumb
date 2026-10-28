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
package org.plumb.tac.translate;

import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VarType;
import com.github.javaparser.ast.type.WildcardType;
import org.plumb.tac.ir.TacType;

/**
 * Map a JavaParser {@link Type} to a {@link TacType}. We keep the mapping deliberately lossy:
 * generics and wildcards are erased, {@code var} types are projected to {@link TacType#UNKNOWN},
 * and unknown reference types fall back to {@code java.lang.Object}.
 */
public final class TypeMapper {

  private TypeMapper() {}

  /**
   * Variant of {@link #map(Type)} that <em>preserves generic type arguments</em> in the resulting
   * {@link TacType}'s class-name payload. Use this for any context where the type-arg information
   * matters -- e.g. the function's declared return type, so {@code ResponseEntity<User>} keeps its
   * {@code <User>} tail and {@code InformationExposureCheck} can inspect it.
   *
   * <p>Primitives, {@code String}, arrays and unknown types are mapped exactly as in {@link
   * #map(Type)}; only the class-or-interface case uses {@code asString()} instead of the simple
   * name.
   */
  public static TacType mapPreservingTypeArgs(Type t) {
    if (t == null) return TacType.UNKNOWN;
    if (t instanceof PrimitiveType pt) {
      return mapPrimitive(pt);
    }
    if (t instanceof ArrayType at) {
      return TacType.arrayOf(mapPreservingTypeArgs(at.getComponentType()));
    }
    if (t instanceof ClassOrInterfaceType ci) {
      String fqn = ci.getNameAsString();
      if ("String".equals(fqn)) return TacType.STRING;
      // asString() keeps the type
      // arguments. e.g. "ResponseEntity<User>",
      // "List<HashMap<String, User>>", "Page<User>".
      return TacType.ofClass(ci.asString());
    }
    if (t instanceof ReferenceType rt) {
      return mapPreservingTypeArgs(rt.getElementType());
    }
    if (t instanceof VarType || t instanceof WildcardType) {
      return TacType.UNKNOWN;
    }
    return TacType.UNKNOWN;
  }

  /**
   * Map a JavaParser ResolvedType (returned by MethodCallExpr.calculateResolvedType() and similar)
   * to a TacType. The ResolvedType is the symbol-table view; the AST Type is the source view. We
   * translate the ResolvedType to a simple FQN string via the qualified name and pass it through
   * resolveWellKnownFqn so common JDK classes land on their FQN (e.g. "Runtime" →
   * "java.lang.Runtime").
   */
  public static TacType mapResolved(com.github.javaparser.resolution.types.ResolvedType rt) {
    if (rt == null) return TacType.UNKNOWN;
    if (rt.isPrimitive()) {
      return mapPrimitiveName(rt.asPrimitive().name());
    }
    if (rt.isArray()) {
      return TacType.arrayOf(mapResolved(rt.asArrayType().getComponentType()));
    }
    try {
      String fqn = rt.describe();
      // strip generics: "List<String>" -> "List"
      int lt = fqn.indexOf('<');
      if (lt >= 0) fqn = fqn.substring(0, lt);
      fqn = fqn.trim();
      if (fqn.equals("String")) return TacType.STRING;
      // If FQN already starts with a package
      // marker, keep as-is. Otherwise resolve the
      // simple name to a known FQN.
      if (!fqn.contains(".")) {
        fqn = resolveWellKnownFqn(fqn);
      }
      return TacType.ofClass(fqn);
    } catch (Exception e) {
      return TacType.UNKNOWN;
    }
  }

  private static TacType mapPrimitiveName(String name) {
    return switch (name) {
      case "boolean" -> TacType.BOOLEAN;
      case "byte" -> TacType.BYTE;
      case "short" -> TacType.SHORT;
      case "char" -> TacType.CHAR;
      case "int" -> TacType.INT;
      case "long" -> TacType.LONG;
      case "float" -> TacType.FLOAT;
      case "double" -> TacType.DOUBLE;
      case "void" -> TacType.VOID;
      default -> TacType.UNKNOWN;
    };
  }

  public static TacType map(Type t) {
    if (t == null) return TacType.UNKNOWN;
    if (t instanceof PrimitiveType pt) {
      return mapPrimitive(pt);
    }
    if (t instanceof ArrayType at) {
      return TacType.arrayOf(map(at.getComponentType()));
    }
    if (t instanceof ClassOrInterfaceType ci) {
      String name = ci.getNameAsString();
      return TacType.ofClass(resolveWellKnownFqn(name));
    }
    if (t instanceof ReferenceType rt) {
      return map(rt.getElementType());
    }
    if (t instanceof VarType || t instanceof WildcardType) {
      return TacType.UNKNOWN;
    }
    return TacType.UNKNOWN;
  }

  /**
   * Most of the JDK is referenced by simple class name in user code. JavaParser does not track
   * {@code import} statements at the type level, so {@code Statement} shows up as the literal
   * string {@code "Statement"} rather than {@code "java.sql.Statement"}. The downstream sink-family
   * patterns in {@code sink-families.yaml} are FQN-only.
   *
   * <p>Resolving every import per file would require a symbol-table pass. The 100+ class table
   * below covers the common JDK classes that appear in security-sensitive code. For non-JDK
   * classes, we return the simple name as a best effort (the legacy {@code *.info/*}-style patterns
   * still work via {@link org.plumb.dfa.taint.InputSourcePatterns}).
   */
  public static String resolveWellKnownFqnStatic(String simpleName) {
    return resolveWellKnownFqn(simpleName);
  }

  private static String resolveWellKnownFqn(String simpleName) {
    if (simpleName == null || simpleName.isEmpty()) return simpleName;
    return switch (simpleName) {
      // java.lang
      case "String" -> "java.lang.String";
      case "Object" -> "java.lang.Object";
      case "Throwable" -> "java.lang.Throwable";
      case "Exception" -> "java.lang.Exception";
      case "RuntimeException" -> "java.lang.RuntimeException";
      case "Runtime" -> "java.lang.Runtime";
      case "Process" -> "java.lang.Process";
      case "ProcessBuilder" -> "java.lang.ProcessBuilder";
      case "StringBuilder" -> "java.lang.StringBuilder";
      case "StringBuffer" -> "java.lang.StringBuffer";
      case "Thread" -> "java.lang.Thread";
      case "ThreadGroup" -> "java.lang.ThreadGroup";
      case "Integer" -> "java.lang.Integer";
      case "Long" -> "java.lang.Long";
      case "Boolean" -> "java.lang.Boolean";
      case "Double" -> "java.lang.Double";
      case "Float" -> "java.lang.Float";
      case "Character" -> "java.lang.Character";
      case "Byte" -> "java.lang.Byte";
      case "Short" -> "java.lang.Short";
      case "Number" -> "java.lang.Number";
      case "Math" -> "java.lang.Math";
      case "System" -> "java.lang.System";
      case "Class" -> "java.lang.Class";
      case "ClassLoader" -> "java.lang.ClassLoader";
      case "Enum" -> "java.lang.Enum";
      case "Iterable" -> "java.lang.Iterable";
      // java.io
      case "File" -> "java.io.File";
      case "FileInputStream" -> "java.io.FileInputStream";
      case "FileOutputStream" -> "java.io.FileOutputStream";
      case "FileReader" -> "java.io.FileReader";
      case "FileWriter" -> "java.io.FileWriter";
      case "RandomAccessFile" -> "java.io.RandomAccessFile";
      case "InputStream" -> "java.io.InputStream";
      case "OutputStream" -> "java.io.OutputStream";
      case "Reader" -> "java.io.Reader";
      case "Writer" -> "java.io.Writer";
      case "PrintStream" -> "java.io.PrintStream";
      case "PrintWriter" -> "java.io.PrintWriter";
      case "BufferedReader" -> "java.io.BufferedReader";
      case "BufferedWriter" -> "java.io.BufferedWriter";
      case "InputStreamReader" -> "java.io.InputStreamReader";
      case "OutputStreamWriter" -> "java.io.OutputStreamWriter";
      case "ObjectInputStream" -> "java.io.ObjectInputStream";
      case "ObjectOutputStream" -> "java.io.ObjectOutputStream";
      case "Serializable" -> "java.io.Serializable";
      case "Console" -> "java.io.Console";
      // java.net
      case "Socket" -> "java.net.Socket";
      case "ServerSocket" -> "java.net.ServerSocket";
      case "DatagramSocket" -> "java.net.DatagramSocket";
      case "URL" -> "java.net.URL";
      case "URI" -> "java.net.URI";
      case "URLConnection" -> "java.net.URLConnection";
      case "HttpURLConnection" -> "java.net.HttpURLConnection";
      // java.sql
      case "Connection" -> "java.sql.Connection";
      case "Statement" -> "java.sql.Statement";
      case "PreparedStatement" -> "java.sql.PreparedStatement";
      case "CallableStatement" -> "java.sql.CallableStatement";
      case "ResultSet" -> "java.sql.ResultSet";
      case "DatabaseMetaData" -> "java.sql.DatabaseMetaData";
      case "DriverManager" -> "java.sql.DriverManager";
      // java.util
      case "Date" -> "java.util.Date";
      case "Calendar" -> "java.util.Calendar";
      case "Random" -> "java.util.Random";
      case "Scanner" -> "java.util.Scanner";
      case "Iterator" -> "java.util.Iterator";
      case "Hashtable" -> "java.util.Hashtable";
      case "Enumeration" -> "java.util.Enumeration";
      case "Formatter" -> "java.util.Formatter";
      case "StringTokenizer" -> "java.util.StringTokenizer";
      case "Timer" -> "java.util.Timer";
      case "TimerTask" -> "java.util.TimerTask";
      case "Map" -> "java.util.Map";
      case "HashMap" -> "java.util.HashMap";
      case "TreeMap" -> "java.util.TreeMap";
      case "LinkedHashMap" -> "java.util.LinkedHashMap";
      case "List" -> "java.util.List";
      case "ArrayList" -> "java.util.ArrayList";
      case "LinkedList" -> "java.util.LinkedList";
      case "Vector" -> "java.util.Vector";
      case "Stack" -> "java.util.Stack";
      case "Set" -> "java.util.Set";
      case "HashSet" -> "java.util.HashSet";
      case "TreeSet" -> "java.util.TreeSet";
      case "Collection" -> "java.util.Collection";
      case "Optional" -> "java.util.Optional";
      case "Properties" -> "java.util.Properties";
      case "Locale" -> "java.util.Locale";
      case "UUID" -> "java.util.UUID";
      case "ResourceBundle" -> "java.util.ResourceBundle";
      // java.util.regex
      case "Pattern" -> "java.util.regex.Pattern";
      case "Matcher" -> "java.util.regex.Matcher";
      // java.util.logging
      case "Logger" -> "java.util.logging.Logger";
      // java.text
      case "SimpleDateFormat" -> "java.text.SimpleDateFormat";
      // java.security
      case "MessageDigest" -> "java.security.MessageDigest";
      // java.lang.reflect
      case "Method" -> "java.lang.reflect.Method";
      case "Field" -> "java.lang.reflect.Field";
      case "Constructor" -> "java.lang.reflect.Constructor";
      // javax.servlet
      case "HttpServletRequest" -> "javax.servlet.http.HttpServletRequest";
      case "HttpServletResponse" -> "javax.servlet.http.HttpServletResponse";
      case "HttpSession" -> "javax.servlet.http.HttpSession";
      // javax.naming
      case "InitialContext" -> "javax.naming.InitialContext";
      case "InitialDirContext" -> "javax.naming.InitialDirContext";
      case "DirContext" -> "javax.naming.directory.DirContext";
      case "LdapContext" -> "javax.naming.ldap.LdapContext";
      case "NamingEnumeration" -> "javax.naming.NamingEnumeration";
      case "SearchResult" -> "javax.naming.SearchResult";
      case "SearchControls" -> "javax.naming.directory.SearchControls";
      case "Attribute" -> "javax.naming.directory.Attribute";
      case "Attributes" -> "javax.naming.directory.Attributes";
      case "BasicAttribute" -> "javax.naming.directory.BasicAttribute";
      case "BasicAttributes" -> "javax.naming.directory.BasicAttributes";
      // javax.xml.xpath
      case "XPath" -> "javax.xml.xpath.XPath";
      case "XPathFactory" -> "javax.xml.xpath.XPathFactory";
      case "XPathExpression" -> "javax.xml.xpath.XPathExpression";
      // javax.xml.transform
      case "Transformer" -> "javax.xml.transform.Transformer";
      case "TransformerFactory" -> "javax.xml.transform.TransformerFactory";
      case "DocumentBuilder" -> "javax.xml.parsers.DocumentBuilder";
      // javax.persistence
      case "EntityManager" -> "javax.persistence.EntityManager";
      case "Query" -> "javax.persistence.Query";
      case "ServletContext" -> "javax.servlet.ServletContext";
      case "ServletRequest" -> "javax.servlet.ServletRequest";
      // javax.naming (InitialContext already added above)
      // default — keep as-is
      default -> simpleName;
    };
  }

  private static TacType mapPrimitive(PrimitiveType pt) {
    return switch (pt.getType()) {
      case BOOLEAN -> TacType.BOOLEAN;
      case BYTE -> TacType.BYTE;
      case SHORT -> TacType.SHORT;
      case CHAR -> TacType.CHAR;
      case INT -> TacType.INT;
      case LONG -> TacType.LONG;
      case FLOAT -> TacType.FLOAT;
      case DOUBLE -> TacType.DOUBLE;
      default -> TacType.UNKNOWN;
    };
  }
}
