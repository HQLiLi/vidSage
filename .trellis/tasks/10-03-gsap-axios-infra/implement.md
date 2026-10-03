# 实施计划：gsap-axios-infra

> 顺序执行；每步有验证命令，失败先修复再前进。回滚点：步骤 1–5 全部是新增文件/依赖，`git checkout -- frontend/ && git clean -fd frontend/src` 即可回退；步骤 6 只改 `.trellis/spec/` 与 skills，独立可逆。

## 前置参照

- 后端契约源（只读，不进 jsonl）：`backend/src/main/java/com/vidsage/web/controller/TaskController.java`、`MediaController.java`、`com/vidsage/model/` 下相关 DTO、`TaskSseHub.java`（仅确认 SSE 边界）
- 官方 skills 仓库：`github.com/greensock/gsap-skills`（MIT），取 `skills/` 子目录下 5 个 skill
- 后端端口：Spring 默认 8080；实现时 `grep -rn "port" backend/src/main/resources/` 复核

## 步骤清单

### 1. 安装依赖
- [ ] `cd frontend && npm install gsap axios`
- 验证：`package.json` dependencies 出现 `gsap`、`axios`；`npm run type-check` 仍绿

### 2. GSAP 基建 `src/lib/gsap.ts`
- [ ] 新建文件：注释说明"全项目唯一 registerPlugin 入口，插件按需在此 import+注册"；当前无插件使用场景，仅 re-export gsap
- [ ] 导出形态：`export { gsap }`（后续注册插件后此处自动对所有消费者生效）
- 验证：`grep -rn "from 'gsap'" frontend/src` 除该文件外无命中（规范生效前先保证入口唯一）

### 3. api 包 `src/api/`
- [ ] 读后端 `TaskController` / `MediaController` 与 model DTO，在 `types.ts` 对齐定义（`TaskView`、`SubmitUrlRequest`、tools 列表项等；后端无 envelope，类型即裸 DTO）
- [ ] `request.ts`：`axios.create({ timeout: 15000 })`——不设 baseURL；挂响应错误拦截器骨架（注释预留：HTTP 状态语义透传给调用方，409 响应体含既有任务视图）
- [ ] `tasks.ts`：`submitUrl`（POST /api/tasks，202）、`submitUpload`（POST /api/tasks/upload，FormData，202）、`getTask`（GET /api/tasks/{taskNo}）、`translate`（POST /api/tasks/{taskNo}/translate）；SSE 端点**不**写进来
- [ ] `media.ts`：`getTools`（GET /api/media/tools）
- 验证：`npm run type-check`；`grep -n "EventSource\|text/event-stream" frontend/src` 无命中

### 4. Vite 代理
- [ ] `vite.config.ts` 加 `server: { proxy: { '/api': 'http://localhost:8080' } }`（保持现有 plugins/resolve 不动）
- 验证：起 `npm run dev`，`curl http://localhost:5173/api/media/tools` 能转发到后端（后端未启动时应得到 500/ECONNREFUSED 而非 404，证明代理命中路径）

### 5. 官方 skills vendoring 到 `.agents/skills/`
- [ ] `git clone --depth 1 https://github.com/greensock/gsap-skills`（临时目录）
- [ ] 拷贝 `skills/{gsap-core,gsap-frameworks,gsap-performance,gsap-timeline,gsap-scrolltrigger}/` 五个目录到 `.agents/skills/`；将仓库 `LICENSE` 放 `.agents/skills/gsap-core/`（或各目录）保留 MIT 声明
- [ ] 不装 `gsap-react`、`gsap-utils`
- 验证：每个目录含非空 `SKILL.md` 且 frontmatter（name/description）完整；`ls .agents/skills/` 与既有 trellis-* skills 共存无冲突

### 6. 规范文档
- [ ] 新建 `.trellis/spec/frontend/animation.md`：按 prd R3 条款清单编写，文首给"API 级用法查 `.agents/skills/gsap-*`"指针；必须含官方三条硬规则（scope 容器、禁 setup 顶层创建、nextTick refresh）
- [ ] 新建 `.trellis/spec/frontend/api.md`：按 prd R6 条款（实例唯一、一域一文件、类型就近、无 envelope 错误约定、SSE 边界、具体文件导入）
- [ ] 更新 `index.md`：索引表加两行；技术栈表加 `gsap ^3.x`、`axios ^1.x`（以 `package.json` 实际版本为准）
- [ ] 更新 `directory-structure.md`：结构示意树加 `lib/`、`api/`；目录职责表加两行（lib=框架无关单例模块；api=后端通信，一域一文件）
- 验证：三份文档内文件名、路径、版本号与仓库实际状态一致（禁止凭记忆写版本）

### 7. 质量门（最后一轮全量）
- [ ] `npm run type-check && npm run lint`
- [ ] 对照 prd.md Acceptance Criteria 逐条勾验
- [ ] 提交（步骤 8 前置）

### 8. 收尾
- [ ] 本任务产出即 spec 沉淀，3.3 无额外语写回；如有执行期新经验（如 axios 类型坑）当场补进 api.md
- [ ] commit：`feat(frontend): 引入 gsap 与 axios 基础设施及动画/API 规范`
