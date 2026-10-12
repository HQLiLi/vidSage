# 关键词纠错与字幕重生成 — 技术设计

> 需求与验收标准在 `prd.md`。本文件只写**怎么做**：边界、契约、数据流、取舍、兼容与回滚。
> 涉及包位与命名的，一律以 `.trellis/spec/backend/directory-structure.md` 的判据为准，本文件只写结论与理由。

## 1. 边界

**进**：关键词的存（DB 新表）、读（新端点）、传（提交与重生成两个入口）、用（摘要器 + 清洗提示词两条通道）、
断点（指纹）、重跑清洗的执行序列与删除动作、后置会话的在途登记与互斥、prompt 注入防护。

**出**：翻译多语言（子任务 B）、前端（父任务 E，另开任务）、`current_stage`（后置会话不写，B5）、
`task` 表结构（不动）。

## 2. 契约

### 2.1 领域词汇（新增）

**`domain/subtitle/model/keyword/SubtitleKeywords`**（record）

```java
public record SubtitleKeywords(List<String> values) {
    public SubtitleKeywords { /* 归一化 + 校验 + 去重，见下 */ }
    public boolean isEmpty();
    public String computeFingerprint();
}
```

紧凑构造器做三件事（**都放这里，单一来源**，两个 HTTP 入口与提交路径共用）：

1. **归一化**：逐条 `trim`；**结构性字符**（`\n` `\r` 与其它 ISO 控制字符）替换成空格，再 `trim`；丢弃空串。
2. **去重**：保留首次出现顺序（同一份列表按不同顺序提交，指纹不变）。
3. **上限校验**：单条 > 50 字、总数 > 50 → 抛 `IllegalArgumentException`（错在调用方 → 既有映射给 400）。
   违反时**不落库、不重跑**。

**`computeFingerprint()` 是唯一的指纹算法落点**（2026-10-12 定）：排序后的列表 → 以 `\u0001` 连接 →
SHA-256 → 取十六进制前 16 位。**先排序**是刻意的——用户调换填写顺序不该导致指纹变化、进而触发一次重跑。
截 16 位：50 条以内的短列表碰撞概率可忽略。

**曾经设计过一个 `domain/subtitle/policy/KeywordFingerprint` 政策类，本次删掉**：

- **它唯一的理由是绕开 spec 措辞**——`directory-structure.md` 曾写「实例出生后仍无任何行为方法」，
  而值对象算自己的指纹是 DDD 的常规形态。该措辞已于 2026-10-12 收窄为「**无状态变更方法**，
  派生查询允许」，理由与它原本的立法意图一致（防的是偷偷改状态，不是防查询）。约束一放开，这个类就没有存在必要了。
- **design 曾给它找的第二个理由「`CheckpointPolicy` 要用它比对」经核实不成立**：`isCurrent(...)` 收的是
  **已经算好的 String**，算法从未在 `CheckpointPolicy` 里被调用，所谓「多处共用」不存在。
- 值对象带派生查询，读起来就是 `kw.computeFingerprint()`，比 `KeywordFingerprint.of(kw)` 少一次跨类跳转。

**没有 `empty()` 静态工厂**：静态工厂 `of` / `none` / `empty` 都在
[spec 的低档待改清单](.trellis/spec/backend/directory-structure.md)里，字面违反「方法名动词开头」。
空列表直接 `new SubtitleKeywords(List.of())` —— 调用点只有仓库实现里的两三处，为它立一个工厂是提前抽抽象。

### 2.2 领域政策（改）

**`domain/subtitle/policy/CheckpointPolicy`** 加一个纯函数：

```java
/** 检查点是否「就是当前这份」：字节数 > 0 且指纹与当前一致。stored 为 null（与关键词无关的产物）时返回 false。 */
public static boolean isCurrent(long checkpointSizeBytes, String storedFingerprint, String currentFingerprint);
```

「判据单源在政策、应用层持事实、域做裁决」的既有分工不变——量字节、读文件仍是调用方的事。

`resolveContext(...)` **不改签名**：缓存是否可用由调用方先判（见 3.3），不可用就传 `null` 进去，
与现有「缓存未命中」走同一条路。

### 2.3 领域端口（新增）

**`domain/subtitle/port/SubtitleKeywordRepository`**

```java
public interface SubtitleKeywordRepository {
    SubtitleKeywords findByWorkspaceKey(WorkspaceKey workspaceKey);
    void replace(WorkspaceKey workspaceKey, SubtitleKeywords keywords);   // 覆盖全量：按 workspace_key upsert
}
```

落 `domain/subtitle/port/` 跟随 `VideoTaskRepository` 的既有落位（仓储端口进 domain）。
外键是 `workspace_key`（`WorkspaceKey` 在 `domain/shared/model/`，domain 内引用，不跨层）。

### 2.4 基础设施（新增）

- `infrastructure/persistence/entity/SubtitleKeywordEntity`（一列 `keywords` 存整份列表）、
  `mapper/SubtitleKeywordMapper`、`repository/MybatisSubtitleKeywordRepository`（实现上面的端口，
  并是连接/拆分分隔符的唯一落点，见 §5.1）。
- `schema.sql` 加表（DDL 见 §5）。

### 2.5 既有类型改动

| 类型 | 改动 | 连带 |
|---|---|---|
| `domain/subtitle/model/cleanup/SubtitleTrack` | 加一个分量 `String keywordsFingerprint` | 这是**形状即契约**的类型（`segments-*.json` 的形状）。raw 传 `null`（该产物与关键词无关），clean 传指纹。**前端读 `.segments` 不受影响**，只是多了一个字段 |
| `domain/subtitle/port/SubtitleCleanser` | `String reference` → `SubtitleKeywords keywords` | `LlmSubtitleCleanser` 实现与 `SubtitleCleanupCoordinator` 调用点；`reference` 本来就是死的（`SubtitlePipeline:205` 写死传 `""`），这次把它改成真正的关键词通道 |
| `domain/subtitle/port/SubtitleContextSummarizer` | `summarize(String content)` → `summarize(String content, SubtitleKeywords keywords)` | `LlmSubtitleContextSummarizer` + 清洗与翻译两处调用点 |
| `application/task/pipeline/SubtitlePipeline` | `prepareAndClean` 从仓储读关键词传给清洗 | — |

> `SubtitleContext` **不动**。理由见 §4 决策 4。

### 2.6 应用层（新增）

**`application/task/session/TaskSessionRegistry`**（`@Component`，机制/协作件档——单一事实的机械容器，
与 `TaskSubscriptionRegistry` 同档；放 `application/task/` 是因为读它的是 `TaskProgressNotifier`，
写它的是字幕管线，与订阅表「管线广播 / web 挂回调」的分工同形）

```java
public final class TaskSessionRegistry {
    public boolean tryStart(WorkspaceKey key, String stageName);   // 已在跑 → false
    public String findRunningStage(WorkspaceKey key);              // 没在跑 → null（原名 runningStage：名词式取值器）
    public void finish(WorkspaceKey key);
}
```

内部就是 `ConcurrentHashMap<WorkspaceKey, String>`。**两条纪律**：

1. **`finish` 必须写在 `finally`**，任何异常路径都不能漏（PRD D4）。漏了会留下永远 RUNNING 的幽灵，
   前端卡 loading，且该工作区的互斥再也释放不掉（比卡 loading 更严重）。
2. **它是一把锁，不是状态**——不落库、不跨重启、不进 SSE 快照的持久化部分，只在**组装快照时被查一次**。

**`application/subtitle/pipeline/SubtitleCleanupCoordinator`** 加一个入口：

```java
public CleanedSubtitle regenerate(MediaInput input, SubtitleKeywords keywords, ProgressListener listener);
```

与既有 `clean(...)` 的区别只有两条：**跳过 `-clean` 检查点**（B2：关键词没变也照样重跑）、
**摘要失败即整体失败**（B3，见 3.3）。产物写法不变（先 JSON 后 SRT，都原子替换）。

### 2.7 应用消息 / 读模型

**`application/task/message/TaskProgressMessage`** 的 `terminal` 派生规则要换（D5 的连带）：

```java
// 现在：terminal = VideoTask.isEventTerminal(taskStatus, translationStatus)
// 改为：terminal = 主状态是终态 && stage 不是 RUNNING
```

**为什么不需要新字段**：后置会话在跑时，快照的 `stage` 必定是 `RUNNING` 态（由 `TaskStageEvent.from`
按登记表产出）；主状态终态时，DB 派生的 stage 必定不是 `RUNNING`（SUCCEEDED / FAILED / SKIPPED）。
两个事实合起来，`stage.state() != RUNNING` 就是「没有在途会话」的等价判据。

> ⚠️ **评审点**：这是把「在途会话」这件事**隐式编码进了 `stage` 的状态位**。备选是给 `TaskProgressMessage`
> 加一个显式分量 `sessionRunning`，代价是同一个事实在 `stage` 与它身上出现两次。我倾向隐式派生
> （保住「一条判据一个来源」），但这是可辩论的。

### 2.8 领域政策（改）

**`domain/task/policy/TaskStageEvent`**

```java
public static StageProgress from(VideoTask task, String runningStage);   // 加一个入参
```

`runningStage != null` → 返回该阶段的 RUNNING 事件（非终态）；否则走现有的主状态 switch。
**政策仍然是纯函数**——登记表的查询由调用方（`TaskProgressNotifier`）做完再把事实传进来，
与 `CheckpointPolicy` 的既有分工一致。

`fromTranslation` 与 `VideoTask` 的翻译分支**本次不动**（那是子任务 B 的 C5）。

### 2.9 HTTP 契约

| 端点 | 请求 | 响应 |
|---|---|---|
| `POST /api/tasks/{taskNo}/subtitles/regenerate` | `RegenerateSubtitlesRequest(List<String> keywords)` | 202 + `TaskResponse` |
| `GET /api/tasks/{taskNo}/keywords` | — | `SubtitleKeywordsResponse(List<String> keywords, String fingerprint)` |
| `POST /api/tasks` | `SubmitUrlRequest` 加可选 `keywords` | 不变 |
| `POST /api/tasks/upload` | 加可选表单字段 `keywords`（可重复） | 不变 |

两个约定必须写进 `@Operation` 说明：

- **`keywords` 为 `null`（字段缺席）= 不改变已有列表**；**`[]`（显式空数组）= 清空**。两者语义不同。
  提交路径与重生成路径口径一致。
- `GET .../keywords` 返回 **`fingerprint`** 而不是让前端自己算——指纹算法是后端的单源，前端复制一份
  迟早漂移。前端拿它与 `segments-*-clean.json` 里的 `keywordsFingerprint` 比对，就知道「这份字幕对不对得上
  当前关键词」（PRD A5 的第二用途）。

两个响应类都是 `web/dto/` 下的 `XXResponse`，请求类是 `XXRequest`（判据 2）。
`RegenerateSubtitlesRequest` 是 HTTP 专属形状（只有 keywords 一个字段），不直接复用领域类型。

**`err` 映射**：任务不存在 → 404；主状态非 SUCCEEDED → 400「字幕尚未就绪」；同工作区已有后置会话 →
409；关键词超限 → 400。全部走既有 `ApiExceptionHandler`。

### 2.10 共享层（新增）

**`infrastructure/provider/llm/PromptDataBlock`**（**包私有**）

```java
static String render(String tag, String body);   // <tag>…</tag>，body 先归一化
static String normalize(String raw);             // 结构性字符 → 空格；trim
```

放包私有而非 `shared/`，跟随同包 `MessageHistory` 的先例（只被同包几个 worker 用的助手，装不进窄缝）。
prompt 拼接是**适配器细节**，不是领域算法，也不该被 `shared/` 的「跨领域纯算法」定位收编。

## 3. 数据流

### 3.1 首次跑（提交时带或不带关键词）

```
POST /api/tasks {url, keywords?}
  → 算 workspace_key → 查重 → INSERT task
  → keywords 非 null 则 replace(workspaceKey, keywords)     ← 新增这一步
  → pipeline.execute(taskNo)                                ← 202 先返回，下面异步
```

`prepareAndClean`：

```
prepare（下载 → 抽音频 → 转写 → 写 segments-<route>.json，keywordsFingerprint = null）
  ↓
读关键词 SubtitleKeywords kw = repository.findByWorkspaceKey(workspaceKey)
  ↓
读 -clean 检查点 → CheckpointPolicy.isCurrent(size, 制品里的指纹, kw.computeFingerprint())
    一致 → SKIPPED，返回
    不一致/不存在 → 往下
  ↓
摘要：缓存可用（见 3.3）就用缓存，否则 summarize(文本, kw) → 成功才落盘
  ↓
切分 → subtitleCleanser.clean(split, context, kw, listener) → 批级进度
  ↓
写 -clean.json（带指纹）+ -clean.srt（原子替换）
```

**与现状的唯一行为差异**：`-clean` 的断点判据从「非空」变成「非空且指纹对得上」。
不带关键词时指纹是**空列表的指纹**（非 null），行为与改动前逐字节一致（PRD AC1）。

### 3.2 重生成

```
POST /api/tasks/{taskNo}/subtitles/regenerate {keywords?}
  ── 同步段 ──
  ① 装载任务（404）
  ② 主状态必须 SUCCEEDED，否则 400
  ③ TaskSessionRegistry.tryStart(workspaceKey, CLEAN_SUBTITLES) → false → 409
  ④ keywords 非 null 则 replace(...)   ← 落库在排队之前，202 的视图就已反映新列表
  ⑤ TaskConcurrencyGate.submit(异步体, 排队被打断 → 释放登记 + 400 级收口)
  ── 异步体 ──
  try {
      cleanupCoordinator.regenerate(input, kw, listener)      // 复用 CLEAN_SUBTITLES 阶段名与 0..100 区间
      删除全部 segments-*-translate-*.json / subtitles-*-translate-*.srt / terms-*.json
      广播终态（SUCCEEDED）
  } catch (e) {
      task.errorMessage = "重新生成失败：" + reason；落库
      广播终态（FAILED，带原因）
  } finally {
      TaskSessionRegistry.finish(workspaceKey)                 ← 硬要求
  }
```

`current_stage` 全程不写（B5）——主管线已终态，`updateStage` 本来也会拒。

### 3.3 摘要缓存的新判据

摘要缓存（`context-<route>.json`）现在是「文件存在即命中」。引入关键词后，**缓存的可用性由 `-clean`
制品的指纹决定**：

| `-clean` 指纹 | 含义 | 动作 |
|---|---|---|
| 与当前一致 | 这份字幕就是当前关键词生成的，当初用的上下文必然也是 | **用缓存，不摘要** |
| 不一致 / `-clean` 不存在 | 上下文可能是别的关键词集下产的 | **重新摘要** |

**为什么 `SubtitleContext` 不需要自己的指纹**：上下文总是**先于**同一次 `-clean` 写入，而 `-clean`
只在清洗**成功后**才落盘。所以「`-clean` 指纹 = F」蕴含「那次运行写的上下文也是 F 的上下文」。
反之若一次运行写了上下文却没写成 `-clean`，`-clean` 还停在旧指纹上 → 判为不一致 → 重摘要。**安全方向
永远是重跑。** 这样 `SubtitleContext` 的形状一个字不用改（它还被翻译侧读）。

`regenerate` 的额外规则：**摘要失败 = 整体失败**（B3）。现有 `CheckpointPolicy.resolveContext` 在摘不到时
返回 `DEGRADED_EMPTY`，两条路径分叉：

- `clean(...)`：照旧降级空上下文继续（首次跑宁可没上下文也要出字幕）；
- `regenerate(...)`：见到 `DEGRADED_EMPTY` 就抛 `IllegalStateException` → 整体失败、什么都不删。

### 3.4 前端读关键词

```
GET /api/tasks/{taskNo}/keywords
  → 装载任务 → repository.findByWorkspaceKey(task.getWorkspaceKey())
  → {keywords: [...], fingerprint: "..."}
```

## 4. 关键决策与被否方案

### 决策 1：指纹落在 `SubtitleTrack` 上，不另开文件、不给文件名加指纹

`SubtitleTrack` 是**形状即契约**的类型（javadoc 明说改它要走接口变更流程），所以先说清楚为什么还是改它。

- **被否：文件名带指纹**（`segments-<route>-clean-<kwHash>.json`），学 `{lang}` 的先例。否决理由：
  `SubtitleArtifactSpec.isDownloadable` 扫目录会把新旧两份 `-clean` 一起列进产物清单，前端会看到两份
  一模一样的字幕文件；而且旧的永远要有人清理——多一类孤儿文件。
- **被否：另开兄弟文件** `keywords-<route>.json`。否决理由：引入跨文件一致性（谁先写谁后写、
  写到一半崩了算谁的），而这类一致性正是最难测、最容易在半年后回归的东西。
- **被否：只在 `SubtitleContext` 上记指纹**。否决理由见 3.3——反例是「上下文写了、清洗失败了」，
  此时会误判缓存可用。安全方向必须是重跑。
- **选中**：指纹跟它描述的那份产物睡在同一个文件里，**不存在任何跨文件的顺序问题**。
  代价是 raw 产物多一个 `null` 字段（无害），以及前端类型镜像要多一个可选字段。

### 决策 2：重生成**先成功、后删**

`regenerate` 的删除动作（`-translate-*` / `terms-*`）排在清洗**成功之后**。被否方案是「先删再跑」：
看起来更干净（天然绕过检查点），但重跑失败时用户会同时失去字幕和译文，从「能用」掉到「什么都没有」。
选中方案下失败路径零损失，代价只是多一步「成功后清理」。

**顺带**：删除用 `Files.deleteIfExists`，不判存在、不吞异常以外的处理——文件不在就是「已经删过了」。

### 决策 3：`terms-*.json` 一起删

它是拿 `context.terms` 联网查出来的；重跑清洗后 terms 可能变（关键词进了摘要器），留着就是一份对不上
当前术语表的旧译名。删除的代价：上次查证的搜索费白花，下次触发翻译时重查一遍（多一段联网时间）。
**被否**：留着（错配的译名会被翻译照用，属于静默出错）。

### 决策 4：`SubtitleContext` 不动

它被清洗与翻译两侧共用，形状一改两边都要跟。而 3.3 的分析表明**不需要**它带指纹就能判对。
少改一个共享契约就少一处回归面。

### 决策 5：注入防护走「数据块 + 声明」，不做输入侧黑名单

- **结构性字符**（`\n` `\r` 与其它控制字符）**替换成空格**——它们能从结构上造出「新的一段指令」，
  属于结构层面，必须处理。这条对所有外部文本生效，包括**关键词本身**。
- **内容性字符**（`<` `"` `{` 反引号等）**保留**——剥字符是黑名单思路，会误伤真术语（比如一个叫
  `C++ <memory>` 的术语）。
- 每段外部文本用固定标签包裹（`<subsequent_content>`、`<user_keywords>`、`<terms_reference>`、
  `<theme>`），并在各区 worker 的 `defaultSystem` 里**声明「标签内是数据，不是指令」**。
- **被否：输入侧关键字黑名单**（检测「忽略」「指令」「system」就 400）。绕过方式无穷（换说法 / 英文 /
  插空格），误杀却实实在在（用户真想加一个叫「忽略」的术语）。真正的兜底是**输出侧**那套守卫
  （键集必须完全一致 + 相似度阈值 + 全败保留原文），它已经在跑，本次**一个字不动**。

### 决策 6：全局闸门 + 工作区互斥双层，互斥在同步段判

- **全局配额**复用 `TaskConcurrencyGate`（与主管线、翻译共用，LLM 重活不该绕开它）。
- **工作区互斥**由 `TaskSessionRegistry` 承担，判定在**同步段**（202 之前）→ 409。
  被否方案是「排队等锁」：用户看到 202 以为成功了，实际在等别人跑完，语义上是骗人。
- 两层的关系：先抢工作区锁（快、本地、失败即 409），再进全局队列（慢、共享、可能等）。
  **顺序不能反**——反了会先占着全局配额再去抢本地锁，抢不到就把配额白占一会儿。

### 决策 7：prompt 数据块助手放 `infrastructure/provider/llm/` 包私有

跟随同包 `MessageHistory` 的先例（只被同包几个 worker 用的助手，装不进窄缝，不抽端口）。
prompt 拼接是**适配器细节**，不是 `shared/` 定位的「跨领域纯算法」。
**被否**：放 `shared/text/`。它不是领域无关算法，只是三个 LLM worker 的共用写法。

## 5. 兼容与迁移

### 5.1 DB

```sql
CREATE TABLE IF NOT EXISTS subtitle_keyword
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
    workspace_key CHAR(12)     NOT NULL COMMENT '工作区短哈希（与 task 表同源）；关键词挂工作区不挂任务，见任务 prd A2',
    keywords      VARCHAR(4096) NOT NULL COMMENT '关键词原文，以 U+0001 连接，顺序即用户填写顺序（已归一化：去首尾空白、结构性字符转空格、去重）；空列表存空串',
    created_at    DATETIME     NOT NULL COMMENT '写入时间',
    CONSTRAINT pk_subtitle_keyword PRIMARY KEY (id),
    UNIQUE INDEX uk_subtitle_keyword_workspace_key (workspace_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='字幕关键词（用户手填，供 LLM 纠音误）';
```

**加表无迁移负担**：`CREATE TABLE IF NOT EXISTS` 对已存在的库无害，新表直接建出来。
（与 `translation_status` 那次加列的处境不同——加列才需要手工 `ALTER`。）

**为什么一个工作区一行、列表挤在一列里**（2026-10-12 定，取代早先的「一关键词一行」）：

- **标准做法**：把「关键词」当实体，一词一行 + `UNIQUE(workspace_key, keyword)`，靠索引反查「哪些工作区用了
  这个词」。这个前提在本项目**不成立**——唯一的查询是 `findByWorkspaceKey`，从来没有「按词查」。
- **不设 UNIQUE 的旧方案本身就不自洽**：早先那版明确拒掉了 `UNIQUE(workspace_key, keyword)`
  （`utf8mb4` 默认排序规则大小写不敏感，`Tim` 与 `tim` 会撞），于是行存储唯一的收益被自己砍掉了，
  只剩成本。
- **顺序白送**：用户填写顺序要能原样还给前端（`GET` 要给回列表）。行存储得再加一列 `position`
  才能还原——InnoDB 不保证按插入序返回；单行天然保序，一个字段都不用加。
- **写入少一个事务**：覆盖全量在行存储下是「DELETE + 批量 INSERT」包在一个事务里，单行就是一条 upsert。
- **指纹那侧本来就把它当集合**：§2.1 是先排序再哈希。逐行记录一个被当集合用的东西是白付的成本。

**列长 `VARCHAR(4096)` 够用**：50 条 × 51 字 + 49 个分隔符 ≈ 2550 字符。**因此不需要新增「总字数」约束**，
长度判据仍然是 §2.1 那两条（单条 ≤ 50、条数 ≤ 50）。

**分隔符选 `U+0001` 而不是逗号/竖线**：后者会跟真术语冲突（`Dr. Smith, Jr.`）。`U+0001` 是 ISO 控制字符，
§2.1 的归一化已经把它换成空格了，**到不了落库这一层**——不需要转义，也不需要在读回时做还原。
`String.split("\u0001", -1)` 是安全的（该字符非正则元字符；`-1` 保住尾部空串，行为可预期）。

**连接/拆分的唯一落点**：`MybatisSubtitleKeywordRepository` 内部成对实现（entity 的 `String keywords` ↔
`SubtitleKeywords`），领域与 HTTP 两侧只见 `SubtitleKeywords`，永不接触分隔符。

**拆分的边界情况**（都在 repository 内处理完，交出去的永远是合法 `SubtitleKeywords`）：

| 库里读到的 | 拆出来 |
|---|---|
| `null`（该工作区还没存过） | 空列表 |
| `''`（存过但是空列表） | 空列表 |
| `'a\u0001b'` | `['a', 'b']`，顺序即填写顺序 |
| 单个词 `'Tim'` | `['Tim']`，无分隔符 |

用 `split("\u0001", -1)`（`-1` 保住尾部空串，行为可预期）。`''` 会 split 出 `[""]`，
**这没关系**——`SubtitleKeywords` 的紧凑构造器本来就丢弃空串，所以交给上层的结果是对的。
去重、trim、上限校验一律在构造器里做，**repository 不重复一遍**。

### 5.2 存量产物

**不做一次性迁移。** 存量 `segments-*-clean.json` 没有 `keywordsFingerprint` 字段，Jackson 反序列化得 `null`
→ `CheckpointPolicy.isCurrent` 返回 `false` → 下次跑管线/重生成时**重新清洗一遍**。这是安全方向
（重跑而不是拿旧的对不上关键词的字幕凑数），代价是老工作区多跑一次清洗。

### 5.3 前端

本次不改前端，但**两处接口变化会让前端立刻看到不同**，要在「前端待办」里点名：

1. `segments-*-clean.json` 多一个 `keywordsFingerprint` 字段（TS 类型补一个可选字段即可，读 `.segments` 不受影响）。
2. `POST /api/tasks` 的响应形状不变，请求体多一个可选字段。

### 5.4 与 `10-11-sse-connection-pairing` 的关系

两个任务都要碰 SSE 的话题面，但**文件不重叠**：本任务**不碰** `web/sse/TaskSseConnectionManager`
（D5：SSE 基础设施零改动），只改 `application/task/message/TaskProgressMessage` 与
`domain/task/policy/TaskStageEvent`。那个任务只改 `TaskSseConnectionManager`。
**唯一可能的交汇点是 `TaskProgressNotifier`**——它引用了 `TaskStageEvent.from`，本任务会改那个调用点；
那个任务不改本类。真同时动工时以「谁先合谁改」为序，不预留抽象。

**与「方法名动词开头」存量清理任务的交汇点**：spec 低档那批要改的名字里，`SubtitleContext.empty()`（→ `ofEmpty()`，
约 30 处调用）在 `CheckpointPolicy:98` 被用到，而本任务要改 `CheckpointPolicy`（加 `isCurrent`）。**同一文件**。
两个任务撞车时以「谁先合谁改」为序；若清理任务先落地，本任务的批 2 跟着改掉那处调用即可（其余 26 处调用
全在测试文件里，与本任务无交集）。

## 6. 回滚形状

按依赖从外到内，**每一步都可独立停在原地而不留半成品**：

| 步 | 回滚动作 | 残留 |
|---|---|---|
| 端点与 DTO | 删路由与两个 DTO | 无 |
| 应用编排（重生成 / 登记表 / 互斥） | 删方法 + 删 `TaskSessionRegistry` | 无（登记表本来就不落库） |
| 清洗器与摘要器签名 | 回到 `String reference` | 无 |
| 指纹（`SubtitleTrack` 字段 + `CheckpointPolicy`） | 回到「非空即完成」 | 存量产物多一个 `null` 字段，Jackson 忽略，无害 |
| DB 表 | `DROP TABLE subtitle_keyword` | 关键词列表丢失（用户可重填） |

**最危险的一步是 DB 表**（唯一有数据的一步），所以它排在最前、最先落地、最晚回滚。
`SubtitleTrack` 的字段一旦上线就**不建议回滚**——它已经写进产物文件了，回滚代码只会让新产物多带一个
没人读的字段，无害但没必要动。

## 7. 待评审的开放点

1. ~~**§2.1 `SubtitleKeywords` 的实例方法 vs spec 措辞**~~ **（2026-10-12 已定）**：
   值对象带派生查询，算法放 `SubtitleKeywords.computeFingerprint()`，政策类 `KeywordFingerprint` 删除；
   spec 的「仍无任何行为方法」收窄为「无状态变更方法」。
2. ~~**§2.7 `terminal` 的隐式派生 vs 显式分量**~~ **（2026-10-12 拍板：隐式派生）**：
   `terminal = 主状态终态 && stage.state() != RUNNING`，不加 `sessionRunning` 分量——保住「一条判据一个来源」。
   代价是「在途会话」被隐式编码进 `stage` 的状态位，批 4 的 javadoc 里要点明这层含义。
3. ~~**§2.9 `POST /api/tasks/upload` 的关键词走可重复表单字段**~~ **（2026-10-12 拍板：走）**：
   Spring 的 `List<String>` 直接支持可重复表单字段，不用转码；两条提交路径口径一致，上传路线第一次出的
   字幕也带纠错。备选（「本次不支持、只能事后重生成」）作废。
4. ~~**§2.1 指纹取 SHA-256 前 16 位十六进制**~~ **（2026-10-12 拍板：够，不带版本位）**：
   50 条以内的短列表碰撞概率可忽略；将来改归一化规则时旧产物自然判为不一致 → 重跑，本就是想要的行为，
   版本位改变不了这个结果，只是把意图写显式一点，不值当。
5. ~~**§3.1 「首次跑不带关键词时行为逐字节一致」**~~ **（2026-10-12 定：以 §5.2 为准）**：
   `-clean` 断点判据从「非空」变成「非空且指纹一致」，严格说不是逐字节一致（存量产物会重跑一次）；
   `prd.md` AC1 已同步改判为「行为等价」，此处只是把同一件事再记一遍。
