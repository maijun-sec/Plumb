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
package org.plumb.dfa.cg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** FunctionSignature unit tests. */
class FunctionSignatureTest {

  @Test
  void parse_canonical_withAllParts() {
    FunctionSignature fs =
        FunctionSignature.parse("com.example.Helper#doIt(java.lang.String):void");
    assertThat(fs).isNotNull();
    assertThat(fs.packageName()).isEqualTo("com.example");
    assertThat(fs.className()).isEqualTo("Helper");
    assertThat(fs.method()).isEqualTo("doIt");
    assertThat(fs.paramTypes()).containsExactly("java.lang.String");
    assertThat(fs.returnType()).isEqualTo("void");
    assertThat(fs.fqClassName()).isEqualTo("com.example.Helper");
  }

  @Test
  void parse_canonical_withNoPackage() {
    FunctionSignature fs = FunctionSignature.parse("Helper#doIt");
    assertThat(fs).isNotNull();
    assertThat(fs.packageName()).isEmpty();
    assertThat(fs.className()).isEqualTo("Helper");
    assertThat(fs.method()).isEqualTo("doIt");
  }

  @Test
  void parse_canonical_withNoReturnType() {
    FunctionSignature fs = FunctionSignature.parse("com.example.Helper#doIt(java.lang.String)");
    assertThat(fs).isNotNull();
    assertThat(fs.returnType()).isNull();
  }

  @Test
  void parse_canonical_withMultipleParams() {
    FunctionSignature fs =
        FunctionSignature.parse("com.example.Foo#bar(java.lang.String,int,double):boolean");
    assertThat(fs).isNotNull();
    assertThat(fs.paramTypes()).containsExactly("java.lang.String", "int", "double");
    assertThat(fs.returnType()).isEqualTo("boolean");
  }

  @Test
  void parse_jni_primitive() {
    FunctionSignature fs = FunctionSignature.parse("Lcom/example/Foo;.bar(I)V");
    assertThat(fs).isNotNull();
    assertThat(fs.packageName()).isEqualTo("com.example");
    assertThat(fs.className()).isEqualTo("Foo");
    assertThat(fs.method()).isEqualTo("bar");
    assertThat(fs.paramTypes()).containsExactly("int");
    assertThat(fs.returnType()).isEqualTo("void");
  }

  @Test
  void parse_jni_object() {
    FunctionSignature fs =
        FunctionSignature.parse("Lcom/example/Foo;.bar(Ljava/lang/String;)Ljava/lang/Object;");
    assertThat(fs).isNotNull();
    assertThat(fs.paramTypes()).containsExactly("java.lang.String");
    assertThat(fs.returnType()).isEqualTo("java.lang.Object");
  }

  @Test
  void parse_jni_array() {
    FunctionSignature fs = FunctionSignature.parse("Lcom/example/Foo;.bar([I)V");
    assertThat(fs).isNotNull();
    assertThat(fs.paramTypes()).containsExactly("[I");
  }

  @Test
  void parse_returnsNullForUnrecognizable() {
    assertThat(FunctionSignature.parse("")).isNull();
    assertThat(FunctionSignature.parse(null)).isNull();
    assertThat(FunctionSignature.parse("not a signature")).isNull();
  }

  @Test
  void canonical_isLowercase() {
    FunctionSignature fs =
        FunctionSignature.parse("com.example.Helper#doIt(java.lang.String):void");
    assertThat(fs.canonical()).isEqualTo("com.example.helper#doit(java.lang.string):void");
  }

  @Test
  void equals_isCaseInsensitive() {
    FunctionSignature a = FunctionSignature.parse("com.example.Helper#doIt");
    FunctionSignature b = FunctionSignature.parse("COM.EXAMPLE.HELPER#DOIT");
    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  void fqClassName_combinesPackageAndClass() {
    FunctionSignature with = FunctionSignature.parse("com.example.Helper#m");
    assertThat(with.fqClassName()).isEqualTo("com.example.Helper");
    FunctionSignature without = FunctionSignature.parse("Helper#m");
    assertThat(without.fqClassName()).isEqualTo("Helper");
  }

  // ===== inner-class signature tests =====

  @Test
  void parse_innerClass_preservesDollarInClassName() {
    FunctionSignature fs = FunctionSignature.parse("com.example.Outer$Inner#compute(int):int");
    assertThat(fs).isNotNull();
    assertThat(fs.packageName()).isEqualTo("com.example");
    assertThat(fs.className()).isEqualTo("Outer$Inner");
    assertThat(fs.simpleClassName()).isEqualTo("Inner");
    assertThat(fs.enclosingClasses()).containsExactly("Outer");
    assertThat(fs.isInnerClass()).isTrue();
    assertThat(fs.method()).isEqualTo("compute");
  }

  @Test
  void parse_anonymousClass_preservesNumericLeaf() {
    FunctionSignature fs = FunctionSignature.parse("com.example.Outer$1#run():void");
    assertThat(fs).isNotNull();
    assertThat(fs.className()).isEqualTo("Outer$1");
    assertThat(fs.simpleClassName()).isEqualTo("1");
    assertThat(fs.enclosingClasses()).containsExactly("Outer");
    assertThat(fs.isInnerClass()).isTrue();
  }

  @Test
  void parse_deeplyNestedInnerClass_splitsFullChain() {
    FunctionSignature fs = FunctionSignature.parse("com.example.A$B$C#m():void");
    assertThat(fs).isNotNull();
    assertThat(fs.className()).isEqualTo("A$B$C");
    assertThat(fs.simpleClassName()).isEqualTo("C");
    assertThat(fs.enclosingClasses()).containsExactly("A", "B");
  }

  @Test
  void parse_topLevelClass_isNotInner() {
    FunctionSignature fs = FunctionSignature.parse("com.example.Helper#m():void");
    assertThat(fs.isInnerClass()).isFalse();
    assertThat(fs.enclosingClasses()).isEmpty();
    assertThat(fs.simpleClassName()).isEqualTo("Helper");
  }

  @Test
  void fqClassName_preservesInnerClassDollar() {
    FunctionSignature fs = FunctionSignature.parse("com.example.Outer$Inner#m");
    assertThat(fs.fqClassName()).isEqualTo("com.example.Outer$Inner");
  }

  @Test
  void canonical_innerClassIsLowercased() {
    FunctionSignature fs = FunctionSignature.parse("com.example.Outer$Inner#compute(int):int");
    assertThat(fs.canonical()).isEqualTo("com.example.outer$inner#compute(int):int");
  }

  @Test
  void innerClassFromTacTranslator_matchesParse() {
    // TacTranslator emits signatures like
    // "Outer$Inner.compute(int):int" (dot, no '#'). The
    // strategy layer is expected to map this to a canonical
    // form first; verify the canonical form round-trips.
    String irStyle = "Outer$Inner.compute(int):int";
    // Adapt to canonical form
    String canonical = irStyle.replaceFirst("\\.(?=[A-Za-z_][\\w$]*\\()", "#");
    FunctionSignature fs = FunctionSignature.parse(canonical);
    assertThat(fs).isNotNull();
    assertThat(fs.className()).isEqualTo("Outer$Inner");
    assertThat(fs.method()).isEqualTo("compute");
    assertThat(fs.paramTypes()).containsExactly("int");
    assertThat(fs.returnType()).isEqualTo("int");
  }
}
