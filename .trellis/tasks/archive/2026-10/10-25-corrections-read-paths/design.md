# 读取路径接入与强制重翻 — 子任务设计

> 技术正典仍是父任务 `../10-25-subtitle-manual-correction/design.md`（§4.1 调用点、§4.4 强制重翻、§5 下载路径）。
> 本文只记本子任务自己拍板的落点，不复述父设计。

## 1. 下载分流

### 1.1 渲染放哪

新开 `application/subtitle/SubtitleArtifactRenderer`（`@Component`，机制/协作件档），职责只有一句：
**给一次产物下载算出「用户可见的字节」**。

```java
Optional<byte[]> render(long taskId, ArtifactType type, String lang, ArtifactFileKind kind, Path workDir);
```

- 返回空 = 这份文件不需要/没法套纠错，调用方发磁盘原文件。
- `SubtitleArtifactQueryService` 继续管「解析参数 → 定位产物 → 求路径」，只在末尾问渲染器要不要替换正文。
- 不塞进查询服务的理由：查询服务的职责是定位产物，渲染是另一段机制；塞进去会让它五个依赖、两个职责。
- 后缀 `-Renderer` 在 spec 登记（`shared/subtitle/SrtRenderer` 已是同义先例：都是「把结构化字幕渲成字节」）。

### 1.2 静态分流表

| type | kind | 动作 |
|---|---|---|
| ORIGINAL | 任意 | 发文件 |
| CLEANED | `context.json` | 发文件 |
| CLEANED | `segments.json` / `subtitles.srt` | 读 CLEANED → 叠原文纠错 → 渲染 |
| TRANSLATED | `terms.json` | 发文件 |
| TRANSLATED | `segments.json` / `subtitles.srt` | 读该 lang 译文 → 叠该 lang 译文纠错 → 渲染 |

`raw=true` 在 `SubtitleArtifactQueryService` 入口直接短路，不经过渲染器。

### 1.3 「有没有纠错」的判据

用 `SubtitleCorrectionOverlay` 已明文承诺、且被单测 `assertSame` 钉住的契约：**没有任何要叠的纠错时原样返回入参**。
渲染器据此判 `applied == track` → 返回空。

- 不新增 `hasCorrections`：那会让每次下载多查一次库，而判据本身已经单源。
- 不把 `applyTo*` 改成返回 `Optional`：契约已经够清楚，改形状只会让翻译侧调用多一层拆包。
- 渲染器 Javadoc 必须写明「依赖的是原样返回契约」，别让后来人把它当成随手写的比较。

### 1.4 序列化与渲染

- JSON：与产物落盘同一份 `ObjectMapper` bean + `writerWithDefaultPrettyPrinter()`，形状与磁盘一致。
- SRT：`SrtRenderer.toSrt(...)`（原文）/ `SrtRenderer.toBilingualSrt(...)`（译文，与翻译落盘同款）。
- 文件名沿用磁盘产物名（`Content-Disposition` 不变），`sizeBytes` 仍报磁盘大小，清单接口不动。

### 1.5 失败口径

- 机器产物读不出来（`SubtitleTrackReader` 返回空）→ 渲染器返回空 → 发磁盘文件。下载不该因为叠加失败而断。
- 读得出来但序列化/渲染抛异常 → 如实上抛 500。**不静默回退成未纠错版本**——那等于给用户一份假的「已改」内容。

### 1.6 返回结构

`ArtifactDownload` 扩成 `(Path path, ArtifactFileKind kind, byte[] content)`，配两个静态工厂：

- `ArtifactDownload.ofFile(path, kind)` —— `content = null`，发文件。
- `ArtifactDownload.ofContent(path, kind, bytes)` —— 发字节。

Controller 按 `content == null` 选 `FileSystemResource` / `ByteArrayResource`，其余响应头不变。

## 2. 翻译取源文本

`SubtitleTranslationCoordinator.translate(...)` 两个重载都加首参 `long taskId`，读 CLEANED 后

```java
SubtitleTrack track = overlay.applyToCleanTrack(taskId, readClean(cleanSeg));
```

- `context.json`、`terms-*.json`、清洗复用判定、指纹、对账一律不碰覆盖层（与父设计 §4.1「不得调用」清单一致）。
- 重翻落盘的译文 `sourceText` 自然来自改后原文，前端「译文待复核」判据不必另造。

## 3. 强制重翻

- `TranslationRequest` 加 `@Schema(..., defaultValue = "false") boolean force`；JSON 不带即 `false`。
- `SubtitleTranslateService.translate(taskNo, source, target, force)` 直接改签名，**不保留三参重载**（唯一生产调用点是 `TaskController`）。
- 执行体里「已有完好译文 → SKIPPED 收口」只在 `!force` 时生效；`force=true` 走真翻译并照常换版。
- 运行中调用返回 409 由 `TaskRunSupervisor.start` 既有互斥兜住，本任务不新增判定。
- `force=false` 的所有行为保持现状（含 SKIPPED 事件与不落版）。

## 4. AC7 防回归

仓库没有 ArchUnit，用一条反射单测钉住：清洗/指纹/对账链路上的类，构造器参数里不得出现
`SubtitleCorrectionOverlay` 或 `SubtitleTrackReader`。名单取 `SubtitleCleanupCoordinator`、
`SubtitleFingerprintCoordinator`、`SubtitleRecleanService`。

## 5. 回滚

只回退下载与翻译接线；数据层（表、端口、API）不受影响，磁盘产物全程未被改写。
