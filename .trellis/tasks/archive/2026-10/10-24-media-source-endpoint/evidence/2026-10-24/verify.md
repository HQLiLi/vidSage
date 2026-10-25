# 2026-10-24 真实例实测：媒体源端点（AC1–AC6）

实例：`mvn -q spring-boot:run -Dspring-boot.run.arguments=--spring.ai.deepseek.base-url=http://127.0.0.1:9/`
（死地址只为验证上传任务不烧 LLM；媒体文件在 acquire 阶段就落盘登记，与后续失败无关）。

测试任务 A（URL 路线）：`abe3af06ddf2429c80816574f2920b3f`，工作区 `BV1j19yYbETS_1`，
文件 `source.mp4`，28,201,193 字节。

## AC1 全量 GET → 200

```
HTTP/1.1 200
Accept-Ranges: bytes
Content-Disposition: inline; filename="source.mp4"
Cache-Control: no-cache
Content-Type: video/mp4
Content-Length: 28201193
```

- body 字节数 28201193 = 磁盘文件大小
- sha256 两边一致：`36eb61e4053c0a560094a4d60afcf7b6c78e465e5cdc0cd8584a3d6e5a003978`

原始头文件：`ac1.h`。

## AC2 单段 Range `bytes=0-99` → 206

```
HTTP/1.1 206
Accept-Ranges: bytes
Content-Disposition: inline; filename="source.mp4"
Cache-Control: no-cache
Content-Range: bytes 0-99/28201193
Content-Type: video/mp4
Content-Length: 100
```

- body 100 字节；`cmp -n 100` 与磁盘文件逐字节一致

原始头文件：`ac2.h`。

## AC3 起点等于文件长度 `bytes=28201193-` → 416

```
HTTP/1.1 416
Content-Range: bytes */28201193
Content-Length: 0
```

- body 0 字节

## AC4 HEAD → 200，有头无体

```
HTTP/1.1 200
Accept-Ranges: bytes
Content-Disposition: inline; filename="source.mp4"
Cache-Control: no-cache
Content-Type: video/mp4
Content-Length: 28201193
```

- `curl -I` 无响应体

## AC5 两个 404

| 场景 | 状态 | 响应体 |
|---|---|---|
| 任务号不存在 | 404 | `{"error":"任务不存在：deadbeef…；请核对 taskNo…"}` |
| 任务在、无 READY 的 `SOURCE_MEDIA`（`95a494d2cd22413aad91ebab03ba44b3`） | 404 | `{"error":"媒体源不可用：taskNo=95a494…；该任务还没有可播放的媒体源，或媒体文件已被回收…"}` |

- 「行在但文件不在」分支由 `MediaSourceQueryServiceTest` 覆盖（只 WARN、不写库）
- 「同一任务多条 READY」分支由 `MediaSourceQueryServiceTest` 覆盖（500 `IllegalStateException`）

## AC6 Content-Type 嗅探

| 路线 | 任务 | 文件 | Content-Type | 字节 |
|---|---|---|---|---|
| URL（mp4） | `abe3af06…` | `source.mp4` | `video/mp4` | 与磁盘一致 |
| 上传（mp3） | `f4b24d0bf60e4ed29b66351ba8e0b048` | 落盘名仍是 `source.mp4` | **`audio/mpeg`** | 与上传的 tone.mp3 完全一致（8782 字节） |

mp3 用例：`ffmpeg -f lavfi -i "sine=frequency=440:duration=2" -q:a 9 tone.mp3` → `POST /api/tasks/upload`。
前端解码器不再会被 `video/mp4` 误导。

## 前端（真浏览器，Playwright + Vite dev server）

| AC | 场景 | 结果 |
|---|---|---|
| AC8 | 打开 `abe3af06…`，`<video>` 元数据 | `readyState=4`、`duration=201.6s`、`1920×1080`、无 error；`currentTime=120` 后正常播放（`paused=false`、`currentTime≈121.5`） |
| AC8 | 拖动后网络请求 | 4 条媒体请求全部 `206 Partial Content`（Network 面板） |
| AC9 | 临时把该任务 `source.mp4` 改名后刷新 | 播放器消失、占位出现（标题 + 新副标题），字幕照常；控制台**只有一条**预期内的 `404 (Not Found)` 网络错误，无未捕获 JS 异常 |
| AC9 恢复 | 文件改回后重新打开 | 播放器恢复，`duration=67.8s`（另一任务 `ace85ee8…` 同样正常） |
| AC10（前半） | 失败态后切到另一个有媒体的任务 | 新任务正常出播放器，旧失败态未带过来 |
| AC10（后半） | 运行中转就绪自动重试 | **只做了静态审查**（watch 条件：同任务 + `phase===READY` + 此前失败过才重挂），未跑真实 run 转态 |

截图：`workspace-video.png`。

## 实测过程留下的数据

- 为验 AC6 音频侧上传了 2 秒静音 mp3，任务号 `f4b24d0bf60e4ed29b66351ba8e0b048`
  （run 因死 LLM 地址停在 `INTERRUPTED`，`SOURCE_MEDIA` 行 `READY`）。未删除，留作后续音频回放样本。
- AC9 的「文件缺失」用临时改名模拟，已改回；`git status` 看不到任何工作区数据改动。

## 质量检查后的补测（2026-10-24 16:30）

检查轮改了 HEAD 语义（忽略 Range）与 416 响应头，重起实例复验：

```
HEAD                     → 200, Content-Length: 28201193, Accept-Ranges: bytes
HEAD + Range: bytes=0-99 → 200, Content-Length: 28201193（忽略 Range，符合 RFC 9110 §14.2）
GET  Range: bytes=28201193-  → 416 + Accept-Ranges: bytes + Content-Range: bytes */28201193
GET  Range: bytes=100-199    → 206 + Content-Range: bytes 100-199/28201193 + Content-Length: 100
```

后端单测 33 条全绿（`MediaSourceQueryServiceTest` 5 + `MediaContentTypeUtilsTest` 17 +
`MediaSourceControllerTest` 11）；全量 `mvn test` 733 tests / 0 failures（子代理跑的那轮）。

## 评审反馈后补测（2026-10-24 16:45–16:52）

反馈两条：①运行中不该说「没有媒体」；②竖屏不能撑坏媒体区。

### 竖屏（AC9b）

把任务 `ace85ee8…` 的 `source.mp4` 临时换成 360×640 的竖屏样本（原文件备份，验完已还原）：

| 量 | 值 |
|---|---|
| `section[aria-label="媒体预览"]` | 1014 × 571（比例 1.778） |
| `<video>` 盒子 | 1014 × 571 |
| 视频内在尺寸 | 360 × 640 |
| 页面是否出现滚动条 | 否 |

画面被 `object-contain` 收进黑框，左列布局不动。截图：`portrait-video.png`。

### 运行中文案（AC9）

临时把任务 `f4b24d0b…` 的 run 状态改成 `RUNNING` 并移走其媒体文件（验完状态与文件均已还原）：

| 状态 | 实际文案 |
|---|---|
| 运行中 + 媒体未产出 | 「媒体准备中…」/「任务解析中，媒体准备好后会自动出现」 |
| 就绪 + 媒体缺失 | 「媒体预览暂不可用」/「该任务暂无可播放的媒体，字幕仍可正常查看」 |

### 前端门禁

`npm run type-check` / `npm run lint` / `npm run build` 全绿（第一次 build 因本机内存压力崩了一次，
清理残留 JVM 后重跑通过）。

## 未在本轮验的

- mkv / flv 等浏览器不支持的容器：按设计走 `@error` 降级占位，未单独造样本。
- AC10 后半（真实 run 从 RUNNING 转 READY 时的自动重挂）。
