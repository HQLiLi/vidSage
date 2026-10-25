# 设计：媒体源播放端点与前端接入

> 落位依据：`.trellis/spec/backend/directory-structure.md`（分层与契约归属）、`error-handling.md`（404 映射）、
> `logging-guidelines.md`（脱敏）、`.trellis/spec/frontend/api.md`（双拼法）。
> 2026-10-24 立。

## 1. 现状事实（设计的前提）

| 事实 | 出处 |
|---|---|
| 后端没有任何媒体字节端点；`/api/media` 下只有 `/tools` | `web/controller/MediaController.java:39` |
| 产物下载是闭集：只有 `segments.json` / `subtitles.srt` / `context.json` / `terms.json` | `web/controller/ArtifactController.java:84-108` |
| 任务快照的 `sourceUri` 是链接原文或上传文件名，不是可播放地址 | `web/dto/TaskResponse.java:19` |
| 上传路线**无条件**落盘为 `source.mp4` | `application/task/SubtitleMainService.java:328`、`domain/media/model/MediaInput.java:18` |
| URL 路线落盘为 `source.<ext>`，`ext` 取自 info.json | `infrastructure/provider/download/ytdlp/YtDlpMediaAcquirer.java:339,373` |
| yt-dlp 调用**没有 `-f`**，走默认 `bestvideo*+bestaudio/best`，源只有音频时产物就是音频 | `YtDlpMediaAcquirer.java:308-318` |
| 源媒体已在 `intermediate_file` 表登记为 `SOURCE_MEDIA` / `READY` | `domain/intermediate/model/IntermediateFileType.java:10` |
| 复用判据是「表里 READY **且** 文件在」，文件丢失只 WARN、不改状态 | `application/media/IntermediateFileStore.java:48-57` |
| 前端 `MediaPane` 是纯占位，注释已写明「等后端补端点」 | `frontend/src/views/workspace/components/MediaPane.vue:2-5` |

## 2. 契约

```
GET  /api/media/source/{taskNo}
HEAD /api/media/source/{taskNo}
```

### 2.1 成功响应

| 条件 | 状态 | 关键响应头 | Body |
|---|---|---|---|
| 无 `Range` | 200 | `Content-Type`、`Content-Length=<size>`、`Accept-Ranges: bytes`、`Content-Disposition: inline; filename=<工作区文件名>`、`Cache-Control: no-cache` | 全文件 |
| 单段 `Range` 可满足 | 206 | 同上但 `Content-Length=<区间长度>`、`Content-Range: bytes <start>-<end>/<size>` | 区间字节 |
| 单段 `Range` 越界 | 416 | `Accept-Ranges: bytes`、`Content-Range: bytes */<size>`、`Content-Length: 0` | 空 |
| `Range` 语法非法 / 多段 | 200 | 同「无 Range」 | 全文件 |
| `HEAD` | 200 | 同「无 Range」（含 `Content-Length`）；**忽略 Range** | 空 |

`Cache-Control: no-cache` 的用意：URL 按 `taskNo` 固定，而同内容重跑可能替换文件、按 `taskNo` 固定地址会
拿到旧缓存（known-bugs §6 第 1 条要求定缓存策略）。本任务不引入 `ETag`，用「必须重验证」把可能过期的强缓存挡掉。

### 2.2 失败响应

沿用全局 `ApiExceptionHandler` 的 `{ "error": message }`：

| 情况 | 状态 | 说明 |
|---|---|---|
| `taskNo` 命中不到任务 | 404 | 复用 `TaskNotFoundException` |
| 任务在，但没有 `READY` 的 `SOURCE_MEDIA` 行 | 404 | 新异常 `MediaSourceNotFoundException` |
| 行在，但工作区里文件不在 / 大小为 0 | 404 | 同上；只记 WARN，不改数据库行 |
| 同一任务出现多条 `READY` 的 `SOURCE_MEDIA` | 500 | 数据自相矛盾（内容变更会级联作废），抛 `IllegalStateException` |

## 3. 分派与落位

| 构件 | 位置 | 职责 |
|---|---|---|
| `MediaContentTypeUtils` | `shared/media/` | 读文件头判 MIME（纯工具，无状态） |
| `MediaSourceQueryService` | `application/media/`（`@Service`） | taskNo → 任务 → 媒体身份 → 工作目录 → 定位源文件 → 嗅探，返回读模型 |
| `MediaSource` | `application/media/query/` | 读模型：`path` / `fileName` / `contentType` / `sizeBytes`，零 web 注解 |
| `MediaSourceNotFoundException` | `application/media/exception/` | 404 专用异常 |
| `MediaSourceController` | `web/controller/` | 接收、卫语句、Range 解析、组装 200/206/416 |
| `mediaSourceUrl(taskNo)` | `frontend/src/api/media.ts`（**新建**，现有 api 目录只有 artifacts / languages / request / tasks） | 产出 `<video src>` 用的全路径 |
| `MediaPane.vue` | `frontend/src/views/workspace/components/` | 播放器 + 缺失降级 |

**为什么 `MediaSourceController` 单独一个类**：现有 `MediaController` 的类注释写明「外部工具诊断 HTTP 入口（只读）」，
它返回的是诊断 DTO；媒体字节是另一种职责（文件流 + Range 语义）。两个控制器同挂 `/api/media` 前缀互不影响。
**为什么代码不下 `/api/tasks`**：端点语义是「媒体」而不是「任务」，任务号只是定位参数（项目所有者 2026-10-24 定）。

**为什么把定位判据放在表上而不是扫目录**：`intermediate_file` 是权威事实源，扫目录会把已作废的缓存当成有效缓存
（`IntermediateFileStore.java:20-26` 写明这条判据的来历）。复用它现成的 `findReady(taskId)`，按
`fileType == SOURCE_MEDIA` 过滤，不新增仓储方法。

**`contentType` 作为 MIME 字符串进入 application 读模型**：嗅探是文件系统事实，必须在 application 之下做；
返回值用 MIME 字符串是最小传递方式。这是一处有意接受的不纯（HTTP 词汇出现在应用层），代价换来
web 层只按给定的 MIME 声明响应头、不再自己判定格式（**字节仍由 web 层读出写回**，见 §5）。

## 4. Content-Type 嗅探规则

读文件头至多 64 字节，按顺序匹配；全不中则按扩展名映射；仍不中回 `application/octet-stream`。

| 特征 | 判定 |
|---|---|
| 偏移 4–7 为 `ftyp` | `video/mp4`；brand 以 `M4A` 开头 → `audio/mp4` |
| 开头 `ID3`，或首字节 `0xFF`、次字节高三位为 `111` **且 layer 位不为 `00`** | `audio/mpeg` |
| 首字节 `0xFF`、次字节高四位为 `1111` **且 layer 位为 `00`**（ADTS） | `audio/aac` |
| `RIFF` + 偏移 8–11 为 `WAVE` | `audio/wav` |
| `RIFF` + 偏移 8–11 为 `AVI ` | `video/x-msvideo` |
| `OggS` | `audio/ogg` |
| `fLaC` | `audio/flac` |
| `1A 45 DF A3`（Matroska/WebM） | 头部含 `webm` → `video/webm`，否则 `video/x-matroska` |
| 扩展名兜底 | `mp4`/`m4v`→`video/mp4`、`m4a`→`audio/mp4`、`webm`→`video/webm`、`mkv`→`video/x-matroska`、`mp3`→`audio/mpeg`、`aac`→`audio/aac`、`wav`→`audio/wav`、`ogg`/`opus`→`audio/ogg`、`flac`→`audio/flac`、`mov`→`video/quicktime`、`avi`→`video/x-msvideo`、`ts`→`video/mp2t` |

## 5. Range 处理

**实现形态：控制器直接写 `HttpServletResponse`，不返回 `ResponseEntity<Resource>` / `ResourceRegion`。**
2026-10-24 实现时确认的技术事实：

- Spring 7 的 `AbstractMessageConverterMethodProcessor` 对「返回 `Resource` 且请求带 `Range`」会自动接管：
  多段被改写成 206 multipart、语法非法被改写成 416，直接推翻「多段/语法非法一律按没有 Range 处理」这条契约。
- `ResponseEntity<?>` 配单个 `ResourceRegion` 选不到转换器（`ResourceRegionHttpMessageConverter.canWrite`
  只认 `Class` / `ParameterizedType`，通配符两者都不匹配）；且该转换器会无条件补 `Content-Range`，
  200 全量分支会莫名带上 `Content-Range: bytes 0-(size-1)/size`。

具体做法：

- `org.springframework.http.HttpRange.parseRanges(...)` 解析（HTTP 语义留在 web 层）。
- 只服务**单段**；多段与语法非法都当没有 Range，回 200（RFC 允许服务器忽略 Range；浏览器只发单段）。
- 单段可满足 → 206，`Content-Range: bytes <start>-<end>/<size>`；越界 → 416 +
  `Content-Range: bytes */<size>` + `Content-Length: 0`。
- 字节用 `StreamUtils.copy` / `StreamUtils.copyRange` 从 `Files.newInputStream` 分块拷（8 KB 缓冲），
  不整文件读入内存。
- `Content-Length` **显式设置**：200 = 文件大小，206 = 区间长度；`HEAD` 忽略 Range、按整文件回头不写体
  （按 `HttpMethod.HEAD.matches(request.getMethod())` 判断，RFC 9110 §14.2 只给 GET 定义区间语义）。
- **先开流再写响应头**：文件在 stat 之后被删或被占用时抛 `IllegalStateException`（`{ "error": … }` 500），
  此时响应还没被改动；只有拷贝中途断连才会在响应已提交后抛 `IOException`。
- 不做 `ETag` / `Last-Modified`：见 §2.1 的缓存取舍。

## 6. 前端接入

`frontend/src/api/media.ts`（新建；api 目录此前没有媒体域文件）：

```ts
/** 媒体字节地址：<video src> 不走 axios，按 api.md 的双拼法写全路径。 */
export function mediaSourceUrl(taskNo: string): string {
  return `/api/media/source/${encodeURIComponent(taskNo)}`
}
```

`MediaPane.vue`：
- 组件直接从 `useWorkspaceStore()` 取 `taskNo` 与 `phase`（与本目录其余组件一致，不新增 props）。
- `<video controls preload="metadata" :src="mediaSourceUrl(store.taskNo)">`，`@error` 置 `failed = true` 退回占位。
- `watch([taskNo, phase])`：换任务时重置 `failed` 并重挂；同一任务 `phase` 变为 `READY` 时**只有此前加载失败过**
  才重挂（`:key` 计数）。运行态下媒体可能还没下完，先失败退占位、转就绪后自动重试一次（AC10）；
  已经在播的重挂会让视频跳回 0 秒，所以不无条件重挂。
- **占位文案按 `phase` 分**（2026-10-24 评审反馈）：
  - 运行中、媒体还没产出 → 标题「媒体准备中…」+ 副文案「任务解析中，媒体准备好后会自动出现」；
  - 就绪但拿不到媒体（老任务源文件已回收等）→ 标题「媒体预览暂不可用」+ 副文案「该任务暂无可播放的媒体」；
  - 元数据还没到手（正常加载中）→ 标题「正在加载媒体…」，无副文案。
  不分的话，用户刚提交 URL 就会看到「该任务暂无可播放的媒体」，把「还没解析完」说成了「没有」。
- **播放器用绝对定位铺满容器**（`absolute inset-0 h-full w-full object-contain`），外层 `relative aspect-video overflow-hidden`。
  竖屏素材（如 360×640）只能被 `object-contain` 收进黑框，撑不动 16:9 区块——不影响左列布局（2026-10-24 实测）。
- 不做字幕联动（PRD 已列不在范围）。

## 7. 验证方式

| 验的是什么 | 怎么验 |
|---|---|
| 200 / 206 / 416 / 404 / HEAD、头部与字节 | `curl -s -D - -o out.bin` 逐条打真实实例，`sha256sum` / `cmp` 比字节 |
| 嗅探 | 上传一个 mp3 建任务后看 `Content-Type`；URL 任务看 mp4；单元测试覆盖各魔数与兜底 |
| 拖进度触发 206 | 浏览器开发者工具 Network 面板看媒体请求出现 206 |
| 前端门禁 | `npm run type-check` / `lint` / `build` |

后端命令按项目既定口径：显式 `JAVA_HOME=/f/java_soft/java21`；**判编译用 `mvn clean test-compile`**，
不用增量 `mvn compile`（会假绿）；量测试结果前先删 `target/surefire-reports`。

## 8. 风险与取舍

| 风险 | 处理 |
|---|---|
| 浏览器解不了的容器（mkv / flv） | 不转码；`@error` 降级占位，文案不承诺「一定能播」 |
| 大文件拖动频繁发 Range | 单段处理是 O(1) 定位（`ResourceRegion` 按偏移写入），不会整文件读入内存 |
| 运行中媒体尚未产出 | 端点 404 → 占位；转就绪后前端重试一次（§6） |
| `ResponseEntity<?>` 通配符 | 仅此一处，200/206 body 类型确实不同；Javadoc 写明理由 |
| 上传音频的行在表里叫 `source.mp4` | 嗅探是唯一可靠判据，不能靠文件名 |
