# 执行计划：字幕纠错的批间上下文

## 状态（2026-09-28）

**已实现并跑通**：`feature/subtitle-context` 分支，用户跑真实视频确认清洗有效；`mvn clean test` 235 例通过。功能与设计见 design.md（按实际落地重写）、需求见 prd.md（同）。

**改道记录（只记这一笔）**：原规划把 LLM 调用抽成 `SubtitleCorrector.Llm` 函数式接口 `(system, user) -> String`，实施时去掉——它表达不了 `.messages()` 多轮消息：批内重试要让模型看见自己上一条错在哪（失败回复作 assistant 消息 + 反馈 user 消息），跨批历史要成对携带往返，窄缝装不下。故 `SubtitleCleanser` 直持 `ChatClient`。

**本轮收尾（「字幕清洗 v0 收尾整理」，2026-09-28 已批准，按步停等审查）**：

1. 删死代码：`SubtitleCorrector` / `SubtitleContextBuilder` / `SubtitleCorrectorTest` / `SubtitleCleanupConfig`（唯一 bean 是无人注入的孤儿）/ `SubtitleContext.isEmpty()`（零调用）
2. 挪包：`com.vidsage.llm` → `provider/llm`，具体类不抽接口（先例 `provider/tool/ExternalProcessRunner`；它不是「同一能力多种来源」的缝，是吃 LLM 的领域 worker）
3. 摘要收口：新建 `provider/llm/SubtitleContextSummarizer` 接走服务层摘要调用，service 摘掉 Spring AI 类型；补摘要测试（正常 / 异常降级 / 坏 JSON 降级）
4. spec 记录 `provider/llm` 现阶段形态；design.md 同步两笔（包名、摘要落点）

## 踩坑备查

- `ChatClient.Builder` 是 prototype；`@Bean ChatClient x(ChatClient y)` 是自引用（容器里没有现成 ChatClient bean），起不来——必须注入 `ChatClient.Builder` 再 `.build()`
- `ChatClient.user(String)` 是**覆盖**不是追加；多轮消息必须 `.messages(...)`
- DashScope SDK 拖进的 `jsonschema-generator:4.31.1`（Jackson 2）会挤掉 Spring AI 需要的 5.0.0，`.entity()` 运行时 `NoSuchMethodError`——已在 pom 排除，原因见 pom 注释

## 仍欠（明确出栈，另行任务）

- **摘要产物落盘（PRD R5）+ 摘要输入截断**：`context-<route>.json` 二级复用未实现，每次清洗重跑重复付摘要调用；当前摘要拿全文，长视频 token 量随全篇涨
- **并发批处理**：维持串行；参考项目 `max_workers: 4` 供届时参考
- **`LLMProvider` 四件套**（provider-pattern「规划中」项）：MVP3 本地模型落地时建；届时 `SubtitleCleanser` / `SubtitleContextSummarizer` 改吃该缝，`vidsage.llm.provider` 键已预留

## 验证命令

```bash
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn clean test
```

`mvn clean` 不能省——改/删源文件后 `target/classes` 的残留 class 会被 Spring 扫到（2026-09-27 踩过）。

## 审查门

- 本轮整理每步改完跑全量测试，绿了停，等审查再进下一步
- 提交、合并、归档逐门批准，不自行执行
- 提交前确认工作区里没有别的任务的在制品混进来
