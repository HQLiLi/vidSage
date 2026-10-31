# 字幕手动纠错 — 技术设计

> 本文是实现口径的正典。产品需求与验收标准见 `prd.md`；子任务执行顺序见 `implement.md`。

## 1. 总体形态：纠错覆盖层

- **事实源是纠错表**，不是磁盘产物。磁盘上的 `original-*` / `clean-*` / `translated-*` 永远保持机器输出。
- 所有"用户可见的读取"经过同一个合并入口（`SubtitleCorrectionOverlay`），避免"页面改了、导出没改"。
- 不采用"每次编辑物化新 CLEANED 版本"：现有作废逻辑会删除译文文件，与"保留旧译文只标过期"冲突，且每改一条就 +1 revision。

## 2. Segment id

### 2.1 模型改动

- `TranscriptionSegment` 增加第四个组件 `String id`，值形如 `seg-5160-9640`（`seg-<beginTimeMs>-<endTimeMs>`）。
- `TranslatedSegment` 同样增加 `String id`，翻译时从对应的 CLEANED 段继承。
- 两个 record 各加一个"老三参/四参"便捷构造器，`id` 缺省时按时间轴合成；紧凑构造器兜底（`id == null` → 合成），保证旧 JSON 反序列化后也有 id。

### 2.2 为什么是时间派生

- 清洗不改时间戳（`LlmSubtitleCleanser` 明写"时间戳是切分阶段定的，纠错不动它们"）。
- 切分是确定性的（`SubtitleSegmentSplitter` 按比例切分），同一份 ORIGINAL + 同一 `max-segment-ms` 重跑后边界不变。
- 因此 id 在重跑后自动一致；切分真的变了时，id 直接对不上 → 可显式标"失效"，不会静默套到别的句子上。

### 2.3 兼容

- 磁盘旧产物缺 `id`：反序列化后由紧凑构造器合成，读取路径无感。
- 新产物落盘时带 `id`；SRT 渲染忽略 `id`。
- 同一产物内时间对唯一：切分丢弃零时长段，ASR 也按时间递增给段。若真出现重复时间对，覆盖层会命中其中每一条——按本设计的定义，同一个 id 本来就是同一句；此时记一条 WARN 便于发现，**不为此改造 id**（加 `-2`/`-3` 后缀会让 id 依赖出现顺序，破坏跨重跑的稳定性）。

## 3. 数据模型

### 3.1 表 `subtitle_correction`

```sql
CREATE TABLE IF NOT EXISTS subtitle_correction (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    task_id         BIGINT       NOT NULL COMMENT '逻辑外键 -> subtitle_task.id',
    segment_id      VARCHAR(64)  NOT NULL COMMENT 'seg-<beginTimeMs>-<endTimeMs>',
    field_type      VARCHAR(32)  NOT NULL COMMENT 'text / translation',
    lang            VARCHAR(32)  NOT NULL DEFAULT '' COMMENT 'field_type=translation 时为目标语种短码；text 时为空串',
    source_text     TEXT         NOT NULL COMMENT '这条纠错依据的源文本：text 行为改前的机器原文；translation 行为保存时用户看到的原文',
    corrected_value TEXT         NOT NULL COMMENT '用户改后的文本',
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NOT NULL,
    CONSTRAINT pk_subtitle_correction PRIMARY KEY (id),
    CONSTRAINT uk_subtitle_correction_task_id_segment_id_field_type_lang
        UNIQUE (task_id, segment_id, field_type, lang),
    INDEX idx_subtitle_correction_task_id (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户对字幕原文/译文的手动纠错（覆盖层）';
```

要点：

- `lang` 用空串而不是 `NULL`：MySQL 唯一约束里 `NULL` 不参与比较，用 `NULL` 会让"同一段两条 text 纠错"都能插进去。
- `field_type` 按规范用 `VARCHAR(32)`，不用 `ENUM`；列名带 `_type` 前缀，与 `source_type` / `run_type` / `artifact_type` / `file_type` 的既有命名一致。库里按枚举名存 `TEXT` / `TRANSLATION`（与 `URL` / `MAIN` 等一致），对外 JSON 里叫 `field`、取值小写 `text` / `translation`（它描述的是"改哪一项"，不是类型枚举的中文翻法）。
- `updated_at` 的写入点就是 upsert 的 `ON DUPLICATE KEY UPDATE` 分支，与 `subtitle_keyword` 同款。
- 不写 `FOREIGN KEY`，任务删除时的清理由应用层负责（当前没有删除任务的入口，先不做级联）。

### 3.2 领域与持久化

- 值对象：`domain/subtitle/model/correction/SubtitleCorrection`（record）+ `SubtitleCorrectionField`（enum：`TEXT` / `TRANSLATION`）。
- 端口：`domain/subtitle/port/SubtitleCorrectionRepository`
  - `findByTaskId(long)`
  - `upsert(SubtitleCorrection)`（按唯一键插入或更新，返回落库后的对象）
  - `deleteByTaskIdAndSegmentIdAndFieldAndLang(...)`
  - `deleteByIds(List<Long>)`
- 实现：`infrastructure/persistence/entity/SubtitleCorrectionEntity` + `mapper/SubtitleCorrectionMapper` + `repository/MybatisSubtitleCorrectionRepository`。

## 4. 应用层

### 4.1 `SubtitleCorrectionOverlay`（`application/subtitle/`，`@Component`）

唯一合并入口，纯内存操作。配套的 `SubtitleTrackReader`（`application/subtitle/`，`@Component`）负责"按 taskId 读完好的 CLEANED / TRANSLATED JSON"，下载路径与校验共用它，不各写一份读文件逻辑：

- `applyToCleanTrack(long taskId, SubtitleTrack track)`：按 `segmentId + field=TEXT` 覆盖 `text`。
- `applyToTranslatedTrack(long taskId, String lang, TranslatedTrack track)`：按 `segmentId + field=TRANSLATION + lang` 覆盖 `translatedText`。
- `matchCorrections(long taskId, SubtitleTrack cleanTrack)`：给每条纠错算 `matched`（当前 CLEANED 里是否有该 id）与 `sourceChanged`（`source_text` 与当前文本是否不同：text 行比机器文本，translation 行比生效原文，见 §4.2）。

**调用点（必须全部走它）**：

1. 字幕产物下载：CLEANED 的 `segments.json` / `subtitles.srt`。
2. 译文产物下载：TRANSLATED 的 `segments.json` / `subtitles.srt`（按 `?lang=`）。
3. 翻译取源文本：`SubtitleTranslationCoordinator` 读 CLEANED 段时。

**不得调用**：清洗复用判定、检查点指纹、对账、指纹级联等"看机器产物"的地方。

### 4.2 `SubtitleCorrectionService`（`application/subtitle/`，`@Service`）

- `save(String taskNo, String segmentId, String field, String lang, String value)`
- `delete(String taskNo, String segmentId, String field, String lang)`
- `list(String taskNo)`
- `clearStale(String taskNo)`：先算 `matched=false` 的集合，再 `deleteByIds`。

`field` 收的是**对外词形**（`text` / `translation`，大小写不敏感），应用层用 `SubtitleCorrectionField.parse`
解析成枚举。词形的两个方向（`parse` / `token`）只写在 domain 枚举上，web 层只把客户端传来的词形原样透传、
不 import `domain`；读模型 `CorrectionView.field` 也存词形（`field.token()`），不存枚举。

校验（全部给明确中文错误信息）：

| 条件 | 结果 |
|---|---|
| taskNo 不存在 | 404（复用 `TaskNotFoundException`） |
| 该任务有 `RUNNING` run | 409（复用 `TaskConflictException`，响应体同现状 `{taskNo, runNo}`） |
| `field` 词形为空或不是 `text` / `translation` | 400（`SubtitleCorrectionField.parse`，在应用层解析） |
| `field=translation` 但 `lang` 为空 | 400 |
| `lang` 没有对应的完好 TRANSLATED 产物 | 400 |
| `segmentId` 在当前 CLEANED 产物里不存在 | 409，提示"字幕条目已过期，请刷新后重试"。**不复用** `TaskConflictException`（它的响应体是 `{taskNo, runNo}`、不含文案）、新建 `CorrectionSegmentOutdatedException`，走通用 `{error}` 形状 |
| `field=translation` 但 `segmentId` 不在该语种译文里（译文字幕版本比原文旧） | 409（`CorrectionSegmentOutdatedException.forOutdatedTranslation`），文案要求"重新翻译后再改"——这种情况刷新页面无效；不拦会存下一条永远叠不上去的纠错 |
| `value` trim 后为空 | 400，提示改用删除恢复机器文本 |
| `value` 超过单条上限（2000 字符） | 400 |

保存时 `source_text` 取"这条纠错所依据的源文本"，两类纠错取的**不是同一份**（2026-10-26 订正）：

- 原文纠错取 CLEANED 中改前的**机器** text——它要识别的是机器产物被重新生成；
- 译文纠错取当时**用户看到的**原文（叠过原文纠错），`sourceChanged` 也就按这个口径比
  （判定实现见 `SubtitleCorrectionOverlay#matchCorrections`）。

这样 `sourceChanged` 才能承担「译文待复核」：用户照着旧原文写的手改译文，在原文后来改过之后会翻成 `true`。
取机器原文的话，用户改原文不会让任何字节变化，判据永远不翻转。

### 4.3 HTTP 契约

新增 `SubtitleCorrectionController`（`web/controller/`，平铺）：

- `GET /api/tasks/{taskNo}/corrections`
  → `{ items: [{ segmentId, field, lang, sourceText, value, matched, sourceChanged, updatedAt }] }`
  `items` **按首次保存顺序**（行 id 升序）返回；重复保存只刷新 `updated_at`、不改 id，改过的条目不会跳到末尾
- `PUT /api/tasks/{taskNo}/corrections/{segmentId}`
  body `{ field: "text"|"translation", lang?: "en", value: "..." }`
  → 200，返回该条纠错
- `DELETE /api/tasks/{taskNo}/corrections/{segmentId}?field=text&lang=`
  → 204
- `DELETE /api/tasks/{taskNo}/corrections?staleOnly=true`
  → 200 `{ removed: N }`

DTO 落 `web/dto/`：`SaveCorrectionRequest`、`SubtitleCorrectionResponse`、`SubtitleCorrectionListResponse`、`ClearStaleCorrectionsResponse`，字段补 `@Schema`。

### 4.4 强制重翻

- `POST /api/tasks/{taskNo}/translate` 请求体增加可选 `force`（默认 `false`）。
- `force=true` 时跳过"同语种已有完好译文 → 跳过"的分支，真重跑该语种。
- 用户手改的译文纠错不动，重翻后仍在覆盖层里生效。
- `force=false` 时行为与现在完全一致。

## 5. 下载路径

`ArtifactController` 目前直接把磁盘文件发出去（文件名到真实文件名的映射在 `SubtitleArtifactQueryService`）。改造规则：

| `type` | `file` | 行为 |
|---|---|---|
| ORIGINAL | `segments.json` / `subtitles.srt` | 直接发磁盘文件 |
| CLEANED | `segments.json` / `subtitles.srt` | 有 text 纠错 → 读 JSON、套覆盖层、重新序列化/渲染 SRT 后返回；无纠错 → 直接发文件 |
| CLEANED | `context.json` | 直接发文件 |
| TRANSLATED | `segments.json` / `subtitles.srt` | 有该 lang 的译文纠错 → 套用后渲染；无 → 直接发文件 |
| TRANSLATED | `terms.json` | 直接发文件 |

- `?raw=1` 对上述所有下载生效：永远发磁盘文件。
- 渲染 SRT 复用现有 `SrtRenderer`（译文 SRT 用与翻译阶段相同的渲染方法，实施时对齐）。
- JSON 序列化复用产物读写已有的 Jackson 配置，避免形状漂移。
- 产物清单里的 `sizeBytes` 仍报磁盘文件大小；不改列表接口。
- **译文产物的原文列不叠原文纠错**（2026-10-26 裁定）：页面判「译文待更新」靠的就是「译文自带的
  `sourceText` ≠ 当前原文」。译文列一旦也把 `sourceText` 换成纠错后的文本，两边相等，过期提示直接失效。
  这类不一致只出现在「改了原文还没重翻」的窗口，此时该行本来就标着「原文已改，译文待更新」，
  重翻一次即消失；单条重翻不在范围内。

## 6. 前端

### 6.1 类型与状态

- `TranscriptionSegment` / `TranslatedSegment` 类型加 `id`。
- `SubtitleCue` 增加 `sourceText`（当前语种译文的源文本）与派生的 `translationStale = !!translatedText && sourceText !== text`。
- `buildCues` 改为按 id 配对；旧数据没有 id 时退回"下标 + 时间戳"配对。
- store 新增纠错相关 action：`saveCorrection`、`deleteCorrection`、`listCorrections`、`clearStaleCorrections`；`cues` 仍是 computed，但来源数据变化后自动重算。

### 6.2 编辑交互

- 原文行、译文行各有一个铅笔：`v-if="!isBusy"`；译文铅笔仅在当前语种有译文时出现。
- 进入编辑态：原 `<p>` 换成 textarea，保留行内布局。
- 失焦保存；保存按钮 `mousedown.prevent` 后显式保存；Esc 放弃并还原；Ctrl/⌘+Enter 立即保存。
- 保存幂等：值未变则不发请求；请求进行中标记 `saving`，避免重复提交。
- 失败：还原编辑前文本 + 行内错误提示。

### 6.3 译文过期、待复核与失效纠错

- 改原文保存成功后，该行译文立即显示"原文已改，译文待更新"（由 `translationStale` 计算得出）。
- 重翻之后 `translationStale` 变回 `false`，但手改译文仍盖在新机器译文上；若这条译文纠错的
  `sourceChanged=true`（当初照着写译文的原文后来改过），改显"译文待复核"（2026-10-26 补，与"待更新"互斥）。
  用户改动这条译文再保存（或删掉重存）即清掉——保存是幂等的，原样点保存不会发请求，标记清不掉。
- 翻译栏的"重新翻译"在存在过期条目时调用 `translateTask(taskNo, { force: true })`。
- 字幕区顶部：`listCorrections` 里有 `matched=false` 的条目时显示"N 条纠错已失效" + 清除按钮（调 `DELETE /corrections?staleOnly=true`）。

## 7. 失败模式与边界

- **运行中编辑**：前端隐藏铅笔；后端 409 兜底。
- **并发编辑（多标签页）**：同唯一键 upsert，后写覆盖；不做冲突检测。
- **重新生成字幕**：旧 segment id 消失，纠错变 `matched=false`，保留不删。
- **切分配置变化 / ORIGINAL 重新转写**：同上，全部标失效，不静默套用。
- **删除纠错**：恢复机器文本；前端刷新该条。
- **CLEANED 产物读不到**（文件被挪走 / 正在换版）：不做失效判定，`matched` 一律按 `true` 返回，`clearStale` 删 0 条。清理是不可逆删除，判不了就不判——不能因为文件一时不在就把用户还能用的纠错清光。
- **磁盘文件被外部改动**：不在本设计范围，按现状处理。

## 8. 兼容与回滚

- 表通过 `schema.sql` 的 `CREATE TABLE IF NOT EXISTS` 建立，无需迁移框架。
- 旧 JSON 缺 id 由紧凑构造器兼容。
- 回滚：代码回退 + `DROP TABLE subtitle_correction`；磁盘产物从未被改写，无需数据修复。

## 9. 验证口径

- 后端：`mvn clean test-compile` + `mvn test`（按项目既有口径，不用增量 `compile` 判绿）。
- 前端：`npm run type-check` + `npm run lint`。
- 端到端冒烟：起后端 + 前端，用一个已完成任务验证 AC1–AC8；SSE/运行中 409 单独验证一次。
- 逐字节一致（AC3/AC8）用 `curl -s ... | sha256sum` 与磁盘文件哈希比对。
