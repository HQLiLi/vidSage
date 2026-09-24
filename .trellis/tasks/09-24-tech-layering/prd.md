# 后端包结构改为技术分层 — PRD

> 独立任务，不挂在 epic `09-24-vidsage` 下 —— 这是架构约定修正，不是 MVP 交付物。
> 本任务**取代**上一会话「ASR 包结构整理（方案 A）」确立的顶层分包方式；`asr/` 包内部的分层结论（契约中性化、Provider 四件套）继续有效。

## 背景与问题

`directory-structure.md` 规定 package-by-feature：每个业务能力一个顶层包（`com.vidsage.asr`），包内自带自己的 `model/`、`provider/`、`web/`。用户认为**同类构件集中管理更舒服**——所有 Controller 在一处、所有 Service 在一处，查找和横向对比成本更低。

当前只有 `asr/` 一个功能包，所以代价还没显现；MVP-3 要加 `llm`、后续还有 `summarize`/`index`，届时 `AsrController`、`LLMController`、`SummarizeController` 会分散在各自功能包的 `web/` 子包里，逐个点开才能看清全部 HTTP 入口。用户在只有 1 个功能包时提出，时机正确。

## 目标

把后端包结构约定从 **package-by-feature** 改为 **扁平技术分层**：按构件类型做顶层分包，同类集中在同一包内。

## 需求

### R1 顶层按技术分层

- `controller/` —— 全部 HTTP 入口集中于此，**这是用户提出本需求的核心动机**
- `service/` —— 全部应用服务集中于此
- `model/` —— 全部进程内数据契约（`record`）集中于此
- `provider/` —— 全部出站实现（外部 API / 模型服务）集中于此

### R2 `model/` 与 `provider/` 保留领域二级包

`controller/` 与 `service/` 保持平铺（类名本身已含领域语义：`AsrController`、`TranscriptionService`）；`model/` 与 `provider/` 下按领域分组（`model/asr/`、`provider/asr/`），避免 MVP-3 的 LLM 落地后这两处变成混杂袋。

### R3 保留领域二级包的同时，`provider/` 内的 Provider 四件套结构不变

`provider/asr/` 下仍是：接口留包根（`ASRProvider`）、实现与专用配置进 `provider/<实现短名>/`（`dashscope/`）。沿用 `provider-pattern.md` 既有约定，只换外层路径。

### R4 目录随代码出现，不预建空目录

**不得**创建当前无代码的目录。`config/`（跨领域装配类的未来落点）本次**不创建**，仅在规范文档中记为未来落点；磁盘上已存在的空 `config/` 目录删除。

理由：空目录不表达任何约束，只制造虚假的结构信号——本需求的起因之一，就是 `controller/`、`service/`、`config/` 三个空目录在磁盘上长期存在，让人误以为技术分层已经落地。

### R5 同步所有失效的规范路径引用

`provider-pattern.md`、`quality-guidelines.md`、`error-handling.md`、`index.md` 共 4 份文档中硬编码了 `asr/...` 路径作为「参照文件」。重构后这些路径全部指空 —— 子 agent 会照着不存在的路径去找参照实现，比不写路径更糟。必须逐处更新。

### R6 同步 MVP-3 规划中的结构假设

`09-24-vidsage-mvp3-translate`（in_progress）的 `implement.jsonl` 与 `handoff.md` 中写死了「新包 `com.vidsage.llm` 按功能分包」。若不改，下一个执行 MVP-3 的子 agent 会按旧约定建包，与重构后的代码结构冲突。

## 不做

- **不改任何类名**。含 `ASRProvider`（全大写缩写开头，规范中有专门提醒「跟随现有代码，不要顺手改正」）
- **不改契约语义**：字段、类型、sealed 层次、`record` 性质一律不动
- **不改配置键与配置文件**：`vidsage.asr.provider`、`vidsage.asr.dashscope.*`、`application.yml` 均不动。包名与配置键无关，`@ConditionalOnProperty` 的值是 `cloud`/`local` 不是包名
- **不改 REST 路径、请求/响应体、状态码**
- **不改 `pom.xml`**
- **不引入 `domain/` 或 `persistence/` 包**（判据见 `design.md`：现有类均无行为与不变量，放 `domain/` 是过度承诺；落库需求尚未动工）
- **不重构业务逻辑**：本次是纯搬包 + 引用更新，不顺手改代码
- **不改 `error-handling.md` 的约定内容**，只改其中的失效路径

## 约束

- 既有硬规则全部继续有效：契约实现方中立、契约一律 `record`、Provider 用 `@Component` + `@ConditionalOnProperty`、Controller 局部 `@ExceptionHandler`、SDK 类型不外泄到接口签名
- 测试包与主包**镜像对应**的规则保留（`controller/AsrControllerTest` 对 `controller/AsrController`）
- Java 21 / Maven，无 wrapper；`mvn test` 是唯一验证手段
- 后端是纯搬包，**不新增依赖、不新增类、不删类**

## 验收标准

- [ ] `cd backend && JAVA_HOME=/f/java_soft/java21 mvn test` 全绿，测试数量与重构前一致（基线实测 16 个：`VidSageApplicationTests` 1 + `DashScopeAsrProviderTest` 11 + `AsrControllerTest` 4，0 失败/0 错误/0 跳过）
- [ ] 全仓库不存在任何 `package com.vidsage.asr;` / `package com.vidsage.asr.*;` 声明
- [ ] `.trellis/spec/` 下不再存在指向旧路径的「参照」引用（`asr/model`、`asr/web`、`asr/provider`、`com.vidsage.asr`），`grep` 验证
- [ ] `git diff` 对已跟踪的 `.java` 文件只包含 `package` 与 `import` 行变更 —— 无字段、方法体、注解值改动（文件移动由 git 识别为 rename）
- [ ] `find backend/src -type d -empty` 无输出（无空目录残留）
- [ ] `config/` 未出现在 `backend/src/main/java/com/vidsage/` 下
- [ ] MVP-3 的 `implement.jsonl`、`handoff.md` 中新建包的落点描述与新约定一致
- [ ] `directory-structure.md` 读完后，能据此把一个新功能（如 `llm`）的所有类落到正确目录，无需再看别的文档

## 备注

- 上游讨论：本任务的命名判据（`model` vs `domain` vs `persistence`）与「目录随代码出现」规则需写进 `directory-structure.md`，使其成为可执行规则而非一句「按技术分层」
- 引用 `index.md` 中 `directory-structure.md` 的索引描述需同步改写（当前为「包结构、按功能分包、类与文件命名」）
