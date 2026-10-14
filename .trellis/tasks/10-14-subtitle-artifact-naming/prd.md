# 字幕产物命名去路线化与 SubtitleRoute 退场

## Goal

字幕产物的文件名不再携带「来源路线」（`asr-cloud` / `asr-local` 这类 token），改为**按产物语义命名**；
随之把 `SubtitleRoute` 及其全部携带点退场。同一工作区里原有的一批旧命名产物不作迁移，
整体改名留档、新产物重新生成。

本任务是子任务 `10-11-subtitle-multilang-translate` 的前置：命名方案先定死，
那个任务里的 `SubtitleWorkspaceFilePaths` 路径 record 才有确定的形态可写。

## Requirements

### R1 新命名表

产物文件名从 `<词根>-<路线>-<阶段>.<ext>` 改为 `<阶段>-<词根>-<lang>.<ext>`。
完整映射（**按对称推出，逐行核对**）：

| 现状（盘上实测） | 改后 | 说明 |
|---|---|---|
| `segments-asr-cloud.json` | **`original-segments.json`** | ASR 提取的原始字幕检查点 |
| `subtitles-asr-cloud.srt` | `original-subtitles.srt` | 原始字幕 SRT（派生视图） |
| `segments-asr-cloud-clean.json` | `clean-segments.json` | 清洗后检查点 |
| `subtitles-asr-cloud-clean.srt` | `clean-subtitles.srt` | 清洗后 SRT（派生视图） |
| `segments-asr-cloud-translate-<lang>.json` | ~~`translate-segments-<lang>.json`~~ → **`translated-segments-<lang>.json`** | 译文检查点，每目标语种一份 |
| `subtitles-asr-cloud-translate-<lang>.srt` | ~~`translate-subtitles-<lang>.srt`~~ → **`translated-subtitles-<lang>.srt`** | 双语 SRT，每目标语种一份 |
| `context-asr-cloud.json` | `context.json` | 字幕上下文（主题 / 源语种 / 术语表） |
| `terms-asr-cloud-<lang>.json` | `terms-<lang>.json` | 术语译名查证结论，每目标语种一份 |

- **词根保留**：`segments`（JSON 检查点）/ `subtitles`（SRT 派生视图）这组对立继续用
- ~~**`translate-` 中缀保留**~~ → **`translated-` 中缀**（2026-10-14 改）
- 路线 token（`asr-cloud` / `asr-local` / `platform-*`）从所有产物名中消失

> **2026-10-14 订正**：翻译侧的两行从 `translate-` 改成 `translated-`。原因是前两组的词根
> （`original-` / `clean-`）都是分词或形容词修饰名词，只有翻译那组是动词原形，夹在中间不齐。
> 因此常量名一并定为 `TRANSLATED_SEGMENTS` / `TRANSLATED_SRT`。
> 注意**阶段名** `SubtitleStageName.TRANSLATE_SUBTITLES`（值为 `translate-subtitles`）是另一个
> 命名空间（SSE / DB 用），**不跟随本次改动**。

### R2 词汇对齐

`raw` 这个说法一并改成 `original`：领域词汇、枚举常量名、局部变量名、注释与 spec 里的字面描述
全部换成 `original`，与 R1 的文件名一致。

### R3 `SubtitleRoute` 退场

删掉路线概念的全部残留：

- `domain/subtitle/model/common/SubtitleRoute` 枚举
- `SubtitleArtifactSpec` 的 `route` 参数、`{route}` 模板占位、`RouteAlternation` 懒加载类
- 五个 record 的 `route` 字段：`SubtitleTrack` / `TranslatedTrack` / `SubtitleArtifact` /
  `CleanedSubtitle` / `TranslatedSubtitle`
- `domain/transcription/port/ASRProvider#route()` 与 `DashScopeAsrProvider` 的实现

判定依据：`route` 的全部出现只有「进文件名」与「被 record 透传」两类，**没有任何一处读它做判断**。

### R4 落地 `SubtitleWorkspaceFilePaths`

新增「任务工作区文件路径」record 及其工厂，收全工作区里的文件：

- 字幕侧 8 项按 R1 的新名字
- 翻译相关三项按目标语种成组（元素内带语种），**不用三个平行列表**——`terms` 与 `translate`
  的份数不保证相同（无专有名词 / 查证未启用 / 全部查证失败时都不落盘）
- 媒体侧三项：原始媒体、yt-dlp 元信息 json、抽取的音频

### R5 存量不迁移

`backend/data/works` 整体改名留作快照（该目录已在 `.gitignore` 内），
应用重启后按新命名重新生成产物。不做任何文件改名脚本、不写兼容读取。

**该决定的已知代价**（接受）：新目录下所有任务都要重跑一遍云 ASR 与 LLM（约 17 个工作区、
媒体合计约 330 MB，其中单个最长视频的转写产物 39 KB）；改名前的产物在
`GET /api/tasks/{taskNo}` 的 artifacts 清单里不再可见（快照目录仍在盘上可查）。

## 范围外

- **媒体获取侧的 local / cloud**：`YtDlpMediaAcquirer`（URL 下载）与 `LocalFileMediaAcquirer`（本机文件）
  按**输入子类型**分派，是「实现方式」而不是「字幕来源路线」，与本任务无关，不动
- 前端
- 术语查证、多语种翻译等既有功能的行为变更

## Acceptance Criteria

- [x] **AC1** 完整跑一次（准备 → 清洗 → 翻译）后，工作区里出现 R1 表格的全部新文件名，
      且**没有任何文件名含 `asr-cloud` / `asr-local` / `platform-` 字样**
- [x] **AC2** `grep -rn "asr-cloud\|asr-local" backend/src/` 输出为空（生产代码与测试都不再引用路线 token）
- [x] **AC3** `grep -rn "SubtitleRoute" backend/src/` 输出为空
- [x] **AC4** `grep -rn "raw" backend/src/main/java/com/vidsage/domain/subtitle/` 不再命中产物命名相关处
      （`raw` 词汇已全面对齐为 `original`）
- [x] **AC5** `GET /api/tasks/{taskNo}` 的 `artifacts` 清单里出现 6 类可下载产物，**名字为 R1 新名**
      （`original-subtitles.srt` / `clean-subtitles.srt` / `translated-subtitles-<lang>.srt`
      及三个对应的 JSON）
- [x] **AC6** 断点复用仍生效：对已跑完的工作区重复触发，命中复用、不调 LLM（日志有「断点复用跳过」）
- [x] **AC7** 多语种并存仍成立：同一工作区翻译两个目标语种后，两份 `translated-*` 产物同时存在、互不覆盖
- [x] **AC8** `SubtitleWorkspaceFilePaths` 对 R1 命名的工作区扫描结果正确（单测覆盖：全产物齐、
      缺 `terms`、空工作区三种情形）
- [x] **AC9** 改名前的旧检查点 JSON（内容里多一个 `route` 键）仍能被解析、不抛异常
      —— 钉一条测试。**这条必须在动手前先验证**：若未知字段会导致解析失败，
      `SubtitleTranslationCoordinator.readClean` 会抛 ISE 让翻译硬失败，代价与「重跑」不同
- [ ] **AC10** `mvn clean test-compile` 与 `mvn test` 全绿，且测试总数不低于动手前的基线
      （基线值在动手前实测记录）
      —— **2026-10-14 改判**：动手前实测**不存在基线**（干净 HEAD 上测试树就编译不过，
      12 个文件是父任务遗留的债，本任务不还）。口径改为「**不新增失败**」，
      详见 `implement.md` 的「AC10 口径」一节；该口径**已达成**（红面 16 → 12）。
      **等你点头后再勾**。

## Notes

- ~~**待确认**：R1 表格里除 `original-segments.json` 外的 7 行是按对称推出来的，需逐行核对~~
  —— 已逐行核对；其中翻译侧两行于 2026-10-14 由 `translate-*` 改为 `translated-*`（见 R1 订正段）
- ~~**开放项**：R4 的 record 落位与工厂签名~~ —— 已定：顶层包 `application/workspace/`、
  工厂 `SubtitleWorkspaceFilePaths.of(Path)`、嵌套 `TranslationFilePaths`，
  技术设计见 `design.md` §5，as-built 见 `implement.md` §4.1
- 本任务的产出方式：`src/main` 由项目所有者手写，AI 负责 `src/test`、spec 更新与运行期验收
  （沿用子任务 B 已定的分工）
