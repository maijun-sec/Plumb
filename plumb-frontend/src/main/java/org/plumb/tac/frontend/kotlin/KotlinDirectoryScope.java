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
package org.plumb.tac.frontend.kotlin;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;

/**
 * A search scope over a directory tree, evaluated on every query.
 *
 * <p><b>Why the Analysis API's own scopes cannot be used here.</b> The Kotlin frontend
 * stages the file it analyses on disk <i>after</i> the session has been built, and the
 * session's module structure was fixed when it was built. The scope the Analysis API
 * builds for a source module is a <i>frozen list</i> of the files that existed at that
 * moment, so a file staged afterwards is outside the scope. The Analysis API then hands
 * the file to a "not under content root" module -- a module with no dependencies -- and
 * every reference in the file resolves to an error type: {@code StringBuilder} is
 * unresolved, {@code Regex} is unresolved, and every value is unknown.
 *
 * <p>{@code GlobalSearchScope.filesScope} has an overload that takes a supplier, which
 * looks like the answer -- but the scope <b>freezes the supplier's result on the first
 * query</b> and never asks again. So the first file analysed resolves and every later
 * one does not.
 *
 * <p>This scope asks the question directly on every call: is this file under the
 * directory? The answer does not depend on when the file was created, so every file the
 * frontend stages is in scope.
 */
final class KotlinDirectoryScope extends GlobalSearchScope {

  /** The root of the tree this scope covers. */
  private final VirtualFile root;

  /**
   * Creates a scope over a directory tree.
   *
   * @param project the project the scope belongs to
   * @param root the root directory; a scope with no root contains nothing
   */
  KotlinDirectoryScope(Project project, VirtualFile root) {
    super(project);
    this.root = root;
  }

  @Override
  public boolean contains(VirtualFile file) {
    if (file == null || root == null) {
      return false;
    }
    VirtualFile current = file;
    while (current != null) {
      if (current.equals(root)) {
        return true;
      }
      current = current.getParent();
    }
    return false;
  }

  @Override
  public boolean isSearchInLibraries() {
    // The scope covers the source tree, not the libraries.
    return false;
  }

  @Override
  public boolean isSearchInModuleContent(Module module) {
    // The Analysis API's standalone project has no IntelliJ modules, so there is no
    // module content to search.
    return false;
  }

  @Override
  public SearchScope intersectWith(SearchScope other) {
    // The scope is used for containment checks and for the module lookup, not for
    // intersecting with another scope; delegating to the other scope keeps the
    // intersection's semantics intact.
    return other.intersectWith(this);
  }

  @Override
  public GlobalSearchScope union(SearchScope other) {
    if (other instanceof GlobalSearchScope global) {
      return global.union(this);
    }
    return super.union(other);
  }

  @Override
  public String getDisplayName() {
    return "Kotlin source root " + (root == null ? "<none>" : root.getPath());
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof KotlinDirectoryScope scope)) {
      return false;
    }
    return root == null ? scope.root == null : root.equals(scope.root);
  }

  @Override
  public int hashCode() {
    return root == null ? 0 : root.hashCode();
  }
}
