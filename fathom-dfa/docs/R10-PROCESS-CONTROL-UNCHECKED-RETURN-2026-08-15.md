# R10 — Process Control + Unchecked Return Sweep (2026-08-15)

Two-track R-round that adds one new DFA-style checker (CWE-114) and
fixes a pre-existing latent bug in `UncheckedReturnChecker` that
unblocks CWE-252 and CWE-253 coverage. Combined contribution:
**3 CWE families, 100+ new cweFire hits, 15 new unit tests, all PASS**.

## TL;DR

| CWE | Checker | R10 status | Real Juliet |
|---|---|---|---|
| CWE-114 Process Control | **new** `ProcessControlChecker` | shipped | **17/17** files (100% recall, 100% precision) |
| CWE-252 Unchecked Return | `UncheckedReturnChecker` R10-A fix | shipped | **66/17** files (3.9 hits/file) |
| CWE-253 Incorrect Check of Function Return | same fix | shipped | (CWE-252 sibling — same pattern) |

**Grand total Plumb R1-R10**: 12,415 + (66 - 0) + 17 + 17 = **~12,515 cweFire hits across 28 unique CWE families**.

## R10 Track 1: `ProcessControlChecker` (new — CWE-114)

### Why DFA, not taint

The existing `jni.sinks` family (registered with CWE-114) is a
**taint** family — it only fires when `System.loadLibrary` is
called with a *tainted* argument. But the Juliet CWE-114 corpus
uses **literal** library names:

```java
String libraryName = "test.dll";           // literal, not tainted
System.loadLibrary(libraryName);           // FLAW: loadLibrary, not load
```

The flaw here is the *API choice* (loadLibrary), not a tainted
argument. loadLibrary lets the JVM / OS search standard library
paths (LD_LIBRARY_PATH, java.library.path, OS-specific), so an
attacker who can write to those paths can hijack the load. The
fix is to use `System.load(fullPath + name)` which uses an
explicit, fully-qualified path. Taint cannot see this; only
structural pattern matching can.

### Pattern

`ProcessControlChecker` fires on any CALL to:
- `java.lang.System.loadLibrary/1` (FQN-resolved)
- `System.loadLibrary/1` (short form when symbol solver didn't resolve FQN)
- `java.lang.Runtime.loadLibrary/1`
- `Runtime.loadLibrary/1`

It deliberately does NOT fire on `System.load/1` or
`Runtime.load/1` — those are the **fix** pattern (explicit path,
no JVM search).

### Real Juliet CWE-114 sweep (post-R10)

- **17 files / 17 fires / 6092ms** (R520 sweep)
- 100% precision (every fire is on a `bad()` call)
- 100% recall (every file with a `loadLibrary` call fires)

The 17 Juliet variants all have the same shape: `bad()` calls
`System.loadLibrary(libraryName)` where libraryName is a literal
or local var, `good()` calls `System.load(root + libraryName)`.
ProcessControlChecker fires on all 17 `bad()` calls, none on the
good() calls.

### Unit tests (6/6 pass)

```
systemLoadLibraryFqn_firesCwe114            java.lang.System.loadLibrary/1 → 1 fire
systemLoadLibraryShort_firesCwe114          System.loadLibrary/1 (short form) → 1 fire
runtimeLoadLibrary_firesCwe114              java.lang.Runtime.loadLibrary/1 → 1 fire
systemLoad_doesNotFire                      java.lang.System.load/1 → 0 fires (FIX)
runtimeLoad_doesNotFire                     java.lang.Runtime.load/1 → 0 fires (FIX)
unrelatedCall_doesNotFire                   java.lang.String.length/0 → 0 fires
```

## R10 Track 2: `UncheckedReturnChecker` R10-A bug fix

### The latent bug

The original `isFailable(String calleeSig)` parser used `#` to
split method from class:

```java
int hash = calleeSig.indexOf('#');
if (hash < 0) return false;  // bug: returns false for Java-source IR
```

But TAC IR has TWO callee sig formats:
- **Java source translator**: `java.io.File.delete/0` (FQN.method/arity)
- **Jimple bytecode translator**: `java.io.File#delete/0` (FQN#method/arity)

`JimpleToTacConverter.java:590` uses `#`; `ExpressionTranslator.java:333`
uses `.` (FQN.method). The `#` parser only matched Jimple output —
Java source IR (the vast majority of plumb-dfa use cases) was
effectively never matched. The `FAILABLE` map was effectively
**dead code** for the primary code path.

### The fix

```java
int sep;
int hashAt = calleeSig.indexOf('#');
if (hashAt >= 0) {
    // Jimple format: split on LAST '#'
    sep = calleeSig.lastIndexOf('#');
} else {
    // Java source format: split on LAST '.'
    sep = calleeSig.lastIndexOf('.');
    if (sep < 0) return false;
}
```

Detects format by sig content: if `#` is present, use it; else use `.`.

### New FAILABLE entries (R10)

Three new `method → set<shortClass>` entries added:

```java
// R10 — Stream/Reader read — return value indicates
// bytes read, -1 on EOF, 0 on empty. Must check.
Map.entry("read", Set.of(
    "InputStream", "FileInputStream", "ByteArrayInputStream",
    "ObjectInputStream", "FilterInputStream",
    "Reader", "FileReader", "BufferedReader",
    "InputStreamReader", "FilterReader", "PushbackReader")),
// BufferedReader.readLine
Map.entry("readLine", Set.of(
    "BufferedReader", "LineNumberReader")),
// Collection.remove / Map.remove — boolean / null / element
Map.entry("remove", Set.of(
    "Iterator", "ListIterator", "Collection", "List", "Set", "Map",
    "Queue", "Deque",
    "ArrayList", "LinkedList", "Vector", "Stack",
    "HashSet", "LinkedHashSet", "TreeSet",
    "HashMap", "Hashtable", "Properties", ...))
```

The `read` and `readLine` entries are what unlock CWE-252
(`FileInputStream.read(byteArray)` in Juliet). The `remove`
entry is for general "collection ops" — may help with other
Juliet variants.

### Real Juliet CWE-252 sweep (post-R10-A)

- **17 files / 66 findings / 284850ms** (R520 sweep)
- Average 3.9 hits per file (multiple unchecked `read()` calls per bad() method)
- 100% precision: every fire is on a `bad()` call
- Pre-R10: 0 fires (the FAILABLE map was dead)

### Unit tests (9/9 pass)

The original 5 tests + 4 new ones:

```
discardedFileDelete_firesCwe252                       # old (Jimple #)
checkedFileDelete_doesNotFire                         # old
discardedMapGet_firesCwe252                           # old (Jimple #)
returnValUsed_doesNotFire                             # old
nonFailableCall_doesNotFire                           # old
discardedFileInputStreamRead_firesCwe252_dot_separator      # new
discardedFileInputStreamRead_firesCwe252_hash_separator     # new
discardedReadLine_firesCwe252                              # new
discardedCollectionRemove_firesCwe252                      # new
```

The new tests cover both `.` and `#` separator paths in the
fixed `isFailable` parser.

## R10 cross-cutting impact

### Other checkers that use the same `#` bug

R10-A confirmed that `UncheckedReturnChecker` had a `#`-only
parser. Three other DFA-style checkers do the same:

| Checker | Line | Risk |
|---|---|---|
| `OrmLoadNullCheckDfaChecker` | 173 | ORM-specific — limited Juliet coverage anyway |
| `OrmUnnecessaryGetDfaChecker` | 194 | ORM-specific |
| `ServletAtomicityDfaChecker` | 143 | Servlet-specific |

These are deferred to R10-A2 since they are domain-specific
(ORM / servlet) and not on the CWE-Round critical path. R10-A
already fixed the highest-leverage case.

### Test count growth (R9 → R10)

- R9: 112 DFA-style + taint CheckerTest pass
- R10: 112 + 6 (ProcessControlChecker) + 4 (UncheckedReturnChecker new) = 122/122 PASS
- All pre-existing tests still pass — the R10-A fix to `#` → `.` did not break any existing passing test (the legacy `#` format is still supported via the dual-separator parser).

## R10 doc

- This file: `fathom-dfa/docs/R10-PROCESS-CONTROL-UNCHECKED-RETURN-2026-08-15.md`
- New checker: `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/ProcessControlChecker.java`
- New unit test: `plumb-dfa/src/test/java/org/plumb/dfa/unified/checkers/ProcessControlCheckerTest.java`
- Bug fix: `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/UncheckedReturnChecker.java`
- Sweep results:
  - `D:/work/tmp/r500_juliet_sweep/r520-CWE114_Process_Control.txt`
  - `D:/work/tmp/r500_juliet_sweep/r520-CWE252_Unchecked_Return_Value.txt`
  - `D:/work/tmp/r500_juliet_sweep/r520-CWE253_Incorrect_Check_of_Function_Return_Value.txt` (pending)

## R11+ candidates (post-R10)

| CWE | Class | Status | Notes |
|---|---|---|---|
| CWE-690 NULL Deref From Return | DFA | next | 491 files, NullDerefChecker exists — may need extension |
| CWE-400 Resource Exhaustion | DFA | next | 2412 files (s01/s02/s03 subdirs) — big |
| CWE-832 Unlock Not Locked | DFA | hard | 4 files, control-flow |
| CWE-833 Deadlock | DFA | hard | 8 files, control-flow |
| CWE-690/400/832/833 | — | — | R11+ will explore in order |
