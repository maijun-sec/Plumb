# Support status - 03-pointer-multilevel

**Field-, array- and container-sensitive aliasing is sound, and its guard transfer is sound with it.** Allocation-site identity, field sensitivity, per-key container cells, cross-method returns and call-site context all resolve; every alias precision gate (12 TN) holds. R1113 found the one way guard transfer was still unsound: the rule suppressed a finding whenever *some* alias was guarded, but the alias map is a may-relation, so a guard on `first` silenced a dereference of a `List.get` result that could equally be an unguarded `second`. It now requires the guard to cover the receiver's whole points-to set. Two limits are recorded rather than hidden: an explicit `x.f = null` is invisible because the fact domain has no null element, and the AST rule cannot follow an alias at all.

## Measured

- True positives: **TP 1/1**
- True negatives: **TN 12/12 (2 known-limitation)**
- Scan mode: per-file, callgraph strategy: `global`.

## Outstanding cases

none

## Known limitations

`L6-unguarded-sibling-field`, `L6-AST-cannot-follow-the-alias`

Full per-case detail: `RESULT.json`. Expectations: `EXPECTATIONS.json`.
