# 编写中文 README 并配页面截图

## Goal

给 vidSage 仓库加一份中文根 README（当前没有），带真实页面截图，推送到 GitHub，让人打开仓库首页就能看懂这是什么、能做什么、怎么跑起来。

## Background / 已核实的事实

- 仓库 `https://github.com/HQLiLi/vidSage`，**保持 private**；GitHub 默认分支是 `dev`（不是 master）。
- 本地与 `origin/dev`、`origin/master` 根目录**都没有 README.md**；只有 `frontend/README.md`（前端包说明）和 `prototype/README.md`（原型沙盒，已 gitignore）。
- 项目构成：
  - `backend/`：Spring Boot 4.1.1 / Java 21，MyBatis-Plus + MySQL（`vidsage` 库，`schema.sql` 启动建表），Spring AI（DeepSeek / 小米 Mimo），DashScope 云端 ASR，SpringDoc OpenAPI，SSE 推送任务进度。
  - `frontend/`：Vue 3.5 + TypeScript + Vite 8 + Pinia + Tailwind CSS v4 + SCSS；上传页 `/`、工作区 `/workspace/:taskNo`、404 页。dev 由 Vite 把 `/api` 反代到 `localhost:8080`。
  - 外部工具：`yt-dlp`（仓库自带 `tools/yt-dlp.exe`）/ `ffmpeg` / `ffprobe`。
  - `asr-service/`、`prototype/` 被 gitignore，不随仓库发布。
- 产品能力（代码与档案核实）：本地文件 / 视频链接提交 → ASR 转写 → LLM 清洗字幕 → 多语种翻译；工作区视频与字幕联动、字幕搜索、关键词、主题摘要、产物下载；SSE 实时进度；支持重新清洗 / 重新翻译。
- 截图可行性：MySQL 已在 3306 运行；库里有任务 `91437acbc5964e9cb7757c0bfdf4e885`，其工作区 `backend/data/works/BV1J3nE6gEQZ_1` 含视频、原文 / 清洗 / 日语译文三类 READY 产物；后端 8080、前端 5173 当前均未启动。
- 敏感信息：`application-local.yml`（含真实 API key / MySQL 密码）**未被 git 跟踪**；git 全量历史里未扫到 `sk-` 形式的密钥。README 的快速开始只能引用环境变量，不能贴本机配置。
- 推送：本机直连 GitHub 会失败，push 需带 `https_proxy=http://127.0.0.1:7897`。
- 分支规则：README + 截图不属于「纯文档例外」，须从 `dev` 开特性分支；改完即停，提交 / 合并 / 推送逐门等批准。

## Requirements

- R1 根目录新增中文 `README.md`，**作品展示向**（约 80–120 行）：项目简介、功能亮点、界面截图、技术栈、架构概述、快速开始、目录结构、已知限制。
- R2 README 引用 `docs/images/` 下 **3 张现场截图**（统一 1440×900）：
  - 上传页（本地文件 / 链接双模式）
  - 工作区（视频 + 双语字幕）
  - 工作区左列（关键词 + 主题）或产物菜单
- R3 截图必须来自当前版本 UI 的真实运行（不是原型图、不是归档里的旧设计评审图）。
- R4 快速开始与仓库实际一致：MySQL 建库、`application-local.yml` 需自建（仓库不含）、环境变量名以 `application.yml` 为准、后端 8080 / 前端 5173。
- R5 不出现任何真实密钥、密码、本机绝对路径、个人账号信息。
- R6 不改动任何产品代码；只新增 README 与截图文件。

## Out of Scope

- 把仓库改为 public（用户已决定保持 private）。
- 新增 LICENSE、英文版 README、演示视频 / GIF、CI 徽章配置。
- 修改 `frontend/README.md` 或 `.trellis` 规范。

## Acceptance Criteria

- [ ] AC1 `README.md` 位于仓库根目录，中文，内容覆盖 R1 的八个部分，通读无占位符（TBD / TODO）。
- [ ] AC2 `docs/images/` 下至少 3 张 PNG，README 中每张都被引用，本地打开 Markdown 图片路径全部可解析。
- [ ] AC3 截图内容与说明一致：上传页有文件 / 链接双模式；工作区截图同时可见视频、字幕与译文；尺寸统一、无浏览器调试浮层。
- [ ] AC4 快速开始的命令可直接照抄：后端 `mvn spring-boot:run`（Java 21）、前端 `npm run dev`；明确写出 `application-local.yml` 不在仓库、需按本机 MySQL 自建；环境变量名与 `application.yml` 逐个对得上。
- [ ] AC5 `README.md` / 截图中不含 `sk-` 密钥、数据库密码、`F:/...` / `C:/...` 本机路径（grep 验证）。
- [ ] AC6 改动提交在 `feature/readme-chinese-screenshots` 分支，经用户批准后合并回 `dev` 并推送；`origin/dev` 上能看到 README 与图片。
- [ ] AC7 收尾时后端 / 前端进程已关闭，工作区无临时截图或日志残留。

## Decisions

- D1 定位：作品展示向（用户确认）。
- D2 截图：现场启动前后端重拍（用户确认）；后端起不来则退回复用归档 `workspace-video.png` + 现场补拍上传页，并在交付说明中标注。
- D3 仓库保持 private（用户确认）。
- D4 只写中文 README，不做英文版（用户原始要求）。
