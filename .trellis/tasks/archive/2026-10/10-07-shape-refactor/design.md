# 形状重构 — 技术设计

> 状态：**三处分叉已定**（2026-10-07，结论见 §六）。执行步骤与示例代码在 `implement.md`。
> 本文只给形状与判据；执行者是用户本人。

## 一、总体思路

现状的病理一句话：**概念只有实例，没有类型**。公式（变更类型 → 落点清单）的输入是「变更类型」，而被加的那个东西没有类型，所以公式无从写起。

因此本轮不做"清理违规"，做**造概念**：

| 概念 | 造出什么 | 加东西时的落点 |
|---|---|---|
| 阶段 | 一个「阶段」类型：名字 + 相对权重 | 枚举一条 + 编排一行 |
| 产出物 | 一个「产出物」类型：命名 + 检查点 + 可见性 | 登记一条 |
| 输入身份 | 身份成为输入的属性（多态），不是外部 switch | 变体自己实现 |

## 二、概念 1：阶段

### 目标形状

```java
// domain/task/model/PipelineStage.java（或 application/task/pipeline/ —— 见分叉 1）
/**
 * 主管线阶段：名字 + 相对权重。全项目唯一一处阶段定义。
 *
 * <p>进度区间不再手写——由权重累加算出。加一个阶段只声明自己的相对权重，
 * 后面所有阶段的区间自动跟着挪。
 */
public enum PipelineStage {

    ACQUIRE("acquire", 30),
    EXTRACT_AUDIO("extract-audio", 15),
    TRANSCRIBE("transcribe", 25),
    WRITE_SUBTITLES("write-subtitles", 5),
    CLEAN("clean", 25);

    // 翻译不在此列：它不随主管线跑，自成 0..100 段（见 VideoTaskPipeline.translateTask）
}
```

区间由权重累加，**集中一处算**：

```java
/** 本阶段在任务级进度里的区间（闭开区间 [start, end)）。 */
public record ProgressRange(int start, int end) { }

/** 按枚举声明顺序累加权重，算出每个阶段的区间。定义即顺序，不需要第二处声明。 */
public static Map<PipelineStage, ProgressRange> ranges() { ... }
```

调用点变成（不再有 `0..70` / `70..100` 这类手写常量）：

```java
subtitlePreparationStage.prepare(input, ProgressMapper.remap(listener, PipelineStage.ACQUIRE.range().to()));
cleanupStage.clean(input, "", ProgressMapper.remap(listener, PipelineStage.CLEAN.range().to()));
```

**一处必须同时处理**：`SubtitlePreparationStage` 内部的二级 remap（它把内部 0..100 映射进自己的 0..60 / 60..95 / 95..100）。这条二级映射要么随枚举化一起消失（把 acquire/extract/transcribe/write-subtitles 提升为平级阶段），要么保留但改从枚举取边界。**这是本任务最大的一处动作，也是 AC7（进度事件序列与数值不变）最容易被破的地方**——改造前后必须逐条比对进度事件。

### 待定分叉 1：做到哪一档

**档位 A（推荐）：只单源——枚举 + 自动区间，编排仍显式手写**

加一个阶段 = 加一条枚举 + 在 `prepareAndClean` 里插一行调用。区间自动。

**档位 B：完整列表化——阶段接口 + 有序列表，管线遍历执行**

```java
public interface PipelineStage {
    String name();
    int weight();
    void execute(StageContext ctx);   // ← 问题在这
}
```

**为什么推荐 A：**

1. A 已经达成目标（改动是常数、不需要手算区间）。B 多给的只是"管线不认识具体阶段"，而**本项目当前的痛苦不在这里**。
2. B 需要阶段间传数据。本项目阶段间的数据通道**已经是工作区文件**（`prepare` 写 `segments-<route>.json`，`clean` 读它），唯一的例外是 `AudioArtifact.path` 在 `SubtitlePreparationStage` 内部往下传。为这一个例外引入 `StageContext`，大概率退化成 `Map<String, Object>` 垃圾袋——**用抽象换来的不是能力，是间接性**。
3. B 强制所有阶段接受同一入参，而各阶段需要的东西天然不同（acquire 要 URL，transcribe 要 audioPath）。统一签名会把差异推到运行时。

**A 的代价（诚实记录）**：阶段顺序仍然只存在于 `prepareAndClean` 的语句顺序里。但那是相邻的几行代码、且类注释已列顺序，读得懂。**不为这个引入列表化。**

## 三、概念 2：产出物

### 目标形状

```java
/**
 * 字幕产出物：命名 + 检查点 + 对外可见性。全项目唯一一处产出物定义。
 *
 * <p>以前这三件事分散在三处（命名在 SubtitleArtifactNames、检查点在 CheckpointPolicy、
 * 可见性在 TaskArtifactScan 的后缀白名单）——加一个产出物要去三个文件各加一段，
 * 而且第三处跨在 domain/task 里，产物却是 subtitle 的。现在三条答案在一行里。
 */
public enum SubtitleArtifact {

    SEGMENTS_RAW("segments-%s.json", NOT_DOWNLOADABLE),
    SEGMENTS_CLEAN("segments-%s-clean.json", DOWNLOADABLE),
    SRT_RAW("subtitles-%s.srt", DOWNLOADABLE),
    SRT_CLEAN("subtitles-%s-clean.srt", DOWNLOADABLE),
    TRANSLATED("subtitles-%s-translated%s.srt", DOWNLOADABLE),
    CONTEXT("context-%s.json", NOT_DOWNLOADABLE),
    TERMS("terms-%s.json", NOT_DOWNLOADABLE);
}
```

由此衍生：

- `SubtitleArtifactNames` 收缩成 `artifact.fileName(route)` 一层（模板＋route token 的拼装），或整体并入枚举
- `TaskArtifactScan` 的**后缀白名单消失**——可见性从"字符串匹配的近似"变成"声明的属性"。扫描改为「对已登记的产出物逐个查文件在不在」
- `CheckpointPolicy` 的 `isCompleted` 保留（判据语义仍单源），但由产出物声明**用哪个检查点规则**

### 待定分叉 2：静态登记表 vs 每类一个类型

**档位 A（推荐）：枚举登记表。**

理由：产出物是**有限且同质**的——7 个，属性就三样（名字模板、检查点规则、可见性）。枚举天然表达"有限且同质"。每类一个类型只在**产出物行为不同**时才值得，现在没有。

**档位 B**：每种产出物一个 `record` + 共同接口。当前是过度工程，且会带来 7 个文件的膨胀。

## 四、概念 3：输入身份

### 目标形状：身份是输入的属性

```java
public sealed interface MediaInput {

    /**
     * 本输入的内容寻址 key（决定落在哪个工作区目录）。
     *
     * <p>身份**不按"输入从哪来"在外部 switch 里分派**——每个变体自己知道自己的身份是什么。
     * 这正是上传路线当初绕不过去的那一步：它的身份是文件内容的 sha256，从位置推不出来。
     *
     * @param facts 文件系统快照（路径 / 大小 / 修改时间），只有本地文件路线需要（并校验快照 path 与自己一致）；
     *              其余变体忽略
     */
    WorkspaceKey getContentKey(MediaFileInfo facts);

    /** 目录名前半段的标签（给人看的），唯一性由 key 保证。 */
    String dirLabel();
}
```

`WorkspaceService` 的 switch 消失：

```java
public Path workspaceFor(MediaInput input) {
    MediaFileInfo facts = ...;                       // 唯一残留的类型判断，只为取事实（经端口），不为选公式
    WorkspaceKey key = input.getContentKey(facts);
    return ensureCreated(rootDir().resolve(WorkspaceDir.of(input.dirLabel(), key).value()));
}
```

### 采样与快照（2026-10-08 定案）

- **`MediaFileInfo` 是自带参照物的快照**：`record MediaFileInfo(Path path, long sizeBytes, long lastModifiedMs)`。
  观测必须说得清「看的是哪个文件」——path 不在快照里，喂错文件不会有任何报错。
  `NOT_APPLICABLE = (null, -1, -1)` 只给 URL / 上传路线当哨兵。`LocalPath.getContentKey` 用
  `facts.path()` 参与公式并断言与 `value` 一致（`Objects.equals`），「观测错文件」从静默隐患变成
  当场 `IllegalArgumentException`。快照存**传入原样** path（absolute 化仍归 `SourceUri` 公式）——
  身份串与目录名与改造前逐字一致，AC7 验收办法不变。
- **stat 采样经端口**：`application/media/port/FileStateReader.getFileState(Path)`，实现落
  `infrastructure/fs/NioFileStateReader`。落位判据：契约是技术词汇、兜底问题「删掉这个端口业务规则
  还成立吗」答「成立」→ application 端口；实现按**能力来源**分桶（DB→persistence、本机进程→process、
  文件→fs），`provider/` 留给外部服务与工具。`getContentKey` 签名不变、保持纯函数——端口只替换
  「谁去读」，不改变「给定 facts 必得同一 key」。
- **失败口径不变**：读不到 → `-1` 占位、`log.debug`、不报错（权威报错归输入获取适配器）。
  原 `WorkspaceService.localPathKey` 的行为与日志逐字搬进 `NioFileStateReader`；读不到时返回
  `(path, -1, -1)` 而非 `NOT_APPLICABLE`（保持 path 与传入一致，否则会被断言误判）。

**否决过的两个形状（别再提）**：① `getContentKey(FileStateReader)`——端口参数进 domain 签名，
失败策略跟着渗进 domain，绕回「domain 碰不碰 I/O」的老问题；② 端口落 `domain/media/port/`——
契约是技术词汇不是领域词汇，走判据第二行（技术词汇 → application 端口）。

### 一处必须诚实处理的地方

`MediaInput` 的类注释现在写着「**本类型只承载位置**」，而 `InWorkspace` 破了它。本任务要做的**不是**把这个例外硬塞回约束里——而是**承认上传路线在原理上做不到**：

> 上传路线的身份是**内容**的 sha256（流式边写边算出来的），而 URL / 本地路径两条路线的身份是**位置**的摘要。内容身份**原理上**无法从位置推导——这不是实现偷懒，是两种身份语义本来就不同。

所以目标形态是：

- **`InWorkspace` 携带的东西从"结果"缩到"身份"**：不再带整个已解析的 `workspaceDir`（Path），只带 `getContentKey` + `dirLabel`（目录名由公式重算，与提交时逐字一致——AC7 要求）
- **改写 `MediaInput` 的类注释**：把「只承载位置」改成准确的表述（位置 + 必要的身份元数据），并写明为什么上传路线必须有这一份元数据

### 待定分叉 3：身份的承载形态

**档位 A（推荐）：`MediaInput` 上的方法（如上）。**

**档位 B**：独立的 `InputIdentity` sealed 类型，`MediaInput.identity()` 返回它。多一层类型，收益是身份可以脱离输入单独传递——但当前没有这个需求。

**档位 C（否决）**：让 `InWorkspace` 也走"从位置推导"（改成对文件内容算 sha256）。**这会改行为**（要读整份文件、且既有工作区目录的归属会变），与 R5 冲突，另立任务。

## 五、交付物：变更类型 → 落点清单（**终稿**，2026-10-08 三批全部落地后回填）

~~雏形 / 初稿~~ 这张表是用户要的"公式"。**每行附 grep 验证**（AC6），命令均为实测过：

| 变更类型 | 判据 | 落点 | grep 验证 |
|---|---|---|---|
| 加一个**管线阶段** | 它属于主管线还是按需派生产物？ | ① `SubtitleStageName` 加一条枚举项（值 = 动词-对象 kebab，带一个相对份额 `weight`）② 发事件处引用 `SubtitleStageName.X.getStageName()` / `.start()` / `.end()`（sealed/编译器管，漏了编译不过）③ **权重区间自动算出**——按枚举声明顺序累加，无手算、无第二处数字（2026-10-08 扁平化已落地，见 `10-08-progress-weight-flattening`） | `grep -rn '"acquire-media"' backend/src/main \| grep -v SubtitleStageName` → 无命中（值字面量单点） |
| 加一个**产出物** | 对外可下载吗？什么算完成？ | `SubtitleArtifactSpec` 加一条（文件名模板 + `downloadable` + `checkpoint` 三问同行） | `grep -rn '"segments-\|"context-' backend/src/main \| grep -v SubtitleArtifactSpec` → 无命中 |
| 加一种**输入来源** | 身份从哪来——位置还是内容？ | ① `MediaInput` 加变体（`permits` 跟改）并实现 `getContentKey()` / `dirLabel()` ② 身份侧 `WorkspaceService` **不动**；提交侧还要跟 `TaskInputType` + `ofTaskInput` 的 case + 提交端点（身份公式之外的落点，列此为诚实说明） | `grep -n 'case MediaInput' WorkspaceService.java` → 无命中（不按变体分派） |
| 加一个 **provider** | 端口在哪层？ | （故意留空——`provider-pattern.md` 已有规范，但落点清单未成文） | — |
| 加一种**状态 / 子状态** | —— | （故意留空——决策 4：生命周期不统一） | — |

最后两行**故意留空**：这是本轮范围的诚实边界，不是遗漏。

**本任务自身代价的诚实记录**：第①行的「编译器管」只管住**引用点**——复述类文字（注释 / 文档 / DDL 里抄一份名单）编译器管不着，批次 2 已把存量复述清零；防止复发的约定（注释不抄 kebab 值、阶段名 = 动词-对象）**尚未写进 spec**，用户定延后到 Javadoc 全局重构一并处理。

## 六、三处分叉的定论（2026-10-07 用户确认）

1. **阶段** — **A（只单源）**。编排保持手写。当时的「权重两层结构」已于 2026-10-08 扁平化（每阶段单 `weight` 字段、区间按声明顺序累加算出，见 `10-08-progress-weight-flattening`）。理由见 §二。
   - 讨论中曾把 A 描述成"不列表化"，但 **A 本身就是 sealed/record 的使用者**（`MediaInput` 已是 sealed + record）——真正的区别不是"用不用 sealed"，而是**要不要为身份再开一个 sealed 层级**，那是第 3 条的事。

2. **产出物** — **枚举登记表**。理由见 §三。
   - 落地时发现两个约束（`implement.md` 里处理）：`SubtitleArtifact` 这个名字**已被占用**（`prepare()` 返回的 record），登记表改名 `SubtitleArtifactSpec`；带 `{lang}` 的三个产出物在扫目录时不知道语种，需要补一个匹配方法。

3. **身份** — **`getContentKey()` 归输入自己 + `InWorkspace` 从"带结果"改成"带身份"**。
   - 讨论澄清：B（独立的 `InputIdentity` sealed 类型）**不是 A 的替代方案，而是 A 多包一层**——`InputIdentity` 除了 key 什么都不装，而身份在项目里只被用来建目录 / 查重 / 落库，三件都只要 key。所以 A/B 之争撤回，实质只有一件事：**身份由谁算**（答案：输入自己）。
   - 真正有内容的设计点是 `InWorkspace` 的字段：`(value, workspaceDir, workspaceKey)` → `(value, key, dirLabel)`——**带身份，不带结果**。目录由 `WorkspaceDir.of(dirLabel, key)` 重算，与提交时逐字一致。
