# 执行计划：字幕产物命名去路线化与 SubtitleRoute 退场

设计与依据见 `design.md`；需求与验收见 `prd.md`。

## 分工（沿用子任务 B 已定的口径）

- **`backend/src/main` 由项目所有者手写**；AI 负责 `backend/src/test`、spec 更新、运行期验收
- `design.md` / `implement.md` **是参考稿不是契约**：与代码不符时按仓库惯例订正，判据以代码为准
- **硬门**：完成即停等审查；禁止链式 commit / push / merge / 归档，逐门等批准

## 动工前的两道验证（**必做，结论会改写后面的批次**）

### V1 · 未知 JSON 字段是否被忽略（`prd.md` AC9）——**已实测，结论：会被忽略**

`SubtitleTrack` / `TranslatedTrack` 去掉 `route` 字段后，旧检查点 JSON 会多出一个未知键。

**实测手段**：起真实 Spring 容器（`VidSageApplication` + `application-local.yml`，本机 MySQL），
拿一个必然不会被声明成字段的键 `__probe` 塞进 `SubtitleTrack` 的 JSON，分别用「容器注入的那个
mapper」与「测试里 `new ObjectMapper()` 那个」解析。两侧结果：

| mapper | 类型 | `FAIL_ON_UNKNOWN_PROPERTIES` | 多出未知键时 |
|---|---|---|---|
| 容器注入（coordinator 拿到手的） | `tools.jackson.databind.json.JsonMapper`（Boot 的 `jacksonJsonMapper`，全仓唯一一个） | **false** | 解析成功 |
| 测试 `new ObjectMapper()` | `tools.jackson.databind.ObjectMapper` | **false** | 解析成功 |

**结论**：AC9 成立，无需给 domain record 加 `@JsonIgnoreProperties`，也无需改 `readClean` 的失败语义。
批 5 直接用现成写法钉一条测试即可。

**顺带查实两条（会省掉无用的改动）**：

1. **本项目全程 Jackson 3**（`tools.jackson.*`）——全仓 `com.fasterxml.jackson.databind.ObjectMapper`
   使用者 **0 处**；容器里 Jackson 2 的 `ObjectMapper` bean **不存在**（`getBeansOfType` 查得 0）。
   别按 Jackson 2 的默认值推这套代码的行为
2. **Jackson 3 把 `FAIL_ON_UNKNOWN_PROPERTIES` 的默认值改成了 false**——所以「测试用裸 mapper
   会抛异常」那个担心不成立（在 Jackson 2 下才成立）。`Jackson2ObjectMapperBuilder` 亦已被
   spring-web 标为待删除，与本项目无关

> 实测用的一次性程序放在系统临时目录（`V1Spring.java`），未入库。

### V2 · 测试基线 ——**已实测，结论：动手前就没有基线（干净 HEAD 编译不过）**

```bash
cd backend
rm -rf target/surefire-reports            # 陈旧报告会让统计失真
JAVA_HOME=/f/java_soft/java21 mvn -q clean test
```

**实测（detached worktree，`ffe641a`，未带工作区任何草稿）**：`mvn clean test-compile` **退出码 1**，
**没有跑到任何测试**。测试侧红面是 **12 个文件编译不过**：

| 测试文件 | 错误条数 |
|---|---|
| `application/task/pipeline/SubtitlePipelineTest` | 44 |
| `domain/task/VideoTaskTest` | 42 |
| `application/subtitle/pipeline/SubtitleTranslationCoordinatorTest` | 26 |
| `web/controller/TaskControllerTest` | 21 |
| `application/task/VideoTaskServiceTest` | 18 |
| `application/task/message/TaskProgressMessageTest` | 10 |
| `domain/subtitle/policy/Bcp47LanguagePolicyTest` | 10 |
| `application/task/notify/TaskProgressNotifierTest` | 8 |
| `infrastructure/persistence/.../MybatisVideoTaskRepositoryTest` | 6 |
| `.../TermVerificationTest` | 3 |
| `.../TaskSseConnectionManagerTest` | 2 |
| `.../TaskSubscriptionRegistryTest` | 1 |

> **⚠ 量这个面必须绕开 `mvn` 的报错清单**（2026-10-14 实测的错判，当天就地订正）：
> `mvn clean test-compile` 当时只报 **5** 个文件，据此写下的基线是错的。根因是 **javac 达到
> `-Xmaxerrs` 上限就停，剩下的文件根本没被检查**，而 maven 的 compiler 插件默认带这个上限。
> 同一份 HEAD 源码实测：`-Xmaxerrs 100` 报 6 个文件（且与 maven 报的那 5 个不是同一组），
> `-Xmaxerrs 10000` 报 12 个。**正确的量法**是直接叫 javac 并放宽上限：
>
> ```bash
> cd backend && find src/test/java -name "*.java" > /tmp/testsrc.txt
> javac -encoding UTF-8 -proc:none -nowarn -Xmaxerrs 10000 \
>   -cp "target/classes;$(cat /tmp/cp.txt)" -d /tmp/tout @/tmp/testsrc.txt
> # /tmp/cp.txt 由 mvn dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt 生成
> grep -oE "[A-Za-z0-9_]+\.java:[0-9]+" <log> | sed 's/:.*//' | sort | uniq -c
> ```
>
> 注意 `target/classes` 得先有货：main 编译红着时用 `mvn compile -Dmaven.compiler.failOnError=false`
> 把能编的 class 都发出来。

**成因与本任务无关**，全是父任务 `10-11-subtitle-multilang-translate` 在 `ffe641a` 留的半截债
（生产侧删了 `translationStatus` 子状态、改了 `translate(...)` 签名，测试侧未同步）：

- 消失的成员：`getTranslationStatus()` / `translationStatus()` / `translationStart()` /
  `translationSucceed()` / `TaskProgressMessage.of` 少一个参数 / `fromTranslation(...)`
- `translate(...)` 新签名要 `(MediaInput, SupportedLanguage 源, SupportedLanguage 目标, ProgressListener)`，
  测试仍在按老签名调（50+ 处）
- 测试里 `sourceLanguage` / `targetLanguage` / `request` 三个局部量不存在

**决定（项目所有者，2026-10-14）：这些文件不动，留给父任务。**
（当时据 maven 的清单误报为 5 个；真实是 12 个，见上表警告框。原则不变——**不替父任务还债**，
但范围以 12 个为准；其中 `Bcp47LanguagePolicyTest` / `TermVerificationTest` 等与翻译子状态
看不出直接关系的几个，待确认是否同一笔债。）

因此本节基线值**为「无」**，`prd.md` AC10 的口径随之下调 —— 见「AC10 口径」一节。
批 5 的测试交付**在本分支上跑不起来**（maven 在 test-compile 阶段就死）。

**绕行办法（批 5 用）**：`mvn clean compile` → 手工 `javac` 把目标测试类编进 `target/test-classes`
→ `mvn surefire:test -Dtest=<类名>`（`surefire:test` 不触发 compile 阶段，红名单原封不动）。
这样本任务自己的测试能实跑；等父任务还完债，它们自动回到正常流水。

## AC10 口径 ——**待批准**（V2 实测后按现状下调）

`prd.md` 原文：「`mvn clean test-compile` 与 `mvn test` 全绿，且测试总数不低于动手前的基线」。
V2 实测后，**前半句在本分支上不可能达成**（动手前就编译不过，且那批文件已定「不动」）。

建议改成：

> **AC10**（改）本任务的**生产代码改动**能通过 `mvn clean test-compile` 的 main 部分编译
> （`mvn clean compile` 退出码 0）；本任务新增/更新的测试能编译并跑通（用 V2 那节的绕行办法实跑）；
> **不新增失败**——父任务遗留的 **12** 个测试文件保持原状，不因本任务改动而扩大失败面
> （量法见 V2 的警告框，别用 `mvn` 的报错清单数）。

**已知代价**（写明白）：本分支交出时整棵 `mvn test` 仍是红的；`SubtitleTranslationCoordinatorTest`
因两边都占（父任务债 + 本任务 AC3）会被本任务改动 route 相关行，其文件级失败状态不变。

## 批 0 · 建分支 ——**已完成**

`feature/subtitle-artifact-naming` 已存在且为当前分支，`task.json` 的 `branch` / `base_branch`
均已设好；实测 `git merge-base --is-ancestor feature/subtitle-multilang-translate HEAD` 通过
（HEAD = `ffe641a` = base 顶端）。

> 原命令留档：
>
> ```bash
> cd /f/personal_project/vidSage
> git status --porcelain          # 先确认工作区状态（含 record 草稿等未提交文件）
> git checkout -b feature/subtitle-artifact-naming
> python ./.trellis/scripts/task.py set-branch 10-14-subtitle-artifact-naming feature/subtitle-artifact-naming
> python ./.trellis/scripts/task.py set-base-branch 10-14-subtitle-artifact-naming feature/subtitle-multilang-translate
> ```

- **base_branch 必须是 `feature/subtitle-multilang-translate`**（本任务从它切出 → 合回它），不是 `dev`、不是 `master`
- 回滚点：`git checkout feature/subtitle-multilang-translate` + `git branch -D feature/subtitle-artifact-naming`

## 批 1 · 命名改造（`SubtitleArtifactSpec` + 5 处调用点）

改（`src/main`，项目所有者执行）：

1. `SubtitleArtifactSpec` 的 8 个常量与模板（`design.md` §2.1 表）
2. `fileName(SubtitleRoute, String)` → `getFileName(String)`（实测名，见 design.md §2.1 注）
3. 删 `compileTemplate` 的 `case "{route}"` 分支 + `RouteAlternation` 整块 + 两个随之失效的 import

调用点（5 处，逐个改签名）：

```bash
grep -rn "SubtitleArtifactSpec\.[A-Z_]*\.getFileName(\|\.fileName(route" backend/src/
```

预期命中：`SubtitlePreparationCoordinator:103-104`、`SubtitleCleanupCoordinator:178-180`、`:208`、
`SubtitleTranslationCoordinator:152-155`、`:178`，以及 `hasTranslationArtifact`（待删，见批 2）。

验证：

```bash
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean test-compile
```

> **判编译一律 `clean`**：`mvn compile` 增量编译会假绿（没被碰过的文件即使编译不过也不报）。

**门**：编译过 → 停，等审查。

## 批 2 · `SubtitleRoute` 退场

按 `design.md` §3 的清单 1→12 顺序做。要点：

- **先删 record 字段，再删枚举**，否则中间态编译不过
- 三个 coordinator 的 `SubtitleRoute route = asrProvider.route();` 与
  `new XxxSubtitle(…, track.route(), …)` 一起清
- 顺带删掉死代码 `SubtitleTranslationCoordinator.hasTranslationArtifact`
  （无生产调用，只服务它的 `props.targetLanguage()` 也一并失去唯一调用者）
  —— **但注意 `Bcp47LanguagePolicy.resolveTargetLanguage` 与
  `ChatClientConfig:116` 的 `translateProperties.targetLanguage()` 是生产活代码，别删**（子任务 B 的
  `handoff.md` §12 订正过这条）

验证：

```bash
grep -rn "SubtitleRoute" backend/src/                      # 期望：空
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean test-compile
```

**门**：`grep` 为空 + 编译过 → 停，等审查。

## 批 3 · `raw` → `original` 词汇对齐

范围**限定**在 `design.md` §4 表里列的位置。动手前先 grep 算账：

```bash
grep -rn "\braw\b\|Raw" backend/src/main/java/com/vidsage/domain/subtitle/ \
    backend/src/main/java/com/vidsage/application/subtitle/
```

> **别批量替换小写 `raw`**：先逐条看语义，确认是「原始字幕」才换；同包内若有别的含义的 `raw`，
> 留着。

**门**：编译过 → 停，等审查。

## 批 4 · `SubtitleWorkspaceFilePaths`

**前置**：三条决策已定（2026-10-14），见 4.0。`design.md` §5.1 的甲/乙/丙与 §7.1 的列表形状
两条**不再是开放项**——落位选了新桶，形状选嵌套。

### 4.0 开工前定的三条（**已定，照此执行**）

| # | 决策 | 定案 | 理由 |
|---|---|---|---|
| ① | 落位 | **新建 `application/subtitle/workspace/`** | `domain/` 排除：`domain/shared` 是共享内核（实测被 7 个上下文依赖、**自身零反依赖**），而工厂要认 `SubtitleArtifactSpec`（`domain/subtitle/`）——留在那里会成为共享内核第一次反依赖上下文。`shared/` 排除：规范明令不得收契约模型（`directory-structure.md:391`）。`query/` 排除：它现有含义是**对外读模型**（`TaskSnapshot` 那族），本类型是**管线内部的工作区视图**，名不副实 |
| ② | 缺席表示法 | `Optional<Path>` 字段 | 实测媒体三项 17 个工作区里最多 15 个有——**缺席是常态不是异常**；`null` 会逼每个消费者判空 |
| ③ | 「集中」的粒度 | **组装集中**，不动既有结构 | 各条命名规则仍各归各家（`SubtitleArtifactSpec` / `AudioArtifact` / `MediaInput` / `YtDlpMediaAcquirer` / `WorkspaceDir` 一律不搬），本 record 就是**唯一的组装点**。跨上下文合并成一个「工作区文件台账」的更大集中**另开任务**，不在本任务内 |

**新桶要办的入册手续（别漏）**：

1. 建 `application/subtitle/workspace/package-info.java`——本仓库每个桶都有，且规范要求
   「空目录不表达任何约束」，桶说明得写清它装什么（照 `application/task/subscribe/package-info.java`
   的形状写：一句「装什么」+ 一条「不许装什么」）
2. **批 6 要把这个新桶登记进 `directory-structure.md`** 的二级包清单，否则下个会话读规范时
   这个桶是个孤儿

### 4.1 目标形状（可直接照抄）

```java
package com.vidsage.application.subtitle.workspace;

public record SubtitleWorkspaceFilePaths(
        Optional<Path> originalSegmentsJsonFilePath,
        Optional<Path> originalSrtFilePath,
        Optional<Path> cleanSegmentsJsonFilePath,
        Optional<Path> cleanSrtFilePath,
        Optional<Path> contextJsonFilePath,
        List<TranslationFiles> translations,      // 按语种成组，语种顺序稳定
        Optional<Path> sourceMediaFilePath,
        Optional<Path> sourceInfoJsonFilePath,
        Optional<Path> audioFilePath) {

    /**
     * 一个目标语种的三件产物。哪件缺席由它自己答——「没有专有名词」和「翻译失败」
     * 都表现为 terms 空，这在元素内部看得见，不用去猜列表下标。
     *
     * @param targetLanguage BCP-47 短码（已归一化）
     * @param termsJsonFilePath 术语查证结论；SKIP_NO_TERMS / 查证未启用 / 全部查证失败时为空
     */
    public record TranslationFiles(String targetLanguage,
                                   Optional<Path> segmentsJsonFilePath,
                                   Optional<Path> srtFilePath,
                                   Optional<Path> termsJsonFilePath) {
    }
}
```

**为什么必须嵌套**（实测依据）：`terms` 与 `translate` 的份数不保证相同——盘上 7 个有译文的
工作区里 **5 个已经错位**（如 `BV1C2ae6DEqW` 译文 `[en]` / 术语 `[]`）。三个平行 `List<Path>`
的任何按下标配对的消费者都会错，且在类型上看不出来。

### 4.2 `of(Path workDirectory)` 的算法

```java
private static final String SENTINEL_LANG = "@";   // 见 4.3 的哨兵说明

public static SubtitleWorkspaceFilePaths of(Path workDirectory) {
    if (workDirectory == null) {
        throw new IllegalArgumentException("工作区目录不能为空");
    }
    // 1) 只列一次目录；列不出（目录不存在 / 不是目录）按空工作区处理，不抛
    List<String> names = listFileNames(workDirectory);
    // 2) 5 个非语种产物：拿 getFileName(null) 的精确名，在 names 里找
    // 3) 语种集合：见 4.2.1
    // 4) 每语种组 TranslationFiles：按 spec.getFileName(lang) 取精确名
    // 5) 媒体三项：见 4.2.2
}
```

- **`workDirectory == null` 是唯一抛的情形**（调用方传参错）。**其余一律不抛**——
  「没找到」的语义是「下游判重跑」，不是错误（`CheckpointPolicy` 那条判据的上游就是这个）。
- 名字一律**从第 1 步的 `names` 里比对**，不再对每个路径单独 stat：一次 I/O，且目录不存在时
  行为统一（全 `empty`）。
- 用 try-with-resources 包 `Files.list`（现有 `SubtitleCleanupCoordinator` 同一写法）。

#### 4.2.1 语种集合怎么切（命名规则仍只有一份）

不另写正则、不硬编码 `translated-segments-`，而是**拿模板自己当模板**：

```java
String probe = SubtitleArtifactSpec.TRANSLATED_SEGMENTS.getFileName(SENTINEL_LANG);
int at = probe.indexOf(SENTINEL_LANG);
String prefix = probe.substring(0, at);        // "translated-segments-"
String suffix = probe.substring(at + 1);       // ".json"
// 对每个 name：startsWith(prefix) && endsWith(suffix) && 中间非空 → 中间那段是语种
```

三个带语种的产物（`TRANSLATED_SEGMENTS` / `TRANSLATED_SRT` / `TERMS`）各切一次，
**取三个集合的并集**——不能只看 `TRANSLATED_SEGMENTS`：术语查证在翻译之前跑，
翻译若中途失败，盘上会出现「有 terms、没 segments」的语种，只看 segments 会漏掉它。

**并集必须排序后落进 `translations`**（`Files.list` 的顺序依赖文件系统，不排序测试会飘）。

#### 4.2.2 媒体三项

| 字段 | 认法 | 实测 |
|---|---|---|
| `sourceInfoJsonFilePath` | 精确名 `source.info.json` | 17 个工作区里 11 个有 |
| `sourceMediaFilePath` | `source.` 打头且**不是** `source.info.json` | 14 个有 |
| `audioFilePath` | `audio-` 打头 | 15 个有 |

- **为什么只能扫**：媒体扩展名由 yt-dlp 定（`source.%(ext)s`），音频名要 `AudioSpec` 才算得出
  （`AudioArtifact.fileName(spec)`），而 `of(Path)` 手上只有目录。
- 三项**都常常缺席**（本地文件输入没有 info json、未抽音频的工作区没有 audio）→ 一律 `Optional.empty()`。
- 同前缀命中多个时（重跑残留）**取排序后第一个**，不抛——确定性优先于猜「哪个才对」。
- `source.info.json` 这个字面量目前只在 `infrastructure/.../YtDlpMediaAcquirer.INFO_JSON_NAME`
  （包级私有）里有一份，本节会在 application 层再写一次。**这是有意接受的小重复**：
  搬到 `domain/media/model`（与 `MediaInput.UPLOAD_SOURCE_FILE_NAME` 同处）能消掉它，
  但多一次跨层搬迁，收益不抵成本。

### 4.3 两处必须写进注释的取舍

1. **哨兵为什么用 `"@"`**：`getFileName` 会过 `Bcp47LanguagePolicy.resolveTargetLanguage`，
   实测它只做「非空 + `trim()` + `toLowerCase()`」、**不查白名单**。`"@"` 是 0x40，
   `String.trim()` 剥的是 ≤ U+0020，且无大小写形态——**过这两道而不变形**。
   换成 `"zh"` 这类真语种也能用，但会被自己的 `toLowerCase` 掩盖问题；换空白字符直接抛 ISE。
2. **为什么不做校验/不变式**：这个 record 是「现状的快照」而不是「合法状态的断言」。
   加了「segments 必须在 srt 也必须在」这类不变式，会在断点复用、上次翻译失败重跑等
   正常场景下抛异常，把一个「该重跑」的信号变成硬失败。

### 4.4 自测清单（批 5 由 AI 写成 `SubtitleWorkspaceFilePathsTest`，对应 AC8）

| 情形 | 断言要点 |
|---|---|
| 全产物齐 | 8 项都 `isPresent`；`translations` 语种齐全且有序 |
| 缺 `terms`（无专有名词的常见场景） | 该语种的 `TranslationFiles.termsJsonFilePath` 空，**其余两项在**；且不影响别的语种 |
| 空工作区 | 全部 `empty` / 空列表，**不抛** |
| 目录不存在 | 同上（`Files.list` 抛的 `NoSuchFileException` 被吞成空工作区） |
| `workDirectory == null` | `IllegalArgumentException`（唯一抛的情形） |
| 两个语种 | 两份 `TranslationFiles` 同时在、互不覆盖（AC7 的单测面） |

**门**：编译过 + 上述自测绿 → 停，等审查。

## 批 5 · 测试（AI 负责）

1. 修既有测试：所有 `SubtitleRoute.` 引用、`fileName` 新签名、五个 record 去字段、
   `ASRProvider` 去方法
2. 补新测试：
   - `SubtitleArtifactSpecTest`：8 个新模板逐条钉住（含 `{lang}` 归一化、非语种模板传 `null` 不抛）
   - `SubtitleWorkspaceFilePathsTest`：**全产物齐 / 缺 `terms` / 空工作区**三种情形（`prd.md` AC8）
   - **未知字段容忍测试**（V1 的结论落地，`prd.md` AC9）：造一份含 `route` 键的 JSON，断言能解析
3. 重跑并比对基线：

```bash
cd backend
rm -rf target/surefire-reports
JAVA_HOME=/f/java_soft/java21 mvn -q clean test
```

**门**：全绿且测试总数 ≥ V2 基线 → 停，等审查。

## 批 6 · spec 更新（AI 负责）

- `directory-structure.md`：产物命名字面（`segments-<route>.json` 等）改新名；`Bcp47LanguagePolicy`
  的政策清单按实际能力订正；`TaskArtifactScan`（已不存在）删除；`shared/subtitle/` 的「产物命名」描述订正
- `error-handling.md`：`:62` / `:154` 两条「项目当前不定义自定义异常类」订正
  （`UnsupportedLanguageException` + `ApiExceptionHandler` 已成事实）
- `frontend/*`：`translate` 要 body、`translationStatus` 消失、新增 `GET /api/languages`（**前端另开任务**）

**门**：停，等审查。

## 批 7 · 运行期验收（AI 负责）

```bash
# 1) 存量留档（不迁移）——★已完成（2026-10-14）
cd /f/personal_project/vidSage/backend
mv data/works data/works-before          # 旧目录整块留作快照；新目录由应用懒创建
```

**改名前实测留档**：17 个工作区 / 348M / 148 个文件；盘上全部产物名带 `asr-cloud`（无 `asr-local`），
分布 `segments` 16 / `clean-segments` 14 / `context` 13 / 翻译 11 组（ja 6、en 5、zh·zh-hans·zh-hant 各 1）
/ `terms` 7 份——印证 prd 里「`terms` 与 `translate` 份数不等」。
改名后 `data/works-before` 计数与改名前逐项一致，`data/works` 为空目录。
**且实测无任何测试读真实 `data/works`**（只有 `NioWorkspaceDirectoryManagerTest` 涉及，用临时目录）
——所以测试红与本任务无关。

```bash
# 2) 起服务（测试红着时必须跳过测试编译：spring-boot:run 绑 test-compile，-DskipTests 不够）
SERVER_PORT=18080 JAVA_HOME=/f/java_soft/java21 \
  mvn -q -Dmaven.test.skip=true spring-boot:run > /tmp/vidsage-run.log 2>&1

# 3) 收工：按 PID 杀子 JVM（TaskStop 只杀 mvn 包装进程，子 JVM 继续占端口）
netstat -ano | grep LISTENING | grep :18080
taskkill //PID <pid> //F
```

> **起服务的两个前置**（2026-10-14 实测踩过）：
> 1. `application-local.yml`（DB 连接）**在 `.gitignore` 里**——新检出的 worktree 没有它，
>    容器会以「Failed to configure a DataSource」启动失败。起服务前确认它在
>    `backend/src/main/resources/` 下
> 2. 本机 MySQL 需在 3306 上（已确认在）

逐条走 `prd.md` 的 AC：

| AC | 手段 |
|---|---|
| AC1 | `ls data/works/<新目录>`，逐个比对 `design.md` §2.1 的表；`grep -c "asr-cloud" <(ls ...)` = 0 |
| AC5 | `curl GET /api/tasks/{taskNo}`，看 `artifacts` 里 6 类可下载产物是新名 |
| AC6 | 重复触发同一语种，日志有「断点复用跳过」、不调 LLM |
| AC7 | 同一工作区翻两个目标语种，两份 `translated-*` 并存、互不覆盖 |

> SSE 抓取：**提交与订阅放同一条 shell**；触发前订阅会被终态快照立刻关掉。
> 验收素材建议沿用子任务 B 用过的那条 B站 视频（其旧产物在 `data/works-before/` 里可对照）。

**门**：AC 逐条有结果 → 停，等审查。

## 批 8 · 收尾

- 更新本目录 `implement.md`（把 V2 基线、V1 结论、实际批次偏差写回）
- 更新子任务 B 的 `handoff.md`（本任务完成后 B 恢复）
- 提交 / 合并 / 归档 **逐门等批准**；合并目标是 `feature/subtitle-multilang-translate`

## 验证命令备忘

```bash
# 判编译：一律 clean
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean test-compile

# 全量测试：量前先删 surefire-reports
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q clean test

# 推送：直连 GitHub 必失败，走本机代理
https_proxy=http://127.0.0.1:7897 git push
```

> **退出码会被管道吃掉**（2026-10-14 实测踩到）：`mvn ... | tail -60` 回显的 `$?` 是 `tail` 的，
> **不是 maven 的**——maven 明明失败（493 行 ERROR）也回显 `exited with code 0`。
> 判绿一律看 `[ERROR]` 段与 `BUILD SUCCESS/FAILURE`，或把退出码单独存下来。

## 回滚

| 阶段 | 回滚 |
|---|---|
| 批 0 后 | `git checkout feature/subtitle-multilang-translate` + `git branch -D feature/subtitle-artifact-naming` |
| 任一批后 | `git checkout -- <文件>` / `git stash`（逐批提交前工作区可整体丢弃） |
| 批 7 后 | 把 `data/works-before` 改回 `data/works` 即回到旧状态（快照目录没动过） |
