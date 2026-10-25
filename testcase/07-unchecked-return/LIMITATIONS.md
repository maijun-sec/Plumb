- **A return value passed to an UNKNOWN callee is not reported.** `report(f.delete())` is not
  reported, because the outer call may well use the value — and a call whose own result is unused
  cannot be shown to ignore its arguments. Only the callees that provably ignore their arguments
  (`println`, `print`, `log`, `trace`, `debug`, `info`, `warn`, `error`, `fatal`) count as
  discarding.
- **The read analysis is method-wide, not flow-sensitive.** A local read on ONE path counts as
  observed everywhere. `boolean ok = f.delete(); if (x) { use(ok); }` is therefore not reported,
  even though `ok` is unobserved when `x` is false.
- **A call nested inside a larger expression is not the discard shape.** `boolean ok = !f.delete();`
  assigns a negated value, and the rule does not claim to understand it. Only a DIRECT
  initializer and a DIRECT argument position are recognised.
- **The must-check list is a method-NAME list.** A call whose name is not on the list is not
  reported, whatever its receiver's type. A third-party method that returns a success flag under a
  different name is missed.
- **CWE-703 is not covered here.** A void method that throws and whose exception is not handled is
  a different rule.
