# 形状重构 — 执行步骤

> **执行者是项目所有者本人。** 本文按概念拆成三个批次，可独立做、独立验证、独立回滚。
> 建议顺序就是下面的顺序：**产出物 → 阶段 → 身份**，风险递增（身份那批算错 key 会算错目录）。

## 三个分叉的定论（前置）

| 分叉 | 结论 |
|---|---|
| 1 阶段 | **A** —— 只单源（名字 + 权重边界），编排保持手写，权重保持两层结构（改扁平会改进度数值） |
| 2 产出物 | **枚举登记表** |
| 3 身份 | **`getContentKey()` 归输入自己 + `InWorkspace` 从"带结果"改成"带身份"** |

**开工前先跑一次基线**（本任务会移动/删除类，必须 `clean`）：

```bash
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q clean test
```
基线：**45 类 / 465 用例 / 0 失败 / 1 跳过**。数字对不上就先查基线，不要往下走。

---

# 批次 1：产出物登记表

**目标**：一个产出物的「叫什么 / 能不能下载 / 是不是检查点」三问，在同一行答完。
**改动面**：新增 1 个枚举；删 `TaskArtifactScan`；改 3 个 stage 类 + `VideoTaskService` + `SubtitleArtifactNames`。
**风险**：低（产物清单的对外行为不变，只是判据从字符串匹配换成声明）。

## 步骤 1.1 — 新建登记表

⚠️ **名字不能叫 `SubtitleArtifact`** —— 那个 record 已被 `prepare()` 的返回值占用。用 `SubtitleArtifactSpec`。

新文件：`backend/src/main/java/com/vidsage/domain/subtitle/model/common/SubtitleArtifactSpec.java`

```java
/**
 * 字幕产出物登记表：叫什么 / 能不能下载 / 是不是检查点。全项目唯一一处产出物定义。
 *
 * <p>以前这三件事分散在三处——命名在 SubtitleArtifactNames、检查点判据在 CheckpointPolicy、
 * 对外可见性在 TaskArtifactScan 的字符串白名单（`endsWith(".srt") || startsWith("segments-")`）。
 * 加一个产出物要去三个文件各加一段，而且第三处还跨在 domain/task 里，产物却是 subtitle 的。
 *
 * <p><b>改模板字符串 = 存量产物全部失配</b>：工作目录按媒体内容寻址，改名会让已有产物从
 * 「已完成」打回「未完成」。改之前想清楚。
 */
public enum SubtitleArtifactSpec {

    /** 原始转写检查点。 */
    SEGMENTS_RAW("segments-{route}.json", true, true),

    /** 原始 SRT：由 JSON 渲染的派生视图，不参与幂等判定。 */
    SRT_RAW("subtitles-{route}.srt", true, false),

    /** 清洗后检查点。 */
    SEGMENTS_CLEAN("segments-{route}-clean.json", true, true),
    SRT_CLEAN("subtitles-{route}-clean.srt", true, false),

    /** 翻译检查点。带目标语种指纹——换语种绝不拿上一份凑数。 */
    SEGMENTS_TRANSLATED("segments-{route}-translate-{lang}.json", true, true),
    SRT_TRANSLATED("subtitles-{route}-translate-{lang}.srt", true, false),

    /** 上下文摘要：清洗与翻译共用。故意不带语种指纹——主题/术语/源语种与译成什么语无关。 */
    CONTEXT("context-{route}.json", false, true),

    /** 术语查证结论：删文件即重查；查证失败的词不进文件，下次自然补查。 */
    TERMS("terms-{route}-{lang}.json", false, true);

    private final String template;
    private final boolean downloadable;
    private final boolean checkpoint;

    /**
     * 渲染文件名。
     *
     * @param routeToken 路线 token（kebab，如 asr-cloud）
     * @param lang       目标语种（已归一化）；模板不含 {lang} 时传任意值，不会被用到
     */
    public String fileName(String routeToken, String lang) {
        String name = template.replace("{route}", routeToken);
        return name.contains("{lang}") ? name.replace("{lang}", lang) : name;
    }

    /** 是不是断点复用的检查点判据载体。派生视图（SRT）为 false——它由 JSON 渲染而来。 */
    public boolean isCheckpoint() {
        return checkpoint;
    }

    /** 该产出物是否进对外产物清单（能不能被下载）。 */
    public boolean isDownloadable() {
        return downloadable;
    }
}
```

**一处诚实记下的代价**：`{lang}` 这种模板填装**不如原来类型安全**。现在 `translateSrtFileName(route, lang)` 少传一个参数编译不过；模板方式要运行时才发现。这是换法的代价，接受它。

## 步骤 1.2 — `SubtitleArtifactNames` 收敛成一层

保留 `routeToken()` 与 `normalizeLanguage()`（它们是 **token 归一化**，不是产出物属性）；其余 8 个 `xxxFileName` **全部删除**，调用点改走枚举。

改造前 → 改造后：

```java
// 前
Path segmentsPath = workDir.resolve(SubtitleArtifactNames.segmentsFileName(route));
// 后
Path segmentsPath = workDir.resolve(
        SubtitleArtifactSpec.SEGMENTS_RAW.fileName(SubtitleArtifactNames.routeToken(route), null));
```

这类拼接在 3 个 stage 里共约 15 处，**机械替换**。建议在 stage 里加一个私有小工具收口，避免每处都写 `routeToken + null`：

```java
/** 本阶段关心的产出物在本任务工作区的绝对路径。 */
private Path artifact(SubtitleArtifactSpec spec, String lang) {
    return workspaceService.workspaceFor(input)   // 或已算好的 workDir
            .resolve(spec.fileName(SubtitleArtifactNames.routeToken(route), lang));
}
```

## 步骤 1.3 — 删 `TaskArtifactScan`，可见性改由声明决定

**改造前**（后缀白名单——近似）：

```java
// domain/task/policy/TaskArtifactScan.java
public static boolean isVisible(String fileName) {
    return fileName.endsWith(".srt")
            || (fileName.startsWith("segments-") && fileName.endsWith(".json"));
}
```

**改造后**：整个类删除。`VideoTaskService.scanArtifacts` 改成「对已登记的产出物逐个查文件在不在」：

```java
/**
 * 扫描任务工作区，收集对外可见的产物清单。
 *
 * <p>判据来自产出物登记表（{@link SubtitleArtifactSpec#isDownloadable()}）——
 * 原来是后缀白名单（`.srt` / `segments-*.json`）的字符串近似，改判据后不会因为
 * 某个内部产物恰好叫 `.srt` 而被误判成可下载。
 */
private List<TaskArtifact> scanArtifacts(String taskNo, String workspaceDir) {
    if (!StringUtils.hasText(workspaceDir)) {
        return List.of();
    }
    Path dir = workspaceService.workspaceRoot().resolve(workspaceDir);
    List<TaskArtifact> found = new ArrayList<>();
    for (SubtitleArtifactSpec spec : SubtitleArtifactSpec.values()) {
        if (!spec.isDownloadable()) {
            continue;
        }
        // 带 {lang} 的产出物需要知道语种——见下面的「一个未决细节」
        ...
    }
    return found;
}
```

### 未决细节（2026-10-07 已定：方案 a）

带 `{lang}` 的三个产出物（翻译 segments / 双语 SRT / 术语）**扫目录时不知道语种**。定为 **(a)：枚举补 `matches(fileName)`**，保留"清单是一份扫描结果"的语义、不引入配置依赖。

⚠️ **实现 (a) 时踩到的坑**：**朴素匹配是有歧义的**。

`segments-{route}.json` 的 `{route}` 若是任意非空串，那么
`segments-asr-cloud-translate-zh.json` **也能被它匹配**（route 取 `asr-cloud-translate-zh`）。于是翻译检查点会被同时算成"原始转写检查点"，清单出现重复项。

**解法：`{route}` 只允许匹配已知的路线 token**，不允许任意串。路线 token 由 `SubtitleRoute` 全量渲染得出，而 `SubtitleArtifactSpec` 与它在**同一个包**（`domain/subtitle/model/common/`），所以不需要额外传参：

```java
/** 文件名是否由本模板渲染得出来（扫目录用）。{@route} 只认已知路线 token，避免把带后缀的档案也吃进来。 */
public boolean matches(String fileName) {
    return pattern().matcher(fileName).matches();
}

/**
 * 模板 → 正则：
 * {route} → 已知路线 token 的交替（如 asr-cloud|asr-local）——不写成 .+，
 *           否则 segments-{route}.json 会把 segments-xxx-translate-zh.json 也匹配掉
 * {lang}  → [a-z0-9-]+（非空）
 * 其余字面量按正则转义（模板里的 . 是真句点）
 */
private Pattern pattern() { ... }   // 懒初始化一次
```

`{lang}` 段保留通配是**有意**的：清单要反映磁盘上真实存在的东西——换了目标语种后旧语种的产物仍在磁盘上，扫描就该列出来。这正是 (a) 相对 (b) 保住的行为。

## 步骤 1.4 — 检查点判据改从登记表取

各 stage 的 `readCheckpoint` 不变（仍调 `CheckpointPolicy.isCompleted(非空)`），但**"哪些产出物是检查点"从隐式变显式**：非检查点（`isCheckpoint() == false`，即 SRT）不该有 `readCheckpoint` 这条路。

**注意**：这一步不要顺手改 `CheckpointPolicy` 的判据语义（"JSON 非空 = 完成、0 字节 = 未完成"那条是设计决策 4 的产物，动它会引入空转写死循环）。

## 批次 1 验证

```bash
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q clean test
```

断言（三条都要过）：

```bash
# ① 后缀白名单彻底消失
grep -rn 'endsWith(".srt")' backend/src/main   # 期望：无命中

# ② 产出物模板只在登记表里出现
grep -rn '"segments-\|"subtitles-\|"context-\|"terms-' backend/src/main \
  | grep -v SubtitleArtifactSpec.java          # 期望：无命中

# ③ TaskArtifactScan 已删除
ls backend/src/main/java/com/vidsage/domain/task/policy/   # 期望：只剩 TaskStageEvent
```

**回滚点**：`git reset --hard` 到批次开工前的提交。本批不碰 HTTP 契约与数据库，回滚零副作用。

---

# 批次 2：阶段名单源（权重不动）

> ✅ **2026-10-07 已做完，含真实验收**。现值：`acquire-media` / `extract-audio` / `transcribe-audio` /
> `write-subtitles` / `clean-subtitles` / `translate-subtitles`。
> 与下面这份计划有两处偏差：① 用 **enum** 而不是 String 常量（原方案的理由写在本文 :298，已被
> `SubtitleRoute`「无字段无方法的闭集枚举」这个同包先例否掉）；② **值改名（原计划的 Step B）随本批一次做掉**，
> 验证改用「把新值映射回旧值再 diff」，AC7 因此仍是机器可判。
> 验收证据、环境坑（yt-dlp 会员限制、`mvn test` 必须 `clean`）见 `handoff.md`。

**目标**：六个阶段名从三处变一处，各处文字复述改成引用。
**改动面**：新增 1 个类；删 6 个名字常量 + **7 处复述**。**权重那 11 个常量一个不碰。**
**风险**：低——不改进度数值，AC7 天然成立（但仍要跑真实任务确认一遍）。

> **本批不做权重**（2026-10-07 定）。原方案「把权重边界收进一处」不满足 AC3；而真正满足 AC3 的
> 「三层压成一层、每阶段一个权重」是**有意的行为变更**（进度数值会变、前端进度条走动方式跟着变），
> 与 AC7 直接冲突，已拆成独立任务（见 `prd.md` 的「后续任务」）。

> ⚠️ **2026-10-07 改名后重扫过**（那次改名已合入本分支）：**下面所有落点的行号一个都没变**——改名只动了类名和文件名。本文已换成新类名。
> 重扫时**新发现一处**：`src/main/resources/schema.sql:22` 的列注释也复述了全套六个阶段名。

> ✅ **新类名已定案（2026-10-07）：`SubtitleStageName`，落 `domain/subtitle/model/common/`。** 原方案的 `PipelineStage` 否掉（读起来是「管线里那一步的执行类」，与存量 `*Stage` 执行类撞义）；`StageName` 也否掉——不带归属，看不出是哪条管线的阶段。定的口径是**归属**：这六个名字是同一条管线（`SubtitlePipeline`，按产出命名）的步。
> **连带代价（已接受）**：`AudioPreparationStage`（media）与 `TaskStageEvent`（domain/task）都要 import 一个 subtitle 上下文的类——前者是 media→subtitle 首例，后者是 domain 层跨上下文引用首例（application 侧先例：`VideoTaskService` ← `SubtitleArtifactSpec`，批次 1 立的）。两边都不成环（已核实 subtitle 侧不 import task / media）。
> **连带改动**：`directory-structure.md:65` 那句「跨上下文共用的词汇进共享内核」的口径要跟着改（措辞待用户过目）；同文件 `:91` 尾句「`XxxStage` 只剩存量，不要再加新类」已按用户要求删除。

> ⚠️ **原方案有一句理由查证失实，已更正**：原文写「改一个名字不会编译失败，只会让前端按老名字渲染进度条的代码失效」。实际**前端不解析 stage 名**——进度条只吃 `progress`（数值）和 `message`（中文），`frontend/src/types/task.ts` 里 `stage` 只是个透传的 `string`。
> 真正的坑是：stage 名要落 `task.current_stage` 这个**库字段**，改名会让库里存量任务与新任务的阶段名静默对不上。

## 步骤 2.1 — 阶段名单源

新文件：`backend/src/main/java/com/vidsage/domain/subtitle/model/common/SubtitleStageName.java`

```java
/**
 * 管线阶段名。全项目唯一一处定义。
 *
 * <p>以前散在三处——AudioPreparationStage（acquire / extract-audio）、
 * SubtitlePreparationCoordinator（transcribe / write-subtitles）、StageEvent（clean / translate），
 * 加一个阶段要记得改三处，漏一处不报错，只是静默不一致。
 */
public final class SubtitleStageName {

    public static final String ACQUIRE = "acquire";
    public static final String EXTRACT_AUDIO = "extract-audio";
    public static final String TRANSCRIBE = "transcribe";
    public static final String WRITE_SUBTITLES = "write-subtitles";
    public static final String CLEAN = "clean";
    public static final String TRANSLATE = "translate";

    private SubtitleStageName() {
    }
}
```

### 改动点（引用 · 行号已按改名后核对）

| 文件 | 行 | 做什么 |
|---|---|---|
| `AudioPreparationStage` | `:45` `:48` | 删两个常量定义；`:138` `:141` `:143` `:146` `:166` `:169` `:171` `:174` 八处 `emit` 改引 `SubtitleStageName.*` |
| `SubtitlePreparationCoordinator` | `:57` `:62` | 删两个常量定义；`:149` `:150` `:161` `:164` `:172` `:175` 六处 `emit` 改引 |
| `StageEvent` | `:22` `:25` | 删 `STAGE_TRANSLATE` / `STAGE_CLEAN` 两个定义 |
| `SubtitleCleanupCoordinator` | `:153` `:157` `:192` | 三处 `StageEvent.STAGE_CLEAN` 改引 |
| `SubtitleTranslationCoordinator` | `:197` `:201` `:262` | 三处 `StageEvent.STAGE_TRANSLATE` 改引 |
| `TaskStageEvent` | `:68` `:70` `:72` | 三处 `StageEvent.STAGE_TRANSLATE` 改引（`:64` 的 `@return` Javadoc 跟改） |
| `LlmSubtitleCleanser` | `:52` `:110` `:205` `:209` | 注释 + 两处 `{@link}` + 一处构造调用 |
| `LlmSubtitleTranslator` | `:61` `:126` `:206` `:210` | 同上 |

**顺带（唯一允许碰的权重相关改动）**：`AudioPreparationStage` 四个进度常量 `ACQUIRE_START` / `ACQUIRE_DONE` / `EXTRACT_START` / `EXTRACT_DONE`（`:54` `:56` `:58` `:60`）是**包级可见**但本包外零引用（已核实）——收成 `private`。**只改可见性，不改值。**

### 复述清单（7 处，改完必须为零）

**散点 3 处：**
1. `StageEvent:14` 的 `@param stage`（`如 "acquire" / "extract-audio" / {@link #STAGE_TRANSLATE}`）→ 改 `{@link SubtitleStageName}`
2. `StageEvent:9-12` 整段「阶段词表」历史（说「归位本类」——本批后就不在本类了）→ 重写为指向 `SubtitleStageName`
3. `VideoTask:182-183` 的 `@param stage` 六词列表 → 改引用

**全套名单 4 处（这才是「加一个阶段要改几处」的真正落点，且改了不会编译失败）：**

| 文件 | 行 | 内容 |
|---|---|---|
| `SubtitlePipeline.java` | `:34-35` | 「内部含 acquire / extract-audio / transcribe / write-subtitles 四个阶段」 |
| `SubtitlePipeline.java` | `:60` | 「prepare 全包阶段（acquire / extract-audio / transcribe / write-subtitles）映射进 0..70」 |
| `application/media/package-info.java` | `:7` | 「（acquire → extract-audio 两阶段编排）」 |
| `resources/schema.sql` | `:22` | 列注释「最近阶段名（与 StageEvent.stage 同词表：acquire/extract-audio/…）」——**改它要动 DDL 注释，想清楚再动** |

**还有 5 处「说我自己驱动的那一步」**（点名是对的，最佳形态是换成 `{@link SubtitleStageName#X}`，不是删）：
`AudioPreparationStage:22` `:98` `:152`、`SubtitlePreparationCoordinator:44` `:80`。

**为什么用 String 常量而不是枚举**：调用点零摩擦（`SubtitleStageName.ACQUIRE` 本身就是 `String`，`emit(listener, SubtitleStageName.ACQUIRE, …)` 与现在逐字一样），而且**不碰 JSON 契约**（`StageEvent.stage` 是 String，直接序列化成 SSE 的 data）。等扁平化那步真需要「阶段 + 权重」的载体时，再决定要不要变成枚举。

## 批次 2 验证

```bash
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
```

```bash
# ① 六个阶段名的字面量只在 SubtitleStageName 出现
grep -rn '"acquire"\|"extract-audio"\|"transcribe"\|"write-subtitles"\|"clean"\|"translate"' \
  backend/src/main | grep -v SubtitleStageName.java     # 期望：无命中

# ② 旧的名字常量已清零
grep -rn 'STAGE_ACQUIRE\|STAGE_EXTRACT\|STAGE_TRANSCRIBE\|STAGE_WRITE_SUBTITLES\|STAGE_CLEAN\|STAGE_TRANSLATE' \
  backend/src/main                                    # 期望：无命中

# ③ 权重常量一个没动（AC3'）
git diff -U0 | grep -E '^[+-].*(ACQUIRE_START|ACQUIRE_DONE|EXTRACT_START|EXTRACT_DONE|ACQUIRE_END|TRANSCRIBE_START|TRANSCRIBE_DONE|WRITE_DONE|PREPARE_END|CLEAN_START|CLEAN_END)'
# 期望：只出现 AudioPreparationStage 那四个的 `static final int` → `private static final int`（可见性收窄），数值一行没变
```

**本批不需要 `clean`**——只删常量、不删类，`target/classes` 不会留陈旧副本。`mvn -q test` 即可（跑全量是为了确认没有别处被带崩）。

> ⚠️ **2026-10-07 实测更正**：不 `clean` 会挂——`NoClassDefFoundError` + JUnit 连测试类都发现不了（IDE 同时在写 `target/classes` 时更容易踩）。**一律 `mvn -q clean test`**；本批实测 45 类 / 469 用例 / 0 失败 / 0 错误 / 1 跳过。

**最重要的一条：进度事件逐条比对（AC7）**

`mvn test` **抓不到进度数值变化**——它是行为契约，不是编译契约。必须手工验：

1. 改之前，起实例跑一条真实任务（B站链接或本地 mp4），订阅 SSE，把事件流存下来：
   ```bash
   curl -N http://localhost:18080/api/tasks/{taskNo}/events > before.txt
   ```
2. 改之后，**删掉工作区产物**（否则断点复用会跳过阶段、事件流不完整），跑同一输入，存 `after.txt`
3. 逐条比对：
   ```bash
   diff <(sed 's/"taskNo":"[^"]*"/"taskNo":"X"/' before.txt) \
        <(sed 's/"taskNo":"[^"]*"/"taskNo":"X"/' after.txt)
   ```
   期望：**除 taskNo 外零差异**。

⚠️ 起实例的两个坑（已实测）：`TaskStop` 杀不掉 `spring-boot:run` 的子 JVM，必须按 PID `taskkill //PID <pid> //F`；Git Bash 的 `/tmp` 与 Windows 程序的不通用，文件一律用相对路径或 Windows 绝对路径。

**回滚点**：同上，`git reset --hard` 到批次开工前。

---

# 批次 3：身份归属

> ✅ **2026-10-08 已完成并通过验收**（四路线目录名 before/after 逐字一致，AC7 表述已按 prd 打勾时的修正为准）。
> **与本节计划的偏差**（按实际发生的记录）：
> ① 实现名为 `FileStateReader.getFileState` / `NioFileStateReader` / `getContentKey`——用户定「按代码名为准」，
> 文档已全部向代码对齐（本文上一版写的 `FileStatReader.stat` / `contentKey` 是定案讨论时的拟名，作废）。
> ② 两条 `workspaceFor` **没有合并**——原计划「合并成一条」有鸡生蛋洞（提交时刻 `InWorkspace` 的 value
> 要等目录算出才能拼），双参 `workspaceFor(label, key)` 保留为「提交时刻专用」，分工写在方法注释里。
> ③ `WorkspaceServiceTest` 用**真实** `NioFileStateReader` 而非 fake（夹具本来就写真文件，保真）；
> 另按 ③ 落点补了 `NioFileStateReaderTest` 四例（正常读 / 不存在 / 目录 / null）。

**目标**：身份由输入自己算（`WorkspaceService` 的 switch 消失）；`InWorkspace` 从"带结果"改成"带身份"。
**改动面**：`MediaInput`（3 个变体各加实现）+ 新增 `MediaFileInfo` + 新增 `FileStateReader` 端口（`application/media/port/`）与 `NioFileStateReader` 实现（`infrastructure/fs/`）+ `WorkspaceService` + `MediaInput.ofTaskInput` + 类注释。
**风险**：**高**——身份算错就是工作区目录算错，产物落错地方或断点复用失效。

> **复核（2026-10-07，批次 2 之后按当前代码重扫）**——两个原草案没写的落点：
> ① `WorkspaceService.normalize` 有**跨包消费方**：`VideoTaskService:100` 用它算落库的 `workspace_key`（URL 路线）。
> 删 `normalize` 时那点要改成 `input.getContentKey(MediaFileInfo.NOT_APPLICABLE)`（URL 路线不需要文件事实，零 I/O）；
> `VideoTaskService:105` 的 `WorkspaceDir.of(input.value(), workspaceKey)` 改用 `input.dirLabel()` 之后目录名逐字不变。
> ② 测试侧要改的（AI 做）：`WorkspaceServiceTest` 的 `WorkspaceService.localPathKey` ×3 处（:57 :60 :65）与两参
> `workspaceFor(label, key)` ×3 处（:103 :113 :115）；`VideoTaskServiceTest:93` 的 `WorkspaceService.normalize`。
> 其余测试只是 `when(workspaceService.workspaceFor(any()))` 打桩，不受影响。
> ③ 端口化新增的测试落点（2026-10-08 定案后补，AI 做）：`WorkspaceServiceTest` 构造处多传一个 fake
> `FileStateReader`；原 `localPathKey` 的三处断言改走 fake，或迁移为 `NioFileStateReader` 的真实临时文件单测；
> 另新增 `NioFileStateReader` 单测（正常读 / 文件不存在 / 传入目录 → 数值 -1 占位、不抛异常、快照 path 与传入一致）。

## 步骤 3.1 — `MediaFileInfo` 值对象

新文件：`backend/src/main/java/com/vidsage/domain/media/model/MediaFileInfo.java`

```java
/**
 * 媒体文件的文件系统快照：路径 + 大小 + 最后修改时间。
 *
 * <p>快照自带参照物（path）——观测必须说得清「看的是哪个文件」。只有本地文件路线的身份
 * 需要它（身份＝路径｜大小｜mtime）。domain 层不做 I/O，stat 由 {@code FileStateReader}
 * 端口（infrastructure 实现）采样后传进来——**身份公式在 domain，文件系统读数由外部提供**。
 * 文件不存在 / 不可读时数值用 {@link #UNAVAILABLE} 占位、path 仍为传入原样
 * （与原 localPathKey 的行为一致：不报错，权威报错归输入获取适配器）。
 *
 * @param path           被观测的文件路径，传入原样、不 absolute 化（absolute 化归 {@code SourceUri} 公式）；
 *                       仅哨兵 {@link #NOT_APPLICABLE} 为 null
 * @param sizeBytes      文件大小；不可用时为 -1
 * @param lastModifiedMs 最后修改时间毫秒；不可用时为 -1
 */
public record MediaFileInfo(Path path, long sizeBytes, long lastModifiedMs) {

    public static final long UNAVAILABLE = -1L;
    /** URL / 上传路线的哨兵：没有文件可观测，path 为 null、数值全 -1，接收方一律忽略。 */
    public static final MediaFileInfo NOT_APPLICABLE = new MediaFileInfo(null, UNAVAILABLE, UNAVAILABLE);
}
```

## 步骤 3.2 — `FileStateReader` 端口与 NIO 实现（2026-10-08 定案）

采样动作经端口进 infrastructure——文件读取是基础设施的活。两处落位的判据：

- **端口落 `application/media/port/`**：契约是技术词汇（`Path` 进、stat 读数出），兜底问题
  「删掉这个端口业务规则还成立吗」答「成立」→ application 端口（directory-structure.md 端口归属判据）。
- **实现落 `infrastructure/fs/`（新开顶层桶）**：按能力来源分桶——DB→`persistence`、本机进程→`process`、
  文件→`fs`，`provider/` 留给外部服务与工具。**注意**：`LocalFileMediaAcquirer` 按此分类也属 fs，
  但批 3 不动它（import 面大、与身份归属无关，要挪另立任务）。

新文件：`backend/src/main/java/com/vidsage/application/media/port/FileStateReader.java`

```java
/**
 * 本地文件 stat 采样端口：读「路径 + 大小 + 最后修改时间」快照。
 * 文件系统读数进应用层的唯一入口——application 不直接碰 {@code Files.size} 这类读数调用。
 */
public interface FileStateReader {

    /**
     * 读文件快照。<b>不抛异常</b>：文件不存在、不可读、不是普通文件时返回 -1 占位数值。
     *
     * @param path 被观测路径；快照的 path 与此参数<b>原样一致</b>（不 absolute 化，absolute 化归 SourceUri 公式）
     * @return 至少包含 path 的快照；{@code null} 参数返回 {@link MediaFileInfo#NOT_APPLICABLE}
     */
    MediaFileInfo getFileState(Path path);
}
```

新文件：`backend/src/main/java/com/vidsage/infrastructure/fs/NioFileStateReader.java`

```java
/**
 * FileStateReader 的 JDK（java.nio.file）实现：stat 读数与原 WorkspaceService.localPathKey 逐字一致——
 * 读不到就降级为占位值、只 log.debug 不报错（权威报错归输入获取适配器，此处重复报只会让错误来源含糊）。
 */
@Component
public class NioFileStateReader implements FileStateReader {

    @Override
    public MediaFileInfo getFileState(Path path) {
        if (path == null) {
            return MediaFileInfo.NOT_APPLICABLE;
        }
        try {
            if (Files.isRegularFile(path)) {
                return new MediaFileInfo(path, Files.size(path),
                        Files.getLastModifiedTime(path).toMillis());
            }
        } catch (IOException e) {
            log.debug("读取本地文件属性失败，按占位值计算工作目录: file={}, reason={}",
                    path.getFileName(), e.getMessage());
        }
        return new MediaFileInfo(path, MediaFileInfo.UNAVAILABLE, MediaFileInfo.UNAVAILABLE);
    }
}
```

**两条容易写错的地方**：

1. **读不到时返回 `(path, -1, -1)`，不是 `NOT_APPLICABLE`**——快照的 path 必须与传入一致，
   否则下一步 `LocalPath.getContentKey` 的断言会把「读不到」误判成「观测了错文件」而当场抛错
   （行为对比：原 `localPathKey` 对不存在的文件照样算出 `path|-1|-1` 身份，不报错）。
2. 日志文案与触发条件（`isRegularFile` 为 false 不打日志、异常才打）**照抄**原 `localPathKey`，行为零变化。

## 步骤 3.3 — 身份成为输入的属性

`MediaInput` **改造前**：

```java
public sealed interface MediaInput {
    record RemoteUrl(String value) implements MediaInput { }
    record LocalPath(Path value) implements MediaInput { }
    record InWorkspace(Path value, Path workspaceDir, WorkspaceKey workspaceKey) implements MediaInput { }
    // 身份由 WorkspaceService.normalize() 的 switch 从外面推
}
```

**改造后**：

```java
public sealed interface MediaInput {

    /**
     * 本输入的内容寻址 key——决定它落在哪个工作区目录。
     *
     * <p>身份**不由外部按变体分派**：每个变体自己知道自己的身份是什么。上传路线当初就是
     * 在这里绕不过去——它的身份是文件内容的 sha256，从位置推不出来，所以只能算出来之后
     * 带着走（见 {@link InWorkspace}）。
     *
     * @param facts 文件系统快照（路径 / 大小 / 修改时间）；只有 {@link LocalPath} 需要
     *              并校验快照 path 与自己一致，其余变体忽略
     */
    WorkspaceKey getContentKey(MediaFileInfo facts);

    /** 目录名前半段的人读标签。唯一性完全由 key 保证，这里只是给人看的。 */
    String dirLabel();

    /**
     * 公网可访问的媒体页面地址。
     * @param value 完整 URL；<b>不要传本地文件路径</b>（本地文件请用 {@link LocalPath}）
     */
    record RemoteUrl(String value) implements MediaInput {
        @Override
        public WorkspaceKey getContentKey(MediaFileInfo facts) {
            return WorkspaceKey.ofHash(value == null ? "" : value);
        }
        @Override
        public String dirLabel() {
            return value == null ? "" : value;
        }
    }

    /** 本机可读的媒体文件路径，直接采用，不下载、不拷贝。 */
    record LocalPath(Path value) implements MediaInput {
        @Override
        public WorkspaceKey getContentKey(MediaFileInfo facts) {
            // 观测必须关于本输入：path 对不上（含 null 哨兵误传）当场报错，正常路径永不触发
            if (facts == null || !Objects.equals(value, facts.path())) {
                throw new IllegalArgumentException(
                        "文件快照与输入不一致: input=" + value + ", snapshot="
                                + (facts == null ? null : facts.path()));
            }
            return WorkspaceKey.ofHash(SourceUri.ofLocalFile(facts.path(), facts.sizeBytes(),
                    facts.lastModifiedMs()).value());
        }
        @Override
        public String dirLabel() {
            Path fileName = value == null ? null : value.getFileName();
            return fileName == null ? "" : fileName.toString();
        }
    }

    /**
     * 已在任务工作区内的媒体文件（上传路线）：身份是文件内容的 sha256，上传时流式边写边算出来的，
     * 无法从位置重推，所以随输入携带。
     *
     * <p><b>携带的是身份，不是结果</b>：目录名由 {@code WorkspaceDir.of(dirLabel, key)} 重算，
     * 与提交时逐字相同——搬整个已解析的目录过来会让「哪个是真身份」有两个说法。
     *
     * @param value    媒体文件路径（已在工作区内，如 {@code <工作区>/source.mp4}）
     * @param key      内容寻址 key（文件内容 sha256 前 12 位）
     * @param dirLabel 目录名的标签段，即上传时的原始文件名
     */
    record InWorkspace(Path value, WorkspaceKey key, String dirLabel) implements MediaInput {
        @Override
        public WorkspaceKey getContentKey(MediaFileInfo facts) {
            return key;      // 身份就在身上，不用算
        }
        // dirLabel() 由 record 的访问器自动满足接口方法
    }
}
```

**注意**：`InWorkspace` 的字段名用 `key`（不是 `getContentKey`），否则和接口方法同名冲突。

**类注释要改写**——现在那句「本类型只承载『位置』」是错的（它自己破了）。改成：

```java
/**
 * 媒体输入来源：契约描述「媒体从哪里来」以及「它的身份是什么」。
 *
 * <p>身份必须由输入自己给出，不能由外部按变体分派——否则加一种输入来源就要回头改
 * WorkspaceService。三条路线里两条的身份从位置推出（URL 原文 / 路径+大小+mtime），
 * 上传路线不行：它的身份是内容 sha256，只能算完带着走（{@link InWorkspace}）。
 */
```

## 步骤 3.4 — `WorkspaceService` 去 switch、去 `localPathKey`

```java
// 前：三路 switch，还带一个抛异常的第三支
public static SourceUri normalize(MediaInput input) {
    return switch (input) {
        case MediaInput.RemoteUrl url   -> SourceUri.ofRemoteUrl(url.value());
        case MediaInput.LocalPath local -> localPathKey(local.value());
        case MediaInput.InWorkspace ignored -> throw new IllegalStateException(...);
    };
}

// 后：删掉 normalize、删掉 localPathKey；stat 读数全部经端口，本类只剩"取事实"
public Path workspaceFor(MediaInput input) {
    if (input == null) {
        throw new IllegalArgumentException("媒体输入不能为空：请传 RemoteUrl / LocalPath / InWorkspace");
    }
    MediaFileInfo facts = factsOf(input);
    return ensureCreated(rootDir().resolve(
            WorkspaceDir.of(input.dirLabel(), input.getContentKey(facts)).value()));
}

/**
 * stat 只在本地文件路线需要；这是"取事实"，不是"选公式"——公式已经住在变体里了。
 * 路径<b>原样</b>传给端口（快照 path 必须与 input.value 一致，getContentKey 的断言靠这个）；
 * 降级、占位、日志都在 NioFileStateReader 侧，本类不再 try-catch。
 */
private MediaFileInfo factsOf(MediaInput input) {
    if (!(input instanceof MediaInput.LocalPath local) || local.value() == null) {
        return MediaFileInfo.NOT_APPLICABLE;
    }
    return fileStateReader.getFileState(local.value());
}
```

**配套改动**：

- 构造函数加 `FileStateReader` 参数（构造注入，quality-guidelines）：
  `public WorkspaceService(WorkspaceProperties properties, FileStateReader fileStateReader)`。
- `Files.size` / `Files.isRegularFile` / `Files.getLastModifiedTime` / `IOException` 的 import 移出本类
  （`Files.createDirectories` 仍留——懒建目录是本类职责，不受影响）。
- 原 `static SourceUri localPathKey(Path)` **整个删除**：读数进了端口、公式进了 `getContentKey`，它两头都不剩。
  类注释里「文件 stat 在本类完成（技术 I/O）」改成「文件 stat 经 `FileStateReader` 端口采样」。
- 包可见性提示：`factsOf` 由 `private static` 变 **`private` 实例方法**（要摸实例的端口字段）。

**原来的两条入口 `workspaceFor(MediaInput)` / `workspaceFor(String label, WorkspaceKey key)` 合并成一条**——第二条（上传路线用）不再需要单独存在，因为 `InWorkspace` 现在能自己给出 key。`VideoTaskService.submitUpload` 的 `settleUpload` 要跟着改成先拼 `InWorkspace` 再调 `workspaceFor`。

## 步骤 3.5 — `ofTaskInput` 跟着改

```java
// 前
case UPLOAD -> new InWorkspace(workspaceDir.resolve(UPLOAD_SOURCE_FILE_NAME), workspaceDir, workspaceKey);
// 后：把 sourceUri 当目录标签（提交时建目录用的就是它）
case UPLOAD -> new InWorkspace(workspaceDir.resolve(UPLOAD_SOURCE_FILE_NAME), workspaceKey, sourceUri);
```

## 批次 3 验证

```bash
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q clean test
```

```bash
# ① normalize 与三路 switch 已消失
grep -rn "normalize(MediaInput\|SourceUri normalize" backend/src/main   # 期望：无命中

# ② WorkspaceService 里不再有按变体分派的 switch
grep -n "case MediaInput" backend/src/main/java/com/vidsage/application/media/WorkspaceService.java
# 期望：无命中（残留的 instanceof 只为取 facts，形式不同）

# ③ stat 读数已移出 application（经端口进 infrastructure/fs；懒建目录的 createDirectories 不算读数，不受此约束）
grep -rn "Files\.size\|Files\.isRegularFile\|getLastModifiedTime" backend/src/main/java/com/vidsage/application
# 期望：无命中
```

**③ 最重要的一条：工作区目录逐字一致（AC7）**

身份算错不会编译报错，也不会让测试失败——**它只会让产物落错地方**。必须手工验：

对三条路线各取一个输入，**改造前后**的目录名必须逐字相同：

| 路线 | 验证办法 |
|---|---|
| URL | 提交同一链接，比对 `task.workspace_dir` 与工作区实际目录名 |
| 上传 | 上传同一文件，比对同上；**再重传一次**，确认仍命中同一目录（内容寻址） |
| 本地文件 | 用同一个本地 mp4，比对同上；**再 `touch` 一次该文件**，确认目录变了（这是三元组身份应有的行为，不是 bug） |

```sql
-- 库里落的就是权威值
SELECT task_no, workspace_dir, workspace_key FROM task ORDER BY created_at DESC LIMIT 5;
```

**回滚点**：同上。

---

# 全部做完之后

1. **跑一次端到端真实链路**（提交 → 字幕 → 清洗 → 翻译），确认 SSE 事件序列与产物清单都对
2. **补写 `design.md` 的「变更类型 → 落点清单」终稿**——现在那里是雏形，改造完才知道每条落点**实际**是几处（AC6 要求每行可 grep 验证）
3. 回写 `.trellis/spec/backend/directory-structure.md`——**这次是确定要改的**：分层归属表加
   `infrastructure/fs/` 一行（文件系统出站适配）；「端口归属判据」处可补 `FileStateReader` 作
   「技术词汇 → application 端口」的实例

## 本任务的诚实边界

- **「加一种状态 / 子状态」和「加一个 provider」两行落点清单留空**——前者是决策 4（生命周期不统一，不做），后者不在本任务范围
- **音频产物（`AudioArtifact.fileName`）不纳入登记表**——它在 media 上下文，且本就不对外可见。产出物概念横跨两个上下文，本批只做 subtitle 侧
