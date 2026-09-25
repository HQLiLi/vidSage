# MVP-2 下载与字幕获取 — PRD

> Epic 路线图见 `../09-24-vidsage/prd.md`，ASR 双路方案与**输入路线/外部工具决策**见 `../09-24-vidsage/design.md`，ASR 模型调研见 `../09-24-vidsage/research/competitive-and-asr.md`。
> 实现参考（本地开源项目源码索引）见 `../09-24-vidsage/research/oss-references.md` 第三节 MVP2 小节。
> **外部进程实测笔记见 `../09-24-vidsage/research/external-process-integration.md`**（编码坑、退出码、yt-dlp 不接受本地文件、音频格式）。
> 规划期（Phase 1.3）应把上述两份 research 都挂入本任务的 `implement.jsonl` 与 `check.jsonl`。

## 范围

1. 下载源适配器接口 + yt-dlp 命令行实现（B站 / YouTube；抖音仅留接口标记实验性，不实现）。
   - **只负责「拿到视频文件」**，不承担音频抽取（理由见 epic design.md「输入路线与外部工具」）。
   - 版本钉死；提供版本探测与新鲜度告警，**不做自动更新**。
2. 音频抽取：ffmpeg 命令行调用，**两条输入路线（URL / 本地文件）共用同一段逻辑**。
   - 输出格式随 ASR 路径：本地 FunASR → wav；云端 DashScope → m4a。
3. 字幕获取双路：
   - 优先平台已有字幕：**人工字幕优先（`--write-subs`），自动字幕兜底（`--write-auto-subs`）**。
     参考项目只实现了自动字幕，人工字幕是缺口，见 `research/oss-references.md` 第 14 条。
   - 无字幕走 ASR：`ASRProvider` 抽象（`vidsage.asr.provider` 配置切换），本地 FunASR 微服务为默认（开发/演示替身），云端 `DashScopeAsrProvider` 为兜底（已落地，提交+轮询）。
4. 阶段产物落盘、可断点复用（管线约定见 epic design.md）。
5. 任务进度接入 MVP-1 的 SSE 推送。
6. **外部工具可用性检查**：yt-dlp / ffmpeg / ffprobe 存在性与版本探测，缺任一即给出「缺什么、怎么补」的明确报错。

## 不做

- ASR 模型 bake-off / 说话人分离（见 epic 未决问题，另行决策）。
- 翻译、总结。

## 验收标准

- [ ] B站 / YouTube 链接产出字幕文件（平台字幕或 ASR 二选一路径均验证过）。
- [ ] 本地 mp4 / 音频文件走 ASR 路径产出字幕。
- [ ] local / cloud 两种 ASRProvider 可通过配置切换且行为一致（同一段音频）。
- [ ] 中途失败后重跑可从已完成阶段续跑（断点复用）。
- [ ] B站字幕拉取的登录 / 风控边界形成实测结论，回写 epic 未决问题。
