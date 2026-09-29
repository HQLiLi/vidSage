# 验收证据：术语官方译名联网查证（2026-09-29，重构版）

> 逐条对应 `prd.md` 验收标准。环境：本地 Spring Boot（8080），`DASHSCOPE_API_KEY` 已设；
> 素材 `BV1axNJ6REoa`（42 段中文剑术解析，MVP-3 同条），`target=en` 经 `application-local.yml`
> 临时覆盖（已还原）。`context-asr-cloud.json` 为改动前三字段版（备份 md5 `7ad1f234a37ed68006494fa6f1762db9`）。
> 本文档对应 **893a959 之后的三条重改意见重构版**（结构见 design.md §六），首版证据已被本版取代。

## 1. 冒烟（先行门槛）—— 通过（同首版，引擎已定案）

`qwen3.8-omni-flash` 支持 `enable_search` 但强制 `agent` 策略 → 思考模式 → 必须流式；
**`qwen-flash` + `search_strategy:"standard"` 非流式一次打通**（`Sekiro: Shadows Die Twice` + Steam 来源，
`prompt_tokens=961`）。引擎定案 qwen-flash，详见 design §一。

## 2. 真实运行：翻译流程内查证 + 独立产物 + context 零改写 —— 通过

> 本节为 qwen-flash（后被换代，见 §4.2/4.3）的首次重构版运行；**最终态以 §4.3 的 qwen3.7-flash 运行为准**
> （terms 文件当前内容即该轮产物：15 条，官方 1 / 通用 4 / 弃权 10）。context 零改写在两种模型下共 6 次运行全部成立。

删翻译检查点与 terms 产物后 `POST /api/subtitles/translate`：50.3s（查证 10.6s + 翻译 39.7s），
`retrySteps=0, untranslatedCount=0`。

- **`context-asr-cloud.json` md5 前后一致（`7ad1f234`）——context 全链路零改写**（含后续所有复跑，共 4 次运行无一例外）。
- 生成独立产物 **`terms-asr-cloud-en.json`**（`VerifiedTerms`），结论：

| 术语 | official | officialNote |
|---|---|---|
| **只狼** | **Sekiro** | **官方译名（来源：www.sekiro.com）** |
| 日耳曼 | German | 通用译名，非官方（来源：en.wikipedia.org） |
| 弹刀 / 架势条 / 打铁 / 气绝 / 震刀 / Ochs / 举顶格 / 好纳尔人 / 菲奥雷 / 农民杀手 / 劈挂 / 受流 / 重庆南坪印象城 | （空串，弃权） | 联网查证未找到有据可依的目标语种译名，弃权 |

- 汇总日志：`术语译名查证完成: 共=15, 官方=1, 通用=1, 无=13, 失败=0, cost=10628ms`；
  逐条 INFO 带 verdict/level/来源 host/耗时（如 `term=只狼, verdict=有, level=official, source=www.sekiro.com`）。
- 弃权语义与项目所有者直觉一致：弹刀/架势条走「放开直译」分支（任务 2 消费时可不标注）。

## 3. 产物即检查点：不删零搜索、删件重查 —— 通过

- **只删翻译检查点（terms 保留）重跑**：日志出现 `术语查证产物已存在，跳过查证（删文件即重查）`，
  逐条查证行数零新增——零搜索。
- **删 terms 产物 + 检查点重跑**：`术语译名查证开始: terms=15` 再次出现，逐条查证行数 15 → 30——重新查证，
  且 context 依旧零改写。

## 4. 迭代过程中的三个发现与修复（全部实测）

### 4.1 查证抽样的逐轮抖动 → 修复（temperature）

复跑中发现**同一词逐轮结论不同**：`只狼` 一轮 `Sekiro`（正确）一轮 `Sengoku`（错，把背景词当成了译名）。
根因：查证请求未设采样温度，抽取任务在默认温度下有创作性抖动。修复：请求带 `temperature:0.1`，
复跑结论回稳。

### 4.2 qwen-flash 别名停在 2025-07-28 快照 → 换代 qwen3.7-flash

项目所有者查明：`qwen-flash` 别名不是滚动跟随最新代，**停在 2025-07-28 快照（14 个月未更新）**。
经官方价目页核对（token 价差在本场景全在分厘级，不构成决策因素；搜索插件费 0.45 元/视频才是大头），
按「新旧」拍板换代：**`qwen3.7-flash`**（当前代、输出 0.8 元/百万为三者最便宜、实测直连）。
`qwen3.8-flash` 贵 3~4 倍且未过冒烟，不选。

### 4.3 qwen3.7-flash 思考链把同步接口拖到超时 → 修复（enable_thinking:false）

首跑 qwen3.7-flash（思考默认开）：**单条 20~45 秒**，15 条 450 秒+，同步 translate 接口 300 秒即超时。
请求加 `enable_thinking:false` 后：**单条 0.6~2.7 秒**、零思维链（completion 从上千 token 降到 17）、
结论质量无损（`只狼 → Sekiro: Shadows Die Twice`，官方，www.sekiro.com）。整轮查证 17.6 秒、
translate 全程 73.5 秒。最终单视频结论：`共=15, 官方=1, 通用=4, 无=10, 失败=0`。

**顺带观察（留给任务 2 的输入）**：qwen3.7-flash 比 qwen-flash「勤快」——本轮给出 5 个结论
（弹刀→deflection、架势条→Stance、日耳曼→German、重庆南坪印象城→Chongqing Nanping Impression City
均为通用译名）。且出现一次弱来源：`重庆南坪印象城` 的 source 是 www.google.com（搜索聚合页而非内容页），
机械接地校验拦不住这种——加强方向：来源域名排除搜索引擎域。

## 5. 失败路径 —— 单测覆盖

`DashScopeTermLookupProviderTest`（10 测，已迁 `provider/llm/dashscope/`）：证据不全弃权 / level 缺失保守降通用 /
围栏剥离 / 坏回复抛 ISE / 缺 key / 空参。`SubtitleTranslationServiceTest` 新增 6 个编排用例：
查证落盘含官方/弃权/失败分流、产物已存在零调用、同语言不查不落盘、provider 缺席零调用不建文件、
全部失败不建文件。`VerifiedTermsTest`：round-trip 与弃权空串不吞成 null。
**`mvn test`：321 个测试全绿**（1 跳过为既有连通性探针）。

## 6. 运维注意（给以后起服务的会话）

Windows 下 `TaskStop`/杀 bash 包装进程**不会**连带杀 `mvn spring-boot:run` 的 Java 子进程——
本轮曾因残留旧 JVM 占用 8080，导致请求打到旧代码（产物被旧逻辑改写，已还原重跑）。
起停服务的正确姿势：`netstat -ano | grep :8080` 找 PID → `taskkill //F //PID <pid>`，重启前核对端口已清空。

## 覆盖与缺口

- 真实运行只覆盖 zh→en（唯一有素材方向，与 PRD 范围一致）；清洗流程零感知由测试与代码审查保证。
- 查证质量为有限抽样：temperature 修复后仅复跑一轮；任务 2 接入消费侧后如仍见抖动，
  下一步可考虑「官方结论需两个独立来源交叉」的加强校验。
- `打铁 → parry`（首版）与 `打铁 → 弃权`（重构版）均为真实搜索结论，抖动本身已由 §4 的修复压低，
  但通用译名的稳定性天然弱于官方译名。
