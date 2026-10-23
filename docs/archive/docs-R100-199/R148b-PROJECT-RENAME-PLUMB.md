# R148b — Project Rename: Fathom-Java → Plumb

**Date**: 2026-07-29
**Round**: R148b (1 round, 1 脚本)
**Type**: Repo rename (跟 R148-meta 同一类操作)

## 背景

R148-meta (2026-07-28) 把 `hello-java-sast` 改名为 `Fathom-Java`, 跟 C++ 的 `Fathom-Cxx`
对齐成 "Fathom" 系列。1 round 完成, 281 tests / 0 fail。

但 R148 后用户改变方向:
- **不再在 Fathom 父目录里混 Cxx 跟 Java** (用户的 `Fathom/` 父目录是另一工作流, 不归
  本仓库管, 直接弃用)
- **Java 项目另外取个名字, 不叫 Fathom 了** (用户原话)
- **不需要带 `-java` 后缀** (既然 cxx/java 已经区分成两个项目, 项目名就一个词)

候选名字 6 个, 用户选 **Plumb**:
- 测深/探测系: sounding-java, plumb-java
- 守护系: sentinel-java, warden-java
- 数据流/SAST 直白系: trace-java, sast-java

选 Plumb 的理由 (用户): "plumb" 是 "测深铅锤", 跟 fathom 同根词 (都跟测深有关), 简洁,
一个词解决命名, 跟 C++ 的 `Fathom-Cxx` 在命名风格上不强行对齐。

## 命名约束 (跟 R148 区别)

| 维度         | R148 (Fathom-Java)         | R148b (Plumb)        |
|------------|---------------------------|----------------------|
| 顶层目录       | `Fathom-Java/`             | `Plumb/`             |
| 模块名        | `fathom-java-{X}`          | `plumb-{X}`          |
| Maven artifactId | `fathom-java-X`        | `plumb-X`            |
| Maven groupId | `org.fathom.java`        | `org.plumb` (去 .java) |
| Java package | `org.fathom.java.*`      | `org.plumb.*` (去 .java) |
| Fat jar    | `fathom-java-main-...-all.jar` | `plumb-main-...-all.jar` |
| mainClass  | `org.fathom.java.main.SastMain` | `org.plumb.main.SastMain` |

**关键不同**: groupId 跟 package 都不带 `.java` 后缀, 跟 C++ 的 `org.fathom.cxx` 不对称
(Java 是 `org.plumb` 单层, C++ 是 `org.fathom.cxx` 双层)。这是用户的选择, 简化命名。

## 4 维 rename 实施

### 文本替换 (5 维, case-sensitive 顺序)

按 "长前缀先做" 原则, 避免部分匹配破坏:
```
1. FATHOM-JAVA      -> plumb      (大写连字符)
2. Fathom-Java      -> Plumb      (PascalCase)
3. org.fathom.java  -> org.plumb  (长串, 含 ., 跟 fathom 词有冲突, 必须先做)
4. fathom-java      -> plumb      (artifactId)
5. fathom           -> plumb      (单词级, 最后做, 处理 .idea/ 跟零散引用)
```

**故意不动**: 大写 `Fathom` 单个词 (60 文件 222 次, 主要是 "Inspired by Fathom" 描述跟
历史 R* doc)。保留 Fathom 在 inspiration context 的引用, 跟 R148 经验一致
("R147 doc + R146 doc + R115-R145 docs 历史路径不动")。

### 目录重命名 (3 步)

```
1. Fathom-Java/                        -> Plumb/
2. fathom-java-{parser,tac,dfa,rules,
   style,reporter,main}/ (7 个)         -> plumb-{...}/
3. {module}/src/{main,test}/java/
   org/fathom/java/  (28 处)            -> {module}/src/{main,test}/java/org/plumb/
```

第 3 步的 28 = 7 模块 × 2 (main/test) × 2 (src/target, target 也重命名了但之后会 mvn clean)。

## 关键 bug 修复 (脚本第一版 vs 第二版)

第一版 `__rename_r148b.py` 在 step 4 (package path rename) 失败:

```python
# 第一版
if 'fathom' + sep + 'java' in rel:
    # 任何 path 包含 'fathom/java' 都会触发
    # 比如 'fathom/java/dfa/cg' 跟 'fathom/java/dfa' 都会被处理
```

**问题**: os.walk(topdown=False) 自底向上遍历时, 多个不同深度的 path 都满足
`'fathom/java' in rel`, 第一次 rename 成功后, 后续迭代拿到的 `dirpath` 已经指向不存在的
旧路径 (`FileNotFoundError: 系统找不到指定的路径`)。

**修复**: 改成精确匹配 path 后缀:
```python
# 第二版
if rel.endswith('fathom' + sep + 'java') or rel == 'fathom' + sep + 'java':
    # 只在 path 精确以 'fathom/java' 结尾时处理
```

第二版 (`__fix_package_path.py` 补丁) 一次性 rename 28 处全部成功。

**经验**: `os.walk` + in-place rename 时, 判断条件必须基于 **path 后缀** (或前缀),
不能用 `in rel`, 否则会在 rename 中途崩。

## 验证

### Maven build

```
mvn -o clean install -DskipTests
[INFO] Reactor Summary for plumb 0.1.0-SNAPSHOT:
[INFO] plumb .............................................. SUCCESS [  0.247 s]
[INFO] plumb-parser ....................................... SUCCESS [  3.128 s]
[INFO] plumb-tac .......................................... SUCCESS [  2.750 s]
[INFO] plumb-rules ........................................ SUCCESS [  0.742 s]
[INFO] plumb-dfa .......................................... SUCCESS [  2.982 s]
[INFO] plumb-style ........................................ SUCCESS [  1.614 s]
[INFO] plumb-reporter ..................................... SUCCESS [  0.476 s]
[INFO] plumb-main ......................................... SUCCESS [ 11.271 s]
[INFO] BUILD SUCCESS
```

8 modules 全部 SUCCESS, fat jar `plumb-main-0.1.0-SNAPSHOT-all.jar` 正确生成。

### Maven test

```
mvn -o test
[INFO] Tests run: 242, Failures: 0, Errors: 0, Skipped: 1  (plumb-dfa)
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0   (plumb-style)
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0    (plumb-reporter)
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0    (plumb-main)
[INFO] ... (plumb-tac 19, plumb-parser 3, plumb-rules 2)
[INFO] BUILD SUCCESS
```

**281 tests / 0 fail / 1 documented skip** (跟 R148-meta 后状态一致)。

### CLI smoke (R147 e2e 5 个例子)

```
$ java -jar plumb-main-...-all.jar -i D:\r147_e2e\MultiStepChain.java
D:\r147_e2e\MultiStepChain.java:17:-1  [LOW] TAINT_SINK_LOG_INJECTION
  Tainted t_t4:<unknown> ([bu-0001-taint-request-param])
  flows into scope.info/1
```

5/5 例子全部按预期 fire:

| 例子                   | chain 深度   | 预期            | 实际 |
|----------------------|------------|---------------|----|
| MultiStepChain       | 2-step 跨函数 | fire 1 finding | 1  |
| ThreeLevelChain      | 3-step     | fire 1 finding | 1  |
| TwoHopWrapper        | 2-step + 2-hop | fire 1 finding | 1  |
| SiblingIsolation     | 2-step 写 c 读 d | 0 findings (sibling) | 0  |
| OneStepRegression    | R146 1-step | fire 1 finding (regression guard) | 1  |

R147 跨函数跨 N-level chain propagation 全部正常。

## 清理

| 清理项                                        | 数量          | 工具                       |
|---------------------------------------------|-------------|--------------------------|
| 空 `org/fathom/` 目录 (rename 后剩下)            | 28          | `mavis-trash`            |
| stale `target/` 目录 (含旧 `fathom-java-*.jar`)   | 7           | `mavis-trash`            |
| rename 脚本自身 (`__rename_r148b.py` 等)         | 2           | `mavis-trash`            |
| 旧 Maven 仓库 `D:\cache\mvn_repo\org\fathom\` | 1 目录 (~20 jars) | `mavis-trash`         |

**经验**: `Remove-Item -Recurse -Force` 在 PowerShell 下被 permission gate 拒绝 (理由:
"PowerShell delete; no Recycle Bin from CLI"), 改用 `mavis-trash` 全部一次过。

## 累计 wins (R148 → R148b)

| 维度               | R148 (hello-java-sast → Fathom-Java) | R148b (Fathom-Java → Plumb) |
|------------------|----------------------------------|-------------------------|
| 改 artifactId     | 7                                 | 7                       |
| 改 groupId        | 1                                 | 1                       |
| 改 package 路径    | `com.hellojava.sast.*` → `org.fathom.java.*` | `org.fathom.java.*` → `org.plumb.*` |
| 改目录 (顶层)        | 1 (`hello-java-sast` → `Fathom-Java`) | 1 (`Fathom-Java` → `Plumb`) |
| 改目录 (模块)        | 7                                 | 7                       |
| 改 package 子目录   | 28 (3 层 `com\hellojava\sast` → `org\fathom\java`) | 28 (2 层 `fathom\java` → `plumb`) |
| 文本替换 文件数       | 289                               | 175                     |
| Maven groupId 后缀 | 跟 package 对称 (`.java` 双层)         | 简化 (无 `.java` 单层)        |
| Tests 验证        | 281 / 0 fail                      | 281 / 0 fail            |
| CLI smoke         | R147 2-step chain 2 findings      | R147 5/5 e2e examples   |
| Bash CWD workaround | 需要 (IDE 重命名删 CWD dir)         | 不需要 (CWD 在 Fathom-Cxx, 不动) |

## 关键 learning

1. **"长前缀先做" 是文本替换的硬性规则**: `org.fathom.java` 必须先于 `fathom` 做, 否则
   `fathom` 先做会把 `org.fathom.java` 变成 `org.plumb.java` 错。5 维顺序是稳定可行的。

2. **`os.walk` + in-place rename 只能用 path 后缀判断**: `in rel` 会让多个不同深度的 path
   都触发, 导致 rename 中途崩。`endswith` 精确匹配才是稳的。

3. **大写 `Fathom` 单词故意保留**: R148 经验 "历史 design doc 不动" 延伸到 "inspiration
   source 不动"。`Inspired by Fathom` 这种描述里的 `Fathom` 保留下来, 跟 C++ `Fathom-Cxx`
   的关联靠 inspiration 而非命名强行对齐。

4. **`mavis-trash` 是 PowerShell 下的安全删除工具**: `Remove-Item -Recurse -Force` 被
   permission gate 拒绝 (理由 "no Recycle Bin from CLI"), `mavis-trash` 走 OS Trash 一次过,
   28+7+2+1 共 38 个删除 0 失败。

5. **Maven 命名简化去掉 `.java` 后缀**: 用户方向 "既然 cxx/java 区分成两个项目就没必要
   再填什么东西了" — `org.plumb` 单层跟 C++ 的 `org.fathom.cxx` 双层不对称, 但简洁。
   project name 跟 package 路径都去 `.java` 一致, 避免命名空间里的 "java" 重复字。

6. **跨项目目录独立性**: 用户的 `Fathom/` 父目录 (cxx+java 合并) 是另一工作流项目,
   跟 Plumb 跟 Fathom-Cxx 都是独立顶层目录。这次 rename 完全不碰 `Fathom/`, 只动
   `Fathom-Java/` → `Plumb/`, 验证了 "Java 单独走" 的独立性。

## 新工作目录

- `D:\work\workspace\idea\engine\Plumb\` (Java 项目, 7 modules: plumb-{parser,tac,dfa,rules,style,reporter,main})
- `D:\work\workspace\idea\engine\Fathom-Cxx\` (C++ 项目, 不动)
- `D:\work\workspace\idea\engine\Fathom\` (另一工作流, 跟 Plumb 无关, 不动)

之后所有 R149+ 命令默认在 `D:\work\workspace\idea\engine\Plumb\` 跑。

## 文件

- 改: `D:\work\workspace\idea\engine\Plumb\` (整个项目, 175 文件文本替换 + 35 目录 rename)
- 删: `D:\work\workspace\idea\engine\Fathom-Java\` (顶层 rename 走了)
- 删: `D:\cache\mvn_repo\org\fathom\` (旧 Maven 仓库)
- 删: 28+7+2 stale dirs (target/ 跟空 org/fathom/ 跟 rename 脚本)
