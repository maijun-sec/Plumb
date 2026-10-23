# _archive/ — 已处理过程文档归档

本目录是 R-840 / R-841 / R-842 文档整理 + R-843 合并归档的产物。
所有过程文档从这里可找回: `mv _archive/{bucket}/{file}.md <原目录>/`

## 目录结构

子目录命名约定: `{原文档目录}-{R 轮次}`, 例如 `docs-R100-199/` 表示原本在 `docs/`, R100-R199 轮次.

| 子目录 | 原位置 | R 轮次 | 文件数 |
| --- | --- | --- | ---: |
| `_archive/docs-R100-199/` | `docs/` | R100-R199 | 83 |
| `_archive/docs-R200-299/` | `docs/` | R200-R299 | 81 |
| `_archive/docs-R300-399/` | `docs/` | R300-R399 | 34 |
| `_archive/docs-R600-699/` | `docs/` | R600-R699 | 1 |
| `_archive/docs-_misc/` | `docs/` | misc | 1 |
| `_archive/plumb-dfa-docs-R0-099/` | `plumb-dfa/docs/` | R0-R99 | 29 |
| `_archive/plumb-dfa-docs-R500-599/` | `plumb-dfa/docs/` | R500-R599 | 6 |
| `_archive/plumb-dfa-docs-R600-699/` | `plumb-dfa/docs/` | R600-R699 | 27 |
| `_archive/plumb-dfa-docs-_misc/` | `plumb-dfa/docs/` | misc | 4 |

**总计**: 266 文件, 2,026,638 bytes (1.93 MB)

## 源 README 内容（R-843 整理时合并进来）

### 源: `docs/_archive/README.md`

```
# docs/_archive/ — 已处理过程文档

本目录是 R-840 / R-841 / R-842 文档整理阶段**被产品 doc 完全覆盖**的过程日志归档。

## 目录结构

docs/_archive/
├── R0-099/      # R1-R99 早期单 bug 修复流水
├── R100-199/    # R100-R199 跨函数 taint / 注解 IR
├── R200-299/    # R200-R299 JPA/Annotation/并发
├── R300-399/    # R300-R399 style checker batch
├── R500-599/    # R500-R582 Coverity Java 移植
├── R600-699/    # R600-R683 comment cleanup / duplicate removal
└── _misc/       # 不带 R 号的文件 (e.g. ENVIRONMENT.md / match*.txt)

同理:
- plumb-dfa/docs/_archive/ — plumb-dfa 引擎 R27-R85x 单 bug 流水
- plumb-rules/docs/ 没有 _archive (RULE_AUTHORING.md 保留作参考)
- fathom-dfa/docs/ (13 个 R1-R24) 属于另一个项目，R1117 起不再进版本库（仍在磁盘上）

## 怎么处理

- 不需要看: 用户/开发者日常查文档走 doc/ (产品文档中心)
- 需要查历史: 本目录的 R 号文件名跟 git log / commit 关联, 按 R 轮次翻
- 找回某个文件: mv docs/_archive/R100-199/R136-SMOKE-TEST.md docs/ 即可
- 彻底删除: 用 git rm 加 commit (推荐) — 文件历史在 git 里仍然有
```

### 源: `plumb-dfa/docs/_archive/README.md`

```
# plumb-dfa/docs/_archive/ — plumb-dfa 引擎 R* 过程日志归档

本目录是 R-840 文档整理阶段**被产品 doc 完全覆盖**的 plumb-dfa 引擎 R* 单 bug 修复流水。

## 目录结构

plumb-dfa/docs/_archive/
├── R0-099/      # R27-R59 cross-fn summary / 跨函数 baseline
├── R500-599/    # R556-R592 stub checker sweep
├── R600-699/    # R601-R683 full Juliet / comment cleanup
└── _misc/       # 单 bug 流水无 R 号

## 跟产品 doc 的对应

| _archive 内容 | 已沉到产品 doc |
| --- | --- |
| R27-R59 跨函数 summary 优化 | doc/tech/CROSS-FUNCTION-ANALYSIS.md §3 Phase 1 |
| R601-R651 full Juliet eval | doc/tech/ARCHITECTURE.md §9 (390 dfa + 74 style) |
| R671-R683 comment cleanup | (纯 cleanup, 无产品价值) |
| AUDIT-checkers-2026-10-12.md (保留在顶层) | doc/tech/ARCHITECTURE.md §3.0 DFA SPI |
| R803-R837 时期 final report (保留在顶层) | doc/tech/ARCHITECTURE.md §8 / §12 |
| 单 bug 流水 (R27-R85x 大部分) | 移到 _archive/ |

## 怎么找回

mv plumb-dfa/docs/_archive/<bucket>/<file>.md plumb-dfa/docs/
```
