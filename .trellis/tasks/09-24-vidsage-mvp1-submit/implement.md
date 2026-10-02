# MVP-1 — 实施计划

> **⚠️ 进度快照（2026-09-30 傍晚，用户电量不足主动中断）——恢复会话先读本节**
>
> - 分支 `feature/mvp1-task-pipeline`（自 dev 切出），任务 in_progress，**所有变更均未 commit**。
> - **步骤 1-5 已完成并通过质检**：批次 A（依赖/持久化三子包/状态机/TaskService/TaskController/
>   TaskPipelineService/ProgressMapper 上提）+ 质检修复 3 项（InWorkspace 接线、日志脱敏、
>   dirName 公式单源化）。
> - **步骤 1 验证全部通过**：MyBatis-Plus `3.5.17` 与 Boot 4.1.1 兼容（兜底分支未触发）、
>   真库连通、schema.sql 随启动自动建表；`mvn test` 全量 **374 全绿**（冒烟测试已标
>   `@ActiveProfiles("local")`，根因是测试类路径 application.yml 遮蔽主配置、缺
>   `spring.profiles.default`；application-local.yml 数据源已启用、`sql.init.mode: always` 已解开）。
> - **步骤 6-8（批次 B）中途停止**：子代理跑了约 31 分钟后被 TaskStop，**工作区可能有部分产物**。
>   恢复动作：先 `git status` 盘点批次 B 涉及文件（TaskProgressMessage / subscribe/ 包 / web/sse/TaskSseHub /
>   SSE 端点 / 管线广播 / clean-translate listener 重载 / artifacts 下载 / TaskNotFoundException→404），
>   核对哪些已落盘、哪些半成品，再整批重派 trellis-implement（范围以本文件步骤 6-8 + design.md 为准）。
> - **未开始**：步骤 9（真实运行证据）、步骤 10（收编旧端点）、步骤 11（spec 回写）。
> - 数据库凭据在 application-local.yml（git 不跟踪）；本会话经历：卡巴斯基 TLS 拦截导致子代理
>   派发三次失败，用户关闭解密后恢复——若再现先查这里。

## 前置条件

- [ ] MySQL 可用：本机服务已运行（3306，Windows 服务 MySQL80）；建库
  `CREATE DATABASE IF NOT EXISTS vidsage DEFAULT CHARACTER SET utf8mb4`
- [ ] 环境变量 `MYSQL_USERNAME` / `MYSQL_PASSWORD` 就绪（或临时在 application-local.yml 给本地默认值，
  密码不入库）

## 步骤（顺序执行，每步可独立验证）

1. **依赖与连通性**：pom 加 `mybatis-plus-spring-boot4-starter:3.5.15` + `mysql-connector-j`；
   application-local.yml 配数据源；`schema.sql` 建 task 表；上下文加载冒烟测试通过。
   ⚠️ **本步骤是最大风险点**（Boot 4.1.1 vs starter 兼容性）——失败即走 design.md 兜底分支并回写 design。
2. **持久化与领域层**：`persistence/` 三子包（`entity/` / `mapper/` / `repository/`，2026-09-30
   项目所有者定调的布局）+ `domain/task/VideoTask` 状态机 + `model/task/` 契约。
   单测：状态机非法迁移拒绝、Repository 转换。
3. **TaskService**：提交（URL / 上传两条路线，上传流式落盘算 sha256）+ 查重 409 + 查询。
4. **TaskController**：`POST /api/tasks`、`POST /api/tasks/upload`、`GET /api/tasks/{taskNo}`。
   Swagger 手工验证 202 / 400 / 409。
5. **异步管线（先不接 SSE）**：虚拟线程执行器 + `vidsage.task.max-concurrent` 信号量 + 阶段编排 +
   状态落库。curl 提交一条短 URL，观察 DB 状态走到 SUCCEEDED。
6. **SSE 与订阅**：`TaskProgressMessage` 定锤 + `subscribe/` 顶层包（`TaskSubscriptionRegistry` 中立注册表 +
   `Subscription` 退订句柄）+ `TaskService.snapshot`（DB 快照）+ `web/sse/TaskSseHub`
   （emitter 持有 / 心跳 / 清理）+ `GET /api/tasks/{taskNo}/events`。
   `curl -N` 验证；多标签页同时看同一任务的并发推送也要验。
7. **clean / translate 接入 ProgressListener**：两个服务加带 listener 重载（批次级事件），
   `SubtitleService.remap` 上提到 `utils/progress/ProgressMapper`，管线区间映射接好。
8. **产物端点**：`GET /api/tasks/{taskNo}/artifacts/{fileName}`，路径穿越防护单测必写。
9. **真实运行证据（旧端点仍在，新旧并存验证）**：一条 B 站 URL 全流程 + 一条本地文件上传全流程
   （各含一次重复提交看 SKIPPED），留档 `research/acceptance-evidence.md`；对照 PRD 验收标准逐条勾。
   ⚠️ 并存期避免对同一输入同时走新旧两端点（工作区并发写）。
10. **收编（新管线跑通并留证之后执行）**：删除三个同步端点（`/api/media/audio`、
    `/api/asr/transcriptions`、`/api/subtitles*`）及失去引用的孤儿 DTO；全量 `mvn test` +
    Swagger 面貌检查（应只剩 TaskController 与 MediaController 的 /tools）。
11. **Phase 3.3 spec 更新**：重建 `database-guidelines.md`（落库规范首次落地，含 persistence 三子包
    布局定调）；更新 `directory-structure.md` 的「当前只建 model/」表述与「持久化三层」小节，
    并登记 `subscribe/`、`web/sse/` 两个新包。

## 验证命令

```bash
cd backend && mvn test                      # 单测全量
mvn spring-boot:run                         # 起服务后 Swagger: http://localhost:8080/swagger-ui.html
curl -N "http://localhost:8080/api/tasks/<taskNo>/events"   # 观察 SSE 流
```

## 风险与回滚点

| 风险 | 步骤 | 应对 |
|---|---|---|
| MyBatis-Plus starter 与 Boot 4.1.1 不兼容 | 1 | design.md 兜底分支（原生 mybatis 装配）；若走兜底必须回写 design.md + spec |
| 删同步端点破坏既有调试习惯 | 10 | 新管线跑通留证后才删；独立提交，git revert 单提交即恢复 |
| 上传大文件内存 / 超时 | 3 | 流式写盘（不 byte[] 进内存）；multipart 上限 2GB 配置化 |
| ASR / LLM 阶段无超时控制，长任务挂死 RUNNING | 5 | MVP 接受（外部命令已有 300s 超时）；管线级看门狗记 follow-up 不进本任务 |
| 工作区并发写（同输入重复提交） | 3 | 409 查重在提交口挡住；signals 信号量兜底资源占用 |

## task.py start 前检查

- [ ] prd.md / design.md / implement.md 三件齐且用户已明确批准本规划
- [ ] implement.jsonl / check.jsonl 均有真实条目（无 `_example` 占位）
- [ ] 步骤 1 的依赖兼容性结论已回写 design.md（无论成败）
