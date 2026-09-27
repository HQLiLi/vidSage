# 设计：静态助手收口到 utils 包

## 一、判据：什么才该搬

散落的静态方法有三种，处理方式不同。判据只有一条：

> **跨包 / 跨层重复，或者该逻辑明显不属于承载它的类。**

按这条判据，包级 static 的「测试钩子」一律不搬——它们只被自己的测试用，是类的内部细节，搬到 `utils/` 反而把私有实现提升成公开 API。

「只被用一次」不是搬的理由。反过来说，本任务也确实要搬两个当前只有一个消费者的方法（`moveAtomically`、`deleteQuietly`），理由见决策 3。

## 二、目标结构

```
com.vidsage.utils
├── fs/
│   └── ArtifactFiles.java          # 新增：产物落盘与大小判定
├── subtitle/
│   ├── SrtRenderer.java            # 新增：SRT 渲染
│   ├── SubtitleArtifactNames.java  # 新增：产物命名
│   ├── SubtitleCorrector.java      # 已有，不动
│   ├── SubtitleSegmentSplitter.java# 已有，不动
│   └── TextSimilarity.java         # 已有，不动
└── text/
    └── TextTail.java               # 新增：文本尾部截取
```

## 三、搬迁映射

| 原位置 | 方法 | 去向 |
|---|---|---|
| `FfmpegAudioExtractor:273`、`LocalFileMediaAcquirer:172`、`YtDlpMediaAcquirer:439`、`AudioPreparationService:222`、`SubtitleService:212`、`SubtitleCleanupService:201` | `sizeOrZero` / `nonEmptySize`（6 份） | `ArtifactFiles.sizeOrZero` |
| `SubtitleService:224`、`SubtitleCleanupService:212` | `isNonEmpty`（2 份） | `ArtifactFiles.isNonEmpty` |
| `SubtitleService:298`、`SubtitleCleanupService:219` | `writeAtomically`（2 份） | `ArtifactFiles.writeAtomically` |
| `FfmpegAudioExtractor:250` | `moveAtomically`（1 份） | `ArtifactFiles.moveAtomically` |
| `FfmpegAudioExtractor:262` | `deleteQuietly`（1 份） | `ArtifactFiles.deleteQuietly` |
| `SubtitleService:267` | `formatTimestamp` | `SrtRenderer.formatTimestamp` |
| `SubtitleService:280` | `toSrt` | `SrtRenderer.toSrt` |
| `SubtitleService:238` | `routeToken` | `SubtitleArtifactNames.routeToken` |
| `SubtitleService:249` | `srtFileName` | `SubtitleArtifactNames.srtFileName` |
| `SubtitleService:256` | `segmentsFileName` | `SubtitleArtifactNames.segmentsFileName` |
| `SubtitleCleanupService:144` | `cleanSrtFileName` | `SubtitleArtifactNames.cleanSrtFileName` |
| `SubtitleCleanupService:137` | `cleanSegmentsFileName` | `SubtitleArtifactNames.cleanSegmentsFileName` |
| `FfmpegAudioExtractor:284`、`LocalFileMediaAcquirer:183`、`YtDlpMediaAcquirer:450` | `tailLines`（3 份） | `TextTail.tailLines` |

原方法在 `SubtitleService` / `SubtitleCleanupService` 里是包级 static，搬走跨包后按规范改成 `public`。

## 四、接口

### `utils/fs/ArtifactFiles`

```java
public final class ArtifactFiles {
    private ArtifactFiles() {}

    /** 文件大小；不存在、不是常规文件或读取失败时返回 0。 */
    public static long sizeOrZero(Path path)

    /** 文件存在且大小 > 0（0 字节视为不存在，用于产物断点判定）。 */
    public static boolean isNonEmpty(Path path)

    /** 原子写盘：先写 <name>.tmp 再替换到目标名，避免留下半截文件冒充成品。 */
    public static void writeAtomically(byte[] bytes, Path target)

    /** 原子改名落盘；失败时清掉临时文件并抛异常。 */
    public static void moveAtomically(Path from, Path to)

    /** 删除文件（不存在即忽略）。清理属 best-effort，失败只记 DEBUG。 */
    public static void deleteQuietly(Path path)
}
```

工具类私有构造器：避免有人 `new ArtifactFiles()`。项目里 `SubtitleSegmentSplitter` 是有状态的实例类（带 `maxSegmentMs`），`ArtifactFiles` 无状态，故全静态。

**保留了两种原子落盘约定，没有统一**：

- `writeAtomically`（services 用）：`.tmp` + `REPLACE_EXISTING`
- `moveAtomically`（ffmpeg 用）：`.part` + `ATOMIC_MOVE`

它们看着像重复，其实机制不同——前者写字节（文件由我们创建），后者是 ffmpeg 自己写完了我们只做改名。统一会丢掉 `ATOMIC_MOVE` 的语义。**这是有意保留的两套，不是漏整理。**

### `utils/subtitle/SrtRenderer`

```java
public final class SrtRenderer {
    private SrtRenderer() {}

    /** 毫秒 → SRT 时间戳 HH:MM:SS,mmm；负值钳到 00:00:00,000。 */
    public static String formatTimestamp(long millis)

    /** 渲染整份 SRT；句数为 0 时返回空字符串。 */
    public static String toSrt(List<TranscriptionSegment> segments)
}
```

`toSrt` 自己调 `formatTimestamp`，两个方法内聚，放同一类。

### `utils/subtitle/SubtitleArtifactNames`

```java
public final class SubtitleArtifactNames {
    private SubtitleArtifactNames() {}

    /** 枚举名 → 产物 token：ASR_CLOUD → asr-cloud。 */
    public static String routeToken(SubtitleRoute route)

    public static String srtFileName(SubtitleRoute route)
    public static String segmentsFileName(SubtitleRoute route)
    public static String cleanSrtFileName(SubtitleRoute route)
    public static String cleanSegmentsFileName(SubtitleRoute route)
}
```

`cleanSrtFileName` / `cleanSegmentsFileName` 原本在 `SubtitleCleanupService`，但它们要调 `SubtitleService.routeToken`——**跨类调包级 static**，正是这次要消掉的味道。五个方法同属「产物叫什么名字」，收在一个类里。

**产物文件名一个字符都不变**，这是硬验收项（工作目录按媒体内容寻址，名字变了会让已有缓存全部失配）。

### `utils/text/TextTail`

```java
public final class TextTail {
    private TextTail() {}

    /** 取文本尾部若干行；全文可能很长，尾部通常已含失败原因。 */
    public static String tailLines(String text, int maxLines)
}
```

## 五、决策

### 决策 1：`utils/` 的两条边界都重划【已与所有者确认】

原规范那句「只收无 Spring / 无 I/O / 无 HTTP 的纯算法或静态助手」，是**从 `utils/subtitle/` 当时仅有的三个纯类反推**出来的，不是从判据推的——两条禁令在实际复用面前都站不住。

**I/O 边界**：重复量最大的两簇恰是 `Files.size` / `Files.write`。允许仅使用 `java.nio.file` 的本地文件系统助手；仍禁网络传输与外部进程调用。

**Spring 边界**：判据从「碰不碰 Spring 这个 jar」改为「**要不要容器跑起来**」。一句话自检——把 Spring 容器整个拿掉，`utils/` 还能不能编译并通过测试？

- **允许**：JDK、slf4j、Jackson（`utils/subtitle/SubtitleCorrector` 已依赖 `ObjectMapper`）、`java.nio.file`、`org.springframework.util.*` 这类纯静态工具
- **禁止**：Spring 注解（`@Component` / `@Service` / `@Autowired` / `@Value` / `@ConfigurationProperties` 等）、`ApplicationContext`、`Environment`、HTTP 类型

Spring 这条必须放宽，否则会同时违反两条项目级硬性要求：`error-handling.md` 要求字符串判空一律用 `StringUtils.hasText`（全库 8 个文件 36 处），`quality-guidelines.md` 头一条要求「不重复造轮子」。

放宽 I/O 需要一条配套护栏：**`model/` 不得依赖 `utils/fs/`**。理由是 `utils/` 从此不再保证「无副作用」，而 `model/` 的紧凑构造器是唯一会被「顺手调个工具方法」污染成 I/O 的地方。挡住它，放宽的代价就只剩「读代码时要看清是不是 fs 包」。

### 决策 2：六份大小判定归一，取带守卫的版本

六个副本其实是同一件事的两种写法：

| 版本 | 副本 | 差异 |
|---|---|---|
| 带守卫 | `FfmpegAudioExtractor`、`AudioPreparationService`、`SubtitleService`、`SubtitleCleanupService` | `Files.isRegularFile(p) ? Files.size(p) : 0L` |
| 无守卫 | `LocalFileMediaAcquirer`、`YtDlpMediaAcquirer` | 直接 `Files.size(p)`，靠 catch 兜底 |

取**带守卫**的版本。无守卫版本对目录调 `Files.size` 在部分文件系统上返回非 0（ext4 上目录就是 4096 字节），会让目录冒充「合法产物」。对 `LocalFileMediaAcquirer` / `YtDlpMediaAcquirer` 无影响（那两处的 Javadoc 写明「只用于日志度量，不影响契约」），对服务层的断点判定则是修 bug。

返回值用 `Math.max(Files.size(p), 0L)`：`AudioPreparationService` 与 `SubtitleService` 的版本已经这么写，防负数（文件系统异常时理论可达）。

### 决策 3：`moveAtomically` / `deleteQuietly` 虽然只有一个消费者，仍搬

按第一节的判据它们该留下。但那样会出现最坏结果：**同一套「原子落盘纪律」被劈成两半**——`writeAtomically` 在 `utils/fs/`，`moveAtomically` 留在 `FfmpegAudioExtractor`，后来者要搞清「产物怎么安全落盘」得查两个包。

规范的反模式清单针对的是「为单一实现预先造**接口**」，不是「静态助手只被用一次」。故一并搬。审查时若认为这违反规范精神，砍掉这两个方法即可，不影响其他搬迁。

### 决策 4：`TextTail` 用 `StringUtils.hasText`，`error-handling.md` 不动

本条**最初是反过来的**，经审查推翻——记在这里以免又被「整理」回去。

原打算：让 `utils/` 用 JDK 写法判空（`text == null || text.isBlank()`），并给 `error-handling.md` 第 66 行的「一律用 `StringUtils.hasText`」开一条 `utils/` 例外。

推翻的理由：`org.springframework.util.StringUtils` 是**纯静态工具，不牵扯容器**——用它不需要启动 Spring，不违反决策 1 里「脱离容器可用」那个承诺。把它换回 `text == null || text.isBlank()` 是**手搓轮子**，违反 `quality-guidelines.md` 第一条；开例外则会让项目里长期存在两套判空写法。

所以：`TextTail.tailLines` 直接用 `StringUtils.hasText(...)`，与库内其他 36 处保持一致，**`error-handling.md` 一个字不改**。

### 决策 5：`emit` 与异常消息兜底不搬

两者都是真重复，但都不搬：

- **`emit(listener, stage, progress, message, state)`**（`AudioPreparationService`、`SubtitleService` 各一份）：两份都在 `com.vidsage.service` **同一个包内**，且行为有别——`AudioPreparationService` 的版本记 `log.debug`，`SubtitleService` 的不记。搬进 `utils/` 要新建单方法包 `utils/progress`，还要为一个 debug 日志的行为差异做决定。收益不抵成本。**留原地**；真要清理，应该是「同包内提取到某个服务基类或直接让调用方自己 `listener.onEvent(new StageEvent(...))`」，那是另一个话题。
- **异常消息兜底**（`AudioPreparationService.describe` 与 `ApiExceptionHandler.errorMessage`）：确实跨了 service / web 两层。但方法只有一行，且两处上下文不同——一处拼进度事件文案，一处填 HTTP 响应体。新建 `utils/error/` 装一行代码，包本身的信息量还不如它的目录名。**留原地**。

若审查时认为 `utils/error/` 值得建，这是最容易追加的一项。

### 决策 6：测试跟随被测类

规范要求测试包与主包镜像对应。`SubtitleServiceTest` 里约 40 行针对 `routeToken` / `srtFileName` / `segmentsFileName` / `formatTimestamp` / `toSrt` 的用例，测的其实是纯函数，只因方法寄居在 `SubtitleService` 才落在这个测试类。搬迁后应整体迁到：

- `utils/subtitle/SrtRendererTest`
- `utils/subtitle/SubtitleArtifactNamesTest`
- `utils/text/TextTailTest`
- `utils/fs/ArtifactFilesTest`

`SubtitleServiceTest` 只留服务编排相关用例（断点复用、进度映射、异常路径等）。`SubtitleCleanupServiceTest` 与 `AudioPreparationServiceTest` 里对 `SubtitleService.segmentsFileName` / `AudioPreparationService.audioFileName` 的引用：前者改为引用新类，后者不动（`audioFileName` 不搬）。

`ArtifactFilesTest` 会写文件，必须用 `@TempDir(cleanup = CleanupMode.NEVER)`——规范里记了 Windows 上 `@TempDir` 清理偶发失败，已在两个测试类独立复现。

## 六、规范改动（逐字）

### `directory-structure.md` 第 199 行

原：

> **`utils/<领域>/` —— 领域化助手包（2026-09-27 立）**：`utils/` 是允许的顶层包，`utils/<领域>/` 装该功能的助手——**只收无 Spring / 无 I/O / 无 HTTP 的纯算法或静态助手**，以领域名限定归属；服务编排、Provider 实现、`*Properties`、契约模型一律不得进入。跨包使用须 `public`，纯内部助手保留包私有。参照：`utils/subtitle/`（...）

改为：

> **`utils/<领域>/` —— 领域化助手包（2026-09-27 立；2026-09-27 修订边界）**：`utils/` 是允许的顶层包，`utils/<领域>/` 装该功能的助手——**只收纯算法助手，以及仅使用 `java.nio.file` 的本地文件系统助手**；以领域名限定归属。服务编排、Provider 实现、`*Properties`、契约模型一律不得进入。
>
> **Spring 边界看容器，不看 jar**：判据是「把 Spring 容器整个拿掉，这个包还能不能编译并通过测试」——**能**才准进。故 `org.springframework.util.*` 这类纯静态工具**允许**；Spring 注解（`@Component` / `@Service` / `@Autowired` / `@Value` / `@ConfigurationProperties` 等）、`ApplicationContext`、`Environment`、HTTP 类型**禁止**。
>
> **其余允许**：JDK、slf4j、Jackson、`java.nio.file`。**其余禁止**：网络传输、外部进程调用。
>
> **`model/` 不得依赖 `utils/fs/`**——`model/` 类型的紧凑构造器里不许做 I/O。这条是允许 `utils/fs/` 存在的前提：`utils/` 从此不再保证「无副作用」，靠这条护栏挡住最危险的误用点。
>
> 跨包使用须 `public`，纯内部助手保留包私有。参照：`utils/subtitle/`（切分器 / 纠错器 / 相似度 / 渲染器 / 产物命名）、`utils/fs/ArtifactFiles`、`utils/text/TextTail`。

### `directory-structure.md` 顶层分包判据表

在 `config/` 行之后补一行：

| `utils/` | 跨领域的纯算法与本地文件系统助手 | 纯静态方法的工具类（私有构造器）；`utils/<领域>/` 二级分包；不要求容器的 `org.springframework.util.*` 工具 | 网络 / 外部进程 / Spring 注解与容器类型 / HTTP 类型；被 `model/` 依赖；业务编排 |

### `error-handling.md`

**不改。** 字符串判空规则保持全库统一，理由见决策 4。

### `directory-structure.md` 技术栈基线表

无改动。本任务不引入依赖。

## 七、行为变化清单

一共 3 处，逐条列出以便审查：

| # | 变化 | 为什么可接受 |
|---|---|---|
| 1 | `sizeOrZero` 归一后补 `Files.isRegularFile` 守卫；对 `LocalFileMediaAcquirer` / `YtDlpMediaAcquirer` 是行为变化（目录不再返回非 0） | 那两处的 Javadoc 写明「只用于日志度量，不影响契约」；对服务层是修 bug |
| 2 | `writeAtomically` 异常消息去掉「字幕 / 清洗」前缀，变成统一的「产物写入失败：<path>；…」 | 消息经 `{ "error": message }` 返回前端，属对外可见。但 `path` 已指明是哪个产物，「缺什么 + 怎么补」两要素不变。若审查要求逐字保留，改为给方法加一个 label 参数即可 |
| 3 | `sizeOrZero` / `isNonEmpty` 归一后不再记 debug 日志 | 被删的日志记的是「文件不存在」——那正是断点判定的正常路径，不是异常。`deleteQuietly` 的日志保留（清理失败是意外且否则无声） |

**产物文件名与内容、HTTP 契约、错误码一律不变。**

## 八、回滚

改动是本分支内的纯结构搬迁，无数据迁移、无配置变更、无依赖变更。回滚 = `git checkout dev` 丢弃分支。若只想撤一半，按 `implement.md` 的分步顺序反向撤——每步都只动一簇，互不依赖。

## 九、验证

```bash
cd backend && mvn test
```

外加人工核对（验收项）：

```bash
# 每个助手的定义只剩 1 处
grep -rn "static.*sizeOrZero\|static.*nonEmptySize\|static.*writeAtomically\|static.*tailLines" src/main/java

# utils/ 不依赖容器：应只命中 org.springframework.util.* 这类纯静态工具
grep -rn "org.springframework" src/main/java/com/vidsage/utils/ | grep -v "org.springframework.util"
# 更直接：查注解与容器类型，应当零命中
grep -rnE "@Component|@Service|@Autowired|@Value|@ConfigurationProperties|ApplicationContext|Environment" src/main/java/com/vidsage/utils/

# 产物文件名未变：SubtitleServiceTest 里既有的断言值原样保留
```
