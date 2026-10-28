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
package org.plumb.style.annotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Curated, well-known annotation FQNs / short names that drive the Coverity Java DFA / annotation
 * checkers.
 *
 * <p>Java's annotation ecosystem is fragmented: a {@code @NonNull} may come from {@code
 * javax.annotation.Nonnull}, {@code jakarta.annotation.Nonnull}, {@code
 * org.jetbrains.annotations.NotNull}, {@code androidx.annotation.NonNull}, or {@code
 * lombok.NonNull}; an {@code @Id} may be {@code javax.persistence.Id} or {@code
 * jakarta.persistence.Id} (the latter is the JPA 3.0 namespace). Rather than force every consumer
 * to enumerate the aliases, this class groups them by purpose.
 *
 * <p>The sets are unordered at the language level but are exposed as {@link LinkedHashSet} so
 * iteration order is stable for deterministic finding messages.
 */
public final class AnnotationFacts {

  private AnnotationFacts() {}

  private static Set<String> set(String... names) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(names)));
  }

  // ─────── 1. Non-null markers ───────

  /**
   * The "this value must not be null" family. Drives {@code UNINIT_NONNULL}: a field with one of
   * these annotations must be assigned a non-null value in every constructor.
   */
  public static final Set<String> NONNULL =
      set(
          "NonNull",
          "NotNull",
          "Nonnull",
          "javax.annotation.Nonnull",
          "javax.annotation.NotNull",
          "jakarta.annotation.Nonnull",
          "jakarta.annotation.NotNull",
          "org.jetbrains.annotations.NotNull",
          "org.jetbrains.annotations.NonNull",
          "androidx.annotation.NonNull",
          "android.support.annotation.NonNull",
          "lombok.NonNull",
          "edu.umd.cs.findbugs.annotations.NonNull",
          "org.checkerframework.checker.nullness.qual.NonNull");

  // ─────── 2. JPA / Hibernate core ───────

  /**
   * Marks a JPA entity (or JPA 3.0 / Hibernate entity). Drives the entity-class detection in ORM_*
   * and HIBERNATE_* checkers.
   */
  public static final Set<String> JPA_ENTITY =
      set(
          "Entity",
          "javax.persistence.Entity",
          "jakarta.persistence.Entity",
          "org.hibernate.annotations.Entity");

  /**
   * Marks a primary-key field. Drives {@code HIBERNATE_BAD_HASHCODE}: an {@code @Id} field is
   * assigned by the persistence layer after the entity is saved, so it must not participate in
   * {@code hashCode()} / {@code equals()}.
   */
  public static final Set<String> JPA_ID =
      set(
          "Id",
          "javax.persistence.Id",
          "jakarta.persistence.Id",
          "org.springframework.data.annotation.Id",
          "org.springframework.data.annotation.Persistent");

  /**
   * {@code @Transient} -- a JPA / Java field that is not persisted. Used by ORM_LOST_UPDATE /
   * ORM_UNNECESSARY_GET to skip persistence checks for these fields.
   */
  public static final Set<String> JPA_TRANSIENT =
      set(
          "Transient",
          "javax.persistence.Transient",
          "jakarta.persistence.Transient",
          "java.beans.Transient",
          "org.springframework.data.annotation.Transient");

  /**
   * Database-generated primary key. Same exclusion scope as {@link #JPA_ID} for
   * HIBERNATE_BAD_HASHCODE purposes.
   */
  public static final Set<String> JPA_GENERATED_VALUE =
      set(
          "GeneratedValue",
          "javax.persistence.GeneratedValue",
          "jakarta.persistence.GeneratedValue");

  /**
   * Transactional method / class. Drives ORM_LOST_UPDATE -- a {@code @Transactional}-annotated
   * method that mutates an entity is supposed to commit the change at the end of the method, so the
   * engine flags detached / transient entities that the method forgot to persist.
   */
  public static final Set<String> JPA_TRANSACTIONAL =
      set(
          "Transactional",
          "javax.transaction.Transactional",
          "jakarta.transaction.Transactional",
          "org.springframework.transaction.annotation.Transactional",
          "org.springframework.transaction.annotation.Tx");

  /**
   * Field-level persistence annotations. A field with one of these is the project's
   * <em>canonical</em> form of a persistent field -- we use this in ORM_UNNECESSARY_GET to know
   * which fields can be addressed by their column attribute rather than by loading the full row.
   */
  public static final Set<String> JPA_COLUMN =
      set(
          "Column",
          "javax.persistence.Column",
          "jakarta.persistence.Column",
          "JoinColumn",
          "javax.persistence.JoinColumn",
          "jakarta.persistence.JoinColumn",
          "Basic",
          "javax.persistence.Basic",
          "jakarta.persistence.Basic");
}
