# 执行计划：HTTP 契约归位与包结构规范修订

> 设计与取舍见 [`design.md`](./design.md)。本文只写**按序执行的动作、验证命令与回滚点**。
> **接手的新会话请先读 [`handoff.md`](./handoff.md)** —— 批准状态、分支拓扑、上一会话的关键上下文与已留档的错误都在那里。

## 前置门（`task.py start` 之前必须全绿）

> **分支拓扑（2026-09-26 定稿）**：本任务分支 `feature/http-contract-layout` 位于 **`cc111b8`**（= 当前 `dev`）；`base_branch` = **`dev`**。合并链只有一层。
> mvp2a 的 Phase 3.3 规范回写**已完成**（`8a05a75`）并已随 mvp2a 并入 `dev`，原冲突面已消除（见 design §九）。

```bash
git branch --show-current     # 期望 feature/http-contract-layout
git merge-base --is-ancestor cc111b8 HEAD && echo "基于 dev 当前点 ✓"
git status --porcelain        # 期望仅 .claude/settings.json（本机配置）+ 本任务目录

# Phase 3.3 已落盘的证据（应全部满足）
grep -c "没有全局异常处理器" .trellis/spec/backend/error-handling.md     # 期望 0
grep -c "tools.jackson"     .trellis/spec/backend/provider-pattern.md    # 期望 > 0
grep -c "CleanupMode"       .trellis/spec/backend/quality-guidelines.md  # 期望 > 0
```

**任何一条不满足 → 停，先查明原因。**

---

## 步骤 0 · 基线实测（不改任何代码）

```bash
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' \
  target/surefire-reports/*.xml | sort | uniq -c
```

**记录准确的测试总数**（2026-09-26 实测为 13 个类 / 159 个测试，全绿；数字会随 mvp2a 归档前的变化而变）。**后续每步都以此为对照。**

**回滚点**：无（未改动）。

---

## 步骤 1 · 建特性分支

```bash
git checkout dev && git pull --ff-only 2>/dev/null || true
git checkout -b feature/http-contract-layout
python ./.trellis/scripts/task.py set-branch 09-26-http-contract-layout feature/http-contract-layout
```

**验证**：`git branch --show-current` 输出 `feature/http-contract-layout`。

---

## 步骤 2 · 包改名：`controller/` → `web/controller/`

1. `git mv backend/src/main/java/com/vidsage/controller backend/src/main/java/com/vidsage/web/controller`
   （若无 `web/` 先 `mkdir -p`；`web/` 因有代码而建，不违「不得创建无代码目录」）
2. 三个文件改 `package com.vidsage.controller;` → `package com.vidsage.web.controller;`
3. 测试镜像：`git mv backend/src/test/java/com/vidsage/controller backend/src/test/java/com/vidsage/web/controller`，同步改 `package` 与 import

**验证**：`grep -rn "com.vidsage.controller" backend/src` 应**零命中**。

**回滚点 A**：`git reset --hard HEAD`（此步独立、可整段丢弃）。

---

## 步骤 3 · 提取嵌套 HTTP 契约到 `web/dto/`

1. `mkdir -p backend/src/main/java/com/vidsage/web/dto`
2. 从 `MediaController` 提出 `PrepareAudioRequest`、`PreparedAudioResponse`；从 `AsrController` 提出 `TranscriptionRequest`
3. 三个文件均为**顶层 record**，`package com.vidsage.web.dto;`，**保留 `@Schema` 一字不动**
4. **类名不改**（`directory-structure.md:130` 禁 `XxxDTO` 类名）
5. `MediaController` / `AsrController` 补 `import com.vidsage.web.dto.*`
6. `MediaController` 的 `toMediaInput` / `toAudioSpec` **留在原地不动**（R6 = 甲，见 design §七）
7. `PreparedAudioResponse.from(...)` 随 record 一起搬走；它在 `web/dto/` 内需为 `public static`（原为包级可见，跨包后不可达）

**验证**：

```bash
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test
```

**回滚点 B**：`git revert` 本步提交。

---

## 步骤 4 · `AudioSpec` 紧凑构造器 + 删除两处 `requireValidSpec`

1. `model/media/AudioSpec.java` 加紧凑构造器，**5 条消息逐字照搬** `AudioPreparationService:232-251`，**异常类型改为 `IllegalStateException`**
2. 删除 `AudioPreparationService.requireValidSpec`（`:232-251`）及调用点
3. 删除 `FfmpegAudioExtractor.requireValidSpec`（`:216-235`）及调用点
4. **改写** `AudioPreparationService:224-231` 注释：保留原防御意图的记录，结论改为「由构造器结构性保证，故不再需要两处」

**注意**：本步会让 3 个测试编译通过但运行失败（见步骤 6）——**这是预期的**，不要为了让它变绿而回退。

**回滚点 C**：`git revert` 本步（与步骤 6 的测试改动同批回滚才自洽）。

---

## 步骤 5 · 边界卫语句（判据 3，见 design §四）

在 `MediaController.toAudioSpec` 的 `trackIndex` 计算之后、`new AudioSpec(...)` 之前插入：

```java
if (trackIndex < 0) {
    throw new IllegalArgumentException("音频轨序号不能为负：当前为 " + trackIndex + "；序号从 0 起计");
}
```

**消息与 `AudioSpec` 构造器内的那条保持一致**（同谓词、不同含义：此处 IAE=客户端之过，构造器 ISE=服务端之过）。

**验证**（此时步骤 6 的测试改造尚未做，先跑针对性切片）：

```bash
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q -Dtest=MediaControllerTest test
```

**回滚点 D**：单行回滚。

---

## 步骤 6 · 测试改造（3 个失效 + 2 个新增）

| # | 动作 |
|---|---|
| 6.1 | **新建** `backend/src/test/java/com/vidsage/model/media/AudioSpecTest.java`：5 条不变量的构造期拒绝（含边界值 `0` / `-1`），断言 `IllegalStateException`；另加两个预置常量构造成功的正例 |
| 6.2 | `FfmpegAudioExtractorTest:299-307` `rejectsInvalidSpecFields` → **删除**，其契约由 6.1 覆盖 |
| 6.3 | `FfmpegAudioExtractorTest:309-321` `rejectsZeroBitrateForLossyContainer` → **删除**，但**必须把**「消息含 `AudioSpec.M4A_AAC_48K_MONO`」这条断言**移植进 6.1** |
| 6.4 | `AudioPreparationServiceTest:253-267` `rejectsInvalidSpecEvenWhenAudioFileExists` → **删除**，并将删除理由（design §五）写入测试文件注释或提交信息 |
| 6.5 | `MediaControllerTest` **新增**用例：「`audioTrackIndex: -1` → 400 + 中文消息」（验证步骤 5 的边界卫语句） |

**验证**：

```bash
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' \
  target/surefire-reports/*.xml | sort | uniq -c
```

**期望**：全绿，总数 = 步骤 0 基线 − 3（删除）+ 6.1 与 6.5 的新增数。

**回滚点 C'**：与步骤 4 同批。

---

## 步骤 7 · 规范改写（design §六 的清单，逐条勾）

按 design §六 的表格改 `directory-structure.md`（**12 处**）、`error-handling.md`（`:37`、`:136`）、`index.md`（`:46`）、`logging-guidelines.md`（`:4`）。

**`provider-pattern.md` 不改** —— 并**必须**把 design §六 末尾那条勘误（`implement.md:193` 引错了文件）写进 `09-25-vidsage-mvp2a` 的 `implement.md:193` 附近，否则该错误断言会一直留在那里误导后来者。**此动作跨越任务边界，需业主确认。**

**验证**：

```bash
grep -rn "com.vidsage.controller\|\`controller/\`" .trellis/spec/    # 期望零命中
grep -n "按技术命名" .trellis/spec/backend/directory-structure.md    # 期望已改写
```

**回滚点 E**：规范改动单独一个提交，便于整体 revert。

---

## 步骤 8 · 验收（design §十）

1. **全量测试**：步骤 6 的命令，全绿
2. **运行时冒烟**：启动后三个端点行为与改前逐字一致；`GET /v3/api-docs` 返回 200 且三个操作描述完好
3. **错误路径**：`POST /api/media/audio` 带 `audioTrackIndex: -1` → **400** + 中文消息
4. **规范一致性**：`directory-structure.md` 不再有「按技术分层」与「不建按技术命名的顶层分包」的字面矛盾

---

## 风险文件与回滚点汇总

| 文件 | 风险 |
|---|---|
| `model/media/AudioSpec.java` | 异常类型 IAE→ISE 是**行为变化**（状态码从 400 变 500）。**这是有意的**，见 design §二判据 3 |
| `MediaController.java` | 边界卫语句是新代码；漏写则客户端错误报 500 |
| `.trellis/spec/backend/error-handling.md` | **与 mvp2a 冲突面**。前置门已挡，但若 mvp2a 归档后该文件仍未更新，须先问业主 |
| `AudioPreparationServiceTest:253-267` | **删除测试**，需在提交信息里写明理由（design §五） |

**回滚点**：A（步骤 2）/ B（步骤 3）/ C+C'（步骤 4+6 同批）/ D（步骤 5）/ E（步骤 7）。**步骤 4 与 6 必须同批回滚**，否则测试红。

---

## 提交切分建议

1. `refactor(web): controller/ 改名为 web/controller/`
2. `refactor(web): 提取嵌套 HTTP 契约到 web/dto/`
3. `refactor(model): AudioSpec 校验移入紧凑构造器，异常改 ISE`（含测试改造）
4. `fix(web): MediaController 补 audioTrackIndex 边界卫语句`
5. `docs(spec): 包结构与错误处理规范修订`

---

## 审查门

**每步完成后停下等业主审查，不要连续推进。** 提交、合并回 `dev`、归档逐门批准。
