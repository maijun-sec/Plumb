# R275 — SpotBugs Batch 3 (IlContainerSequencing, RangeArrayLength, …)

R275 added 25 SpotBugs rules to `plumb-style`. pl-0321 through pl-0344.

## Key Rule: R275 fix for RangeArrayLength / RangeArrayOffset

The original checks looked for `MethodCallExpr` with name `length`,
but `.length` in JavaParser is a `FieldAccessExpr`, not a `MethodCallExpr`.
The fix in R275:

```java
if (!(arr.getIndex() instanceof FieldAccessExpr)) continue;
FieldAccessExpr fa = (FieldAccessExpr) arr.getIndex();
if (!"length".equals(fa.getNameAsString())) continue;
```

For `RangeArrayOffsetCheck`, the 4th argument (index 3) was wrong —
`System.arraycopy` signature is `(src, srcPos, dst, dstPos, length)`,
so the length is argument index **4**, not 3.

## New Rules (25)

IlContainerSequencingCheck, RangeArrayLengthCheck, RangeArrayOffsetCheck,
RangeStringLengthCheck, RangeStringIndexCheck, RangeArrayIndexCheck,
UcUselessObjectCheck, NpSyncOnUnsharedVarCheck, NpLoadOfKnownNullCheck,
BcEqualsNoNullCheckCheck, BcEqualsNoHashcodeCallCheck,
BcBadCastToAbstractCollectionCheck, EcNoReadIfEqualToWriteCheck,
EcUnrelatedInterfacesCheck, EcNullArgToVarargCheck, EcBadArrayStoreCheck,
NmSameSimpleNameAsSuperCheck, HeSignatureDeclaresHashcodeOfObjectCheck,
DmiHardcodedAbsolutePathCheck, DmiBadMonthCheck, DmiCollectionOfUrlsCheck,
DmiDoneCheck, DmiUselessMethodCheck, IilDereOfReadlineCheck.

## Tests: 25

## Backup

`D:\work\tmp\r275_done\`
