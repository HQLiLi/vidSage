# 日志规范

> 2026-09-24 随首个日志实现落地重建（此前因全库无日志语句而删除，见 [index.md](./index.md)）。
> 以 `web/controller/TaskController.java`、`application/transcription/pipeline/TranscriptionStage.java`、`infrastructure/provider/asr/dashscope/DashScopeAsrProvider.java` 的真实代码为依据。

---

## 一、选型：SLF4J 门面 + Boot 内置，不引第三方日志依赖

- **代码只面向 SLF4J 门面**：用 lombok `@Slf4j` 生成 `log` 字段（lombok 已在 `pom.xml`）。禁止直接依赖 Logback / Log4j2 的 API
- **不引第三方日志插件**（`logstash-logback-encoder`、`spring-boot-starter-log4j2` 等）：Spring Boot 4.1 内核已内置结构化日志（ECS / Logstash / GELF 三种格式，Logback 与 Log4j2 两条线都支持），需要 JSON 输出时改配置即可，零新增依赖：

  ```yaml
  # 需要接 ELK / Loki 采集时再开启，当前默认文本格式
  logging:
    structured:
      format:
        console: ecs
  ```

- 什么时候才重新评估（与 [quality-guidelines.md](./quality-guidelines.md)「实现方式选当前主流」同源）：

  | 触发条件 | 动作 |
  |---|---|
  | 要接 ELK / Loki 采集 | 开 Boot 内置 structured logging，不引 encoder |
  | 日志量大到同步输出成为瓶颈 | 再评估 Log4j2 异步 |
  | 要接 Grafana Tempo 等做链路关联 | Micrometer Tracing + OTLP |

理由：门面隔离实现，换后端只改配置、业务代码零改动。`pom.xml` 里 dashscope SDK 排除 `slf4j-simple` 正是防止第二个日志实现混入（见 pom 内注释）。

## 二、级别分层

| 层 | 级别 | 记什么 | 参照 |
|---|---|---|---|
| Controller | INFO | 请求入口与出口（sourceType、language、segments、cost）；入参不合法 WARN | `TaskController` |
| 阶段 / 服务 | DEBUG | 编排节点（委托哪个 provider、耗时多少）；与 Controller 耗时相减即框架开销 | `TranscriptionStage` |
| Provider 外部调用 | INFO | 有外部副作用的锚点：任务提交/结束的 taskId、status、cost | `DashScopeAsrProvider` |
| Provider 内部节点 | DEBUG | 地址解析、结果下载等中间步骤 | `DashScopeAsrProvider` |

- **外部系统锚点必须 INFO**：taskId 这类能去外部控制台回查的 id 不许降为 DEBUG
- 配置基线（`application.yml`）：`root: INFO`、`com.vidsage: DEBUG`；线上嫌吵下调业务包级别，不动 root

## 三、写法

- **参数化占位符**：`log.info("收到转写请求: sourceType={}, language={}", a, b)`；禁止用 `+` 拼接构造消息
- **消息语言与异常信息一致**：中文为主、专有名词保留原文，规则见 [error-handling.md](./error-handling.md)「错误信息语言」
- 已处理的异常（如 Controller 局部 handler 映射 400）在 handler 里记 **WARN**；未处理的异常由框架统一记录，**不要先 log.error 再原样上抛**（双重记录）
- 计时：`System.currentTimeMillis()` 起止相减，毫秒表达（`cost={}ms`）；当前外部调用均为秒级，无需更精细

## 四、脱敏红线（硬性要求）

| 不许进日志 | 原因 | 替代做法 |
|---|---|---|
| API key 等密钥 | 泄露凭证 | 只打 model 等非敏感配置字段 |
| `*Properties` record 整体 | record 的 `toString()` 会带出 apiKey（如 `DashScopeProperties`） | 逐字段取值 |
| 完整音频 URL | 公网 URL 常带 Signature 等签名参数，整条打印等于泄露临时凭证 | `describeAddress` 只打主机名或 `oss-temporary-storage` 标记 |
| 转写全文等用户内容 | 用户数据 | 只打 segments 条数等度量 |
| Controller 请求体原值 | URL/路径指向用户资源 | 只打 sourceType 等「请求形态」 |

### 例外：开发期专用的校验失败详情（2026-09-28）

为判断相似度守卫（`SubtitleCleanser#validate`）拦下的究竟是模型真改写、还是合法的术语替换被误判，
字幕正文（校验失败详情里内嵌的「原…改成了…」）可在 **TRACE 级别、且仅当
`vidsage.subtitle.cleanup.log-rejected-text=true`** 时落盘。

- **为什么可以**：该开关只写在**不进仓库**的 `application-local.yml`，生产配置里根本没有这个键——
  这是结构性保证，不是「记得别开」；且叠加 TRACE 级别闸门（生产基线是 `com.vidsage: DEBUG`，
  TRACE 不输出），开关被误开也过不了第二道闸门。
- **边界**：只为上述取证目的，**不得**推广成「用户内容可以进日志」。字幕正文的常规日志（含 DEBUG）
  仍受本节红线约束——不含 detail 的那条 DEBUG 才是生产环境唯一的读数来源。

## 五、反模式清单

| 不要做 | 依据 |
|---|---|
| `System.out.println` / `e.printStackTrace()` | 脱离日志体系，无级别无时间戳 |
| 字符串拼接构造日志消息 | 「写法」节 |
| 把 record / 配置对象整体打进日志 | 脱敏红线 |
| 打完整外部 URL 或转写文本 | 脱敏红线 |
| 异常先 log 再原样上抛 | 双重记录 |
| 引第三方日志实现依赖 | 「选型」节 |
| 外部锚点（taskId）打 DEBUG | 「级别分层」节 |
