# MVP-1 — 技术设计

## 新增构件与包落点（依 `directory-structure.md`）

| 构件 | 位置 | 说明 |
|---|---|---|
| 任务状态机 | `domain/task/VideoTask`（**新顶层包**） | directory-structure.md 预告的 `domain/` 升级触发条件正是「任务生命周期状态机」：拒绝非法状态迁移（如 SUCCEEDED → RUNNING）。与 `model/` 并存、不合并 |
| 表映射 | `persistence/entity/VideoTaskEntity`（**新顶层包 `persistence/`**） | `@TableName`，普通类 + Lombok `@Getter/@Setter`，仅 `persistence/` 内部可见 |
| 数据访问 | `persistence/mapper/VideoTaskMapper` | `interface extends BaseMapper<VideoTaskEntity>`，仅 `persistence/repository/` 可见，`service/` 禁止注入 |
| 对上契约 | `persistence/repository/VideoTaskRepository` | `@Repository` **具体类**（不抽接口），包装 Mapper，做实体 ↔ record 契约转换；`service/` 唯一持久化入口 |
| 对外契约 | `model/task/TaskView`、`TaskStatus`、`TaskInputType` | record / enum + `@Schema`，直接充当 HTTP 响应（判据 1：JSON 形状即可对外） |
| SSE 事件 | `model/progress/TaskProgressMessage` | `record TaskProgressMessage(String taskNo, TaskStatus taskStatus, StageEvent stage, TranslationStatus translationStatus)` —— **SSE payload 定锤形态**（translationStatus 可空，2026-10-02 两段式新增，见「翻译事件的接线」）；`StageEvent` 本体保持最小不动 |
| 提交/查询编排 | `service/TaskService` | 校验、查重、落库、上传落盘——提交与查询的唯一入口；快照组装（`snapshot(taskNo)`，DB 读）也在此 |
| 异步管线 | `service/TaskPipelineService` | 虚拟线程执行、按阶段调用既有服务、进度回调 → 落库 + 调注册表广播 |
| 订阅机制 | `subscribe/TaskSubscriptionRegistry` + `Subscription`（**新顶层包**，2026-09-30 项目所有者定调） | 中立发布-订阅注册表：`taskNo → List<Consumer<TaskProgressMessage>>`，`subscribe / broadcast / complete`；无 HTTP 类型，service 与 web 都可依赖 |
| SSE 连接管理 | `web/sse/TaskSseHub`（**web/ 第三个角色子包**） | 持有 `SseEmitter`：心跳、断连 / 超时清理、终态 `complete()`；controller 订阅时把「写 emitter」的 Consumer 挂进注册表 |
| HTTP 入口 | `web/controller/TaskController` + `web/dto/SubmitUrlRequest` 等 | 「接收 → 校验 → 委托 → 返回」，不写业务逻辑 |

> **持久化包布局（2026-09-30 项目所有者定调）**：`persistence/` 下按 `entity/`、`mapper/`、
> `repository/` 三个子包组织（类不平铺在 `persistence/` 根）。当前单表不再分领域二级包，
> 表多时再议。directory-structure.md 的「持久化三层」小节未定义子包布局，Phase 3.3 需把本定调回写进去。

`utils/progress/ProgressMapper`（新）：`SubtitleService.remap` 的静态逻辑上提——本任务出现第二个
使用者（管线对 clean 的区间映射；2026-10-02 两段式后 translate 事件自成 0..100 段、不再经过映射），
符合「同一判据只有一份」的搬迁判据（纯函数、无状态）。

## 数据流

**提交**（两条路线收敛到同一服务层入口）：

- `POST /api/tasks`，JSON `{"url": "..."}` → `MediaInput.RemoteUrl`
- `POST /api/tasks/upload`，multipart `file` → 流式落盘（写盘同时算 sha256）→ 存入工作区 →
  `MediaInput.InWorkspace`（2026-09-30 批次 A 裁决，见权衡记录）

调用链：`TaskController`（接收 → 校验 → 委托）→ `TaskService`：算 `workspace_key`
（URL / 本地路径复用 `WorkspaceService.normalize + shortHash` 包内 static，只算 key 不触发建目录；
上传路线直接用内容 sha256）→ 查重（同 key 且 PENDING / RUNNING → 409 返回既有任务）→
`VideoTaskRepository.insert`（PENDING）→ `TaskPipelineService.execute(taskNo)` 异步启动 →
202 + `TaskView`。HTTP 响应毫秒级返回，不等管线。

**订阅**（前端拿到 taskNo 后立即发起，与执行并行）：

`TaskController.subscribe(taskNo)` → 建 `SseEmitter` → `TaskSseHub.register`（挂断连 / 超时清理钩子）
→ `TaskService.snapshot(taskNo)` 从 DB 组装快照 → `TaskSubscriptionRegistry.subscribe(taskNo,
「写 emitter」的 Consumer)` 挂进注册表 → controller 先推快照，长连接挂起等增量。
刷新页面 = 重连 = 新 snapshot，进度无感恢复。

**执行**（`TaskPipelineService`，按阶段顺序；每阶段开始前 DB 更新 `current_stage` 并推 SSE；
**2026-10-02 两段式裁定：翻译不再在自动管线内**，管线到清洗为止）：

管线为每次执行组一个复合 `ProgressListener`：① 更新 DB `current_stage`；② 组
`TaskProgressMessage(taskNo, taskStatus, stage, translationStatus)` 广播给全部订阅者。阶段与任务级区间：

1. `acquire` / `extract-audio` / `transcribe` / `write-subtitles` —— 一次
   `SubtitleService.prepare(input, remap(listener, 0..70))` 全包（其内部已把自身 0..100 映射进 0..70）
2. `clean` —— `SubtitleCleanupService.clean(input, reference, listener)`（带 listener 重载），
   批级进度 remap 进 70..100

任一阶段抛异常 → 状态机迁移 RUNNING → FAILED（落 `error_message`，广播终态 TaskProgressMessage 后停止，
不再继续后续阶段）；全部完成 → SUCCEEDED（落 `finished_at`）→ 广播终态 → `TaskSseHub` 完成
该任务全部 emitter。产物本就按阶段落盘在工作区，失败后重提交同一输入自动断点复用（SKIPPED）。

**翻译按需触发（2026-10-02 两段式，`TaskPipelineService.translateTask(taskNo)`）**：

主任务 SUCCEEDED 后，用户显式 `POST /api/tasks/{taskNo}/translate` 触发翻译——不看翻译的人不花 LLM 钱。
触发判定按序：

1. 任务不存在 → `TaskNotFoundException`（404）；
2. 主状态非 SUCCEEDED → `IllegalArgumentException`（400，「字幕尚未就绪」，错在调用方触发太早）；
3. 翻译进行中（translation_status = RUNNING）→ 直接返回当前视图，不重入；
4. 翻译检查点（`segments-<route>-translate-<target>.json`）已在（上一次成功的翻译或两段式改造前的
   存量产物）→ **幂等收敛**：`translationSucceed` 落库后返回，绝不发 LLM（判据复用
   `SubtitleTranslationService` 的「检查点非空 = 已完成」断点哲学，由其新增 `hasTranslationArtifact`
   暴露检查点判定——route / 目标语种 / 文件名规则单源在翻译服务内）；
5. 正常路径：同步段 `translationStart` 落库（RUNNING，202 响应视图因此反映触发后的状态）→ 异步
   （虚拟线程 + 复用主管线的 `Semaphore` 限流）执行 `SubtitleTranslationService.translate(input,
   listener)`，事件**自成 0..100 段**直通广播（stage=translate，不做区间映射——主任务的 0..100 已跑完）；
   终态 `translationSucceed / translationFail` 落库后按 `broadcastTerminal` 模式广播 + `complete` 收口，
   终态 TaskProgressMessage 的 taskStatus 用主状态 SUCCEEDED、stage 用 translate。

**翻译子状态**：task 表新增 `translation_status ENUM('RUNNING','SUCCEEDED','FAILED') NULL`
（NULL = 从未触发），与主状态机独立、仅当主状态 SUCCEEDED 后才允许流转（`domain/task/VideoTask` 的
`translationStart / translationSucceed / translationFail`，非法迁移抛 IllegalStateException）。
翻译失败信息复用 `error_message` 列（「翻译失败：」前缀），主状态保持 SUCCEEDED 不变。

SSE 事件样例（`event: progress`，data 为 `TaskProgressMessage` JSON；主管线事件 translationStatus 为
null，翻译事件携带翻译子状态）：

```json
{"taskNo":"a1b2c3d4e5f6","taskStatus":"RUNNING","stage":{"stage":"transcribe","progress":70,"message":"开始转写","state":"RUNNING"},"translationStatus":null}
```

**查询 / 产物**：`GET /api/tasks/{taskNo}` 返回 `TaskView`（含状态、当前阶段、错误信息、产物清单——
扫描工作区内已知后缀产物：`*-translate-*.srt`、`*-clean.srt`、原始 `*.srt` 等，名字 + 字节数）；
`GET /api/tasks/{taskNo}/artifacts/{fileName}` 流式下载，**必须在 workspace 目录内 resolve 并拒绝逃逸**
（防路径穿越）。

## SSE 契约（本次定锤 `StageEvent` 过渡态）

- `GET /api/tasks/{taskNo}/events`，`text/event-stream`（Spring MVC `SseEmitter`，非 WebFlux）。
- 事件名 `progress`，data 为 `TaskProgressMessage` JSON。
- 连接建立即推一条 **snapshot**（当前任务状态 + 最近阶段事件，从 DB 组装），此后增量推送；
  任务到终态（SUCCEEDED / FAILED）推完最后一条即完成 emitter。前端刷新页面重连后拿 snapshot
  即恢复进度，无需 Last-Event-ID 回放（事件不落库，DB 快照即权威——本地单实例足够）。
- 每 30s 推一条注释行心跳，防代理 / 客户端断连。
- **翻译事件的接线（2026-10-02 两段式补）**：翻译批级事件仍走同一任务事件流（stage=translate，
  自成 0..100 段），且 `TaskProgressMessage` 增加可空 `translationStatus` 字段——翻译事件的
  taskStatus 恒为主状态 SUCCEEDED，若不携带翻译子状态，`TaskSseHub` 与订阅端点的终态判定
  （taskStatus 终态即 complete 收口）会在第一条翻译进度事件后把连接收掉。终态判定更新为
  「主状态终态 **且** translationStatus ≠ RUNNING」：连接保持到翻译终态，快照（重连恢复）同样按
  翻译子状态粗粒度还原（RUNNING → stage=translate/0/「翻译进行中」；SUCCEEDED → 100/「翻译完成」；
  FAILED → 0 + 失败信息），精确进度仍由增量事件携带。
- **分层接线**：订阅机制独立为顶层包 `subscribe/`——中立、无 HTTP 类型，service 与 web 都可依赖
  （与 `utils/` 同类「机制包」先例，判据测试可过：写得出它不许装什么——业务编排与 HTTP 类型均禁）。
  事件流：管线 `TaskPipelineService` → `TaskSubscriptionRegistry.broadcast` → controller 订阅时注册的
  「写 emitter」Consumer → `web/sse/TaskSseHub`（心跳 + 清理）。快照从 DB 组装，走
  `TaskService.snapshot(taskNo)`。`SseEmitter`（HTTP 类型）只存在于 `web/sse/`。
  `subscribe/` 与 `web/sse/`、`domain/`、`persistence/` 一并在 Phase 3.3 回写 directory-structure.md。

## 任务表（MySQL 8，库名 `vidsage`）

`schema.sql`（Spring SQL init，`CREATE TABLE IF NOT EXISTS`，不引 Flyway——单表用不上迁移框架）：

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT 自增主键 | 内部 |
| task_no | varchar(32) 唯一索引 | 对外暴露的短 UUID（自增 ID 不外泄） |
| input_type | ENUM('URL','UPLOAD') | |
| source_uri | varchar(2048) | URL 原文或上传文件原始名（日志不落原值，见既有约定） |
| workspace_key | char(12) 索引 | `WorkspaceService` 短哈希，查重用 |
| workspace_dir | varchar(512) | 工作区相对路径 |
| status | ENUM('PENDING','RUNNING','SUCCEEDED','FAILED') | 状态机权威存储 |
| current_stage | varchar(64) | 最近阶段名（`StageEvent.stage` 同词表） |
| translation_status | ENUM('RUNNING','SUCCEEDED','FAILED') NULL | 翻译子状态（2026-10-02 两段式新增）：NULL = 从未触发；仅主状态 SUCCEEDED 后按需流转 |
| error_message | varchar(2048) | 终态错误（中文，面向用户；主任务失败或翻译子状态失败——后者带「翻译失败：」前缀） |
| created_at / updated_at / started_at / finished_at | datetime | |

## 依赖与配置

- `com.baomidou:mybatis-plus-spring-boot4-starter:3.5.17`（Boot 4 + Jackson 3 支持自 3.5.15 起；
  3.5.17 为该线最新补丁 2026-07-08，Maven Central 核实。**Boot 4.1.1 兼容性在实现步骤 1 验证**：
  上下文冒烟 + 单条 insert。兜底：若 4.1 下自动装配失败，降级 `com.baomidou:mybatis-plus` 核心 +
  原生 `mybatis-spring` 手工装配，届时回写本文件与 spec）
- `com.mysql:mysql-connector-j`（Boot 管版本）
- `application-local.yml`：`spring.datasource.url=jdbc:mysql://localhost:3306/vidsage`，
  账号走环境变量 `MYSQL_USERNAME` / `MYSQL_PASSWORD`（默认 root），不把密码写进仓库
- `spring.servlet.multipart.max-file-size` / `max-request-size`：默认 2GB（配置化）
- `vidsage.task.max-concurrent`（默认 2）：`Semaphore` 限制同时执行的管线数，超出保持 PENDING 排队
- 上传的断点复用：上传流式落盘时**按内容算 sha256**，工作区目录 =
  `sanitize(原始文件名)-<sha256 前 12 位>`——同一文件重复上传命中同一目录，比「路径+时间戳」指纹更稳
  （`WorkspaceService` 需新增接受显式 key 的入口，URL / 本地路径两条旧路线不动）

## 权衡记录

- **MySQL 而非 H2 / SQLite**：开发机已有运行中的 MySQL 8 服务（零安装成本）；JD 关键词；
  代价是交付镜像多一个 MySQL 容器（epic 部署形态本就计划 Docker Compose）。打包成单应用时再评 SQLite
  （项目所有者 2026-09-30 决策）。
- **SseEmitter 而非 WebSocket / WebFlux**：全项目 servlet 栈 + 虚拟线程；进度推送是单向流，
  WebSocket 的双向能力用不上。`web/sse/` 作 web/ 的角色子包而非顶层包：当前 SSE 相关只有
  TaskSseHub 一个构件 + controller 里一个端点，顶置单类顶层包过不了「能否写出它不许装什么」判据；
  **升级触发条件**——出现多传输协议并存（SSE + WebSocket）或 sse/ 内构件 ≥3 个时，再议升独立顶层包。
- **事件不落库**：只落任务级 `current_stage` / `status`。回放完整历史事件属伪需求，
  「DB 快照 + 重连 snapshot」已覆盖刷新页面恢复进度。
- **重复提交拒绝（409）而非共享任务**：两个并发任务写同一内容寻址工作区会竞态
  （ffmpeg / DashScope 双跑），拒绝是唯一安全解；断点复用已让「重跑」很便宜。
- **上传路线的工作区归属（批次 A 勘察裁决，2026-09-30）**：下游阶段服务一律按 `MediaInput` 重算
  工作区目录，上传文件若以 `LocalPath` 下传，会被按「绝对路径+大小+mtime」算出**另一个**目录——
  任务 `workspace_dir` 与产物落点分家，且 mtime 随重传漂移、断点复用失效。裁决：`MediaInput`
  新增 `InWorkspace` 变体（携带已解析的工作区目录、文件路径与内容 key），上传落盘后直通下游，
  `WorkspaceService.workspaceFor` 对其直接命中零重算；重传同内容 → 同目录 → 产物断点复用成立。
- **旧同步端点两阶段收编（2026-09-30 项目所有者定调）**：先保留、新旧并存，待新管线端到端跑通
  并留下真实运行证据后再删（同一任务内的收尾步）。并存安全：旧端点无状态、与任务表零耦合；
  唯一注意点——并存期**避免对同一输入同时走新旧入口**（同一内容寻址工作区会被并发写，
  409 查重只覆盖新管线）。删除后 Swagger 单步调试能力由管线阶段日志 + 服务层测试顶替。
- **翻译未就绪映射 400 而非 409（2026-10-02 两段式）**：主任务未 SUCCEEDED 时触发翻译，语义上是
  「调用方触发太早」，按 error-handling.md「错在调用方 → IAE → 400 + WARN」走既有映射，**不新建
  自定义异常类型**（规范明示「状态码需要跨层区分时才造类型」——TaskConflictException 的 409 携带
  既有任务视图给前端可操作出路，此处无既有任务可附，消息文本「字幕尚未就绪：…请先等主管线完成」
  已自解释；为一次映射造第三类异常属过度设计）。
- **translationStart 在同步段落库（2026-10-02 两段式）**：触发端点 202 返回前先把翻译置 RUNNING，
  响应视图因此权威反映「已触发」；信号量排队被打断时以 translationFail 收口（可重新触发，状态诚实）。
  若改为异步段才置 RUNNING，202 响应可能显示「从未触发」，前端无法区分「触发成功」与「触发丢失」。
- **翻译失败信息复用 error_message 列（2026-10-02 两段式）**：不加 translation_error_message 列——
  同一任务同一时刻只有一种「进行中 / 最近终态失败」，加前缀「翻译失败：」即可区分；TaskView /
  SSE 失败快照的消息出口保持单一。
- **translation_status 的存量迁移（2026-10-02 两段式）**：`CREATE TABLE IF NOT EXISTS` 不会为旧表
  加列且 MySQL 8 无 `ADD COLUMN IF NOT EXISTS`——已有旧库需手动 `DROP TABLE task`（本地测试数据，
  由 sql.init 重建）或 `ALTER TABLE task ADD COLUMN translation_status ...`；schema.sql 头部注释已注明。
  两段式改造前的存量任务若已有翻译产物（检查点在），首次触发翻译走幂等收敛，与新建任务行为一致。
