# 字幕模型重构 8/8：收尾（网络超时 + spec 落笔 + 旧件清查 + 全量回归）

> 父任务 [10-15-subtitle-task-model-refactor](../10-15-subtitle-task-model-refactor/prd.md)；
> **终态设计一律引它的 [design.md](../10-15-subtitle-task-model-refactor/design.md)**（权威），
> 本文只写本段的范围、取舍与验收，不复述终态、不另开一份设计。
> 段级技术落点与执行顺序见 [implement.md](./implement.md)；技术取舍见 [design.md](./design.md)。
> **前置 S7（`10-17-subtitle-reconcile-endpoint`）已归档合并；本段从 `dev` 切分支。**
> 约束定义见父 [handoff.md](../10-15-subtitle-task-model-refactor/handoff.md) 的 **C46**（handoff 已降为论证留档）。

## Goal

后端 8 段里最后一段，四件事：

1. **给网络调用补超时（C46）** —— 把「线程卡死 → `RUNNING` 行永久占锁 + 并发闸门许可泄漏 → 队列永久堵死」
   这个缺口，降成「超时 → `FAILED` → 重跑收敛」。
2. **`.trellis/spec/backend/` 落笔** —— 把状态机口径（D-6）从任务卡设计文档搬进长期规范；
   订正规范索引的失实。
3. **旧件残留清查** —— 复核父任务 AC2 的清单，并把 `.trellis/spec/` 里拿已删类当先例的地方清掉。
4. **全量回归** —— 父任务 AC9。

## 现状与欠账（代码实证）

盘点在 2026-10-20 做过一轮，结论如下（**其中两条与父设计/交接的原有判断不符，已在 design §0 记明**）。

| 欠账 | 现状实证 | 本段动作 |
|---|---|---|
| **LLM（DeepSeek 路线）无超时** | Spring AI 的 `DeepSeekChatProperties` **没有 timeout 字段**；它取 Boot 自动配置的 `RestClient.Builder`（`spring-ai-starter-model-deepseek` → `spring-boot-starter-restclient`，依赖树已确认）。Boot 的 `spring.http.clients.read-timeout` / `connect-timeout` **默认都是 null = 不限** | 全局配置补上 |
| **术语查证 / ASR 结果下载无超时** | `DashScopeTermLookupProvider:64`、`DashScopeAsrProvider:63` 都从 `RestClient.Builder` 建 `RestClient`，同一条路 | 同上（一处配置覆盖） |
| **ASR 主等待是无界轮询** | [DashScopeAsrProvider.java:99](../../backend/src/main/java/com/vidsage/infrastructure/provider/asr/dashscope/DashScopeAsrProvider.java#L99) 调 SDK 的 `transcription.wait(...)`；反编译 SDK 2.20.6 确认其内部是**没有终止条件的循环**（只在任务结束时返回，否则 1s→5s 退避后无限重试；503/504 也无限重试） | **改代码**：自己拿 `fetch()` 写带截止时间的轮询（design §2） |
| **状态机口径不在 spec 里** | 三张状态机的值语义与写入路径只活在父 `design.md` §4；`.trellis/spec/` 全域 grep「状态机」只命中 `directory-structure.md` 的一句归属规则 | 新建 `state-machine-guidelines.md`（design §3.1） |
| **规范索引失实** | [backend/index.md:25](../../.trellis/spec/backend/index.md#L25) 把 `database-guidelines.md` 只列在「**已删除规范的重建记录**」一节里，而它 2026-09-30 就重建了 | 补进「规范索引」表 |
| **spec 里拿已删类当先例** | `provider-pattern.md:30`（`VideoTaskRepository`）、`quality-guidelines.md:159`（同）、`guides/comment-guidelines.md` 6 处（`VideoTask` / `WorkspaceKey` / `VideoTaskServiceTest` / `isEventTerminal(TaskStatus)`） | 全部换现存名字（design §3.3） |

**已查清、不需要动的**（免得后人重查一遍）：

- **Mimo / OpenAI 路线本来就有超时**：走官方 OpenAI Java SDK + OkHttp，`spring.ai.openai.chat.timeout` 默认 60s（`AbstractOpenAiProperties.getTimeout()` 已确认接线到客户端）。
- **短链跳转本来就有超时**：`BilibiliIdentityProperties.shortLinkTimeout` 默认 2s，连接与读取都设，且自建 request factory 禁了自动跳转。
- **外部进程本来就有超时**：`ExternalProcessRunner` 强制要求正数超时，默认 300s。
- **`backend/src` 旧件已清零**：父 AC2 清单 15 个类型名中 14 个零命中，唯一命中的 `TaskStatus` 4 处全是阿里 SDK 同名枚举（正常）；字段/方法名、表名、中文失败前缀常量、`schema.sql` 旧表与 `uq_` 索引全部归零。

## 本段范围

1. **全局 HTTP 超时**：`application.yml` 增 `spring.http.clients.connect-timeout` / `read-timeout`。
2. **收 Spring AI 重试次数**：`spring.ai.retry.max-attempts`（默认 10 会把它变成"设了超时也等 44 分钟"）。
3. **ASR 轮询改有界**：`DashScopeAsrProvider` 不再调 `transcription.wait(...)`，自建带截止时间的 `fetch()` 轮询；
   新增 `vidsage.asr.dashscope.transcription-timeout` 配置项。
4. **spec 落笔**：新建 `state-machine-guidelines.md`；`backend/index.md` 索引订正；两份文档加指针。
5. **spec 旧名清理**：backend 2 处 + guides 6 处。
6. **父文档同步**：父 `design.md` §7.6 的 C46 行、`handoff.md` 的 C46「实现时要查」按订正惯例处理。
7. **全量回归** + 父任务 AC1 / AC2 的证据收齐。
8. **（审查轮追加）删掉零生产调用点的领域迁移方法** —— `SubtitleArtifact.supersede()` / `confirmDeletion()`、
   `IntermediateFile.revive()` / `supersede()` / `confirmDeletion()`。它们把状态机建模了两遍，
   而生效的只有 SQL 那一遍；留着会让读者以为"作废必须过那道校验"。见 design §5 D8。

## 本段不做

| 不做 | 原因 |
|---|---|
| **给 DashScope SDK 的 HTTP 层设超时** | 它自带默认（connect 120s / write 60s / read 300s），且那是**每次请求**的超时，不是本段要解的问题（本段要解的是循环没有出口）。改它要走 `Constants.connectionConfigurations` 全局静态，副作用面比收益大 |
| **给非网络的挂住兜底** | C46 已记残余风险（如 `destroyForcibly` 后仍不退出的进程）；那是另一类机制 |
| **改 `ExternalProcessRunner`** | 早已强制正数超时，与本段无关 |
| **修 `frontend/src` 与 `.trellis/spec/frontend/` 的旧名** | 父 prd 明示前端接接口归 C60 另开任务；那几处会在 C60 落地时整段重写，现在改是白工。**只在 implement.md 记一条待办** |
| **`SubtitlePipeline` 拆三条管线** | 未排期（S5/S6 欠账 #7） |
| **全站鉴权 / 版本回退 / 定时对账** | 父 prd 范围外 |

## Acceptance Criteria

- [ ] **AC1 全局超时生效**：`application.yml` 有 `spring.http.clients.connect-timeout` 与 `read-timeout`；
      起服务后跑一次真实 LLM 调用（清洗或翻译）**正常走完**，不因超时被误杀。
- [ ] **AC2 重试次数收窄**：`spring.ai.retry.max-attempts` 落地；**实测钉死**「配的值对应几次尝试」
      （Spring AI 属性名叫 `max-attempts`，接线却走 `RetryPolicy.maxRetries(...)`，差一次），
      把实测结论写进 design 的取值段。
- [ ] **AC3 ASR 不再无界**：`backend/src/main` 里对 SDK `transcription.wait(...)` 的调用**归零**；
      有界轮询由单测覆盖：替身喂「永远 `RUNNING`」→ 到 deadline 抛异常（不挂住）。
- [ ] **AC4 ASR 正常路径不回归**：`SUCCEEDED` / 「无语音」（`ASR_RESPONSE_HAVE_NO_WORDS`）/ 任务失败
      三个分支行为与改前一致（单测覆盖）；真库跑一次真实转写成功。
- [ ] **AC5 状态机口径进 spec**：`.trellis/spec/backend/state-machine-guidelines.md` 存在，
      含三张状态机的图、每个值的**全部**写入路径与路径类型（正常 / 恢复）、两条不变量
      （每个值恰好一条写入路径；零路径的死值不许留）；**抽查**每条写入路径都能在代码里指到落点。
- [ ] **AC6 索引失实订正**：`backend/index.md` 的「规范索引」表含 `database-guidelines.md` 与
      `state-machine-guidelines.md` 两行，且「已删除规范的重建记录」一节不再把它读成"尚未重建"。
- [ ] **AC7 spec 旧名清零**：`grep -rn 'VideoTask\|WorkspaceKey\|TaskInputType\|TaskStatus\|SubtitleRoute\|SourceUri' .trellis/spec/backend .trellis/spec/guides`
      **归零**。唯一例外是阿里 SDK 的 `TaskStatus` —— 它指的是**阿里那个同名枚举**（`provider-pattern.md`
      自己就写明"是阿里 SDK 类型"），不是本项目删掉的那个，须逐条点明而非只报一个数。
      **口径（2026-10-20 审查轮定）**：**规范只写"现在是什么"，不写"什么已经退场"。**
      实施中途曾一度把 `SubtitleRoute` / `SourceUri` 当"历史校准例"保留、靠一句"这三个类已退场"兜住 ——
      **作废**：读者不需要知道谁退过场，只需要知道现在该怎么做。那些例子改成不点名或换用现存类。
- [ ] **AC8 旧件残留复查**：父 AC2 清单在 `backend/src`（main + test）复跑一遍清查，仍全零命中。
- [ ] **AC9 父文档同步**：父 `design.md` §7.6 与 `handoff.md` 的 C46 表述按订正惯例处理
      （废弃的直接删；prd 用删除线 + 日期）。
- [ ] **AC10 全量回归**：`mvn clean test-compile` 过；全量测试绿（**动手前重量基线**）；后端能起服务；
      一次完整流程（提交 → 产物 → 翻译 → 重新生成字幕）走通。
- [ ] **AC11 父任务 AC1 / AC2 证据收齐**：AC1（六张表齐备、索引名符合 N4、枚举列 `VARCHAR(32)`、
      无 DB 外键、`schema.sql` 头部写明存量迁移步骤）与 AC2（旧件零残留）各出一份可核对的证据。
- [ ] **AC12（审查轮追加）死迁移方法清零**：`grep -rn 'supersede()\|confirmDeletion()\|revive()' backend/src`
      **归零**；需要那些状态的测试夹具改走 `restore(...)`；全量测试绿（基线 **700** = 711 − 11 个迁移用例）。
- [ ] **AC13（审查轮追加）状态守卫补进 SQL**：`markSupersededAndClearFiles` / `markDeleted`（产物）/
      `markDeleted`（中间产物）三条 UPDATE 各带 `AND status = ...`；**真库验证**三条在 happy path 上
      仍命中（`Updates: 1`），四个 run 全 `SUCCEEDED`、零回收失败。

## Notes

- **两条与原有判断不符的盘点结论**（本段新查出，父设计/交接据此订正，详见 design §0）：
  ① 「网络调用没有超时」**只对 DeepSeek 路线成立**，Mimo 路线自带 60s；
  ② 真正的硬缺口不是"没设超时"，是 **`transcription.wait()` 这个循环没有出口** —— 配置解决不了。
- **测试基线**：动手前静态数一次 `@Test` / `@ParameterizedTest`（S7 归档时的值是 **708**，须重量确认）。
- **脱敏红线**：`source_uri`（URL 原文 / 上传原始文件名）不得写日志。本段新增的日志同样不拼路径与目录名。
- **超时值是工程取值不是产品指标**：判据是「比正常最坏耗时宽一档、又能收敛」，别拿它当 SLA 论证。
- 环境命令（判编译、跑测试、起服务、推送代理）见父 [handoff.md §10](../10-15-subtitle-task-model-refactor/handoff.md)。
