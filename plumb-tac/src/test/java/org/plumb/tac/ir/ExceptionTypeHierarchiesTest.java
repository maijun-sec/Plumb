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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The exception hierarchy catalogue: what it answers, and -- more importantly -- what it refuses to
 * answer.
 *
 * <p><b>Why the refusals are the interesting half.</b> Three of the four answers keep an
 * exception-flow edge alive, and only one drops it. A catalogue that answered "no" too eagerly
 * would lose real flows -- a false negative -- so the tests below pin the cases where the answer
 * must stay "I do not know": a type the catalogue does not know, a name that is not fully
 * qualified, and a language whose exceptions may share a base class.
 */
@DisplayName("TAC: the exception hierarchy catalogue")
class ExceptionTypeHierarchiesTest {

  private static final ExceptionTypeMatcher MATCHER = ExceptionTypeHierarchies.defaultMatcher();

  @Test
  @DisplayName("a supertype of the thrown type catches it")
  void aSupertypeOfTheThrownTypeCatchesIt() {
    assertThat(MATCHER.relation(SourceLanguage.JAVA, "java.io.IOException", "java.lang.Exception"))
        .as("an IOException is an Exception")
        .isEqualTo(ExceptionTypeMatcher.Relation.CATCHES);
    assertThat(
            MATCHER.relation(
                SourceLanguage.JAVA, "java.io.FileNotFoundException", "java.io.IOException"))
        .as("a FileNotFoundException is an IOException")
        .isEqualTo(ExceptionTypeMatcher.Relation.CATCHES);
  }

  @Test
  @DisplayName("a subtype of the thrown type may catch it")
  void aSubtypeOfTheThrownTypeMayCatchIt() {
    assertThat(MATCHER.relation(SourceLanguage.JAVA, "java.lang.Exception", "java.io.IOException"))
        .as("the runtime value may be an IOException")
        .isEqualTo(ExceptionTypeMatcher.Relation.MAY_CATCH);
  }

  @Test
  @DisplayName("two unrelated classes never catch each other")
  void twoUnrelatedClassesNeverCatchEachOther() {
    assertThat(
            MATCHER.relation(
                SourceLanguage.JAVA, "java.io.IOException", "java.lang.RuntimeException"))
        .as("an IOException is not a RuntimeException")
        .isEqualTo(ExceptionTypeMatcher.Relation.NEVER_CATCHES);
  }

  @Test
  @DisplayName("the same type catches itself")
  void theSameTypeCatchesItself() {
    assertThat(MATCHER.relation(SourceLanguage.JAVA, "java.io.IOException", "java.io.IOException"))
        .as("a handler of the same type catches it")
        .isEqualTo(ExceptionTypeMatcher.Relation.CATCHES);
  }

  @Test
  @DisplayName("a type the catalogue does not know is not decided")
  void aTypeTheCatalogueDoesNotKnowIsNotDecided() {
    assertThat(MATCHER.relation(SourceLanguage.JAVA, "com.acme.MyEx", "java.lang.Exception"))
        .as("a user-defined exception may extend anything")
        .isEqualTo(ExceptionTypeMatcher.Relation.UNKNOWN);
    assertThat(MATCHER.relation(SourceLanguage.JAVA, "java.io.IOException", "com.acme.MyBase"))
        .as("a user-defined handler may extend anything too")
        .isEqualTo(ExceptionTypeMatcher.Relation.UNKNOWN);
  }

  @Test
  @DisplayName("a simple name is not a qualified name")
  void aSimpleNameIsNotAQualifiedName() {
    assertThat(MATCHER.relation(SourceLanguage.JAVA, "IOException", "java.lang.Exception"))
        .as("a program may declare its own IOException, so the short name decides nothing")
        .isEqualTo(ExceptionTypeMatcher.Relation.UNKNOWN);
  }

  @Test
  @DisplayName("a language the catalogue does not cover is not decided")
  void aLanguageTheCatalogueDoesNotCoverIsNotDecided() {
    assertThat(MATCHER.relation(SourceLanguage.GO, "error", "string"))
        .as("Go's panic values have no exception hierarchy")
        .isEqualTo(ExceptionTypeMatcher.Relation.UNKNOWN);
    assertThat(MATCHER.relation(SourceLanguage.TYPESCRIPT, "TypeError", "RangeError"))
        .as("JavaScript's catch clause has no type")
        .isEqualTo(ExceptionTypeMatcher.Relation.UNKNOWN);
  }

  @Test
  @DisplayName("a language with multiple inheritance never rules a handler out")
  void aLanguageWithMultipleInheritanceNeverRulesAHandlerOut() {
    assertThat(MATCHER.relation(SourceLanguage.PYTHON3, "ValueError", "TypeError"))
        .as("two unrelated Python exceptions can share a subclass")
        .isEqualTo(ExceptionTypeMatcher.Relation.UNKNOWN);
    assertThat(MATCHER.relation(SourceLanguage.PYTHON3, "ValueError", "Exception"))
        .as("a supertype still catches")
        .isEqualTo(ExceptionTypeMatcher.Relation.CATCHES);
    assertThat(ExceptionTypeHierarchies.singleInheritance(SourceLanguage.PYTHON3))
        .as("Python exceptions may have more than one base")
        .isFalse();
    assertThat(ExceptionTypeHierarchies.singleInheritance(SourceLanguage.JAVA))
        .as("a Java class has exactly one superclass")
        .isTrue();
  }
}
