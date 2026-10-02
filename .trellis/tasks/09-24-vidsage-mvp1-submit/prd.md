# MVP-1 任务提交与异步管线 — PRD

> Epic 路线图见 `../09-24-vidsage/prd.md`，技术方案见 `../09-24-vidsage/design.md`。
> 实现参考（本地开源项目源码索引）见 `../09-24-vidsage/research/oss-references.md` 第三节 MVP1 小节，
> 已挂入本任务 `implement.jsonl` / `check.jsonl`（原「复用 0009 经验」指主项目的 SSE 实践，不在本仓库）。

## 目标与用户价值

把「每个步骤单独调用的同步临时端点」收编为一条异步管线：用户提交一次输入（B站 / YouTube 链接，
或上传本地视频 / 音频文件），系统自动跑完 下载 → 抽音频 → ASR → 清洗 → 翻译 全流程。任务落库、
状态可查询，并通过 SSE 实时推送当前进行到哪个阶段——调用方不再面对「发一个请求干等到超时」的黑盒。

本任务是将来前端「上传视频 + 实时步骤进度展示」页面的唯一后端依赖；前端另立子任务，不在本任务范围。

## 范围

1. **总提交接口**：URL 输入（JSON）与文件上传（multipart）两条提交路线，统一返回任务视图（202）。
2. **任务实体落库**（MyBatis-Plus + MySQL）与任务状态机 PENDING → RUNNING → SUCCEEDED / FAILED。
3. **异步执行管线**：提交后异步编排既有服务（媒体获取 / 抽音频 / 转写 / 清洗 / 翻译），不再做占位阶段
   （2026-09-30 项目所有者定调，原 PRD 的「空实现 / 占位阶段」表述作废）。
4. **SSE 进度推送端点**：按任务订阅，推送阶段级事件，定锤 `model/progress/StageEvent` 的过渡形态。
5. **任务状态查询端点** + 完成后产物（双语 SRT 等）下载端点。
6. **清洗 / 翻译服务接入 ProgressListener**（比照 `SubtitleService.prepare` 既有做法），翻译 / 清洗
   支持批次级进度。
7. **旧同步端点收编（两阶段，2026-09-30 项目所有者定调）**：先保留 `POST /api/media/audio`、
   `POST /api/asr/transcriptions`、`POST /api/subtitles`（含 /clean、/translate）不动；**新的异步管线
   端到端跑通并留下真实运行证据之后**，再在本任务收尾删除（其 Javadoc 自称「临时面」并预告
   「管线落地后应改为触发入口或直接删除」，见 `SubtitleController.java:29-35`）；
   `GET /api/media/tools` 工具诊断端点长期保留。

## 不做

- 前端页面：实时进度展示另立前端子任务，待本任务落地后创建。
- 结构化总结：MVP-4 落地后作为新阶段挂入管线（阶段化事件设计已天然支持）。
- 任务删除 / 重试端点：失败后重新提交同一输入即自动断点复用（工作区按内容寻址），无需专门 API。
- 用户体系 / 权限、多用户隔离。
- 翻译目标语种进请求参数：沿用配置 `vidsage.subtitle.translate.target-language`（现状不变）。

## 验收标准

- [ ] 提交 URL 或上传本地视频 / 音频文件后，HTTP 秒级返回 202 与任务 ID；任务持久化（MySQL 可查）、
      状态可查询。请求不再等全流程跑完，长视频不再顶客户端超时。
- [ ] SSE 端点按任务实时推送阶段事件（开始 / 成功 / 跳过 / 失败）；对同一输入二次提交能看到
      SKIPPED（断点复用）事件。
- [ ] 一条 B 站 URL 提交后无人值守跑完全流程（下载 → 抽音频 → ASR → 清洗 → 翻译），完成后能通过
      产物端点取到双语 SRT。
- [ ] 非法输入（同时给 url 与文件、都不给、不支持的链接 / 格式、上传空文件、重复提交运行中任务）
      返回明确 4xx 错误。**2026-10-02 项目所有者调整**：「不支持的链接 / 格式」暂以管线 FAILED +
      SSE 失败事件呈现（提交口白名单等于复制 yt-dlp 站点知识，已在 TaskController.submitUrl 注释
      记录当前支持范围）；提交口预检还是解析期失败，待前端落地后按真实交互再定，届时回改本条。
- [ ] 阶段失败时任务状态为 FAILED、错误信息可查询、SSE 收到 FAILED 事件。
- [ ] 至少一条真实运行证据（learning-records / acceptance-evidence）。

## 已确认事实（2026-09-30 勘察）

- 三个同步控制器的 Javadoc 均自称「临时面」并预告本任务（`SubtitleController.java:29-35`、
  `MediaController.java:29-34`、`AsrController.java:16-18`）。
- `model/progress/ProgressListener` + `StageEvent` 已存在，注释自述「真实形状由 MVP-1 的 SSE payload
  定锤」；目前仅 `AudioPreparationService`、`SubtitleService` 接入。
- `backend/pom.xml` 无任何持久化依赖，落库从零开始。数据库选型已定 **MySQL**
  （本机 8.0.37 以 Windows 服务运行于 3306，2026-09-30 项目所有者确认；打包形态若落地再评估 SQLite）。
- MyBatis-Plus 3.5.15 起提供 `mybatis-plus-spring-boot4-starter`，官方支持 Boot 4 + Jackson 3
  （对 Boot 4.1.1 的兼容性列为实现第一步验证项，兜底方案见 design.md）。
- 前端为 Vue3 + Vite 空脚手架（仅默认 `App.vue` / `counter.ts`）；「用户上传」需新增 multipart 入口
  （现有端点只收 URL / 服务端本地路径，不收文件流）。
- 工作区按输入内容寻址（`WorkspaceService`），产物天然支持断点复用；
  `StageEvent.StageState.SKIPPED` 语义已存在。
- 虚拟线程已全局开启（`application.yml` `spring.threads.virtual.enabled: true`），
  同步阻塞等待不占平台线程（`AsrController.java:17` 注释）。

## 关键决策（2026-09-30，grill 确认）

1. **MVP-1 纯后端先行**，前端实时进度页面另立子任务，待本任务落地后创建。
2. **数据库用 MySQL 8 + MyBatis-Plus**；项目所有者原话：「后续如果涉及打包成应用的话，再考虑用 sqlite」。
3. **管线终点到「翻译」为止**，总结（MVP-4）后挂。
4. **旧同步端点删除**，保留工具诊断端点。
