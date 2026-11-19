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

/**
 * The repair of a class body the compiler truncated.
 *
 * <h2>What the compiler does</h2>
 *
 * <p>The compiler's syntax tree for a class whose body holds a nested class is
 * TRUNCATED at the nested class: the outer class keeps the members that came
 * BEFORE it and loses every member that came after it. The members that were
 * lost do not disappear -- they come back as nodes of the FILE, and they come
 * back in pieces: a method's signature and its body become two separate
 * file-level nodes, neither of which has any children.
 *
 * <p>So the loss is threefold at once, and none of the three parts is visible
 * in the tree:
 *
 * <ul>
 *   <li>the outer class's own members are gone -- including every sink in
 *       them;
 *   <li>the fragments that come back are lowered as MODULE-level code, so the
 *       side car invents a function that the source does not have, and invents
 *       calls whose keys name methods the source does not have at that level;
 *   <li>a sink inside a lost member is not merely unreported: the fragment
 *       that holds it has no call expression in it, so the sink is not
 *       lowered as a call at all.
 * </ul>
 *
 * <h2>What this module does</h2>
 *
 * <p>The truncation is a property of the COMPILER, not of the side car, and it
 * happens because a nested class sits inside the outer class. So the repair is
 * to take the nested class out of the outer class -- in the TEXT the compiler
 * reads -- and let the compiler build the outer class without it:
 *
 * <pre>
 *   class Outer {          class Outer {
 *     class Inner {  -&gt;     |  (the inner class, blanked out)
 *       go() {}                |
 *     }                        |
 *     m() { sink() }           m() { sink() }
 *   }                         }
 * </pre>
 *
 * <p>The blanking keeps the LENGTH and the LINE COUNT of the text, so every
 * position in the repaired tree is the position in the original source: the
 * findings the repair produces point at the lines the reader can see.
 *
 * <p>The nested class itself is not lost by this: the compiler's own tree has
 * it, complete, as a node of the file. It is lowered from there.
 */

import {
  createScanner,
  isClassDeclaration,
  isClassExpression,
  SyntaxKind,
} from "typescript/unstable/ast";

/**
 * A class whose body the compiler truncated.
 *
 * @typedef {object} TruncatedClass
 * @property {object} node the class node, as the compiler gave it
 * @property {number} bodyStart the offset of the class body's opening brace
 * @property {number} bodyEnd the offset just past the class body's closing brace
 */

/**
 * The classes of a file whose body the compiler truncated.
 *
 * <p>The nested class of a truncated class is a node of the FILE, not a member
 * of the outer class, so the search has to look at the file's own statements
 * rather than at the members of the class.
 *
 * @param {object} sourceFile the compiler's source file
 * @param {string} text the source text
 * @return {TruncatedClass[]} the truncated classes
 */
export function truncatedClasses(sourceFile, text) {
  const out = [];
  const statements = sourceFile?.statements ?? [];
  for (const statement of statements) {
    if (!isClassNode(statement)) continue;
    const membersEnd = statement.members ? statement.members.end : undefined;
    if (typeof membersEnd !== "number" || typeof statement.end !== "number") continue;
    // The compiler closes a class it built completely: the class's own closing
    // brace comes after the last member. A class it cut short ends where its
    // member list ends.
    if (statement.end > membersEnd) continue;
    const body = classBodyRange(text, statement);
    if (!body) continue;
    out.push({ node: statement, bodyStart: body.open, bodyEnd: body.close });
  }
  return out;
}

/**
 * The nodes of the file that a truncated class used to own.
 *
 * <p>They are the file's own nodes whose text lies inside the class body: the
 * nested classes, and the fragments of the members that came after them.
 *
 * @param {object} sourceFile the compiler's source file
 * @param {TruncatedClass[]} truncated the truncated classes
 * @return {object[]} the nodes, in source order
 */
export function membersOfTruncatedClasses(sourceFile, truncated) {
  const out = [];
  const statements = sourceFile?.statements ?? [];
  for (const statement of statements) {
    const start = statement.getStart?.(sourceFile);
    if (typeof start !== "number") continue;
    for (const entry of truncated) {
      if (start < entry.bodyStart || start >= entry.bodyEnd) continue;
      out.push(statement);
      break;
    }
  }
  return out;
}

/**
 * The text with the nested classes blanked out.
 *
 * <p>Only the nested CLASSES are blanked. The fragments of the other members
 * are left alone: they are exactly what the compiler needs to put the outer
 * class back together, and blanking them would throw the members away instead
 * of restoring them.
 *
 * @param {string} text the source text
 * @param {object[]} nodes the nested class nodes
 * @param {object} sourceFile the compiler's source file, for the positions
 * @return {string} the repaired text
 */
export function blankNestedClasses(text, nodes, sourceFile) {
  if (!nodes || nodes.length === 0) return text;
  const chars = [...text];
  for (const node of nodes) {
    const start = node.getStart?.(sourceFile);
    const end = node.end;
    if (typeof start !== "number" || typeof end !== "number") continue;
    for (let i = start; i < end && i < chars.length; i += 1) {
      // The line count has to survive: a blanked line that swallowed its
      // newline would move every line below it.
      if (chars[i] !== "\n") chars[i] = " ";
    }
  }
  return chars.join("");
}

/**
 * The class body's braces, found by matching them in the text.
 *
 * <p>The compiler's node for a truncated class ends where the truncation
 * starts, so the node cannot say where the body ends. The TEXT can: the body's
 * closing brace is the one that matches the class's opening brace.
 *
 * @param {string} text the source text
 * @param {object} node the class node
 * @return {{open: number, close: number}|null} the brace offsets, or null when
 *     they cannot be matched
 */
export function classBodyRange(text, node) {
  if (typeof text !== "string" || !text.length) return null;
  const start = node.getStart?.();
  if (typeof start !== "number") return null;
  const scanner = createScanner(true, undefined, text);
  scanner.setText(text, start);
  let depth = 0;
  let opened = false;
  let open = -1;
  for (;;) {
    const kind = scanner.scan();
    if (kind === SyntaxKind.EndOfFileToken) break;
    if (kind === SyntaxKind.OpenBraceToken) {
      depth += 1;
      if (!opened) {
        opened = true;
        open = scanner.getTokenStart();
      }
      continue;
    }
    if (kind === SyntaxKind.CloseBraceToken) {
      depth -= 1;
      if (opened && depth === 0) {
        return { open, close: scanner.getTokenEnd() };
      }
    }
  }
  return null;
}

/**
 * Whether a node is a class the compiler put at the file's level.
 *
 * @param {object} node the node
 * @return {boolean} true when it is a class
 */
function isClassNode(node) {
  return isClassDeclaration(node) || isClassExpression(node);
}
