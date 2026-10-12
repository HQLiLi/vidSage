# 翻译多语言选择与翻译子状态下线

> **状态：planning，未开工。** 父任务 `10-11-subtitle-keywords-multilang` 的**子任务 B**。
> 需求共识的完整来源在父任务 `prd.md` 的 C / D 两节，本文件只写**本子任务自己的**目标、边界、
> 已定决策与验收标准。技术设计另写 `design.md`。
> 来源：2026-10-11 会话 grill 共识。**依赖子任务 A（`10-11-subtitle-keyword-regenerate`）先落地 D 组地基。**

## Goal

翻译的目标语种不再由配置文件定死，改由**前端提供固定白名单供用户选择**——用户选 en 就翻英文、
选 zh-Hant 就出繁体，多次选择的结果**共存**。随之取消 DB 里的翻译子状态：一列 `translation_status`
本来就表达不了"en 翻完了、ja 还在跑"。

## 范围

**做**：C 节（目标语种参数化 + 白名单 + 跳过判据 + 删 DB 翻译子状态 + `error_message` 收口）。

**不做**：关键词与重跑清洗（子任务 A）、前端（父任务 E 节，另开任务）。父任务 F 节的四条同样适用。

**依赖**：进度与并发地基（父任务 D 节）由子任务 A 落地，本任务**直接用**，不重复实现。

## 已定决策与理由

### D1（2026-10-11 拍板）DB 翻译子状态**全删**，不是"留着不落库"

删的面：`task.translation_status` 列、`TranslationStatus` 枚举、`VideoTask` 的三个翻译迁移方法
（`translationStart` / `translationSucceed` / `translationFail`）、`TaskStageEvent.fromTranslation`、
`TaskProgressMessage.translationStatus` 字段、`TaskResponse.translationStatus` 字段、
`VideoTask.isEventTerminal` 的翻译分支。简化后 `terminal` = 主状态终态 **且** 没有在跑的后置会话。

**被否的**：只删列、把枚举与状态机留在内存里（状态时对时错，重连快照会撒谎——这是最糟的中间态）。

**必须知情的后果**：翻译中途刷新页面，"正在翻译"靠的是 D 组的内存登记（子任务 A 落地），
**不再是 DB**。这正好也是删它的理由——一列 NULL 的 ENUM 表达不了多个语种各自的状态。

### D2（2026-10-11 拍板）跳过判据升级为"主语言 + 中文正简脚本"

现在的 `Bcp47Language.isSameMainLanguage` **只比主语言子标签**（连字符前那段），于是
**简中视频选繁体会被判成"同一门语言"→ 直接跳过翻译、不调 LLM**，一句话都不会转——而简繁转换恰恰是
白名单里两个中文选项存在的意义。

但"只比主语言"的**原始理由仍然成立**：LLM 可能吐 `zh-CN` 而目标写 `zh`，直接全标签比对会失配 →
中文视频被送去"中文译中文"，白烧一轮还可能改坏原文。

所以判据升级成：归一化到 **(主语言, 中文正简脚本)** 二元组，两边都相同才跳过。

| 输入 | 归一化 |
|---|---|
| `zh` / `zh-CN` / `zh-SG` / `zh-Hans` | (zh, 简体) |
| `zh-TW` / `zh-HK` / `zh-MO` / `zh-Hant` | (zh, 繁体) |
| `ja` / `en` / 其它 | 脚本视为空，仍只比主语言 |

结果：`zh → zh-CN` 照旧跳过（保住原有修正），`zh → zh-Hant` **真翻**。

### D3（2026-10-11 拍板）白名单做成枚举 + 别名表，不写成散落的 if

「简体中文」这种东西天然有多个名字（`zh` / `zh-CN` / `zh-SG` / `zh-Hans`），繁体同理。所以：

- 新枚举值带三样：`code`（写进产物名与提示词的 BCP-47 短码）+ `label`（「繁体中文」这类中文名）
  + **一份别名字符串集合**。
- **这份别名表就是 D2 那条跳过判据的单源**——"简体怎么归一、繁体怎么归一"只写一次，解析与跳过判定
  都调它，不各写一份。
- 解析住在既有的 `Bcp47Language` 政策类（它本来就是无状态、零 I/O 的纯函数语种政策）。

**名字**：枚举叫 `SupportedLanguage`。不叫 `TargetLanguage`——`targetLanguage` 已经到处是字段名与参数名，
会撞成 `TargetLanguage targetLanguage`。放进 `domain/subtitle/model/common/`（`SubtitleRoute` /
`SubtitleStageName` / `SubtitleArtifactSpec` 都在那儿）；`SubtitleArtifactSpec` 渲染 `{lang}` 的入参
也从裸 `String` 收敛成这个枚举。

**白名单内容**：`zh-Hans` / `zh-Hant` / `en` / `ja`。不上 `zh`（裸的）——它等同于 `zh-Hans`，
列两个简中选项只会让用户困惑。

### D4（2026-10-11 拍板）目标语种从 system 提示词移到 user 提示词

`subtitleTranslateChatClient` 现在在**构造 bean 时**就把配置里的目标语种 `formatted` 进 system 提示词。
目标语种变成每次请求的参数之后，这个位置不成立。移出 system 段，由 user 提示词携带
（`LlmSubtitleTranslator.buildUserPrompt` 本来就带了一份，是双保险）。

### D5（2026-10-11 拍板）白名单走独立端点 `GET /api/languages`，后端做校验

不塞进任务视图（它是与任务无关的静态信息，每个任务响应背一份没道理），也不在前端写死（两端各存一份
清单迟早对不上）。`label` 由后端给（`zh-Hant` → 「繁体中文」），前端不维护短码到中文名的映射。

**必须校验**：目标语种会进**产物文件名**和**提示词**，不校验的话用户传个带 `/` 或长篇大论的串就能
拼出坏文件名/坏提示词。白名单外一律 **400**（错在调用方 → IAE → 400，走既有映射）。

### D6（2026-10-11 拍板）失败原因写回 `task.error_message`

事件不落库，刷新一下失败原因就没了——用户只会看到"没有翻译产物"，跟"从来没点过翻译"长得一模一样。
`error_message` 列本来就在（现在翻译失败就是这么写的），前缀「翻译失败：」与「重新生成失败：」区分来源。

**必须知情的代价**：这一列的语义变成"主任务失败 **或** 最近一次后置会话失败"——一个 SUCCEEDED 的任务上
挂着一句错误文案。

## 验收标准

- **AC1** `GET /api/languages` 返回四项，每项带 `code` 与中文 `label`（`zh-Hans` / `zh-Hant` / `en` / `ja`）。
- **AC2** 选 `en` 翻译 → 产出 `-translate-en.*`；**再选 `zh-Hant` 翻译 → 两份产物共存**，
  且先翻的那份**没有被删、没有被覆盖**。
- **AC3** 简繁真的转了：中文源素材选 `zh-Hant`，译文是繁体（不是"跳过翻译照抄原文"）。
- **AC4** `zh → zh-CN` **仍然跳过**（不调 LLM、发 SKIPPED 事件）——D2 升级没有把原来的修正弄丢。
- **AC5** 白名单外的语种（`de`、`zh`、空串、`../../etc`）一律 **400**，且**没有产生任何文件**。
- **AC6** 同一语言重复触发走幂等：产物已在则**不发 LLM**（可断言无模型调用）。
- **AC7** 后端全库 `grep -r "translation_status\|TranslationStatus\|translationStart\|translationSucceed\|translationFail\|fromTranslation"` 为空
  （含 `src/main`、`src/test`、`schema.sql`）。**前端不在本次范围**，它的 `translationStatus` 引用留给前端任务清。
- **AC8** 翻译失败时 `task.error_message` 带「翻译失败：」前缀；主状态保持 SUCCEEDED。
- **AC9** 翻译期间 SSE 能收到进度、跑完收到终态；刷新页面后重新连上能看到"正在翻译"（D 组登记生效）。
- **AC10** 同一工作区并发触发两个翻译，后到者 **409**（D 组互斥）。
- **AC11** 后端测试基线（当前 **498**）只增不减、全绿。
- **AC12** `schema.sql` 带存量库迁移说明（删列没有 `IF EXISTS`，MySQL 8 也不支持），口径同
  `translation_status` 那次加列的先例。

## 待开工时展开

- `SupportedLanguage` 的完整形式（枚举值、label 文案、别名集合）与 `Bcp47Language` 新增的两个方法签名；
  以及 `resolveTargetLanguage(String)`（现吃配置值、空白抛 ISE）在改吃请求参数后**错误语义的变化**
  （配置错误是 ISE/500，调用方传参不合法是 IAE/400）。
- `SubtitleTranslationCoordinator.hasTranslationArtifact(input)` 现在**不带语种**，多语言后必须带——
  它是"按需触发的幂等判据"，签名与调用点都要改。
- `TaskController.translate` 的请求体 DTO 落位（`web/dto/` 下新建还是复用既有形状）。
- `TaskStageEvent.from` 简化后的完整分支（主状态 × D 组登记的组合）。
- `TaskResponse` / `TaskProgressMessage` / 前端 `types/task.ts` / `enums/task.ts` 的字段删除面
  （前端**本次不改**，但要在"前端待办"里点名，因为它靠 `translationStatus` 算 `isTranslating`）。
- `SubtitleTranslateProperties` 删掉 `targetLanguage` 字段后，那个 record 与 `@DefaultValue` 的收尾。
- **（2026-10-12，兄弟任务 `10-11-subtitle-keyword-regenerate` 的 check 移交）翻译侧摘要缓存
  「文件存在即命中」不看关键词指纹**：重生成若在「摘要成功落盘 → 清洗失败」处断掉，会留下一份
  比 `-clean` 新的 `context-<route>.json`；清洗侧按 `CheckpointPolicy.isCurrent` 会安全地重摘要，
  但翻译侧（`SubtitleTranslationCoordinator`）之后触发时会拿**新上下文配旧字幕**。
  安全方向是翻译侧也按指纹判——与本任务 `hasTranslationArtifact` 签名改动同在一条链上，开工时一并定。

## Notes

- 任务创建：2026-10-11，`base_branch = dev`，`--no-start`。
- 父任务：`10-11-subtitle-keywords-multilang`。
- 兄弟：`10-11-subtitle-keyword-regenerate`（**本任务依赖它落地的 D 组地基**）。
- `VideoTask.isEventTerminal` 的 javadoc 里早就写着"翻译后续会改成多语言选择，因此未来会删除判断是否
  处于翻译"——本任务兑现的就是这句。
