# 字幕手动纠错 — 执行计划

> 父任务不写业务代码。本文只管子任务顺序、验证命令、评审门与回滚点。

## 前置

1. 阅读 `prd.md` 与 `design.md`。
2. 每个子任务从 `dev` 开自己的 feature 分支；合回 `dev`（本任务树统一以 `dev` 为基线）。
3. 后端构建与测试按项目口径：
   - 判编译：`mvn clean test-compile`（**不要用 `mvn compile` 增量结果判绿**）
   - 跑测试：`mvn test`
   - 测试基线：以本任务开始时的 `mvn test` 通过数为准，只增不减。
4. 前端：`npm run type-check` + `npm run lint`。

## 步骤 1：数据层与 API（子任务 `10-25-corrections-data-api`）

- [ ] `TranscriptionSegment` / `TranslatedSegment` 加 `id` 与缺省构造器；旧 JSON 兼容测试。
- [ ] `schema.sql` 加 `subtitle_correction` 表。
- [ ] 领域值对象、端口、Entity/Mapper/Repository 与仓储单测。
- [ ] `SubtitleCorrectionOverlay`（合并与 matched 计算）及单测。
- [ ] `SubtitleCorrectionService` + 校验 + 409/404/400 分支单测。
- [ ] `SubtitleCorrectionController` + DTO + `@Schema`。
- [ ] Swagger 手工确认端点形状。

**评审门 G1**：`mvn clean test-compile` 与 `mvn test` 全绿；用 `curl` 验证 save/list/delete/clearStale 四个端点各一次；确认运行中写返回 409。
**回滚点**：本子任务全部改动可单独回退；表可 `DROP`。

## 步骤 2：读取路径接入与强制重翻（子任务 `10-25-corrections-read-paths`）

- [ ] CLEANED `segments.json` / `subtitles.srt` 下载套用纠错；无纠错走直发。
- [ ] TRANSLATED 同 lang 下载套用；其他语种不受影响。
- [ ] `?raw=1` 全类型直发。
- [ ] 翻译取源文本时套用纠错。
- [ ] `POST /translate` 增加 `force`；`force=false` 行为不变。
- [ ] 清洗复用判定、指纹、对账路径不得套用纠错（补一条防回归测试或断言）。

**评审门 G2**：`mvn test` 全绿；用同一个已完成任务验证：
- 无纠错时下载哈希 == 磁盘文件哈希（AC3）
- 加一条纠错后下载内容变化、`?raw=1` 仍等于磁盘文件（AC8）
- 改原文后 force 重翻，译文来自改后原文（AC4 后半）
**回滚点**：只回退下载与翻译接线，数据层保留。

## 步骤 3：前端（子任务 `10-25-corrections-frontend`）

- [ ] 类型与 `buildCues` 加 `id` / `sourceText` / `translationStale`，兼容无 id 旧数据。
- [ ] 原文/译文独立编辑态与快捷键、幂等保存、失败还原。
- [ ] 译文过期即时标记 + "重新翻译"走 force。
- [ ] 失效纠错提示条 + 一键清除。
- [ ] 运行中禁用编辑。

**评审门 G3**：`npm run type-check` + `npm run lint` 绿；手工走查 R2/R4/R5/R7 的交互。
**回滚点**：前端单独回退，后端接口保留无副作用。

## 步骤 4：父任务集成评审

- [ ] 逐条验收 AC1–AC8。
- [ ] 确认磁盘产物文件在全程未被改动（对同一任务前后哈希比对）。
- [ ] 确认没有把纠错套进清洗复用/指纹路径（代码走查）。
- [ ] 汇总遗留项到 `known-bugs.md`（如有）。

## 步骤 5：收尾

- [ ] 更新 `.trellis/spec/`：新增"字幕纠错的读取口清单"一条，写明哪些路径必须套用、哪些禁止套用。
- [ ] 三个子任务按顺序归档；父任务最后归档。
- [ ] 提交信息按项目惯例，推送带本机代理。
