# 执行计划：字幕术语替换

## 前置状态

- 分支：`feature/subtitle-term-replacement`（从 `dev` 开出，已 `set-branch`）
- 任务：`09-28-subtitle-term-replacement`，`base_branch=dev`
- 改动文件 5 个：`ChatClientConfig.java`、`SubtitleCleanser.java`、`CleanedSubtitle.java`、`SubtitleCleanupService.java`、`CleanedSubtitleResponse.java`；测试 2 个：`SubtitleCleanserTest.java`、`SubtitleCleanupServiceTest.java`

## 步骤清单

### 第一批：提示词（纯文本，不改代码逻辑）

- [ ] **1. 改 A —— 总结提示词**（`config/ChatClientConfig.java`，`ContentSummarizer` bean）
  - 重写 `fixed` 定义：正确写法 = 应当替换成的写法；正确写法是外语原文时直接写原文
  - 重写 `note` 定义：替换理由；必须提 asr 与 fixed，但只能写成「为什么该换成 fixed」，不得写成并列口吻
  - 删三条：`需要提外语原文时写成「德语原文 Mittelhau」这样的说明`、`不要重复 asr 或 fixed 里的写法`、`不要出现「应为」「识别错误」这类修正字眼`
  - 主题要求补一句：提到术语表里的词时用 `fixed` 写法
  - 保留：同音误写推断 / 拿不准不要编 / 只列专有名词 / 最多 30 条 / 只输出 JSON

- [ ] **2. 改 B —— 清洗提示词**（`config/ChatClientConfig.java`，`subtitleCleanupChatClient` bean）
  - 开头「禁止翻译、禁止改写」→ 收窄为「只修正识别错误，禁止改写：不改句式、不改措辞、不合并或拆分句子」
  - 「- 保持原语言：……」→ 前面加「除术语替换外，」
  - 新增「术语替换的判定」段（正文见 `design.md` 第三节第 3 条）
  - 新增正例 + 反例各一条完整句（正文见 `design.md` 第三节第 4 条）

- [ ] **3. 改 C —— 用户模板**（`provider/llm/SubtitleCleanser.buildUserPrompt()`）
  - 术语行渲染：`- "米特号" 应为 "Mittelhau"（德语原文 Mittelhau）` → `- "米特号" → "Mittelhau"（候选正确写法）：ASR 把德语术语记成了中文音译`
  - 正文引导语：`请修正以下字幕，保持原语言，不要翻译：` → `请修正以下字幕，术语按上面的术语参考判断是否替换；其余内容保持原语言，不要翻译：`
  - 术语命中判定（只列本批真出现过的）**不动**

- [ ] **4. 验证 1：编译 + 现有测试全绿**
  ```bash
  cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
  grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' target/surefire-reports/*.xml
  ```
  预期：与改动前同数、零 failure。提示词是字符串字面量，`SubtitleCleanserTest` 用的是桩 system prompt，不受影响。

- [ ] **5. 🚦审查门 1：停，等批准**（不提交、不继续）
  - 提交信息（批准后）：`feat(prompt): 术语替换的判定口径与 fixed/note 语义`

### 第二批：代码

- [ ] **6. 改 D —— 校验层基线豁免**（`SubtitleCleanser.clean()` + `validate()`）
  - 新增基线构造：本批原文里把命中术语（`asr != fixed`）的 asr 字面替换成 fixed
  - 通过条件改为 `max(ratio(原文, 候选), ratio(基线, 候选)) ≥ 阈值`
  - `validate()` 增加「命中术语」入参（包级可见，跟着改）
  - 阈值与重试文案不变；**不做强制**，保留原写法不触发重试

- [ ] **7. 改 E —— 术语替换指标 + 契约**
  - `CleanseResult` 加 `int termHits, int termApplied`（口径见 `design.md` 第六节）
  - 每批一条 `info` 日志（命中/替换数 + 未替换清单），汇总行并入 `SubtitleCleanupService` 现有「字幕清洗完成」那条
  - `CleanedSubtitle` 同步加两字段；`toCleaned(...)` 两处调用点（正常路径 + 断点复用）补参
  - `CleanedSubtitleResponse` 加两个 `@Schema` 字段并更新 `from()`

- [ ] **8. 补单测**（`SubtitleCleanserTest`，沿用「真 ChatClient + 桩 ChatModel」手法，用 `SubtitleContext` 造术语）
  - 替换了 → 不被拦（原会因 0.571 被判改太狠的那类文本）
  - 没替换 → 也不被拦
  - 只换一半 → 能过，且 `termApplied` 如实上报
  - 全批不换 → `termHits > 0, termApplied == 0`，未替换清单进日志
  - 术语行渲染格式断言（现在全仓没有一条断言 `参考术语表` 段，属新增覆盖）
  - `SubtitleCleanupServiceTest:62` 构造 `CleanseResult` 处随签名改

- [ ] **9. 验证 2：全量测试**
  ```bash
  cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
  grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' target/surefire-reports/*.xml
  ```

- [ ] **10. 🚦审查门 2：停，等批准**
  - 提交信息（批准后）：`feat(subtitle): 校验层基线豁免与术语替换指标`

### 第三批：验收复跑

- [ ] **11. 删断点产物**（不删会走 `readCheckpoint` 复用、根本不调 LLM）
  ```bash
  cd "backend/data/works/https___www.bilibili.com_video_BV1Tk3Q69Erd__spm_id_from=333.1365.list.card_arch-42c1fbca3275"
  rm -f segments-asr-cloud-clean.json subtitles-asr-cloud-clean.srt
  ```

- [ ] **12. 起服务**（8080 被占，用 8081）
  ```bash
  cd backend && JAVA_HOME=/f/java_soft/java21 mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
  ```

- [ ] **13. 打接口**（URL 已核实：sha256 前 12 位 = `42c1fbca3275`，与既有目录名一致；**不传 reference**，与本次基线一致）
  ```bash
  curl -s -X POST http://localhost:8081/api/subtitles/clean \
    -H 'Content-Type: application/json' \
    -d '{"url":"https://www.bilibili.com/video/BV1Tk3Q69Erd/?spm_id_from=333.1365.list.card_archive.click&vd_source=60a7fe67b4220e755961a42c50ce321b"}'
  ```

- [ ] **14. 比对产物**
  ```bash
  cd "backend/data/works/https___www.bilibili.com_video_BV1Tk3Q69Erd__spm_id_from=333.1365.list.card_arch-42c1fbca3275"
  grep -o 'Oberhau\|Unterhau\|Mittelhau\|Pommel\|乌波号\|翁特号\|米特号\|密度号\|帕姆\|第一件\|第一剑' segments-asr-cloud-clean.json | sort | uniq -c
  ```

  | 词 | 预期 |
  |---|---|
  | `Oberhau` / `Unterhau` / `Mittelhau` / `Pommel` | 3 / 2 / 4 / 1 |
  | `乌波号` / `翁特号` / `米特号` / `密度号` / `帕姆` | 0 |
  | `第一剑` / `第一件` | 1 / 0 |

  同时看 HTTP 响应里的 `termHits / termApplied` 是否与产物一致，以及日志里的未替换清单。

- [ ] **15. 收进程**：`taskkill //F //T //PID <java_pid>`（`mvn spring-boot:run` 的父进程杀不干净子 JVM，必须按 PID 连树杀）

- [ ] **16. 结论回写 `prd.md` 验收清单**，跑 3.3 spec 更新，再走归档

## 回滚点

| 回滚点 | 范围 | 手段 |
|---|---|---|
| 审查门 1 后 | 提示词（A/B/C） | `git revert` 提交 1；纯文本，不影响编译 |
| 审查门 2 后 | 代码（D/E） | `git revert` 提交 2；提示词改动可保留 |
| 验收不过 | 全量 | 两次提交都 revert 回 `dev` |

## 已知风险（登记）

1. **R3 不许点名「必须换」+ 验收要四处全中** → 成败押在模型判断力上，可能要迭代几轮提示词。这是已拍板的路线。
2. **正例用了本期真实例子** → 模型可能读成「这期就该这么换」而非「这一类怎么判」，反例是主要对冲。
3. **指标是保守近似** → 变体形（`Mittel Hau`）会被算成未替换，日志多报一条，属可接受偏差。
