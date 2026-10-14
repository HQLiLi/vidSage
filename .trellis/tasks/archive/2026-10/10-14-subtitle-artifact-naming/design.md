# 设计：字幕产物命名去路线化与 SubtitleRoute 退场

需求与验收标准见 `prd.md`。本文件只写技术设计。

## 1. 摘要

两条改动互为因果：

1. **产物名去路线** —— `SubtitleArtifactSpec` 的模板去掉 `{route}` 段、`fileName` 去掉
   `SubtitleRoute` 参数。改完后「同一份产物只有唯一名字」，不再有「换 ASR provider 后旧检查点
   骗过断点判据」的区分需求（该需求由路线 token 承担，而它现在是唯一承担者）。
2. **`SubtitleRoute` 整体退场** —— 全仓审计确认它对**任何判断**都不起作用（`prd.md` R3 的
   判定依据）。第 1 条做完后它连文件名都不进，彻底没有存在理由。

附带两件：`raw` 词汇对齐为 `original`；落地 `SubtitleWorkspaceFilePaths`。

## 2. 新命名落到代码

### 2.1 `SubtitleArtifactSpec` 的枚举常量与模板

| 常量 | 模板 | `downloadable` | `checkpoint` |
|---|---|---|---|
| `ORIGINAL_SEGMENTS` | `original-segments.json` | ✓ | ✓ |
| `ORIGINAL_SRT` | `original-subtitles.srt` | ✓ | ✗ |
| `CLEAN_SEGMENTS` | `clean-segments.json` | ✓ | ✓ |
| `CLEAN_SRT` | `clean-subtitles.srt` | ✓ | ✗ |
| `TRANSLATED_SEGMENTS` | `translated-segments-{lang}.json` | ✓ | ✓ |
| `TRANSLATED_SRT` | `translated-subtitles-{lang}.srt` | ✓ | ✗ |
| `CONTEXT` | `context.json` | ✗ | ✓ |
| `TERMS` | `terms-{lang}.json` | ✗ | ✓ |

两个布尔一律不动：**JSON 是检查点、SRT 是派生视图**这条分工与命名无关。

> 上表已按 2026-10-14 落地的代码订正（判据以代码为准）。相对本文件初稿的三处偏差：
> SRT 侧常量用 `_SRT` 而非 `_SUBTITLES`；翻译侧常量与文件名统一用 `translated-` 而非
> `translate-`（理由见 `prd.md` R1 的订正段）；取值方法叫 `getFileName` 而非 `fileName`
> （与 `SubtitleStageName` 的 Lombok `getStageName()` 惯例一致）。
>
> 改常量名的连带：`SEGMENTS_RAW` → `ORIGINAL_SEGMENTS`、`SRT_RAW` → `ORIGINAL_SRT`、
> `SEGMENTS_CLEAN` → `CLEAN_SEGMENTS`、`SEGMENTS_TRANSLATED` → `TRANSLATED_SEGMENTS`、
> `SRT_TRANSLATED` → `TRANSLATED_SRT`。全仓引用点由 implement.md 的批 1 覆盖。

### 2.2 `fileName` 新签名

```java
public String getFileName(String lang) {
    return template.contains("{lang}")
            ? template.replace("{lang}", Bcp47LanguagePolicy.resolveTargetLanguage(lang))
            : template;
}
```

不带 `{lang}` 的模板（`original-segments` / `original-subtitles` / `clean-*` / `context`）
**完全不碰 `lang` 参数**——这条短路必须保留：调用方传 `null` 是常态，`resolveTargetLanguage(null)`
会抛 ISE。

### 2.3 一并删掉的东西

- `compileTemplate` 里的 `case "{route}"` 分支
- `RouteAlternation` 懒加载持有类（整块）
- `SubtitleArtifactSpec` 的 `import java.util.Arrays` / `java.util.stream.Collectors`（只服务于它）

`compileTemplate` 的机制**保留**——「未知占位符早失败」那条守卫有价值：
模板写错时早抛，而不是被当字面量、永远匹配不到任何文件且不报错。

## 3. `SubtitleRoute` 退场清单

按依赖顺序：

| # | 文件 | 现状 | 改法 |
|---|---|---|---|
| 1 | `domain/subtitle/model/common/SubtitleRoute.java` | 枚举（4 值，仅 `ASR_CLOUD` 实产） | **删文件** |
| 2 | `domain/subtitle/model/common/SubtitleArtifactSpec.java` | 见 §2 | 去 route |
| 3 | `domain/subtitle/model/cleanup/SubtitleTrack.java` | `(SubtitleRoute route, String language, …)` | 去首字段 |
| 4 | `domain/subtitle/model/translation/TranslatedTrack.java` | 同 | 去首字段 |
| 5 | `domain/subtitle/model/common/SubtitleArtifact.java` | `(Path, Path, SubtitleRoute, …)` | 去字段 |
| 6 | `domain/subtitle/model/cleanup/CleanedSubtitle.java` | 同 | 去字段 |
| 7 | `domain/subtitle/model/translation/TranslatedSubtitle.java` | 同 | 去字段 |
| 8 | `domain/transcription/port/ASRProvider.java` | `SubtitleRoute route();` | 删方法 |
| 9 | `infrastructure/provider/asr/dashscope/DashScopeAsrProvider.java` | `route()` 实现 | 删 |
| 10 | 三个 `Subtitle*Coordinator` | `SubtitleRoute route = asrProvider.route();` + 构造返回时传 `track.route()` | 删局部量、构造点去参数 |
| 11 | `domain/subtitle/package-info.java` | 列举里提到 `SubtitleRoute` | 改文档 |
| 12 | 测试 | 所有 `SubtitleRoute.` 引用（含常量 `ROUTE` 之类） | 删或改 |

**3–7 是「落盘契约」变更**：`SubtitleTrack` / `TranslatedTrack` 是 JSON 检查点的持久化形状，
少一个字段意味着**旧文件里多一个未知字段**——见 §6 的兼容性。

## 4. `raw` → `original` 词汇对齐

| 位置 | 现状 | 改后 |
|---|---|---|
| `SubtitleArtifactSpec` 常量 | `SEGMENTS_RAW` / `SRT_RAW` | `ORIGINAL_SEGMENTS` / `ORIGINAL_SRT` |
| `SubtitlePreparationCoordinator` | `readRaw(...)`、局部量 `rawSeg` | `readOriginal(...)`、`originalSeg` |
| `SubtitleCleanupCoordinator` | `readRaw(...)`、局部量 `rawSeg` / `raw` | 同上 |
| 各 coordinator 注释 | 「原始字幕」等描述 | 与新名字对齐，用 `original` |
| `.trellis/spec/backend/*` | `segments-<route>.json` 这类字面 | 改新名（批 6） |

范围限定在**字幕域**（`domain/subtitle/` + 三个 `Subtitle*Coordinator`）。`domain/media` 与
其他地方若有同名的 `raw`，先 grep 确认语义无关再决定是否动——**不要把别处的 `raw` 一起换掉**。

## 5. `SubtitleWorkspaceFilePaths`

> **本节是设计稿；as-built 的真实形态（方法名、字段名、文件划分）见 `implement.md`
> §4.1 的代码与 §4.2.3 的差异表。**

### 5.1 落位 —— **已定：新建 `application/workspace/`**（顶层包，2026-10-14）

原稿列了甲（`application/subtitle/query/`，推荐）/ 乙（`application/task/query/`）/ 丙（原地
`domain/shared/model/`）三选一，**三条都没选**，改选了新桶。理由按排除法：

1. **`domain/shared/` 排除**——不是「domain 不许 I/O」那么简单（规范字面限的是**紧凑构造器**，
   而工厂是 static 方法，够不着）。真正的问题是：`domain/shared` 是**共享内核**，
   实测被 7 个上下文依赖（`domain/{media,subtitle,task}`、`application/{media,subtitle,task}`、
   `infrastructure` 三处、`shared/progress`），而**它自身零反依赖**——里面唯一的跨文件 import 是
   `ProgressListener → StageProgress`（都在 shared 内）。工厂要认 `SubtitleArtifactSpec`
   （`domain/subtitle/`）与 `domain/media` 的文件名，留在这儿就是共享内核**第一次反过来依赖
   依赖它的上下文**，共享内核的前提当场作废
2. **`shared/` 排除**——规范明令 `shared/<领域>/` 「契约模型一律不得进入」（`directory-structure.md:391`）
3. **`query/` 排除**——它在本仓库的现有含义是**对外读模型**（`TaskSnapshot` / `TaskArtifact` /
   `TaskArtifactFile`，面向 REST 查询的投影）。本类型是**管线内部的工作区视图**，零 web 注解、
   不上接口，名不副实
4. **归 `subtitle` 上下文**——它的含义是「一次字幕任务的工作区长什么样」，消费方全是字幕管线

新桶的入册手续：建 `package-info.java`（写清装什么、不许装什么），并把该桶登记进
`directory-structure.md` 的二级包清单（批 6）。做法见 `implement.md` §4。

> 另有第三条路「类型留 domain、工厂挪 application（拆开）」被否：多一个类，且 domain 里躺着一个
> 只有 application 消费的词汇——在 5.4 的「组装集中」定案下没有收益。

### 5.2 字段 —— **已定：嵌套 + `Optional`**

字幕侧 8 项 + 媒体侧 3 项。翻译三项**不按平行 `List<Path>` 收**，改用
`List<TranslationFilePaths>`，元素内带语种（详见 §7.1）。所有「可能没有」的项用 `Optional<Path>`，
不用 `null`（实测媒体三项在 17 个工作区里最多 15 个有——缺席是常态）。

### 5.3 工厂 `of(Path workDirectory)` 的算法

route 消失后，字幕侧的非语种产物已**完全确定**，只有语种产物与媒体文件需要扫：

```
1. 列目录一次 → List<String> names（目录不存在按空工作区处理，不抛）
2. 媒体侧（名字不含语种，按前缀认）：
   - source.info.json           → 精确名
   - source.* 且非 info          → sourceMedia（source.<ext> 的 ext 只有 info json 知道，只能扫）
   - audio-*                     → audio
   同前缀命中多个 → 取排序后第一个，不抛
3. 语种集合：三个带语种的 spec（TRANSLATED_SEGMENTS / TRANSLATED_SRT / TERMS）
   各调自己的 `getLanguage(name)` 反解出语种，**取三者并集**
   （术语查证跑在翻译之前，会出现「有 terms 没 segments」的语种），
   并**排序**（Files.list 顺序依赖文件系统）
4. 非语种产物：拿 SPEC.getFileName(null) 的精确名在 names 里比对（不再逐个 stat）
5. 缺席一律 Optional.empty() / 空 List，**不抛**（「没找到就走下游判重跑」）
```

**反解归位**（2026-10-14 定）：原稿的「哨兵 `"@"` 切前后缀」已废弃。反解改由登记表自己提供
——`SubtitleArtifactSpec` 的 `{lang}` 正则加**一对**捕获组，新增 `getLanguage(String fileName)`。
（只写右括号会让正则语法错，`Pattern.compile` 在枚举 `<clinit>` 里抛，整个类不可用。）
正解反解同源于模板那一份正则；顺带拆掉一颗雷：哨兵原本依赖
`resolveTargetLanguage` 只做 trim + toLowerCase 不做白名单校验（已实测确实是），
那个方法将来一旦加校验，整个 `of` 会当场炸。

`workDirectory == null` 才抛 `InvalidWorkDirectoryException`（`extends IllegalArgumentException`；
调用方传参错）。目录在却读不出来时抛 `UncheckedIOException`（环境故障，不伪装成「没产出」）。

`workDirectory == null` 才抛 `IllegalArgumentException`（调用方传参错）。

### 5.4 「集中」的粒度 —— **已定：组装集中**（2026-10-14）

产物名今天散在 5 处（`SubtitleArtifactSpec` / `AudioArtifact.fileName` /
`MediaInput.UPLOAD_SOURCE_FILE_NAME` / `YtDlpMediaAcquirer` 的两个常量 / `WorkspaceDir.of`）。
曾考虑合并成一个「工作区文件台账」，**否**：会撞两条线（`shared/` 不得收契约模型；四层结构是
按上下文分包，合并等于造新的跨上下文巧合）。

定案：**各条命名规则各归各家原地不动，本 record 就是唯一的组装点**——这正是 R4 的意图。
跨上下文的更大集中另开任务。

## 6. 兼容性与存量处理

- **不迁移**（`prd.md` R5）：`backend/data/works` 整体改名留快照，新产物重新生成
- **旧检查点 JSON 多一个 `route` 键**：domain record 没加 `@JsonIgnoreProperties(ignoreUnknown=true)`，
  全仓也没有覆盖 Boot 自动配置的 `FAIL_ON_UNKNOWN_PROPERTIES`（`application.yml` 无 `jackson` 段、
  无自定义 `ObjectMapper` bean）→ 预期**被静默忽略**。
  **这是读配置推断的，必须实测**（`prd.md` AC9）：若不成立，`readClean` 会抛 ISE 让翻译**硬失败**，
  与「当未完成重跑」是两种代价
- **旧产物从 artifacts 清单消失**：`scanArtifacts` 按新模板过滤，旧名匹配不上 → 清单变空。
  已接受（快照目录仍可人工查）

## 7. 风险与开放项

### 7.1 三个平行 `List<Path>` 的下标不可靠（**已定：改用嵌套元素**）

现有字段形状是：

```java
List<Path> translatedSegmentsJsonFilePath,
List<Path> translatedSrtFilePath,
List<Path> translatedTermsJsonFilePath,   // ← 长度不保证与上面相同
```

`terms` 与 `translate` 的份数**不保证相同**——`SubtitleTranslationCoordinator.verifyTermsIfNeeded`
有三条**设计如此**的不落盘路径：`SKIP_NO_TERMS`（视频没有专有名词，完全正常场景）、
`SKIP_LOOKUP_DISABLED`（provider 未配置）、全部查证失败（`!outcome.persistable()`）。
盘上实测 7 个有译文的工作区里 **5 个**已经错位（如 `BV1C2ae6DEqW` 译文 `[en]` / 术语 `[]`）。

**任何按下标配对的消费者都会错**。两个出路：

- **(甲) 嵌套元素** —— **已定（2026-10-14）**：`List<TranslationFilePaths> translationFilePaths`，
  `TranslationFilePaths` **单独成文件**（`application/workspace/`），字段是
  `(langCode, translatedSegmentsJsonFilePath, translatedSrtFilePath, translatedTermsJsonFilePath)`，
  把「这个语种有没有 terms」变成元素内部的事
- ~~(乙) 保住平行列表~~——否：它要求每个消费者自己反解语种并记住「不可压配对」，把一条
  类型上本该成立的约束推给了纪律

### 7.2 其他

- **`from` vs `of`** —— **已定：`of`**。仓库惯例是「构造自己→`of*`，跨类型映射→`from`」，
  本工厂入参是原始值（`Path`），形如 `WorkspaceDir.of(label, key)`
- **落位**（§5.1）—— **已定**：新建顶层包 `application/workspace/`
- **`INFO_JSON_NAME`** —— **改判（2026-10-14）**：原稿写「不搬迁」，理由是收益不抵一次跨层搬迁。
  但既然要顺手把媒体侧的前缀也收成常量，搬迁成本就被摊薄了，**结论翻成搬**：
  `source.` 前缀族收进 `MediaInput`（`SOURCE_FILE_PREFIX` / `UPLOAD_SOURCE_FILE_NAME` /
  `SOURCE_INFO_JSON_NAME` 三兄弟同处），`YtDlpMediaAcquirer` 反过来引用它
  （`infrastructure → domain` 方向合法）。原先它在仓库里被断言 4 次，现在只剩 1 处。
  音频前缀同理收进 `AudioArtifact.AUDIO_PREFIX`
- **命名表 7 行**：`prd.md` R1 已按项目所有者确认定稿；其中翻译侧两行于 2026-10-14 改为
  `translated-*`（`prd.md` R1 订正段）

> **2026-10-14 收口**：本文件的开放项**已全部关闭**——
> §5.1 落位（顶层包 `application/workspace/`）、§5.2 嵌套 + `Optional`、
> §5.4「集中」取组装集中、§7.1 列表形状（嵌套，与父任务
> `handoff.md` §9.3 的记载一致）、§7.2 的 `of` 与 `INFO_JSON_NAME`（不搬迁）。
> 逐条理由见各节；落位那次讨论里还查实了「`domain/shared` 被 7 个上下文依赖、自身零反依赖」
> 与「`resolveTargetLanguage` 不查白名单」两条事实，都已写进对应小节。
