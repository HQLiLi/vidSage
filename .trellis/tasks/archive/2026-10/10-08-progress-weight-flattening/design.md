# 进度权重扁平化 — 技术设计

## 一、目标形状：阶段定义单源

阶段定义（名字 + 相对份额）落在**已有的** `domain/subtitle/model/common/SubtitleStageName` 上，不再另立载体。

```java
public enum SubtitleStageName {

    ACQUIRE_MEDIA("acquire-media", 42),
    EXTRACT_AUDIO("extract-audio", 42),
    TRANSCRIBE_AUDIO("transcribe-audio", 49),
    WRITE_SUBTITLES("write-subtitles", 7),
    CLEAN_SUBTITLES("clean-subtitles", 60),
    TRANSLATE_SUBTITLES("translate-subtitles", 0, false);

    @Getter
    private final String stageName;

    /** 相对份额：只比大小，不必凑满 100。 */
    private final int weight;

    /** 是否在主管线上分任务级进度；翻译不在。 */
    private final boolean inMainPipeline;

    /** 本阶段在任务级进度里的起点（含）/ 终点（不含），0..100。 */
    public int start() { return range().start(); }
    public int end()   { return range().end(); }

    // RANGES 在类加载时按声明顺序累加算出：done * 100 / total，int 除法即截断。
    // total 只累计 inMainPipeline 的份额（翻译的 0 不进分母）。
    // 不在表里的阶段（翻译）调用 start()/end() 直接抛 IllegalStateException。
}
```

要点：

- **份额只声明一次**，区间从声明算出（`start()` / `end()`），没有第二个需要同步的源。
- **累加机制不可单测 vs 可单测**：本设计选了可单测的形态——区间在类加载时算一次存表，语义集中在这一个类里。
- 分母是主管线份额之和（`42+42+49+7+60 = 200`），与「凑满 100」解耦：加阶段不必回头改别人。
- **截断不是四舍五入**：`(42+42+49) * 100 / 200 = 66.5 → 66`，今天显示的就是 66（[保真的前提]）。
- **`inMainPipeline` 是显式字段**，不用「weight == 0」当代理——份额为 0 与「不参与分配」是两件事，用巧合值表达另一件事会让下一个人踩坑。
- **命名不动**：`SubtitleStageName` 仍叫这个名字。加份额后 `Name` 后缀不算精确，但 `*Stage` 后缀在本仓库已被构件类占用（改名后会剩 `*Coordinator` / `*Dispatcher` / `*Transcriber`），叫 `SubtitleStage` 会与构件类混成兄弟相。

## 二、数据流：三层 → 一层

**改造前**（acquire 完成为例，一件事被算了两次）：

```
AudioPreparationStage 报 50（自己的 0..100）
  → SubtitlePreparationCoordinator 压进 prepare 内部 0..60 → 30
    → SubtitlePipeline 压进任务级 0..70 → 21
```

**改造后**：

```
AudioPreparationCoordinator 直接发 ACQUIRE_MEDIA.end() → 21
```

换算链从 3 层压到 1 层，深度恒为 1：**「本阶段内部进度」× 本阶段区间 → 任务级值**。

## 三、谁取数：一条规则 + 一处例外

**规则**：谁发事件谁取数——发事件的构件手里就有 `SubtitleStageName`，直接取 `start()` / `end()`。父级（`SubtitlePipeline`）只透传 listener，不再持任何区间。

**例外**：clean 的批级进度不是构件发的。`LlmSubtitleCleanser`（infrastructure）按 `batchNo * 100 / totalBatches` 连续报自己的 `0..100`，它是**连续进度生产者**（不像 acquire / extract / transcribe / write 只发开始·完成两个端点值）。换算由它的调用者 `SubtitleCleanupCoordinator` 做完再传下去：

```java
subtitleCleanser.clean(split, subtitleContext, reference,
        ProgressMapper.remap(listener, CLEAN_SUBTITLES.start(), CLEAN_SUBTITLES.end()));
```

`ProgressMapper` 因此**保留**——扁平后它只剩这一个使用者，但「0..100 自报 → 区间」仍是通用换算，且这条边界「provider 只报自己的百分比、换算归编排」值得保留成显式的一步。

翻译的 `LlmSubtitleTranslator` 同样报批级百分比，但翻译自成 `0..100` 直通，不经映射。

## 四、数值与保真性

档 A（保真档）：

| 阶段 | 份额 | 区间 | 今天 | 差异 |
|---|---|---|---|---|
| acquire-media | 42 | `[0, 21)` | 21 | — |
| extract-audio | 42 | `[21, 42)` | 42 | — |
| transcribe-audio | 49 | `[42, 66)` | 66 | — |
| write-subtitles | 7 | `[66, 70)` | 70 | — |
| clean-subtitles | 60 | `[70, 100)` | 70→100 | — |
| translate-subtitles | — | 不自成区间 | 0→100 直通 | — |

完整事件序列与今天的差异**只有一格**：

```
今天：0 / 21 / 21 / 42 / 42 / 66 / 70 / 70 / 70 / 100 / 100
之后：0 / 21 / 21 / 42 / 42 / 66 / 66 / 70 / 70 / 100 / 100
                                  ↑ 写字幕「开始」：70 → 66
```

那一格是 R8 的有意修正：今天写字幕的「开始」事件借用 `WRITE_DONE`（发的是终点值），修正后各阶段的「开始」值 = 上一阶段的「完成」值，整条序列变成规则形状。

**用户已定的口径**：份额数值是渲染进度条用的相对份额，**不追求反映真实耗时**。因此不存在「按耗时重分配」的目标——选保真档是为了让验收可机器判（AC7 的期望值 = 旧序列 + 这一格 -4）。

## 五、改名（三个，纯改名）

| 现名 | 新名 | 依据 |
|---|---|---|
| `AudioPreparationStage` | `AudioPreparationCoordinator` | 它认领 acquire + extract 两个阶段、各发阶段事件——正是 `*Coordinator` 的判据；扁平后它还要各管两个区间，判据更硬 |
| `MediaAcquisitionStage` | `MediaAcquirerDispatcher` | **零阶段事件**：按 `MediaAcquirer.supports` 选一个适配器再委托，是「做事的人」不是阶段 |
| `TranscriptionStage` | `Transcriber` | **零阶段事件**：只是把命令委托给 `ASRProvider` 的包装；转写的阶段事件由 `SubtitlePreparationCoordinator` 发 |

判据出处：`.trellis/spec/backend/directory-structure.md` 的命名判据节（`*er`/`*or` = 做事的人；`*Coordinator` = 认领进度阶段的那一步）。本次落地后该节的「待改」清单要清空。

## 六、讨论中否掉的方案（别再重开）

| 方案 | 否掉的理由 |
|---|---|
| **手写累计值字段**（每个阶段同时声明权重和累计权重） | 等于把「自动累加」换成「人工累加」：中间插一个阶段要手改后面每一行，改错没有编译器保护——正是本任务要消掉的问题；不满足 AC6 |
| **父级替构件 remap**（构件仍报 0..100，父级给窗口） | 做不到彻底扁平：`SubtitlePreparationCoordinator` 一个构件装 4 个阶段，父级没法只给一个窗口，二级映射必须留着 |
| **保留层级、常量改从表取** | 需要「一组阶段共享父区间」的组权重，等于每阶段一个权重之外还有组权重 = 没扁平化 |
| **另立阶段定义载体**（把名字+权重搬去 application） | `SubtitleStageName` 已被 `domain/task/policy/TaskStageEvent` 与两个 infrastructure provider 引用；搬到 application 会让 infrastructure 反向依赖 application（层倒置） |
| **名字/权重分家**（名字留 domain、权重表放 application） | 两处声明，「唯一一处阶段定义」破掉 |
| **`SubtitleStage`** 当新名 | 与 `*Stage` 构件类混成兄弟相；且用户此前已否掉 `PipelineStage`（读起来像执行类）与 `StageName`（不带归属） |
| **`weight == 0` 当「不参与分配」的代理** | 用巧合值表达另一件事；改为显式 `inMainPipeline` 字段 |
| **把累计值回填进枚举实例字段**（非 final，static 块里赋值） | 枚举常量变成可变，多一个初始化顺序问题，收益为零 |

## 七、诚实边界与代价

1. **枚举声明顺序成了语义**（区间靠它累加）。类注释里写一句，避免有人以为顺序可随意调整。
2. **domain 里混进了渲染用数字**。份额是「进度怎么分配」的 UI 关切，严格说不属于业务规则。仍放 domain 的理由是**归属**（阶段名本身就在这里，且被 infrastructure 引用），不为这一层洁癖再拆一次。
3. **阶段顺序本身仍只存在于编排代码的语句顺序里**。枚举提供区间、不提供执行顺序——这是上一个任务（形状重构）已定的档位 A（不做列表化遍历）。
4. **翻译的 `start()` / `end()` 调用即抛**。这是刻意的：翻译没有任务级区间，与其返回一个假值（两者都会是 100），不如让误用当场响。
5. **AC7 的那一格差异必须在验证报告里显式写明**，否则会被当成回归。
