# 会话移交（2026-10-07）

> **本文件是会话状态快照，给下一个会话接手用。** 设计、步骤、验收标准**不在这里复述**——
> 见同目录 `prd.md`（需求 + AC）、`design.md`（三个概念的目标形状 + 三处分叉定论）、
> `implement.md`（三个批次的步骤 + 示例代码 + 验证命令）。
>
> 记在这里的是**那三份文档里没有的东西**：做到哪了、下一步谁做什么、哪些账没还。
>
> **本版是 2026-10-07 第四次刷新**——那次会话把**批次 2 做完并做了真实验收**（枚举化 + 六阶段值改名）。
> 上一版里「新类名未定案」「批次 2 未开工」等说法已作废。
>
> **2026-10-08 第五次刷新**：批次 3 开工前的形状讨论定案（三轮问答）——`MediaFacts` 改名
> `MediaFileInfo`、快照加 path、stat 采样开 `FileStateReader` 端口、实现落新开的 `infrastructure/fs/`。
> design.md / implement.md 已按定案重写；main 侧只有 `MediaFileInfo.java` 草稿（半成品，编译不过）。
>
> **2026-10-08 第六次刷新（归档前终版）**：批次 3 **完成并通过验收**；文档标识符全部对齐代码实名
> （用户定「按代码名为准」：`FileStateReader.getFileState` / `getContentKey`——第五次刷新里的
> `FileStatReader.stat` / `contentKey` 是讨论时的拟名，**作废**）。AC1–AC7 已逐条对账打勾（见 prd）。
> 验收原始日志与夹具（`ac7-run/`、`works-ac3`、`works-ac7-after`）已按用户确认删除，**结论以上文为准**。
> 本会话执行收尾链：提交 → 合并 dev → 推送 → finish-work → 完整归档。

## 一句话状态

**批次 1、2、3 全部完成并验收（AC 全勾）。任务处于收尾链：提交 → 合并 dev → 推送 → finish-work → 归档。
另一个任务 `10-07-pipeline-stage-rename` 已做完、已归档、已合回本分支。**

## 仓库状态

- 分支 `feature/shape-refactor`（base `dev`）；用户 2026-10-08 批准合并 `dev` 并**推送**（此前「不推」的口径作废）
- `feature/pipeline-stage-rename` 分支还在（已合并，停在 `dc2aced`）——获批后该删
- 仓库**外**的临时物：`../vidsage-before`（worktree @ `6bda4f5`，批次 3 验收的对照代码）**保留**——以后验「目录名/事件流逐字一致」还是这套办法；证据日志已删

## 批次进度

| 批次 | 状态 | 验证 |
|---|---|---|
| 1 产出物登记表 | ✅ 完成并推送 | 45 类 / 469 用例 / 0 失败 / 1 跳过 |
| 2 阶段名单源 | ✅ **完成**（枚举化 + 六值改名 + 注释/文案 + 真实验收） | 同上；另加 SSE 事件流逐条比对**零差异** |
| 3 身份归属 | ✅ **完成**（`getContentKey` 多态 + `MediaFileInfo` + `FileStateReader` 端口 + 去 switch） | 469+4 用例全绿；**四路线目录名 before/after 逐字一致**（URL `…-acb6245a5db8`、上传 `source.mp4-9466c90f2836` 重传命中同目录、本地夹具 `local-fixture.m4a-f6fdf4ed2885`）+ after 侧断点复用吃到 before 侧产物 |

### 批次 2 的真实验收（AC7）~~证据在 `f:/personal_project/ac7-run/`~~（**证据文件 2026-10-08 已删，结论以下文为准**）

同一输入（上传路线，复用已有工作区的 `source.mp4`）在**改动前**（`../vidsage-before` worktree）与**改动后**各跑一次主管线，11 条阶段事件逐条比对：

- **条数、顺序、`progress`、`state`、`message` 全同**；差异只有四条改名（`acquire→acquire-media`、`transcribe→transcribe-audio`、`clean→clean-subtitles`、`translate→translate-subtitles`；`extract-audio` / `write-subtitles` 两次都没动）
- 进度数值实测 `0 / 21 / 21 / 42 / 42 / 66 / 70 / 70 / 70 / 100 / 100`，与改名前逐字相同
- 方法：两边各起一次实例、`VIDSAGE_WORKSPACE_ROOT` 指到**独立目录**（不碰 `data/works/`）；事件序列取自应用日志的「任务阶段事件」行——**SSE 订阅有竞态，抓不到最早那两条**（本地文件 acquire/extract 太快）
- 未覆盖：`translate` 阶段（要显式触发 + 联网查证，为省配额没跑；单测覆盖了）

### 批次 2 期间发现的三件事（下次会再踩）

1. **yt-dlp 下不了那条 B 站视频**：报「Format(s) 1080P 60帧 are missing; you have to become a premium member…」，卡到 300 秒超时。**改动前后一样，不是本批造成的**——URL 路线重跑老输入会踩；要么给 yt-dlp 配 cookies，要么走上传/本地路线。
2. **`mvn test` 不加 `clean` 会挂**：`NoClassDefFoundError` + JUnit 连测试类都发现不了（IDE 同时在写 `target/classes` 时更容易踩）。`mvn clean test` 正常。implement.md 里「本批不需要 clean」那句已更正。
3. **失败文案改了口径（方案 c）**：`SubtitlePipeline.failTask` 不再把阶段名拼进中文文案（原来是「任务在 `<stage>` 阶段失败：…」，会嵌 kebab 值）。现在恒为「任务执行失败：<原因>」——阶段名由 SSE / 快照的 `stage` 字段单独给。

## 一条硬约定：谁做什么

**main 代码由项目所有者本人写（他要自己练），测试由 AI 改。** 批次 1、2 都这么走的（AI 只在他明说「你改」时动过 main 的注释与文案）。

AI 的职责：给步骤 + 示例代码 + 指出坑 → 用户写 main → AI 改测试 + 跑验证 + 报结果。
**不要直接动 main 源码**（`backend/src/main/`），除非用户明确说「你改」。
（2026-10-08：`MediaFileInfo.java` 草稿是用户自己建的，半成品状态归他补——AI 别顺手修。）

## 批次 3（已完成）——形状定案与偏差记录（别再重开）

核心已落地：`MediaInput.getContentKey(MediaFileInfo)` 多态 + 新增 `MediaFileInfo`（快照带 path）+ **`FileStateReader` 端口（`application/media/port/`）与 `NioFileStateReader`（`infrastructure/fs/`，新开桶）** + `WorkspaceService` 去 switch 去 `normalize` 去 `localPathKey` + `InWorkspace` 从「带结果」改成「带身份」+ `ofTaskInput` 跟着改。**验收与偏差见 `implement.md` 批次 3 节的 ✅ 注记**（两条 `workspaceFor` 未合并的鸡生蛋洞、`NioFileStateReaderTest` 四例）。

**2026-10-08 形状定案（三轮问答，别再重开）**：

1. **改名**：`MediaFacts` → `MediaFileInfo`（「媒体事实」拗口；`MediaInfo` 被否——它承诺的是媒体探测信息，会诱人往里塞 duration/codec）。
2. **快照带 path**：`MediaFileInfo(Path path, long sizeBytes, long lastModifiedMs)`——观测自带参照物；哨兵 `NOT_APPLICABLE=(null,-1,-1)` 给 URL/上传路线；`LocalPath.getContentKey` 用 `facts.path()` 并断言 `Objects.equals(value, facts.path())`，读不到时实现返回 `(path,-1,-1)` 而非哨兵（否则断言误判）。
3. **stat 经端口**：`getContentKey(facts)` 签名不变、保持纯函数；端口只换「谁去读」。端口归 application（判据：技术词汇 + 兜底「删掉业务规则还成立」）；实现按**能力来源**分桶落新开的 `infrastructure/fs/`（`provider/` 留给外部服务/工具；`LocalFileMediaAcquirer` 不动，要挪另立任务）。**用户明确否掉的两个形状**：`getContentKey(FileStateReader)` 签名进端口（失败策略渗进 domain）、端口落 `domain/media/port/`（技术词汇不走 domain 判据行）。

**风险**：算错 key = 工作区目录算错 = 产物落错地方 / 断点复用失效，**不编译报错、测试也可能不红**。验收与批次 2 同法：before/after 各跑三条路线，比对目录名（见 implement.md；身份串公式未变，目录名应逐字一致）。

**复核时新发现的三个落点（原草案没写）→ 全部落地**：

1. `WorkspaceService.normalize` 的跨包消费方 `VideoTaskService:100` → 已改 `input.getContentKey(MediaFileInfo.NOT_APPLICABLE)`（URL 路线零 I/O）。
2. 测试侧 touchpoints → 已改完（`WorkspaceServiceTest` / `VideoTaskServiceTest:93` / `SubtitlePipelineTest`）。
3. 端口化测试 → 已补：`WorkspaceServiceTest` 用真实 `NioFileStateReader`（非 fake）+ 新增 `NioFileStateReaderTest` 四例（正常读 / 不存在 / 目录 / null）。

## 为什么批次 2 不做权重（这个决定容易忘）

- `implement.md` **原方案**是「把三层权重边界收进一处」——它**不满足 AC3**
- 真正满足 AC3 的是「三层压成一层、每阶段一个权重」，但那是**有意的行为变更**：进度数值会变（现在的任务级边界 `0 / 42 / 66.5 / 70 / 100` 是三层映射挤出来的半整数，扁平后复现不了），**前端进度条的走动方式跟着变**
- 它与 AC7（进度数值不变）**直接冲突**，混在一起就没法验证
- 用户定：**拆成独立任务**。已记在 `prd.md` 的「后续任务」一节（**尚未建任务**）

## 命名判据：已落地，别再重开这个话题

`directory-structure.md` 里现有的三条：

> **`*er` / `*or` = 做事的人**（domain 端口，或端口的实现、分派）。
> **`*Coordinator` = 认领进度阶段、驱动若干协作者的那一步**（判据：**它认不认领阶段事件**）。
> **管线的所有者不带后缀**，前缀说**产出什么**（`SubtitlePipeline`）。

**2026-10-07 批次 2 的两处 spec 改动**：`:91` 尾句「`XxxStage` 只剩存量，不要再加新类」**已按用户要求删除**（用户原话：不是全项目禁用 `Stage`，是之前那些 `Stage` 用法不对）；`:65` 的落位判据从「跨上下文共用的词汇进共享内核」改成「**落位跟归属走；只有归属落位会造出上下文互相依赖时才退到共享内核**」（`WorkspaceKey` 的既有落位仍成立）。新类 `SubtitleStageName` 因此落 `domain/subtitle/model/common/`。

**用户明确否掉的三个方案，别再提**：全改成 `-er` 名（2 个硬撞端口、1 个软撞）、`MediaPipeline`（从**输入**命名，等于回到「不知道是什么管线」）、拆成音/视频两条管线（后端无分支点，拆出来是两套 95% 相同的编排）。**阶段名本身**也否掉了两个：`PipelineStage`（读起来是「管线那一步的执行类」）、`StageName`（不带归属）。

**存量还没改的**（`*Stage` 只剩这三个）：`AudioPreparationStage`（名字仍成立，它认领 acquire + extract-audio 两个阶段）、`MediaAcquisitionStage` 与 `TranscriptionStage`（**一个阶段事件都不发**，该用 `-er` / `-or` 名，拟 `MediaAcquirerDispatcher` / `Transcriber`——**用户定「先放着，以后连同管线一起改」**）。

## 待还的账

1. **注释偏多**——用户 2026-10-07 说「现在的注释都太多了……要求后面统一处理」。**同日晚些的口径**：用户原话「**反正 javadoc 最后还要整体重构一遍**」——即**另开一次全局 Javadoc 重构**，不是本任务的活。批次 2 期间只顺手删了明确的复述/过期段（`StageEvent` 的「阶段词表」段、两个 LLM 类的常量注释、`VideoTask.updateStage` 的六词 `@param`、`schema.sql` 列注释里的词表、`AudioPreparationStage.prepare` 的「阶段一/二」两行），并把注释里的 kebab token 换成中文阶段名。判据见 memory `comment-style-plain`。
   - **两条约定还没写进 spec**（用户说「后面再说」）：①「注释里不许抄 kebab 值」；②「**阶段名 = 动词-对象**」（本批正是按它改的值：`acquire-media` / `transcribe-audio` / `clean-subtitles`）。要立规则的话，`directory-structure.md` 命名约定节 + 一条验收 grep 就够。
2. ~~`CheckpointPolicy.java:18` 旧测试类名~~ → **已消**（2026-10-08 grep 零命中，改名任务后续已修）。
3. ~~SSE 事件流逐条比对~~ → **批次 2 已跑，零差异**（见上）。**`translate` 阶段未走真实链路**（单测覆盖，省配额跳过——已接受的边界）。
4. **`8ccef11` 的提交说明不完整**——没提 `Bcp47Language` / `VerifiedTerms` 两处文档改动（AI 提交前用了过期的 `git status`，流程失误）。已推送，不改。
5. **`backend-before-reconstruction/`**（873 个文件的旧代码对照副本）：已进 `.gitignore`，**重构完成后删除**（归档后另开清理即可）。
6. ~~未推送~~ → 用户 2026-10-08 批准推送，随本收尾链执行。
7. **权重扁平化**独立任务**还没建**（记在 `prd.md` 的「后续任务」）——归档后的**首选下一任务**；连带可议的还有存量 `*Stage` 改名（已定「连同管线一起改」）与 `SubtitlePipeline` 执行模型拆分（AI 建议缓）。
8. ~~spec 加 `infrastructure/fs/` 一行~~ → **已完成**（2026-10-08，含端口归属实例 `FileStateReader`）。

## 会话脉络里值得记的（不在任务文档里）

- 用户正在**自学 DDD**，要求讲解**分两段**：先给标准做法，再单列本项目的自加约束与代价。**别把项目选择说成"DDD 就是这样"**（memory `explain-standard-vs-project`）
- 用户的注释偏好是**要少**：理由压缩成一句、不独立成段；**复述类内容（抄取值/名单、讲来历）直接删，不是压缩**；判据是「**删了会不会有人踩坑**」（memory `comment-style-plain`）
- 用户会主动质疑设计，**这些质疑大多是对的，先算账再答，别防守**。批次 2 期间他连问 `enum` / `sealed` / 值改名 / 失败文案，每次都问到点子上
- **给名字之前先 grep 算账**：这个后缀/前缀在这个仓库里已经是什么意思、被谁占了。**别先给候选再补理由**（memory `naming-must-match-fact`）
- 用户对词汇很较真，会问「**这个类/这个方法到底是干啥的**」——**先读代码再答，别凭名字猜**（本批他问过 `transcribe` 做什么，答案在 `SubtitlePreparationCoordinator:161-176`：调 ASR 拿内存里的结果，落盘在下一步）
- 用户说过「现在是重构，不用管是否麻烦」——他要的是**对的形状**，不是省事的方案。但也不接受为了一句写错的规范去拆类
- **验收要真跑，能自动判就别用人眼**：批次 2 的 AC7 用「把新值映射回旧值再 diff」保住了机器可判；下次照此办理
