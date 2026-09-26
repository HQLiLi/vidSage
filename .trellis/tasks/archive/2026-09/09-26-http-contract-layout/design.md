# 技术设计：HTTP 契约归位与包结构规范修订

> 需求与已定决策见 [`prd.md`](./prd.md)。本文只写技术设计、取舍与迁移。

## 一、目标结构

```
com.vidsage
├── VidSageApplication.java        # 启动类，留根包
├── web/                           # 新增顶包：HTTP 入站适配层
│   ├── controller/                # AsrController、MediaController、ApiExceptionHandler
│   └── dto/                       # TranscriptionRequest、PrepareAudioRequest、PreparedAudioResponse
├── service/                       # 不变，平铺
├── model/                         # 不变，按领域二级包
│   ├── asr/                       # TranscriptionResult、TranscriptionSegment（保留 @Schema）
│   ├── media/                     # AudioSpec 等（新增紧凑构造器不变量）
│   └── process/                   # ExternalToolReport（保留 @Schema）
└── provider/                      # 不变，按领域二级包
```

**`controller/` 顶包消失**，整体改名为 `web/`，其下按**角色**分两个子包。

## 二、三条归属判据（各自独立，互不推导）

### 判据 1：HTTP 契约类型放哪

> **该类型的 JSON 形状是否就是要对外暴露的形状？**
> - **是** → 直接充当 HTTP 契约，类型留在 `model/`，字段补 `@Schema`
> - **否**（含 `Path`、嵌套内部规格等需展平之物）→ 在 `web/dto/` 自持 record，从模型类型映射

**依据**：`ExternalToolReport`（`model/process/`，字段全 `String`/`boolean`）与 `TranscriptionResult`（字段为 `List<Segment>` + `String`）形状可直接对外，故被 Controller 直接返回；`AudioArtifact`（`model/media/AudioArtifact.java:13`，含 `Path audioPath` + 嵌套 `AudioSpec`）形状需改造，故有 `PreparedAudioResponse`。**现有代码已按此判据运行，本设计只是把它写成规则。**

> ⚠️ 本条推翻 `prd.md` 早期的另一读法（「两条互斥出路二选一」）。证据见 `prd.md` §Background-2。

### 判据 2：`model/` 能否带 `@Schema`（R4）

**能，但有条件**：仅当该类型**直接充当对外契约**（判据 1 的「是」分支）。

**规范改为**：`model/` 禁止**改变行为**的框架注解（Jackson 绑定注解等）；`@Schema` 这类**零运行时影响的纯文档元数据**，在「直接充当对外契约」时允许。

**理由**：`directory-structure.md:36` 禁止清单的立法意图是「防止外部框架语义渗进中立契约」，其列举的「行为方法、SDK 类型」都是**有实质影响**的东西。`@Schema` 跳过整个运行时——删光它接口行为一个字节不变。规则字面网住了它，立法意图不覆盖它。

### 判据 3：异常类型选择（R7，已获业主批准）

> **唯一判据：这个非法值是谁产生的**
> - **调用方**（请求体 / URL / 路径）→ **边界卫语句** → **400** / WARN
> - **服务端自己算出** → **构造器不变量** → **500** / ERROR
>
> 本次实现分别用 `IllegalArgumentException` / `IllegalStateException` 承载，**但判据以语义表达、不绑类型**——将来引入自定义异常体系时（见 `prd.md`「后续任务」），只需补类型映射，判据不动。

**落地规则**：任何「拿调用方的值构造 `model/` 类型」的代码，**必须先**在边界卫语句拦一次。构造器那层是**最后防线**，它触发即意味着边界漏了。

**为何必须分两处**：`AudioSpec` 的构造器只看得到 `sampleRateHz == 0`，**看不到这个 0 是客户端传的还是服务端算的**。「谁之过」只能在知道来源的地方决定。

**本项目的具体落点**（实测 `MediaController:160-164`）：5 个实参里 **4 个来自预置常量**（恒定合法），**只有 `trackIndex` 客户端可达**。故：

- 边界**只需 1 条卫语句**：`trackIndex < 0` → IAE → 400
- 构造器保留 **5 条**不变量 → ISE → 500

**`audioTrackIndex` 因此被检查两次——这不是冗余**：「调用方给的合法吗？」（边界）与「我们内部造出来的符合不变量吗？」（构造器）是**两问两答**，同一个谓词、不同的失败含义，而含义决定状态码。

**残留风险（有意接受）**：边界卫语句若漏写，客户端错误会被构造器报成 **500**。取舍理由是失败方向反转——现状是服务端 bug **伪装成 400/WARN 静默存活**（`ApiExceptionHandler:30,38` 对 IAE 只记 WARN），新设计则把服务端疏漏**喊成 500/ERROR，可见**。**宁可把服务端缺陷大声暴露，也不要藏进「用户错误」里。**

## 三、`model/` 的两处例外（都须写入规范）

| 例外 | 条款 | 改成 |
|---|---|---|
| `@Schema` | `directory-structure.md:36` 禁止列表 | 「框架注解（**改变行为的**，Jackson 除外）」；补判据 2 |
| 构造期不变量 | `directory-structure.md:36`「行为方法」+ `:55`「只有字段与 Javadoc 约束」 | 明确**紧凑构造器承载的字段不变量**不属「行为方法」；`:55` 改为「只有字段与**约束**（Javadoc 声明的、或紧凑构造器强制的）」 |

**为何紧凑构造器不算「行为方法」**：它不是实例方法，且实例出生后**仍无任何行为方法**——不变量在出生时验过，此后 `AudioSpec` 只是一袋数据。

## 四、`AudioSpec` 校验迁移（R5 = 甲）

### 改动

```java
// model/media/AudioSpec.java —— 新增紧凑构造器（异常类型由 IAE 改为 ISE）
public record AudioSpec(AudioContainer container, int sampleRateHz, int channels,
                        int bitrateKbps, int audioTrackIndex) {

    public static final AudioSpec WAV_PCM_16K_MONO = ...;
    public static final AudioSpec M4A_AAC_48K_MONO = ...;

    public AudioSpec {
        if (container == null) throw new IllegalStateException("音频容器不能为空：...");
        if (container == AudioContainer.M4A && bitrateKbps <= 0) throw new IllegalStateException("...");
        if (sampleRateHz <= 0)     throw new IllegalStateException("...");
        if (channels <= 0)         throw new IllegalStateException("...");
        if (audioTrackIndex < 0)   throw new IllegalStateException("...");
    }
}
```

**消息文本逐字照搬**现有两处（`AudioPreparationService:232-251` / `FfmpegAudioExtractor:216-235`），只有异常类型变。

### 删除

- `AudioPreparationService.requireValidSpec`（`:232-251`）及其调用点
- `FfmpegAudioExtractor.requireValidSpec`（`:216-235`）及其调用点
- `AudioPreparationService:224-231` 的「重复是有意为之」注释 → **改写**为记录原防御意图 + 结论改为「由构造器结构性保证，故不再需要两处」

### HTTP 边界行为（对照）

| | 现状 | 甲之后 |
|---|---|---|
| 客户端传 `audioTrackIndex: -1` | `toAudioSpec` 造出非法 spec → `prepare()` → 服务层 IAE → **400** | `toAudioSpec` 卫语句 → IAE → **400**（同一处理器、同一消息） |
| 服务端某处算出非法值 | 服务层 IAE → **400 + WARN**（误报为客户端错误，且不告警） | 构造器 ISE → **500 + ERROR**（正确暴露服务端 bug） |

### 边界新增卫语句

`MediaController.toAudioSpec`（`:160-164` 附近）新增：

```java
int trackIndex = request.audioTrackIndex() == null
        ? preset.audioTrackIndex()
        : request.audioTrackIndex();
if (trackIndex < 0) {
    throw new IllegalArgumentException("音频轨序号不能为负：当前为 " + trackIndex + "；序号从 0 起计");
}
return new AudioSpec(preset.container(), preset.sampleRateHz(), preset.channels(),
        preset.bitrateKbps(), trackIndex);
```

## 五、兼容性与迁移

- **无外部 API 变化**：URL、请求/响应 JSON 形状、状态码、错误消息**全部不变**
- **包改名波及面实测 = 5 个文件**，全是自身 `package` 声明或测试 import，无其它包依赖
- **无数据库 / 无持久化**，无迁移脚本

### 测试影响（3 个，必须处理）

| 测试 | 处理 | 理由 |
|---|---|---|
| `FfmpegAudioExtractorTest:299-307` `rejectsInvalidSpecFields` | 改写为「`new AudioSpec(...)` 抛 `IllegalStateException`」，移入新建 `model/media/AudioSpecTest` | 构造即抛，测不了 extractor |
| `FfmpegAudioExtractorTest:309-321` `rejectsZeroBitrateForLossyContainer` | 同上；**保留**「消息含 `AudioSpec.M4A_AAC_48K_MONO`」断言 | 同上 |
| `AudioPreparationServiceTest:253-267` `rejectsInvalidSpecEvenWhenAudioFileExists` | **删除，无法改写** | 它构造「非法 spec + 断点命中」场景；甲之后非法 spec 不可能存在 |

**关于第 3 个的删除理由（必须留档）**：该测试注释记录了一次真实修复（「改 2：spec 校验前置到断点判断之前」），守护的是「非法 spec + 对应文件恰好存在时，不得返回未经校验的产物」。甲之后**该场景不可能构造**——非法 `AudioSpec` 不存在。**它守护的整类 bug 结构性消失，这是甲的最强收益，不是覆盖损失。** 相应地，`AudioSpecTest` 新增的构造抛异常用例**覆盖了同一契约的另一半**（契约本身不可被违反）。

### 新增测试

- `model/media/AudioSpecTest`：5 条不变量的构造期拒绝（含边界值 0 / -1）+ 两个预置常量构造成功
- `web/controller/MediaControllerTest`：新增「`audioTrackIndex` 为负 → 400」用例（验证边界卫语句）

## 六、规范改写清单（精确到 file:line）

### `directory-structure.md`

| 行 | 现状 | 改为 |
|---|---|---|
| `:5` | 「`controller/` 下能一眼看全所有 HTTP 入口，是采用这种分层的核心动机」 | `web/controller/` |
| `:14` | 结构示意图 `├── controller/` | 加 `web/` 与两个子包 |
| `:34` | `\| controller/ \|` 行 | `web/controller/`；「请求体 record」从允许列表**移出** |
| `:36` | `model/` 禁止「框架注解（Jackson 除外）、行为方法」 | 按 §三 改写 |
| `:40` | 「`controller/`、`service/` **平铺**」标题 | `controller/` 移出该规则 |
| `:44` | 「…再套一层二级目录是**冗余层级**」 | **有意推翻**。须写明新判据（按角色分，而非按领域；`AsrController` 与 `PrepareAudioRequest` 是两种构件，不是冗余层级） |
| `:47` | 「新增领域时：`controller/`、`service/` 直接加类」 | `web/controller/` 直接加类 |
| `:55` | `model` 行「只有字段与 Javadoc 约束」 | 按 §三 改写 |
| `:108` | package-by-feature 曾被推翻的历史 | **须补说明**：`web/controller/`+`web/dto/` 是**按角色**分（构件类型），不是 package-by-feature（按功能），故不重蹈覆辙 |
| `:131` | stereotype 表 `controller/`→`@RestController` | `web/controller/`→`@RestController` |
| `:132` | 「Controller …**平铺在 `controller/`**」 | `web/controller/` |
| `:144` | 「不建 `dto/`、`util/` 这类**按技术命名**的顶层分包」 | **措辞矛盾修复**：改为「不建**按形状/角色**分类的桶式顶层包（`dto/`、`util/`、`common/`）」，明确**构件层**（有职责边界、填得满 `:32-38` 职责表）不受此限；补「为何 `dto/` 作 `web/` 子包可以、作顶层包不可以」 |
| `:130`（配套） | 「契约子结构 \| 语义名，**不叫 `DTO` / `VO`**」 | **不改**，但须补配套说明：包名可叫 `dto/`、类名不可叫 `XxxDTO` 的**理由**（原文见 §七「`web/dto/` 的破例说明」），并列出已知破例（`controller/ApiExceptionHandler`）以表明该模式是倾向而非铁律 |
| `:146` | 参照 `controller/AsrController.java` | `web/controller/AsrController.java` |
| `:151` | 「Controller 放 `controller/`…**请求体** record 定义在 Controller 内部」 | 改路径；**补响应体归属规则**（按判据 1） |

### `error-handling.md`

| 行 | 改动 |
|---|---|
| `:3`、`:134-146` | **`mvp2a` 会改**（其 `implement.md:183` 已声明把「暂不建全局 `@ControllerAdvice`」更新为已建立）。**本任务不得与之冲突 → 硬依赖，见 §九** |
| `:37` | 补判据 3「谁之过」纪律：只有**来自调用方**的值可用 IAE 拒绝；服务端自身算出的非法值须抛 ISE |
| `:136` | 参照 `controller/AsrController.java` → `web/controller/AsrController.java` |

### `index.md`

| 行 | 改动 |
|---|---|
| `:46` | 「HTTP 契约模型（请求/响应 record）字段都要有 `@Schema` 描述」→ 按判据 1 精确化：**直接充当对外契约的模型**字段要有；需展平的自持 `web/dto/` record 同理 |

### `logging-guidelines.md`

| 行 | 改动 |
|---|---|
| `:4` | 参照 `controller/AsrController.java` → `web/controller/AsrController.java` |

### `provider-pattern.md`

**无需改动。** 核查结论：`implement.md:193` 称「`provider-pattern.md` 写『`model/` 禁止框架注解（Jackson 除外）』」——**该断言有误**，全仓 grep `框架注解` 只命中 `directory-structure.md:36` 与 `provider-pattern.md:23`（后者说的是 Provider **接口**不含框架注解，与 `model/` 无关）。**此勘误须记录，避免后来者去 `provider-pattern.md` 找一条不存在的规则。**

## 七、取舍记录

| 决定 | 取舍 |
|---|---|
| `web/` 改名（而非保留 `controller/`） | 换来名字准确性，付出 5 文件 + 规范 12 处改写。业主选择社区惯例优先 |
| `web/dto/`（而非 `contract/`、`payload/`） | 业主选择社区惯例（2026-09-26 明确）。**代价是接受一处破例，须在规范写明理由**（原文见下） |

### `web/dto/` 的破例说明（须原文写入 `directory-structure.md` 配套条款）

> **包名用分类词（`dto/`）是社区惯例，标识桶的内容；类名用「动作名 + 角色后缀」（`Request` / `Response`），因为 `DTO` 是分类词、不承载方向与操作语义。二者尺度不同，是分工而非双重标准。**

**佐证**：HTTP 契约的角色后缀 `Request` / `Response` 与服务层契约的 `Command` / `Result` **同构**——

```
TranscriptionRequest  (HTTP 入参)  ↔  TranscriptionCommand  (服务层入参)
PreparedAudioResponse (HTTP 出参)  ↔  TranscriptionResult   (服务层出参)
```

故 `PrepareAudioRequest` = 动作名（PrepareAudio）+ 角色后缀（Request），**完全符合 `:129` 的既有约定**；`DTO` 从来不在该项目的角色词表里（`:130` 明确排除）。

**已知破例（须诚实记录）**：本项目角色包的类名基本带同名后缀——`controller/`→`*Controller`、`service/`→`*Service`、`provider/`→`*Provider`；**`web/dto/` 会是唯一不同字的一个**。但 `controller/ApiExceptionHandler` 已是一例（它并非 Controller），**故该模式是倾向而非铁律**，破例不需要回避，只需要写明。
| **推翻 `:44` 平铺** | 业主明确要求按角色分子包。规范须同步改写，否则即制造新的「规范 vs 代码打架」 |
| 转换留 `web/controller/`（R6=甲） | 42 行里大半是 HTTP 输入校验，`:152` 本就把「校验」划给 Controller；真正"不属于 HTTP"的只有末尾几行构造。不必为它开第三子包 |
| 紧凑构造器（R5=甲）而非 `public static` 方法 | 结构性保证 > 纪律性保证：后者漏调即破，前者不可能持有非法实例。代价是删 1 个回归测试 |
| 接受「边界漏写 → 500」 | 见 §二 判据 3。失败方向从**静默**翻转为**可见** |

## 八、风险与回滚

| 风险 | 缓解 |
|---|---|
| 包改名遗漏引用 | 波及面仅 5 文件（实测）；`mvn test` + 启动冒烟即可全量验证 |
| `AudioSpec` 静态预置常量失败形态变化 | 两个常量实参为字面量、恒定合法。**若日后写错**，失败形态是 `ExceptionInInitializerError`（`Error`，`ApiExceptionHandler` 不接）→ 掉到 Spring 默认 **500**，消息只剩 `Caused by`。**已记入本文档；值不值当加防护留待将来** |
| 边界卫语句漏写 | 是**有意接受**的失败模式（判据 3），且失败可见 |
| 规范改写与 `mvp2a` 冲突 | 见 §九 |

**回滚**：本任务是纯重构 + 文档，**无数据迁移、无外部契约变化**。任一步出问题直接 `git revert` 对应提交即可，不存在需要清理的半成品状态。

## 九、分支拓扑与前置依赖

> **分支拓扑（2026-09-26 已定稿）**：本任务分支 `feature/http-contract-layout` 现位于合并提交 **`cc111b8`**（= 当前 `dev`）；`base_branch` = **`dev`**。
> **合并链只有一层**：本任务 → `dev`。

### 拓扑是怎么走到这一步的（留档，避免后来者误判基线）

1. 业主决定**不等 mvp2a 归档**、直接在其基础上开分支。执行前核实：`feature/mvp2a-media-to-audio` 当时相对 `dev` **领先 0 个提交**——mvp2a 的全部工作（61 个路径）都是**未提交**状态。因此"基于它开分支"物理上**必须先提交它**，否则新分支起点仍是 `dev`，两件事的改动无法分离（`MediaController.java` 在磁盘上只有一个版本，说不清哪几行属于哪个任务）。
2. mvp2a 的代码提交为 **`4bd3574`**，本任务分支从它开出，`base_branch` 暂设为 `feature/mvp2a-media-to-audio`。
3. 随后先补做了 **mvp2a 的 Phase 3.3 规范回写**（提交 **`8a05a75`**）——它原本与本任务在 `directory-structure.md` 的 `:32-38` 表、`error-handling.md` 的 `:134-146` **有交集**，先做掉即消除冲突面。
4. mvp2a 合并进 `dev`（`--no-ff`，得到 **`cc111b8`**）；本任务分支 fast-forward 到同一提交；`base_branch` 由 `feature/mvp2a-media-to-audio` **改回 `dev`**。

> 若第 4 步没做（即 base 仍写着 mvp2a 分支），本任务合入时 target 会指向一个**已并入 dev** 的分支，记录即失准。

### ⚠️ 未推送

`dev` 当前 **ahead of `origin/dev` 3 个提交**（`4bd3574`、`8a05a75`、`cc111b8`）——合并**只在本地**。本项目**有 remote**（`origin` → `https://github.com/HQLiLi/vidSage.git`）；是否推送需业主单独批准。

### 前置依赖：已全部解除

原「必须先等 mvp2a 归档」与「Phase 3.3 未做」两条依赖**均已解决**：Phase 3.3 已完成，mvp2a 已并入 `dev`。mvp2a 自身仅剩**归档**（其门 4），与本任务无冲突。

### 开工前置检查

见 `implement.md` 的「前置门」。

## 十、验收方式

- **编译 + 全量测试**：`cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test`，并读 surefire 报告取准确数字（`mvn -q` 会吞 summary）
- **运行时验收**：启动后 `GET /api/media/tools`、`POST /api/media/audio` 与 `/api/asr/transcriptions` 行为与改前逐字一致；`GET /v3/api-docs` 仍返回 200 且三个操作描述完好
- **错误路径验收**：`POST /api/media/audio` 带 `audioTrackIndex: -1` → **400** + 中文消息（验证边界卫语句）
- **规范一致性**：`directory-structure.md` 中不再存在「按技术分层」与「不建按技术命名的顶层分包」的字面矛盾
