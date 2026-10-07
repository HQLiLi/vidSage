# 会话移交（2026-10-07）

> **本文件是会话状态快照，给下一个会话接手用。** 设计、步骤、验收标准**不在这里复述**——
> 见同目录 `prd.md`（需求 + AC）、`design.md`（三个概念的目标形状 + 三处分叉定论）、
> `implement.md`（三个批次的步骤 + 示例代码 + 验证命令）。
>
> 记在这里的是**那三份文档里没有的东西**：做到哪了、下一步谁做什么、哪些账没还。
>
> **2026-10-07 第二轮刷新**：本轮补了两笔账、禁了一条 spec 判据、分出一个新任务。
> 上一版里「工作树干净」已经作废。

## 一句话状态

**批次 1 已完成、已提交、已推送。批次 2 文档本轮定案、代码未动。批次 3 未开始。
新任务 `10-07-pipeline-stage-rename` 已建、规划工件已提交，排在批次 2 之后。**

## 仓库状态

- 分支 `feature/shape-refactor`（base `dev`），**5 个提交未推送**（远端落后 5）
- 新分支 `feature/pipeline-stage-rename` 已建，指向**同一个提交**，**未切过去**
- 工作树干净

```
27c8b65  chore(task): 记录 10-07-pipeline-stage-rename 的分支元数据
f899d4b  chore(task): 10-07-pipeline-stage-rename 规划工件
e144df5  docs(spec): 管线构件后缀判据暂停——与实际不符
1af5f25  docs(backend): 补回 resolveTargetLanguage 的调用点约束
399126f  docs(task): 10-07 批次 2 定案——只做阶段名单源，权重移出
c180916  chore: 排除重构对照副本目录 + 任务状态推进     ← 上一版停在这里
8ccef11  refactor(backend): 产出物登记表——命名 / 检查点 / 可见性三问一处答
```

## 批次进度

| 批次 | 状态 | 验证 |
|---|---|---|
| 1 产出物登记表 | ✅ **完成并推送** | `mvn clean test` → **45 类 / 469 用例 / 0 失败 / 1 跳过** |
| 2 阶段名单源（权重不动） | ⬜ **文档本轮定案，代码未动** | 待做 |
| 3 身份归属 | ⬜ 未开始 | 待做 |

## 一条硬约定：谁做什么

**main 代码由项目所有者本人写（他要自己练），测试由 AI 改。** 批次 1 就是这么走的，继续沿用。

AI 的职责是：给步骤 + 示例代码 + 指出坑 → 用户写 main → AI 改测试 + 跑验证 + 报结果。
**不要直接动 main 源码**（`backend/src/main/`），除非用户明确说「你改」。

## 下一件事：批次 2

完整步骤见 `implement.md` 的「批次 2」节。要点（**落点已逐行核过，见 `implement.md`**）：

### main（项目所有者做）

1. **新建** `domain/shared/model/PipelineStage.java` —— **六个 String 常量**，`public final class` + 私有构造器。
   - ⚠️ **本轮不加权重字段**——加了就是提前做扁平化（那是拆出去的独立任务）
   - 为什么用 String 常量不用枚举：调用点零摩擦、不碰 JSON 契约
2. **删 3 处定义点**：`AudioPreparationStage:45,48`、`SubtitlePreparationStage:57,62`、`StageEvent:22,25`
3. **改 32 处引用**（代码 25 + Javadoc/注释 7），分散在 7 个文件
4. **消灭文字复述**——实际是 **6 处**，不是原以为的 4 处：
   - 散点 3 处：`StageEvent:9-12`（整段「阶段词表」历史）、`StageEvent:14` 的 `@param`、`VideoTask.updateStage:182-183` 的六词列表
   - **全套名单 3 处**（本轮 grep 新发现，原清单漏了）：`VideoTaskPipeline:34-35`、`VideoTaskPipeline:60`、`media/package-info:7`
   - 另有 8 处**散文里的点名**（`{@code acquire}` 之类，不带引号所以上轮 grep 没抓到），其中 5 处是「说我自己驱动的那一步」，换成 `{@link PipelineStage#X}` 即可；3 处是重复全套名单，按复述处理
5. **顺带**：`AudioPreparationStage` 四个进度常量收 `private`（已核实：全仓只有本文件内 8 处引用）。**这是本批唯一允许碰的权重相关改动：只改可见性，不改值。**

### test（AI 做，11 处）

`SubtitleCleanupStageTest` ×4、`SubtitleTranslationStageTest` ×3、`VideoTaskPipelineTest` ×4 里引用 `StageEvent.STAGE_*` 的断言。

⚠️ **但测试侧的裸字面量远不止这 11 处**——还有 **35 处**直接写 `"clean"` / `"transcribe"` 等（走 `String` 传参，批次 2 不改签名 → 不会编译失败 → 改不改是选择）。用户尚未在「全改」与「只改表达阶段语义的那些」之间表态。

### 验证

- `mvn -q test`（**不用 `clean`**——只删常量不删类）
- 三条 grep 断言见 `implement.md`
- **最重要**：真实任务跑一遍比对 SSE 事件流，`diff` 除 `taskNo` 外零差异

## 为什么批次 2 不做权重（这个决定容易忘）

- `implement.md` **原方案**是「把三层权重边界收进一处」——它**不满足 AC3**
- 真正满足 AC3 的是「三层压成一层、每阶段一个权重」，但那是**有意的行为变更**：进度数值会变（现在的任务级边界 `0 / 42 / 66.5 / 70 / 100` 是三层层映射挤出来的半整数，扁平后复现不了），**前端进度条的走动方式跟着变**
- 它与 AC7（进度数值不变）**直接冲突**，混在一起就没法验证
- 用户定：**拆成独立任务**。已记在 `prd.md` 的「后续任务」一节（**尚未建任务**）

## 本轮新增：命名判据（2026-10-07 定案，来自长讨论）

**三个名字各自错在哪：**

1. **`Video` 是错的，不只是不够好**——音频是一等输入、走同一条管线。证据：前端文案就是「把**音视频**，变成可检索的文字」；上传页只收「视频或音频」；工作区有音频卡片形态（mp3/m4a/wav/aac/flac/ogg/opus 白名单）；后端 `MediaInput` 三个变体里没有音/视频维度，`runExtractStage` 里零判断。后端领域词汇全是 media / audio，`Video` 只出现在**聚合根与管线**两处。
2. **`Stage` 是位置不是执行者**，而且这个位置被两类东西共用——六个 `*Stage` 里 4 个认领进度阶段事件，2 个一个都不认领。
3. **「执行者」名已被 domain 端口占满**（`SubtitleCleanser` / `SubtitleTranslator` / `SubtitleContextSummarizer` / `MediaAcquirer` / `AudioExtractor`）——给阶段构件起执行者名，最自然的几个已经被占了。

**判据（从数据里长出来的，不是先立规则）：**

> **`*er` / `*or` = 做事的人**（domain 端口，或端口的实现、分派）。
> **`*Coordinator` = 认领进度阶段、驱动若干协作者的那一步**。
> **管线的所有者不带后缀**，前缀说**产出什么**（`SubtitlePipeline`）。

**spec 那条旧判据已禁**（`e144df5`）：`directory-structure.md:90` 的「以 `Stage` 结尾 + 一个阶段对应一个执行类」两头都不成立。`Service` 后缀不许那半条保留了。

**用户否掉的方案**（别再提）：不改全 `-er`（2 个硬撞端口、1 个软撞）；不叫 `MediaPipeline`（从**输入**命名，和 `VideoTaskPipeline` 同样是"不知道是什么管线"，且更泛）；**不拆成音/视频两条管线**（后端无分支点，拆出来是两套 95% 相同的编排）。

## 新任务：`10-07-pipeline-stage-rename`

- 分支 `feature/pipeline-stage-rename`（已建，未切），base 是 `feature/shape-refactor`
- 范围：`VideoTaskPipeline` → `SubtitlePipeline` + 三个 `*Stage` → `*Coordinator` + spec 新判据 + 测试类改名
- **⚠️ 顺序约束：必须排在批次 2 之后**——批次 2 的清单是逐行核过的，先改名会让那批行号全部失效；反过来本任务的落点全是类名出现的位置，随时重扫都是一分钟
- **状态 `planning`，未 `start`**——等用户过一遍 `prd.md` / `implement.md`
- **四条 Open Questions 未表态**：① `TranscriptionStage` 合不合并（它已被调用方绕过一半）② `VideoTaskPipeline:34-36` 那段与批次 2 重叠的 Javadoc 归谁 ③ 变量名跟不跟改 ④ 是否挂成 10-07 的子任务

## 待还的账

1. ~~`Bcp47Language.resolveTargetLanguage` 的 Javadoc~~ → ✅ **本轮已补并提交**（`1af5f25`）
2. ~~`SubtitleArtifactSpec` 的类注释~~ → ✅ **本轮关闭，不改**（用户：「现在的我还能看懂」，那条「改模板 = 存量失配」的警告不加）
3. **`8ccef11` 的提交说明不完整**——没提 `Bcp47Language` / `VerifiedTerms` 两处文档改动（AI 提交前用了过期的 `git status`，这是流程失误）。已推送，不改。
4. **`backend-before-reconstruction/`**（873 个文件的旧代码对照副本）：已加进 `.gitignore`，**重构完成后删除**。

## 会话脉络里值得记的（不在任务文档里）

- 用户正在**自学 DDD**，明确要求讲解**分两段**：先给标准做法，再单列本项目的自加约束与代价。**别把项目选择说成"DDD 就是这样"**（已存 memory `explain-standard-vs-project`）
- 用户的注释偏好是**要少**：理由压缩成一句、不独立成段；判据从"有没有历史价值"改成"**删了会不会有人踩坑**"（已存 memory `comment-style-plain`）
- 用户会主动质疑设计（批次 1 里连问了 `Path`/`String`、枚举实例、正则、性能开销四轮）——**这些质疑大多是对的，先算账再答，别防守**。本轮同样：他质疑 `PipelineStage` 这个名字，查下来确实站不住（`*Stage` 后缀已被 6 个 `@Component` 占用）
- 用户对**名实不符**极度敏感，会连续追问到名字和事实对齐为止。**给名字先算账：这个后缀在这个仓库里已经是什么意思**
