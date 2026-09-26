# HTTP 契约归位与包结构规范修订

## Goal

把散落在「Controller 内部嵌套类」与「`model/` 包」里的 HTTP 契约类型归位到**按角色划分的 `web/` 层**，让 HTTP 入站适配这一层在磁盘上一眼可辨；同时修掉 `directory-structure.md` 中两处**字面自相矛盾/留白**的条款，并把 MVP-2a 遗留的三条规范冲突（`@Schema`、字段不变量校验、转换归属）连同新发现的**异常分类漏洞**一并收进规范。

**用户价值**：规范与代码停止打架；HTTP 契约有确定的归处；服务端 bug 不再伪装成「用户传错参数」静默存活。

## Background：已查证的事实

### 1. HTTP 契约类型现状分布

| 类型 | 位置 | 消费方 | 带 `@Schema` |
|---|---|---|---|
| `TranscriptionRequest` | 嵌套在 `controller/AsrController.java` | 仅 HTTP | 是 |
| `PrepareAudioRequest` | 嵌套在 `controller/MediaController.java` | 仅 HTTP | 是 |
| `PreparedAudioResponse` | 嵌套在 `controller/MediaController.java` | 仅 HTTP | 是 |
| `TranscriptionResult` | `model/asr/` | **HTTP + service/provider** | 是 |
| `TranscriptionSegment` | `model/asr/` | **HTTP + service/provider** | 是 |
| `ExternalToolReport` | `model/process/` | **HTTP + service** | 是 |

### 2. 关键判据：控制器策略分裂是有理由的，不是疏忽

- `MediaController.tools()` → 直接返回 `model/process/ExternalToolReport`（`model/process/ExternalToolReport.java:16-30`，字段全 `String`/`boolean`）
- `AsrController.transcribe()` → 直接返回 `model/asr/TranscriptionResult`（字段为 `List<TranscriptionSegment>` + `String`）
- `MediaController.prepareAudio()` → 自持 `PreparedAudioResponse`（`model/media/AudioArtifact.java:13` 含 `Path audioPath` + 嵌套 `AudioSpec`，形状需展平）

> **判据：内部契约的 JSON 形状能否直接对外** —— 能则直接用 + `@Schema`；不能则自持 HTTP record + 映射。
> 早期曾误读为「两条互斥出路二选一」，**已纠正**：它们是同一判据的两个分支。

### 3. 改名的波及面（实测）

引用 `com.vidsage.controller` 的文件共 **5 个**，全是自身 `package` 声明或测试 import：

```
main/controller/ApiExceptionHandler.java、AsrController.java、MediaController.java
test/controller/AsrControllerTest.java、MediaControllerTest.java
```

**没有任何其它包依赖 `controller/`。**

### 4. `directory-structure.md` 的两处缺陷

**(a) 字面矛盾**：`:3` 要求「顶层包按『构件类型』划分」，`:144` 却禁「按技术命名的顶层分包」—— 而 `controller/`/`service/`/`model/`/`provider/` 全属此类。`:144` 自开 `config/` 特例，恰证明作者心智模型是「此类默认禁」但漏了那四个。
**根因**：`:144` 把「**构件层**」（有职责边界，填得满 `:32-38` 职责表）与「**形状/角色桶**」（`dto/`、`util/`，职责栏填不出）混称为「按技术命名」。

**(b) 响应体留白**：`:151` 只规定「**请求体** record 定义在 Controller 内部」，对响应体只字未提。

### 5. MVP-2a 遗留的规范冲突（`09-25-vidsage-mvp2a-media-to-audio/implement.md`）

- **`:193`（第 8 条）**：`model/` 能否带 `@Schema`——规范写「禁止框架注解（Jackson 除外）」，但 `model/asr/` 已带
- **`:194`（第 9 条）**：`model/` 能否用紧凑构造器做字段不变量校验——`AudioSpec` 的 5 条校验在 `AudioPreparationService:232-251` 与 `FfmpegAudioExtractor:216-235` **各写一份**，连文案逐字相同

**⚠️ 勘误**：`:193` 称该规则出自 `provider-pattern.md`——**断言有误**。全仓 grep `框架注解` 只命中 `directory-structure.md:36` 与 `provider-pattern.md:23`（后者说的是 Provider **接口**，与 `model/` 无关）。**规则实际住在 `directory-structure.md:36`。**

**⚠️ 第 9 条的重复是「有意为之」**，理由写在 `AudioPreparationService:224-231`：断点分支会绕过抽取实现，服务层必须自守；并明言「契约约束不能放进 `model/`（directory-structure.md 规定 model 无行为），故不抽公共方法」。**故这不是待修的疏忽，而是要改那条规范。**

### 6. `error-handling.md` 当前过时

`:3`、`:140`、`:144` 仍写「没有全局异常处理器」「暂不建全局 `@ControllerAdvice`」，但 `ApiExceptionHandler` **已建立**。mvp2a 的 `implement.md:183` 已声明会更新它——**尚未执行**。

### 7. 异常分类漏洞

`error-handling.md:37` 把 `IllegalArgumentException` 定义为「错在调用方」，但**异常类型无法承载「值从哪来」的信息**。`ApiExceptionHandler:30,38` 对 IAE 只记 **WARN**（理由：「使用者输入问题，不该触发 ERROR 告警」）。**服务端 bug 一旦伪装成 IAE，会同时向客户端谎称「你传错了」且不触发任何告警。**

可达性实测：主代码 `new AudioSpec(` 仅 3 处——`MediaController:160-164` 与 `AudioSpec.java:21,24` 两个预置常量。`:164` 的 5 个实参里 4 个来自预置常量，**唯一客户端可达的是 `trackIndex`**。

## Requirements

- **R1 包结构**：`controller/` 改名为 `web/`，其下**按角色**建两个子包 `web/controller/` + `web/dto/`；3 个嵌套 HTTP 契约 record 提为顶层类型移入 `web/dto/`。
  - **record 类名不改**（`:130` 定「契约子结构 → 语义名，不叫 `DTO`/`VO`」；实测全仓 26 个 record 零 DTO/VO 后缀）
  - **有意推翻 `:44`**「`controller/` 平铺……再套一层二级目录是冗余层级」。该条判据是「包内类名是否已自带领域语义」，而 `AsrController`/`PrepareAudioRequest` 均自带领域——**按原判据平铺才成立**。规范须同步改写，不能只改代码。
  - `web/dto/` 命名采社区惯例（业主 2026-09-26 明确）。**代价：接受一处破例并写明理由** —— 包名用分类词标识桶的内容，类名用「动作名 + 角色后缀」（`Request`/`Response` 与服务层 `Command`/`Result` **同构**）；`DTO` 是分类词、不承载方向与操作语义，**从来不在本项目的角色词表内**（`:130` 明确排除）。原文见 [`design.md`](./design.md) §七

- **R2 修 `:144` 措辞矛盾**：改禁「**按形状/角色**分类的桶式顶层包」（`dto/`、`util/`、`common/`），明确**构件层**不受此限（判据：能否填满 `:32-38` 职责表）。

- **R3 补响应体归属规则**：为 `:151` 补判据 2 的完整规则。

- **R4 `model/` 与 `@Schema`**：**允许**，条件是该类型**直接充当对外契约**（判据 2 的「能」分支）。规范把 `:36` 禁止列表改为「**改变行为的**框架注解」；`@Schema` 为零运行时影响的纯文档元数据，规则字面网住了它但立法意图不覆盖它。

- **R5 字段不变量校验**（业主选定「甲」）：5 条校验移入 `AudioSpec` 的**紧凑构造器**，异常类型 **IAE → `IllegalStateException`**（判据 3）；删除两处 `requireValidSpec` 及调用点；改写 `AudioPreparationService:224-231` 注释。record 实例仍为纯数据（无实例行为方法）——不变量在**出生时**验过。
  - 实测连锁反应：3 个测试失效（见「测试影响」）

- **R6 转换逻辑归属**（业主选定「甲」）：`toMediaInput` / `toAudioSpec` **留在 `web/controller/MediaController`**。依据：42 行里大半是 HTTP 输入校验，`:152` 本就把「校验」划给 Controller；真正「不属于 HTTP」的只有末尾几行构造。
  - record **不带转换函数**（业主判定「record 本身属于数据类」）

- **R7 异常分类判据（谁之过）**——唯一判据是「**这个非法值是谁产生的**」：

  | 产生者 | 检查点 | 状态码 | 日志 | 当前实现的异常 |
  |---|---|---|---|---|
  | 调用方（请求体/URL/路径） | **边界卫语句**（适配层入口） | **400** | WARN | `IllegalArgumentException` |
  | 服务端自己算出 | **构造器不变量**（`model/` 紧凑构造器） | **500** | ERROR | `IllegalStateException` |

  > **判据以语义表达，不绑类型。** 判据是「谁之过」；异常类型只是当前实现。将来引入自定义异常体系时，规范只需补一句类型映射，**判据本身不动**。教训直接来自 `:144`——把判据绑死在实现措辞（「按技术命名」）上，就会随实现演进而翻车。

  - **落地规则**：任何「拿调用方的值构造 `model/` 类型」的代码，**必须先**在边界卫语句拦一次；构造器是最后防线，触发即意味边界漏了
  - **本项目落点**：边界**只需 1 条**卫语句（`trackIndex < 0` → IAE）；构造器保留 5 条（→ ISE）。`audioTrackIndex` 因此被检查两次——**不是冗余**：「调用方给的合法吗？」与「我们内部造出来的符合不变量吗？」是两问两答，同谓词不同含义，含义决定状态码
  - **残留风险（有意接受）**：边界卫语句漏写则客户端错误报 **500**。取舍理由：失败方向从**静默**翻转为**可见**——现状是服务端 bug 伪装成 400/WARN 存活；新设计把它喊成 500/ERROR。**宁可直接暴露服务端缺陷，也不要藏进「用户错误」里。**

- **R8 规范改写**：按 `design.md` §六 清单改写 `directory-structure.md`（12 处）、`error-handling.md`（`:37`、`:136`）、`index.md`（`:46`）、`logging-guidelines.md`（`:4`）。**`provider-pattern.md` 不改**。

## Acceptance Criteria

- [ ] `grep -rn "com.vidsage.controller" backend/src` 零命中
- [ ] `web/controller/` 含 `AsrController`、`MediaController`、`ApiExceptionHandler`；`web/dto/` 含 `TranscriptionRequest`、`PrepareAudioRequest`、`PreparedAudioResponse`（均为顶层 record，类名未变）
- [ ] `model/media/AudioSpec.java` 的紧凑构造器含 5 条检查、抛 `IllegalStateException`；`AudioPreparationService` 与 `FfmpegAudioExtractor` 中已无 `requireValidSpec`
- [ ] `POST /api/media/audio` 带 `audioTrackIndex: -1` → **400** 且响应体为中文「缺什么+怎么补」
- [ ] 三个端点（`/api/media/tools`、`/api/media/audio`、`/api/asr/transcriptions`）的成功与失败行为、状态码、消息与改前**逐字一致**（`audioTrackIndex` 为负仍为 400）
- [ ] `GET /v3/api-docs` 返回 200，三个操作的中文描述与 schema 完好（`@Schema` 未丢失）
- [ ] `cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test` 全绿；读 surefire 报告计数 = 基线 − 3 + 新增
- [ ] 新建 `model/media/AudioSpecTest` 覆盖 5 条不变量的构造期拒绝（含边界值 `0`/`-1`）+ 两个预置常量正例 + 移植来的「消息含 `AudioSpec.M4A_AAC_48K_MONO`」断言
- [ ] `MediaControllerTest` 新增「`audioTrackIndex: -1` → 400」用例
- [ ] `directory-structure.md` 中「按技术分层」（`:3`）与「不建按技术命名的顶层分包」（`:144`）的字面矛盾消失
- [ ] `directory-structure.md` 含响应体归属规则，且 `:44` 被改写并说明「为何按角色分子包不违 `:108` 的 package-by-feature 教训」
- [ ] `directory-structure.md:130` 附近含「**包名可叫 `dto/`、类名不可叫 `XxxDTO`**」的配套理由（原文取自 `design.md` §七），并列出已知破例 `controller/ApiExceptionHandler` 以表明该模式是倾向而非铁律
- [ ] `error-handling.md` 含判据 3「谁之过」纪律
- [ ] `index.md:46` 的 `@Schema` 要求按判据 2 精确化

## Out of Scope

- **本次不引入自定义异常体系**（方向已获业主批准，另立任务，见「后续任务」）
- **不建 `domain/` / `persistence/`**（`directory-structure.md:60`）
- **不改 `provider-pattern.md`**（`implement.md:193` 的误引只做勘误记录，不改该文件）
- **不重构 `toMediaInput`/`toAudioSpec` 的内部逻辑**（只新增 1 条边界卫语句）
- **不动 `model/` 里 `@Schema` 的现有用法**（判据 2 认可现状）
- **不修复 `error-handling.md` 的过时段落**——那是 mvp2a 的 `implement.md:183` 声明的范围

## 后续任务（本次不做，方向已获业主批准）

### 自定义异常体系

业主于 2026-09-26 批准方向（**允许**引入），**本次不实施**。

- **实测波及面**：**82 处生产抛出**（53 处 `IllegalArgumentException` + 29 处 `IllegalStateException`，跨 12 个文件）+ **测试 91 处引用**。对比：本任务改名仅波及 5 个文件——量级不同，故必须另立任务
- **届时要做**：定义类型层次 → 改 `ApiExceptionHandler` 映射 → 迁移 82 处 → 更新 91 处测试断言 → 把 R7 的**语义判据**映射到新类型
- **收益（本次已查证）**：
  1. **收窄 400 桶**——`ApiExceptionHandler:35` 按 JDK 类型捕获，任何恰好长成 IAE 的东西都被认作客户端错误（`Path.of` 的 `InvalidPathException extends IllegalArgumentException`、`Enum.valueOf` 的 IAE）。**症状已现**：`MediaController:126`、`:152` 两处已被迫显式 catch 再重包成中文消息
  2. **命名即意图**——可 grep、可审计，不再依赖 `error-handling.md:37` 那条无法强制执行的类型约定
  3. 为 `error-handling.md:145` 记的「错误码体系：未定义」铺路
- **它不解决 R7 的「谁之过」**：构造器仍看不到值的来源。若要它在这点上起作用，需配套「构造处 catch 中性异常再重分类」模式（谓词不重复，但异常驱动控制流、漏包后果与边界卫语句相同）。**该选择留给后续任务**
- **`error-handling.md:39` 本次不改**：它写的是「项目**当前**不定义自定义异常类……等到需要时再引入，不要提前造」——**该陈述现在依然成立**，正指向待办状态。遵从 `index.md`「不要在功能落地前凭空补规范」

## 分支拓扑与依赖

**定稿（2026-09-26）**：本任务分支 `feature/http-contract-layout` 位于 **`cc111b8`**（= 当前 `dev`）；`base_branch` = **`dev`**。**合并链只有一层**：本任务 → `dev`。

走到这一步的经过（留档）：

1. 业主决定不等 mvp2a 归档、直接在其基础上开分支。核实发现 mvp2a 相对 `dev` **领先 0 个提交**（61 个路径全未提交），故"基于它开分支"物理上必须先提交它
2. mvp2a 代码提交为 **`4bd3574`**；本任务分支从它开出，`base_branch` 暂设为 mvp2a 分支
3. **先补做了 mvp2a 的 Phase 3.3 规范回写**（提交 **`8a05a75`**）——原与本任务在 `directory-structure.md:32-38` 表、`error-handling.md:134-146` **有交集**，先做掉即消除冲突面
4. mvp2a 以 `--no-ff` 并入 `dev`（**`cc111b8`**）；本任务分支 fast-forward 到同一点；`base_branch` **改回 `dev`**

### ⚠️ 未推送

`dev` 当前 **ahead of `origin/dev` 3 个提交**——合并**只在本地**。本项目**有 remote**（`origin` → `https://github.com/HQLiLi/vidSage.git`）；是否推送需业主单独批准。

### 前置依赖已全部解除

原「必须先等 mvp2a 归档」与「Phase 3.3 未做」两条依赖**均已解决**。mvp2a 自身仅剩归档（其门 4），与本任务无冲突。前置检查命令见 `implement.md` 与 `design.md` §九。

## 测试影响

| 测试 | 处理 | 理由 |
|---|---|---|
| `FfmpegAudioExtractorTest:299-307` `rejectsInvalidSpecFields` | 删除，契约移入新建 `AudioSpecTest` | 构造即抛，测不了 extractor |
| `FfmpegAudioExtractorTest:309-321` `rejectsZeroBitrateForLossyContainer` | 删除，断言移植到 `AudioSpecTest` | 同上 |
| `AudioPreparationServiceTest:253-267` `rejectsInvalidSpecEvenWhenAudioFileExists` | **删除，无法改写** | 它构造「非法 spec + 断点命中」场景；R5=甲之后非法 spec 不可能存在 |

**第 3 个的删除理由（须留档）**：该测试注释记录了一次真实修复（「改 2：spec 校验前置到断点判断之前」），守护「非法 spec + 对应文件恰好存在时不得返回未经校验的产物」。R5=甲 之后**该场景不可能构造**——**它守护的整类 bug 结构性消失，这是甲的最强收益，不是覆盖损失。** 相应地 `AudioSpecTest` 的新用例覆盖了同一契约的另一半。
