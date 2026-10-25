# 读取路径接入与强制重翻 — 执行清单

> 设计口径见 `design.md`（本子任务）与父任务 `design.md` §4.4 / §5。

## 0. 前置

- 分支：从 `dev` 切 `feature/corrections-read-paths`，合回 `dev`。
- 构建命令（Git Bash）：

```bash
cd backend && JAVA_HOME=/f/java_soft/java21 mvn clean test-compile   # 判编译只认这个
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
```

## 1. 下载分流

- [ ] `ArtifactDownload` 扩为 `(path, kind, content)`，加 `ofFile` / `ofContent` 工厂。
- [ ] 新增 `application/subtitle/SubtitleArtifactRenderer`（`@Component`）：
  - [ ] `render(taskId, type, lang, kind, workDir) -> Optional<byte[]>`
  - [ ] 分流表按 `type × kind` 穷举；ORIGINAL / `context.json` / `terms.json` 直接空。
  - [ ] CLEANED：`trackReader.readCleanTrack` → `overlay.applyToCleanTrack` → `applied == track` 则空。
  - [ ] TRANSLATED：`readTranslatedTrack(lang)` → `applyToTranslatedTrack` → 同上。
  - [ ] JSON 用产物同一 `ObjectMapper` + `writerWithDefaultPrettyPrinter()`；SRT 用 `SrtRenderer`。
  - [ ] 读不到机器产物 → 空（回落发文件）；渲染/序列化失败 → 上抛。
- [ ] `SubtitleArtifactQueryService.openArtifact(..., boolean raw)`：
  - [ ] `raw` 短路，直发文件。
  - [ ] 非 raw 调渲染器，有字节则 `ofContent`，否则 `ofFile`。
- [ ] `ArtifactController.download` 加 `raw` 查询参数，按 `content` 选 `FileSystemResource` / `ByteArrayResource`；
      更新 Javadoc 与 `@Operation` 描述。

## 2. 翻译取源文本

- [ ] `SubtitleTranslationCoordinator.translate(...)` 两个重载加首参 `long taskId`。
- [ ] 读到 CLEANED 后 `overlay.applyToCleanTrack(taskId, track)` 再用于翻译与上下文摘要。
- [ ] `SubtitleTranslateService` 传 `run.getTaskId()`。
- [ ] 确认 `context.json` / `terms-*.json` / 清洗复用 / 指纹 / 对账路径未接线（只读走查）。

## 3. 强制重翻

- [ ] `TranslationRequest` 加 `boolean force` + `@Schema`（说明默认 false）。
- [ ] `TaskController.translate` 透传。
- [ ] `SubtitleTranslateService.translate` 改签名加 `force`；`runPipeline` 里「已有完好译文」分支加 `!force`。
- [ ] 更新 `@Operation` 描述：`force=true` 跳过「已有译文」复用。

## 4. 测试

- [ ] `SubtitleArtifactRendererTest`（新）：分流表、零纠错返回空、CLEANED 原文/TRANSLATED 同语种覆盖、
      其它语种不串、SRT 与 JSON 形状、读不到产物返回空。
- [ ] `SubtitleArtifactQueryServiceTest`：`raw` 短路、有渲染结果用字节、无结果发文件、ORIGINAL/CONTEXT/TERMS 不渲染。
- [ ] `ArtifactControllerTest`：`raw=1` 透传、字节响应的 Content-Type 与文件名。
- [ ] `SubtitleTranslationCoordinatorTest`：翻译输入是叠加后的原文、其它语种纠错不影响、force 无关。
- [ ] `SubtitleTranslateServiceTest`：`force=true` 不跳过、`force=false` 仍跳过、taskId 透传。
- [ ] `TaskControllerTest`：`force` 透传；运行中 409 覆盖 force 路径。
- [ ] AC7 防回归：反射单测钉住 `SubtitleCleanupCoordinator` / `SubtitleFingerprintCoordinator` /
      `SubtitleRecleanService` 构造器不依赖 `SubtitleCorrectionOverlay` / `SubtitleTrackReader`。

## 5. 验证门

- [ ] `mvn clean test-compile` 绿。
- [ ] `rm -rf target/surefire-reports && mvn test` 全绿；记录本次总数作为新基线。
- [ ] AC1–AC7 逐条对照（AC1/AC2/AC3 可在 query service 层用真实文件 + 真实 overlay 验证：
      零纠错返回 `ofFile`、有纠错返回字节、`raw` 恒 `ofFile`）。

## 6. 收尾

- [ ] spec 登记：`-Renderer` 后缀；「字幕纠错的读取口清单」一条（必须套用 / 禁止套用）。
- [ ] 等用户审查后提交；提交/合并/归档逐门批准。
