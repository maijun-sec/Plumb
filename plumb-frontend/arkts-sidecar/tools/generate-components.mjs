// Regenerate src/components.json from the ArkTS SDK's own declarations.
//
// WHY THIS IS A TOOL AND NOT A HAND-WRITTEN LIST. The list decides which
// names the compiler parses as components of the declarative UI DSL. A name
// that is missing from it is an ordinary call: the file still parses, the
// call still becomes a CALL, and the difference is only visible in the
// callee signature's arity. That is exactly the kind of difference nobody
// notices until a finding stops matching.
//
// So the list is DERIVED from the SDK's declarations, and the derivation is
// kept here so that it can be checked and repeated.
//
// WHAT IS A COMPONENT. In the SDK's declarations a component is declared as
// a constant:
//
//     declare const Text: TextInterface;
//     declare const Column: ColumnInterface;
//
// The classes next to it -- `TextAttribute`, `ColumnAttribute` -- are the
// attribute bags, not the components, and the `*Instance` constants are the
// attribute instances. Neither is a component.
//
// Run it with `node tools/generate-components.mjs` from the sidecar directory.

import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const sidecarRoot = resolve(here, "..");
const componentDirectory = resolve(
  sidecarRoot,
  "node_modules",
  "@arkts",
  "declarations",
  "dist",
  "component",
);

/** The names that are NOT components, even though they are declared next to them. */
const NOT_COMPONENTS = [/Instance$/, /Attribute$/];

const declaration = /^\s*declare\s+(?:const|function)\s+([A-Za-z_$][A-Za-z0-9_$]*)/gm;

function main() {
  let files;
  try {
    files = readdirSync(componentDirectory).filter((name) => name.endsWith(".d.ts"));
  } catch (e) {
    console.error(`the SDK's component declarations are not at ${componentDirectory}`);
    process.exit(1);
  }
  const names = new Set();
  for (const file of files) {
    const text = readFileSync(join(componentDirectory, file), "utf8");
    declaration.lastIndex = 0;
    let match;
    while ((match = declaration.exec(text)) !== null) {
      const name = match[1];
      if (NOT_COMPONENTS.some((pattern) => pattern.test(name))) {
        continue;
      }
      names.add(name);
    }
  }
  const sorted = [...names].sort();
  const target = resolve(sidecarRoot, "src", "components.json");
  writeFileSync(target, `${JSON.stringify(sorted, null, 2)}\n`, "utf8");
  console.log(`wrote ${sorted.length} component names to ${target}`);
  console.log(sorted.slice(0, 20).join(", "));
}

main();
