# MVP-3 LLM 翻译与双语 SRT — PRD

> Epic 路线图见 `../09-24-vidsage/prd.md`，技术方案见 `../09-24-vidsage/design.md`。
> 实现参考（本地开源项目源码索引）见 `../09-24-vidsage/research/oss-references.md` 第三节 MVP3 小节；规划期（Phase 1.3）应把它挂入本任务的 `implement.jsonl` 与 `check.jsonl`。

## 范围

1. LLM 唯一翻译路线（DeepSeek，OpenAI 兼容接口），接口层不为传统机器翻译 API 预留实现。
2. 上下文感知的整段翻译，术语与称谓全片一致。
3. 架构预留「直译 -> 反思 -> 重写」进阶轮次（借鉴 VideoLingo 三遍过），本期只实现第一遍直译，轮次以配置/接口形式预留。
4. 导出原文 + 译文双语 SRT。
5. 翻译阶段产物落盘、断点复用，进度接入 SSE。

## 不做

- 反思/重写轮次的完整实现（本期只留接口）。

## 验收标准

- [ ] 输入 MVP-2 产出的字幕，输出双语 SRT，时间轴与原文对齐。
- [ ] 同一术语/人名全片译法一致（抽查验证）。
- [ ] 至少一条真实运行证据（learning-records）。
