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
package org.plumb.main;

import java.util.List;

/**
 * One entry of a compilation database: a compile that happened, and the unit it was about.
 *
 * <p><b>Why this is read rather than taken from the captures.</b> The compilation database is the
 * interchange format: it is what a build hands to an analyser, and it is what an analyser written
 * against another tool's output would hand over as well. So the analyser reads the DATABASE, not
 * the agent's private capture files. That is what makes the two tools interchangeable -- the
 * analyser does not care which one produced the database.
 *
 * @param directory the compile's working directory
 * @param arguments the compiler and its arguments, one token per element
 * @param file the compilation unit's path
 */
public record CompilationDatabaseEntry(String directory, List<String> arguments, String file) {

  public CompilationDatabaseEntry {
    arguments = arguments == null ? List.of() : List.copyOf(arguments);
  }
}
