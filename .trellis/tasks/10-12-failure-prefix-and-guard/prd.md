# 失败前缀常量化 + 后置生命周期守卫改名

> 出自 `10-12-comment-domain-pilot` 的审查（2026-10-12），是注释收口时**发现的、但按「只改注释」
> 的边界没动的**两笔债。**纯代码改动，行为必须逐字等价。**
>
> 依赖：等 `10-12-comment-consolidation` 的 domain 试点合并、归档后开工——两笔都改到试点动过的文件。

## Goal

消掉两处「命名 / 口径与事实不符」的债：

### 1. 三处失败前缀口径不齐

| 前缀 | 现在长什么样 |
|---|---|
| `重新生成失败：` | ✅ 常量 `VideoTask.REGENERATE_FAILURE_PREFIX`，且 `TaskProgressNotifier:98` 真在用 |
| `翻译失败：` | ❌ `SubtitlePipeline:335` 里的硬编码字面量 |
| `任务执行失败：` | ❌ `SubtitlePipeline:314` 里的硬编码字面量，**连常量都没有** |

**后果**：`VideoTask.errorMessage` 的注释现在只能写「各自带前缀区分」——写不了「见 X 常量」，
因为写了就是假话。而且光加常量不改生产者**会让「下次改前缀会漏」更容易发生**：
常量看着像权威单源，实际 `SubtitlePipeline` 仍是另一处真相。

### 2. 守卫命名与作用域不符

`VideoTask.requireMainSucceededForTranslation` 的名字只说「翻译」，实际也被
`recordRegenerateFailure`（重新生成）在用——它真正守的是「主状态 SUCCEEDED 才能进第二生命周期」。

## 范围

**做**

- `VideoTask` 增两个常量：`TASK_FAILURE_PREFIX = "任务执行失败："`、`TRANSLATION_FAILURE_PREFIX = "翻译失败："`；
- `SubtitlePipeline` 的两处字面量改为引用常量（314、335 行）；
- `requireMainSucceededForTranslation` → `requireMainSucceededForPostLifecycle`（1 处声明 + 4 处调用）；
- 改完把 `VideoTask` 里那两处注释的常量引用补上（`translationFail` 的 `@param` 说明、
  `clearRegenerateFailure` 的正文、`errorMessage` 字段注释）。

**不做**

- **不动测试**。`SubtitlePipelineTest` / `VideoTaskTest` 等 5 处断言用的是字面量——它们断言的是
  **最终文案**，用字面量更贴合断言意图，也更能在常量被误改时报警；
- 不做任何其他重构；不顺手改别的命名。

## 验收标准

- [ ] **行为逐字等价**：`mvn clean test` 全绿，**测试文件一行未改**；三个前缀的字符串值不变。
- [ ] `SubtitlePipeline` 里不再出现 `"任务执行失败："` 与 `"翻译失败："` 两个字面量。
- [ ] `requireMainSucceededForTranslation` 全仓零命中。
- [ ] `git diff` 只含上述改动。

## 为什么单开

三处前缀 + 一处命名，跨 `domain` 与 `application` 两层。单开一笔好回滚、好 review——
混进那个 46 文件的注释 diff 里，reviewer 分不清哪行是注释、哪行是代码。
