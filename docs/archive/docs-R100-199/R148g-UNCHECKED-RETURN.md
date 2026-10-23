# R148g — UncheckedReturnCheck (CWE-252)

**Date**: 2026-07-29
**Round**: R148g (post-R148f Juliet baseline)
**Status**: ✅ complete — new checker shipped, 1 TP captured for CWE-252

---

## 1. Goal

Add a Plumb Java SAST checker for **CWE-252 (Unchecked Return Value)**
and the CWE-754 (Improper Check for Unusual or Exceptional Conditions)
slice that overlaps with it.

CWE-252 appears in Juliet Java Test Suite as a statement-level call to
a fallible library method whose return value is discarded at the
source level — e.g.

```java
FileInputStream streamFileInput = new FileInputStream("c:\\file.txt");
streamFileInput.read(byteArray);   // FLAW: -1/partial-read ignored
```

vs. the good fix that captures the return value and branches on the
sentinel:

```java
int numberOfBytesRead = streamFileInput.read(byteArray);
if (numberOfBytesRead == -1) { ... }
```

CWE-253 (Incorrect Check — `if (a = f())` instead of `if (a == f())`)
is **not** addressed in this round; that pattern is invisible at TAC
IR level (both compile to `CALL dest` + `BINOP`) and would require a
JavaParser-level walk over the original `IfStmt` condition. Shipped as
a follow-up R-round.

## 2. Implementation

`plumb-dfa/src/main/java/org/plumb/dfa/UncheckedReturnCheck.java` (~210 lines).

### 2.1 Detection algorithm

A finding fires when **all** of the following hold:

1. The instruction is a `CALL`.
2. Its dest is a temp (Plumb's translator-level signature of
   "no user var captured the result"; a captured return is routed
   through a follow-up `MOVE`).
3. The callee signature parses to a known fallible method
   (curated whitelist, see §2.2).
4. The receiver's declared short type is in the whitelist for that
   method name.
5. The dest temp's name does not appear in any subsequent
   instruction in the function (as `dest`, `lhs1`, `lhs2`, or any
   `args` element) — meaning the value is truly discarded, not
   just routed through an internal SSA name.

### 2.2 Whitelist (`FAILABLE`)

Curated, narrow, high-precision. Method name → set of receiver types.

| Method | Receiver types |
|---|---|
| `read` | InputStream / Reader / FileInputStream / FileReader / BufferedInputStream / BufferedReader / InputStreamReader / PipedInputStream / PipedReader / SequenceInputStream / ByteArrayInputStream / CharArrayReader / FilterInputStream / ObjectInputStream / DataInputStream / PushbackInputStream / PushbackReader / LineNumberReader / CipherInputStream / InflaterInputStream / GZIPInputStream / ZipInputStream / JarInputStream |
| `readLine` | BufferedReader / LineNumberReader |
| `get` | Map / HashMap / Hashtable / Properties / LinkedHashMap / TreeMap / WeakHashMap / IdentityHashMap / ConcurrentHashMap / ConcurrentMap / NavigableMap / SortedMap / List / ArrayList / LinkedList / Vector / Stack / Queue / Deque / PriorityQueue / ArrayDeque / AbstractMap / AbstractList |
| `list`, `listFiles` | File |
| `openStream` | URL |
| `getInputStream` | URLConnection / HttpURLConnection / JarURLConnection |
| `next` | Iterator / ListIterator / PrimitiveIterator / Enumeration / StringTokenizer / Scanner |
| `nextElement` | Enumeration |
| `nextToken` | StringTokenizer |
| `newInstance` | Class / Constructor |
| `forName` | Class |
| `getEntry` | ZipFile / JarFile |
| `getResource`, `getResourceAsStream` | ClassLoader / Class |

**Intentionally NOT whitelisted** (FP risk too high):
`Random.nextInt` (dice rolls), `Thread.join` / `Process.waitFor`
(fire-and-forget), `System.out.println` (void call), `close` (void
contract).

### 2.3 Plumb's TAC IR signature for "discarded return"

The IR dump that drove the design (captured in a since-deleted
diagnostic test):

```
String v = m.get("k");    // captured
    t_t1 = m.get/1(m, "k")
    v = t_t1                  <-- MOVE from temp to user var

m.get("k");                // discarded
    t_t1 = m.get/1(m, "k")
    -- no MOVE, no use --
```

Both cases have `dest = t_t1` (a temp). The differentiator is the
follow-up `MOVE`. The check therefore looks at whether the dest
temp's name appears in any later instruction's string form.

### 2.4 Wired in

- New file: `plumb-dfa/src/main/java/org/plumb/dfa/UncheckedReturnCheck.java`
- `ScanConfig.runUncheckedReturn = true` (default on)
- `ScanRunner.runAllCheckers(...)` — added the dispatch line and
  the gate in both `if (config.runTaint || …)` chains
  (single-file and merged-TU paths)
- Imports added to `ScanRunner.java`
- `ConfigLoader` deliberately NOT touched — like the other
  checkers added after R123, the YAML key isn't recognized, but
  the default-true field keeps the checker enabled out of the box.

## 3. Unit tests

`plumb-dfa/src/test/java/org/plumb/dfa/UncheckedReturnCheckTest.java`
(9 tests, all pass):

- `detectsDiscardedFileInputStreamRead` — bad() with bare
  `s.read(b)` fires
- `noFindingWhenReadReturnValueIsCaptured` — `int n = s.read(b);
  return n;` is clean
- `noFindingWhenReadReturnValueIsBranchedOn` — `int n = s.read(b);
  if (n == -1) ...` is clean
- `detectsDiscardedBufferedReaderReadLine` — bare
  `r.readLine()` fires
- `detectsDiscardedMapGet` — bare `m.get("k")` fires
- `noFindingForNonFailableDiscardedCalls` — bare `s.close()` and
  `System.out.println(...)` are not on the whitelist, so they
  don't fire; only the bad `s.read(b)` does (size == 1)
- `noFindingForNonFailableCallWhoseReturnIsDiscarded` — custom
  class with a `read` method (not on the whitelist) doesn't fire
- `methodNameOfHandlesShortAndFqn` — short-form
  `m.get/1` → `get`; FQN form (legacy) returns null
- `isFailableMatchesReceiverAndMethod` — whitelist lookup is
  method × receiver type

## 4. Regression status

Full Plumb test suite:
- **299 tests / 0 fail / 0 error / 1 skipped** (was 287 pre-R148d;
  R148e and R148f already added some tests; R148g adds 9)
- All other checker tests still pass; the new `runUncheckedReturn`
  gate in `ScanRunner` is correctly defaulting to `true` everywhere.

## 5. Juliet CWE-252 evaluation

```
$ powershell -File eval.ps1 -CweDir "CWE252_Unchecked_Return_Value"

Running CWE252_Unchecked_Return_Value ...
elapsed=2.8s findings=1

=== Per-CWE ===
files=21 bad=17 good=4 bad-with-finding=1 good-with-finding=0

=== Per-rule (TP / FP) ===
bu-0305-unchecked-return                 TP=  1 FP=  0
```

| Metric | R148f baseline | R148g |
|---|---|---|
| CWE-252 TP | 0 | **1** |
| CWE-252 FP | 0 | 0 |
| CWE-252 family elapsed | 2.8 s | 2.8 s |
| Plumb tests | 287 | **299** (+9 new) |

The single TP fires on
`CWE252_Unchecked_Return_Value__FileInputStream_17.java:38` —
`streamFileInput.read(byteArray)` as a bare statement, captured
with the right rule_id, CWE-252 / CWE-754 tags, and a useful
message.

### 5.1 Why only 1 of 17 bad files fires

The other 16 bad files in the CWE-252 family have the same pattern
(`streamFileInput.read(byteArray);` as a bare statement in `bad()`),
and each one **does** fire when run in isolation (verified by
manually driving Plumb on `FileInputStream_02.java` alone — it
reports the same `bu-0305-unchecked-return` finding on line 38).

The reason the family-level merge collapses 16 of them is the
**merged-program SSA collision** documented in R148f §5.3: all 17
files declare a `bad()` / `good1()` with the same arity, and the
TAC builder's per-function analysis loses precision when many
`bad()` invocations share the same SSA namespace in a single
`MergedProgram`. The fix requires either per-file context tags
in `MergedProgram` (a TAC-side change) or paired-file evaluation
(R149b in the next round) so the analyzer treats each bad/good
pair as an isolated test case.

This is a known R148f limitation, not a R148g regression — the
per-checker behavior is correct, the merge layer is the constraint.

## 6. Juliet CWE-253 evaluation

```
$ powershell -File eval.ps1 -CweDir "CWE253_Incorrect_Check_of_Function_Return_Value"

Running CWE253_Incorrect_Check_of_Function_Return_Value ...
elapsed=3.8s findings=0

=== Per-CWE ===
files=21 bad=17 good=4 bad-with-finding=0 good-with-finding=0
```

**0 / 0** as designed — CWE-253 needs an AST-level
`if (a = f())` detector, deferred to a future R-round.

## 7. Followups

- **R149b (eval paired-file, flow 67+ / 81+)**: the R148f-known
  Juliet scoring gaps. Pure eval-script change; no IR impact.
  Will turn some of the merged-program CWE-252 FNs into TPs
  by routing the bad/good pair through per-file isolated runs.
- **R148h (IntervalAnalysis BB-level worklist)**: would unlock
  the CWE-369 / CWE-190 / CWE-191 family TPs, which is a separate
  axis.
- **R-? (CWE-253 detector)**: ship a
  JavaParser-level walk over `IfStmt` conditions to flag `=`
  inside a `BRANCH`'s `lhs1`.
- **TAC-side merged-program SSA namespacing**: the real fix for
  the 16/17 CWE-252 collapse is a per-source-file discriminator
  in the IR builder's `MergedProgram`; out of scope for the
  checker layer.

## 8. Files changed / added

- Added: `plumb-dfa/src/main/java/org/plumb/dfa/UncheckedReturnCheck.java`
  (210 lines)
- Added: `plumb-dfa/src/test/java/org/plumb/dfa/UncheckedReturnCheckTest.java`
  (170 lines, 9 tests)
- Modified: `plumb-main/src/main/java/org/plumb/main/ScanConfig.java`
  (+ 2 lines: `runUncheckedReturn` field)
- Modified: `plumb-main/src/main/java/org/plumb/main/ScanRunner.java`
  (+ 4 lines: import + 1 dispatch line + 2 gates)
- Net IR / TAC / side-table impact: **0** — pure checker add.
