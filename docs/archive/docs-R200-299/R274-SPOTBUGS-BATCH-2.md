# R274 — SpotBugs Batch 2 (DmBoxedPrimitiveForParsing, NpAlwaysNull, …)

R274 added 25 SpotBugs rules to `plumb-style`. pl-0296 through pl-0320.

## New Rules (25)

| ClassName | SpotBugs rule |
|-----------|----------------|
| DmBoxedPrimitiveForParsingCheck | DM_BOXED_PRIMITIVE_FOR_PARSING |
| NpAlwaysNullCheck | NP_ALWAYS_NULL |
| EcIncompatibleTypesCheck | EC_INCOMPATIBLE_TYPES |
| EcUnrelatedTypesCheck | EC_UNRELATED_TYPES_USED |
| EcBadArrayCompareCheck | EC_BAD_ARRAY_COMPARE |
| EsyncEmptySyncCheck | ESYNC_EMPTY_SYNC |
| FeFloatingPointCheck | FE_FLOATING_POINT |
| FiUselessBlockCheck | FI_USELESS_BLOCK |
| IcSuperclassGenericCheck | IC_SUPERCLASS_USES_SUBCLASSED_GENERIC |
| IcastIntegerMultiplyCastToLongCheck | ICAST_INTEGER_MULTIPLY_CAST_TO_LONG |
| ImAverageComputationCheck | IM_AVERAGE_COMPUTATION |
| ImBadCheckForOddCheck | IM_BAD_CHECK_FOR_ODD |
| IoResourceBothCheck | IO_RESOURCE_USED_AS_BOTH_INPUT_AND_OUTPUT |
| MfClassMasksFieldCheck | MF_CLASS_MASKS_FIELD |
| MfMethodMasksFieldCheck | MF_METHOD_MASKS_FIELD |
| NmConfusingInspectionCheck | NM_CONFUSING_INSPECTION |
| PzReuseEntryCheck | PZ_DONT_REUSE_ENTRY_OBJECTS_IN_ITERATORS |
| QbaQuestionableBooleanCheck | QBA_QUESTIONABLE_BOOLEAN_ASSIGNMENT |
| RvNegatingCompareToCheck | RV_NEGATING_RESULT_OF_COMPARETO |
| RvRemoveRepeatCallsCheck | RV_REMOVE_REPEAT_METHOD_CALLS |
| RvReturnValueIgnoredCheck | RV_RETURN_VALUE_IGNORED_BAD_PRACTICE |
| SiInstanceBeforeFinalCheck | SI_INSTANCE_BEFORE_FINALS_ASSIGNED |
| SwlSleepWithLockCheck | SWL_SLEEP_WITH_LOCK_HELD |
| UwfFieldNotInitializedCheck | UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR |
| WmiWrongMapIteratorCheck | WMI_WRONG_MAP_ITERATOR |

## Tests: 25

## Backup

`D:\work\tmp\r274_spotbugs_done\`
