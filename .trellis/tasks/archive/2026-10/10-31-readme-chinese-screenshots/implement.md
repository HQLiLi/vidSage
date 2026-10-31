# 执行计划：中文 README + 页面截图

## 前置确认

- 用户已确认：作品展示向、现场重拍、仓库保持 private、只写中文。
- 开工前 `git status` 需干净（当前有 5 个未跟踪的 IDE 文件：`backend/.classpath` 等，不纳入提交）。

## 步骤

1. **开分支**
   ```bash
   git switch -c feature/readme-chinese-screenshots
   ```

2. **起后端**（Git Bash 需显式 JDK 21；测试跳过用 `-Dmaven.test.skip=true`）
   - 先确认 8080 未被占用。
   - `mvn -f backend/pom.xml spring-boot:run -Dmaven.test.skip=true`，后台跑。
   - 冒烟：`curl -s http://localhost:8080/api/tasks/91437acbc5964e9cb7757c0bfdf4e885` 返回任务 JSON 即为就绪。

3. **起前端**
   ```bash
   cd frontend && npm run dev -- --host 127.0.0.1
   ```
   冒烟：`http://127.0.0.1:5173/` 可打开。

4. **截 3 张图**（Playwright，视口 1440×900，禁用动画后截图）
   - `docs/images/upload.png`：上传页 `/`，展示本地文件 / 链接双模式。
   - `docs/images/workspace.png`：`/workspace/91437acbc5964e9cb7757c0bfdf4e885`，等就绪 → 选目标语种「日语」（已有 READY 译文，不应触发新 run）→ 截图，要求视频、原文、译文同时可见。
   - `docs/images/workspace-panels.png`：同页左列关键词 + 主题；关键词为空时通过 UI 添加 2–3 个词（如 VidSage / 字幕清洗 / 翻译），仅改本地库数据。
   - 若后端起不来：改用归档 `workspace-video.png`，上传页单独起前端补拍，并在交付说明里注明。

5. **写根 `README.md`**（作品展示向，约 100 行）
   - 结构：一句话介绍 → 功能亮点 → 截图 → 技术栈表 → 架构简述 → 快速开始 → 目录结构 → 已知限制。
   - 快速开始必须写明：MySQL 建 `vidsage` 库、自建 `application-local.yml`（仓库不含）、环境变量 `DEEPSEEK_API_KEY` / `MIMO_API_KEY` / `DASHSCOPE_API_KEY` / `YT_DLP_PATH` / `FFMPEG_PATH` / `FFPROBE_PATH`、后端 8080、前端 5173。
   - 不写死任何密钥、绝对路径、个人账号。

6. **验证**
   - 图片路径：`grep -o 'docs/images/[^)]*' README.md` 后逐个 `test -f`。
   - 密钥 / 路径扫描：`grep -nE 'sk-[A-Za-z0-9]{20,}|zwl61758157|F:/|C:/' README.md docs/images`。
   - 截图检查：确认无 devtools / 光标 / 报错浮层，尺寸 1440×900。
   - `git status` 只应出现 README + docs/images/。

7. **收尾**
   - 关后端 / 前端进程（`spring-boot:run` 的子 JVM 按 PID `taskkill`，TaskStop 杀不掉）。
   - **停下等审查**：汇报改了什么 / 怎么验证 / 哪些没验证，不提交、不合并、不推送。
   - 用户批准后：提交 → `git push -u origin feature/...`（带 `https_proxy=http://127.0.0.1:7897`）→ 再等批准合并回 `dev` → 推送 dev → 删特性分支。

## 风险与回滚

- 后端启动失败（数据源 / key）→ 走截图回退方案，不阻塞 README。
- 点击「翻译」可能触发真实 run 与 API 消耗 → 只切换目标语种加载已有译文，不点重新翻译。
- 截图含 B 站视频画面与公开链接 → 用户已接受现有任务作为展示素材；如介意，改用无版权样例视频重跑（本计划不默认做）。
- 回滚：删除 `README.md`、`docs/images/`，`git switch dev` 丢弃分支即可。

## 不在本计划内

- 改 public、加 LICENSE、英文 README、演示 GIF、CI 徽章。
