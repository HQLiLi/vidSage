# 执行计划：媒体源播放端点与前端接入

> 口径：后端先做完并自测，前端再接。每步产出可独立验证的中间态；后端契约一变，先改 `design.md` 再改代码。
> 2026-10-24 立。

## 环境与命令口径

```bash
# 后端（Git Bash）
export JAVA_HOME=/f/java_soft/java21
cd backend
mvn clean test-compile          # 判编译一律用它，不用增量 mvn compile（会假绿）
rm -rf target/surefire-reports  # 量结果前先清，避免读到陈旧报告
mvn -q -Dtest='MediaSource*Test,MediaContentTypeUtilsTest' test

# 前端
cd frontend && npm run type-check && npm run lint && npm run build
```

## 阶段一：后端

### 1. Content-Type 嗅探工具
- [ ] 新建 `shared/media/MediaContentTypeUtils`：`public static String detect(Path file)`，规则照 `design.md` §4。
      读文件头用 `Files.newInputStream` + `readNBytes(64)`；读失败按扩展名兜底，不吞异常也不抛（嗅探失败不该打挂播放）。
- [ ] 文件头魔数判定拆成小的 private 方法，每个方法一句白话 Javadoc（项目注释口径：首行大白话）。
- [ ] 单测 `shared/media/MediaContentTypeUtilsTest`：mp4（ftyp）、m4a（brand M4A）、mp3（ID3 / FF Ex）、
      wav（RIFF+WAVE）、ogg、flac、webm、mkv、扩展名兜底、空文件 / 认不出 → `application/octet-stream`。

### 2. 应用层定位
- [ ] `application/media/query/MediaSource`（record，零注解）：`Path path`、`String fileName`、`String contentType`、`long sizeBytes`。
- [ ] `application/media/exception/MediaSourceNotFoundException`（`RuntimeException`，带 taskNo）。
- [ ] `application/media/MediaSourceQueryService`（`@Service`）：
      注入 `SubtitleTaskFinder` + `IntermediateFileStore`；
      `public MediaSource locate(String taskNo)`：
      1. `finder.require(taskNo)` → `finder.requireWorkspace(task)` → `finder.workspaceDir(workspace)`；
      2. `fileStore.findReady(task.getId())` 按 `fileType == SOURCE_MEDIA` 过滤；
         0 条 → `MediaSourceNotFoundException`；多于 1 条 → `IllegalStateException`（数据自相矛盾）；
      3. `workDir.resolve(fileName)`，`ArtifactFileUtils.isRegularFile` 且 `size > 0`，否则 `MediaSourceNotFoundException`
         **并记 WARN、不改数据库行**（自愈口径同 `IntermediateFileStore.isReusable`）；
      4. `MediaContentTypeUtils.detect` + `Files.size` 组装读模型。
- [ ] `ApiExceptionHandler` 增 `@ExceptionHandler(MediaSourceNotFoundException.class)` → 404，WARN，文案走 `errorMessage(e)`。
- [ ] 单测 `MediaSourceQueryServiceTest`：任务不存在 / 无 READY 行 / 文件丢失 / 正常四条，用临时目录 + mock 端口。

### 3. HTTP 入口
- [ ] 新建 `web/controller/MediaSourceController`，`@RequestMapping("/api/media/source")`，`@Tag(name = "媒体源")`。
- [ ] `@GetMapping("/{taskNo}")` 返回 `ResponseEntity<?>`，流程：
      卫语句（taskNo 空白 → IAE）→ `queryService.locate(taskNo)` → 解析 `Range` 头（`HttpRange.parseRanges`）
      → 按 `design.md` §5 组装 200 / 206 / 416。
      所有分支都显式设 `Accept-Ranges`、`Content-Length`、`Content-Type`；200 分支额外 `Content-Disposition: inline`
      与 `Cache-Control: no-cache`。
- [ ] 日志：`log.info("下载媒体源: taskNo={}, file={}, range={}", ...)`；不打标题、不打绝对路径（脱敏红线）。
- [ ] 切片测试 `web/controller/MediaSourceControllerTest`（`@WebMvcTest`，mock 应用服务）：
      200 头部与字节、206 头部与区间字节、416、404、HEAD（有 `Content-Length`、无 body）、无 Range 与多段回 200。

### 4. 后端实测（真实例）
- [ ] `mvn spring-boot:run`（需要时加 `-Dmaven.test.skip=true`）。
- [ ] 按 PRD AC1–AC6 逐条 `curl`，把命令与输出存进 `evidence/`：
      `curl -s -D headers.txt -o body.bin localhost:8080/api/media/source/$T`、
      `-H 'Range: bytes=0-99'`、`-H 'Range: bytes=<size>-'`、`-I`、以及 404 两个场景。
- [ ] 字节比对：`sha256sum body.bin <磁盘文件>` 与 `cmp -n 100 body.bin <磁盘文件>`。
- [ ] AC6：上传一个 mp3 建任务后看 `Content-Type`；URL 任务看 mp4。

**评审门（后端）**：AC1–AC7 全 PASS 且证据落盘后，再动前端。

## 阶段二：前端

### 5. 地址与组件
- [ ] 新建 `frontend/src/api/media.ts`，导出 `mediaSourceUrl(taskNo)`（全路径 `/api/media/source/...`，注释说明双拼法）。
- [ ] `MediaPane.vue` 改造：取 store 的 `taskNo` / `phase`；`<video controls preload="metadata">`；
      `@error` 退占位；`watch` 任务或 phase 变化时重置失败态并重挂；占位文案改中性。
- [ ] 检查 `TranscriptPane` 等邻接组件不受影响（媒体区高度不变）。

### 6. 前端实测
- [ ] 真浏览器打开有媒体任务：能播、能拖动，Network 里出现 206（AC8）。
- [ ] 无媒体任务：占位、控制台无未捕获异常（AC9）。
- [ ] 运行中打开一次：占位 → 转就绪后播放器自动出现（AC10）。
- [ ] `npm run type-check` / `npm run lint` / `npm run build`（AC11）。

## 阶段三：收尾

- [ ] `.trellis/spec/frontend/api.md` 的「同源相对路径」一节补一句：`<video src>` 属「吃不到 baseURL」的第三例，
      走 `mediaSourceUrl()` 全路径（只加这一条，不动其它失实条目）。
- [ ] 若实现与 `design.md` 有偏差（例如 `HEAD` 需要单独映射、`ResourceRegion` 不设 `Content-Length`），
      回写 `design.md` 现状，不留「计划是 A、代码是 B」。
- [ ] 已知问题登记：若 mkv/flv 等容器降级占位的现象值得记录，追加到 `known-bugs.md`（不新建文档）。

## 回滚点

- 阶段一完成前：删除新增文件即可，无存量改动。
- 阶段一完成后、阶段二前：前端未动，后端端点独立可用，可单独回滚。
- `ApiExceptionHandler` 与 `api/media.ts` 是仅有的两处存量文件改动，冲突面小。
