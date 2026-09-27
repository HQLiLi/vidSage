# 执行计划：静态助手收口到 utils 包

## 前置

- 分支：`feature/utils-extract`（已从 `dev` 开出）
- 构建命令在 `backend/` 下执行；Git Bash 下需显式指定 JDK 21 路径
- 每一步都是**独立可提交的一簇**，改完即停等审查

## 验证命令

```bash
cd backend && mvn test
```

跑之前先删 `target/surefire-reports`——旧报告会留在目录里，看「测试数」时容易把上一轮的算进来。

---

## Step 1：`utils/fs/ArtifactFiles`（收益最大，先做）

**动作**

1. 新建 `backend/src/main/java/com/vidsage/utils/fs/ArtifactFiles.java`
   - `public final` + 私有构造器 + 全静态
   - `sizeOrZero(Path)` —— 取带守卫版本：`Files.isRegularFile(p) ? Math.max(Files.size(p), 0L) : 0L`，catch `IOException` → `0L`，**不记日志**
   - `isNonEmpty(Path)` —— `sizeOrZero(p) > 0`
   - `writeAtomically(byte[], Path)` —— `.tmp` + `REPLACE_EXISTING`；失败抛 `IllegalStateException("产物写入失败：" + target + "；请检查磁盘空间与目录写权限。原因：" + e.getMessage(), e)`
   - `moveAtomically(Path, Path)` —— `ATOMIC_MOVE`；失败先 `deleteQuietly(from)` 再抛，消息沿用原 `FfmpegAudioExtractor` 的「音频产物落盘失败：…」
   - `deleteQuietly(Path)` —— `Files.deleteIfExists`，失败只记 `log.debug`（`@Slf4j`）
2. 删掉六处 `sizeOrZero` / `nonEmptySize` 定义，调用点改指 `ArtifactFiles.sizeOrZero`
   - `provider/audio/ffmpeg/FfmpegAudioExtractor.java`（同时删 `moveAtomically`、`deleteQuietly`）
   - `provider/download/local/LocalFileMediaAcquirer.java`
   - `provider/download/ytdlp/YtDlpMediaAcquirer.java`
   - `service/AudioPreparationService.java`
   - `service/SubtitleService.java`（同时删 `isNonEmpty`、`writeAtomically`）
   - `service/SubtitleCleanupService.java`（同时删 `isNonEmpty`、`writeAtomically`）
3. 新建 `backend/src/test/java/com/vidsage/utils/fs/ArtifactFilesTest.java`
   - `@TempDir(cleanup = CleanupMode.NEVER)`（会写文件）
   - 覆盖：不存在 → 0；空文件 → 0；有内容 → 字节数；目录 → 0（**这条正是守卫修的那个 bug，必须有**）；`writeAtomically` 写完后内容正确且无 `.tmp` 残留；`moveAtomically` 成功后源不存在、目标存在

**验证**：`mvn test` 全绿；`grep -rn "static.*sizeOrZero\|static.*nonEmptySize" src/main/java` 只剩 `ArtifactFiles` 一处定义。

**回滚点**：本步只动 6 个文件 + 新增 2 个，`git checkout -- <paths>` 即可。

---

## Step 2：`utils/subtitle/SrtRenderer` 与 `SubtitleArtifactNames`

**动作**

1. 新建 `utils/subtitle/SrtRenderer.java`：`formatTimestamp(long)`、`toSrt(List<TranscriptionSegment>)`，从 `SubtitleService` 原样搬，改 `public`
2. 新建 `utils/subtitle/SubtitleArtifactNames.java`：`routeToken`、`srtFileName`、`segmentsFileName`（从 `SubtitleService`）+ `cleanSrtFileName`、`cleanSegmentsFileName`（从 `SubtitleCleanupService`），全部 `public`
3. `SubtitleService` / `SubtitleCleanupService` 删除这些定义，调用点改指新类；`SubtitleCleanupService` 不再引用 `SubtitleService.*`
4. **产物文件名的字符串一个字符都不许改**（`"subtitles-" + token + ".srt"` 等）
5. 测试搬迁：
   - `SubtitleServiceTest` 里 `routeToken` / `srtFileName` / `segmentsFileName` / `formatTimestamp` / `toSrt` 五组用例整体迁到新建的 `utils/subtitle/SrtRendererTest` 与 `utils/subtitle/SubtitleArtifactNamesTest`，**断言值原样保留**（它们是「文件名没变」的验收证据）
   - `SubtitleCleanupServiceTest:68,112` 对 `SubtitleService.segmentsFileName` 的引用改为 `SubtitleArtifactNames.segmentsFileName`
   - `SubtitleServiceTest` 只留服务编排用例（断点复用、进度映射、异常路径）

**验证**：`mvn test` 全绿；测试总数不减（搬迁不删覆盖）。

**回滚点**：本步只动 `service/` 两个类 + `utils/subtitle/` + 三个测试类。

---

## Step 3：`utils/text/TextTail`

**动作**

1. 新建 `utils/text/TextTail.java`：`tailLines(String, int)`，判空用 `StringUtils.hasText(...)`，与库内其他 36 处一致。`org.springframework.util.*` 是纯静态工具、不牵扯容器，`utils/` 允许用——见 design 决策 4（那条最初是反过来的，别再改回去）
2. 删掉 `FfmpegAudioExtractor`、`LocalFileMediaAcquirer`、`YtDlpMediaAcquirer` 三处私有副本，调用点改指 `TextTail.tailLines`
3. 新建 `utils/text/TextTailTest.java`：覆盖 null、空白、行数少于 `maxLines`、行数多于 `maxLines`、恰好等于

**验证**：`mvn test` 全绿；`grep -rn "static.*tailLines" src/main/java` 只剩一处定义。

**回滚点**：本步只动 3 个 provider 类 + 新增 2 个文件。**这是价值最低的一步，要砍就先砍它。**

---

## Step 4：规范同步

**动作**（改法逐字见 `design.md` 第六节）

1. `directory-structure.md` 第 199 行的 `utils/<领域>/` 段落整段替换
2. `directory-structure.md` 顶层分包判据表在 `config/` 行后补 `utils/` 行

`error-handling.md` **不动**——判空规则保持全库统一。

**验证**：通读改动后的两处，确认与实际代码一致——规范里写的包，磁盘上真的存在；规范里写的禁令，代码里真的没犯。

**回滚点**：两处文档改动，独立于代码。

---

## Step 5：全量验证

**动作**

1. 删 `backend/target/surefire-reports`，跑 `mvn test`，记下测试数与失败数
2. 跑 design 第九节的三条人工核对命令
3. 起一次冒烟实例（可选）：`mvn spring-boot:run`，确认 Swagger UI 能起来、`/v3/api-docs` 可访问

**验证输出必须贴原始结果**，不许只写「全绿」。

---

## 审查门

- **Step 1–3 每步改完即停**，等审查后再进下一步。不要一口气把三步都做完再一起给人看。
- **Step 4 的规范改动要和 Step 1–3 的代码改动一起审**——规范描述的是代码的样子，分开审看不出对不上。
- 提交前按分支工作流规则：`/trellis:finish-work` 或手动，**提交 / 合并 / 归档逐门批准**。
