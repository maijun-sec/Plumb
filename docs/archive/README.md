# docs/archive/ — 过程文档归档

本目录存放**过程文档**：按轮次记录的改动流水、审计报告、评测数据、覆盖率分析。

这些文档**不是规格**。它们记录的是某一时刻的快照，描述「某轮做了什么」，而不是「系统现在
是什么样」。需要了解当前系统行为时，请读 [`../tech/`](../tech/)、[`../rules/`](../rules/)、
[`../usage/`](../usage/)、[`../build/`](../build/)。

## 目录结构

子目录命名约定：`{原文档目录}-R<轮次范围>`。

| 子目录 | 原位置 | 轮次 | 文件数 |
| --- | --- | --- | ---: |
| `docs-R100-199/` | `docs/` | R100-R199 | 83 |
| `docs-R200-299/` | `docs/` | R200-R299 | 81 |
| `docs-R300-399/` | `docs/` | R300-R399 | 34 |
| `docs-R600-699/` | `docs/` | R600-R699 | 1 |
| `docs-_misc/` | `docs/` | misc | 1 |
| `plumb-dfa-docs-R0-099/` | `plumb-dfa/docs/` | R0-R99 | 29 |
| `plumb-dfa-docs-R500-599/` | `plumb-dfa/docs/` | R500-R599 | 6 |
| `plumb-dfa-docs-R600-699/` | `plumb-dfa/docs/` | R600-R699 | 27 |
| `plumb-dfa-docs-_misc/` | `plumb-dfa/docs/` | misc | 4 |

各轮次段的大致主题：

| 轮次段 | 主题 |
| --- | --- |
| R0-R99 | 早期单 bug 修复、跨函数 summary 基础 |
| R100-R199 | 跨函数 taint、注解 IR、call graph 策略 |
| R200-R299 | JPA / Annotation / 并发 checker、Z3 路径条件 |
| R300-R399 | style checker 批量新增、source-to-sink path 设计 |
| R500-R599 | Coverity Java 规则移植、stub checker sweep |
| R600-R699 | 全量 Juliet 评测、注释清理、重复 checker 移除 |
| R800+ | 优化、覆盖率、trace 审计、testcase 分类测量 |

## 怎么用

- **日常查文档** → 不看这里，走 `docs/README.md` 的产品文档索引。
- **查历史** → 文件名的 R 号与 git log / commit 关联，按轮次翻。
- **找回某份文件** → `mv docs/archive/<bucket>/<file>.md docs/process/`，然后按内容性质决定
  放 `process/` 还是 `tech/`。
- **彻底删除** → `git rm` + commit，历史仍在 git 里。

## 归档不等于已验证

归档的前提是「结论已沉淀到产品文档」。这个前提**曾经不成立**：本目录里的多份文档描述的类名
（例如 `TaintAnalysis`、`CallResolver`、`TaintCatalog`）在当前代码库中并不存在，文档描述的
是更早的代码形态。

因此从本目录引用任何技术结论前，**必须回到源码验证**。同理，若某份归档文档的结论需要成为
产品文档的一部分，应当按当前代码重新验证一次再写进去，并去掉轮次编号。