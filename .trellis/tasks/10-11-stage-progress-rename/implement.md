# 执行清单：StageEvent → StageProgress

> 本文档是开工时的展开版执行计划。需求与判据见 `prd.md`。

## 前置

- 分支：`feature/stage-progress-rename`（自 `dev` 切出，`dev@0f7d28f`）
- 基线：`mvn clean test` → **498 用例 / 0 失败 / 0 错误 / 1 跳过**；前端 `npm run type-check` 零错误
  （基线以 HEAD 源码 `@Test` 计数 498 核定。journal 里 10-11 收尾记的 497 是收尾中途的旧数，
  两个 chore 提交只碰 `.trellis`，不改变基线）
- 工作区必须干净起步

## 已定决策（2026-10-11 拍板，执行期不再变更）

| # | 决策 | 出处 |
|---|---|---|
| D1 | `StageEvent` → `StageProgress`、`StageState` → `State`、`onEvent` → `onProgress` | prd 已定 |
| D2 | `TaskStageEvent` **暂留不改** | 本轮用户拍板 |
| D3 | 指向载荷的**主代码**局部变量/参数改成 `stageProgress`；**测试内变量名不动** | 本轮用户拍板 |
| D4 | `TaskProgressMessage event` **不改**（那是 SSE 消息，另一个类型） | 边界判据 |

## 保护清单（替换时必须绕开，一个都不能碰）

含 `StageEvent` 子串但**不属改名对象**：

- `TaskStageEvent`（政策类，16 处自引用 + 文件名）——用词边界替换天然避开
- `TaskProgressMessage event`：`TaskSubscriptionRegistry.broadcast`、`TaskSseConnectionManager.sendTo` 及其 lambda
- 与本次无关的 `Event` 词族：`isEventTerminal` / `EventSource` / `addEventListener` / `MouseEvent` / `useTaskEvents` / 测试方法名里的 `...Event` 字样

## 步骤

### S1 三组全局改名（机械替换）

对 `backend/src` + `frontend/src` 全量，用**词边界**替换（`\b` 保证 `TaskStageEvent` 内的 `StageEvent` 不被命中）：

| 旧 | 新 | 说明 |
|---|---|---|
| `\bStageEvent\b` | `StageProgress` | 避开 `TaskStageEvent` |
| `\bStageState\b` | `State` | 含 `StageEvent.StageState` → `StageProgress.State`；`TaskStageEvent.java` 内的也要改（它引用旧枚举） |
| `\bonEvent\b` | `onProgress` | 避开 `translationEvent(...)` |

**验证点**：替换后 `git diff --stat` 只应显示预期的行数变化，**不得出现整文件重写**（文件是 CRLF，sed 误伤行尾会表现为全文件 diff）。

### S2 载荷文件改名

`git mv backend/src/main/java/com/vidsage/domain/shared/model/StageEvent.java` → `StageProgress.java`

### S3 主代码局部变量（手工，约 10 处）

指向载荷的变量/参数改名 `stageProgress`（对齐仓库既有 `VideoTask videoTask` 惯例）：

| 文件 | 位置 |
|---|---|
| `domain/shared/port/ProgressListener.java` | 方法参数 + `noop()` lambda |
| `application/task/notify/TaskProgressNotifier.java` | 4 个方法参数 + 4 行 Javadoc `@param` |
| `application/task/pipeline/SubtitlePipeline.java` | 2 个 lambda |
| `shared/progress/ProgressMapper.java` | 1 个 lambda（**不可用 `progress`**，该处已有 `int progress`） |

四个 coordinator 不涉及（走 `emit(listener, stage, progress, message, state)`，无 `event` 变量）。

### S4 Javadoc 订正（2 处失实）

按 `doc-revision-convention`（过期的重写为现状）改写：

- `StageProgress.java`：删「这是过渡形态：真实形状应由 MVP-1 的 SSE payload 定锤」——MVP-1 已定锤且结论是「保持现状」
- `ProgressListener.java`：删同款过渡态说法；补进「三层共用、SSE 只是链尾消费者」

### S5 spec 同步（1 处）

`.trellis/spec/backend/directory-structure.md:67` —— 共享内核值对象举例中的 `StageEvent` 改 `StageProgress`。

## 验证门

| # | 门 | 判据 |
|---|---|---|
| G1 | 后端编译 + 全量测试 | `mvn clean test` → 498 用例 / 0 失败 / 0 错误 / 1 跳过（与基线逐项一致）。**必须 `clean`**：改了 32 个文件，`target/classes` 会留陈旧 class |
| G2 | 前端类型检查 | `npm run type-check` 零错误 |
| G3 | **正向断言**新名必须命中 | 逐模式比对 HEAD 基线与改动后计数，要求**一一对应**；**不能只查旧名没了**（10-06-naming-alignment 的教训：旧名门禁因类内非限定调用恒真通过，导致改名整体漏做） |
| G4 | 保护对象未被误伤 | `TaskStageEvent` 仍存在且自引用数不变；`TaskProgressMessage event` 参数名未变 |
| G5 | **零附带改动**（执行期新增） | 把新旧两版都归一化掉那三组词后 diff 应为空——除下表列出的手工改过的文件外，其余文件不允许有任何差异。用于证明这次 diff「只有改名，没有夹带」 |

后端构建环境（Git Bash）：`JAVA_HOME=/f/java_soft/java21`，`mvn` 在 `/f/java_soft/apache-maven-3.9.6/bin/mvn`。surefire 报告会留陈旧文件，取数前先删 `target/surefire-reports`。

## 回滚点

单条命令级：整体回滚 `git checkout -- .` + `git clean -fd`（S2 的文件改名由 `git mv` 完成，回滚后 `git status` 应回到干净）。

## 不做

- SSE 契约面（`TaskSseConnectionManager` 配对、`terminal` 字段、前端断连）→ 归 `10-11-sse-surface-consolidation`
- SSE 载荷按消费方裁剪
- `TaskStageEvent` 改名（D2）
- 测试内局部变量名（D3）

---

## 执行记录（2026-10-11）

### 改名覆盖

`backend/src` + `frontend/src` 共 **32 文件**，逐模式比对 HEAD 基线与改动后计数，**一一对应**：
`StageEvent` 185→0（新名 `StageProgress` 185）、`StageState` 91→0（新名 `State` 91）、`onEvent` 25→0（新名 `onProgress` 25）。
文件改名由 `git mv` 完成，git 识别为 rename。

### G5 归一化门禁结果

把新旧两版都归一化掉那三组词后，**仅 5 个文件**残留差异——正是本次手工改过的：
`TaskProgressNotifier.java`(34) / `SubtitlePipeline.java`(14) / `ProgressListener.java`(19) /
`ProgressMapper.java`(7) / `types/task.ts`(2)。其余 27 个文件的改动**恰好只有**那三组词替换，无夹带。

### 行尾

32 个改动文件统一为 LF。仓库 `core.autocrlf=true`（存储层本就是 LF，checkout 时补 CRLF），
故提交内容与行尾无关。执行期 `SubtitlePipeline.java` 一度成了唯一的 CRLF，已拉齐。

### 执行期发现并修正的两处

1. **S4 漏做**：首轮只订正了 `ProgressListener` 的过渡态 Javadoc，漏了 `StageProgress.java`。
   由 G5 归一化门禁运行时暴露（该文件当时仍含「这是过渡形态」），已补。
2. **Javadoc 失实（独立核查抓出）**：`StageProgress.java` 初稿写「transcription、subtitle、task
   各自的阶段都上报它」。核实：`domain/transcription`、`application/transcription`、
   `infrastructure/provider/asr` 对 `ProgressListener` 引用**为零**，`Transcriber` 不接 listener
   （由 `SubtitlePreparationCoordinator` 调用）；转写阶段的进度实际由字幕侧发出。
   已改写为「media 与 subtitle 两条线」。

### spec 同源订正（所有者裁定：改，2026-10-11）

`.trellis/spec/backend/directory-structure.md:67` 同写「task / transcription / subtitle 三处共用」——
与上述核实结果同属失实。经所有者确认后一并订正为「media / subtitle / task 三处共用」，
并标注订正日期与原措辞，判据同 `doc-revision-convention`（过期的重写为现状）。

### 验证

- G1 `mvn clean test` → **498 / 0 失败 / 0 错误 / 1 跳过**（改动冻结后复跑）
- G2 `npm run type-check` → 零错误
- G3/G4 见上；由 `trellis-check` 独立复核，**1–4、6 项 PASS**，第 5 项由本记录的「执行期发现」第 2 条闭环
