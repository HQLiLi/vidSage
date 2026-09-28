# 静态助手收口到 utils 包

## Goal

`utils/` 包直到 2026-09-27 才被规范承认。此前跨模块复用的静态助手无处可去，只能寄居在 `service/`、`provider/`、`web/` 的某个类里，于是同一段逻辑被复制多份、各自漂移。本任务把这些助手收口到 `utils/<领域>/`，并修正规范里「utils 不收 I/O」这一条——它与实际的复用面不符。

## 背景：重复的实测规模

侦察 `backend/src/main/java` 全部静态方法后的结果（不含 `utils/` 内已有的三个类）：

| 簇 | 重复份数 | 分散在 |
|---|---|---|
| 产物大小判定 | 6 | `FfmpegAudioExtractor`、`LocalFileMediaAcquirer`、`YtDlpMediaAcquirer`、`AudioPreparationService`、`SubtitleService`、`SubtitleCleanupService` |
| 原子写盘 | 2 | `SubtitleService`、`SubtitleCleanupService` |
| 文本尾部截取 | 3 | `FfmpegAudioExtractor`、`LocalFileMediaAcquirer`、`YtDlpMediaAcquirer` |
| SRT 渲染与产物命名 | 8 个方法 | `SubtitleService`（5 个）、`SubtitleCleanupService`（2 个），且后者已反向调用前者的包级方法 |
| 进度事件 `emit` | 2 | `AudioPreparationService`、`SubtitleService` |
| 异常消息兜底 | 2 | `AudioPreparationService`（service 层）、`ApiExceptionHandler`（web 层） |

其中「产物大小判定」一簇已经漂移出错：`LocalFileMediaAcquirer` 与 `YtDlpMediaAcquirer` 的版本缺 `Files.isRegularFile` 守卫，对目录调用 `Files.size` 在部分文件系统上返回非 0，会让目录冒充「合法产物」。

## Requirements

### R1 重新划定 `utils/` 的边界【已与项目所有者确认】

现规范写「只收无 Spring / 无 I/O / 无 HTTP 的纯算法或静态助手」。这九个字是**从 `utils/subtitle/` 当时仅有的三个纯类反推**出来的，不是从判据推的——两条禁令在实际复用面前都站不住，一并改掉。

**I/O 边界**：重复量最大的两簇恰恰是本地文件系统操作。

- **允许**：纯算法助手；仅使用 `java.nio.file` 的本地文件系统助手
- **禁止**：网络传输、外部进程调用

**Spring 边界**：判据从「碰不碰 Spring 这个 jar」改为「**要不要容器跑起来**」。一句话自检——把 Spring 容器整个拿掉，`utils/` 还能不能编译并通过测试？

- **允许**：JDK、slf4j、Jackson、`java.nio.file`、`org.springframework.util.*` 这类纯静态工具
- **禁止**：Spring 注解（`@Component` / `@Service` / `@Autowired` / `@Value` / `@ConfigurationProperties` 等）、`ApplicationContext`、`Environment`、HTTP 类型

后者是必须的：`error-handling.md` 硬性要求字符串判空一律用 `StringUtils.hasText`（全库 8 个文件 36 处），而 `quality-guidelines.md` 头一条硬性要求是「不重复造轮子」。把 Spring 静态工具一并禁掉，会同时违反这两条。

对应新增子包 `utils/fs/`。同时补一条护栏：`model/` 不得依赖 `utils/fs/`——`model/` 的紧凑构造器里不许做 I/O。

### R2 收口清单

| 目标 | 装什么 |
|---|---|
| `utils/fs/ArtifactFiles` | 产物大小判定（6 份归一为 1）、`isNonEmpty`、`writeAtomically`、`moveAtomically`、`deleteQuietly` |
| `utils/subtitle/SrtRenderer` | `formatTimestamp`、`toSrt` |
| `utils/subtitle/SubtitleArtifactNames` | `routeToken`、`srtFileName`、`segmentsFileName`、`cleanSrtFileName`、`cleanSegmentsFileName` |
| `utils/text/TextTail` | `tailLines`（3 份归一为 1） |

### R3 明确不搬的东西

以下经判断留在原地，理由写进 `design.md`，不在本任务范围内：

- 包级 static 测试钩子（`DashScopeAsrProvider.buildParam`/`toResult`、`YtDlpMediaAcquirer.locateMedia`/`scanProducts`、`ExternalToolDiagnostics.parse*` 等）——它们是类的内部细节，为可测性开放，搬走破坏封装
- Controller 里的 `toMediaInput` / `toAudioSpec`——规范明确要求模型 ↔ HTTP 契约转换留在 `web/controller/`
- `AudioPreparationService.audioFileName` / `sampleRateKilo`——单消费者，且规范写明「这些名字只有本服务知道」
- `WorkspaceService` 的 7 个命名助手——无外部调用者，内聚于该类
- `emit`、异常消息兜底 `describe`/`errorMessage`——同簇但收益不抵成本，理由见 `design.md`

### R4 测试随被测类走

搬走的纯函数，其单元测试要从原宿主类的测试类迁到镜像包。`SubtitleServiceTest` 里约 40 行针对 `routeToken` / `srtFileName` / `segmentsFileName` / `formatTimestamp` / `toSrt` 的用例应整体迁走。

### R5 规范同步

`directory-structure.md` 需改：`utils/` 的边界表述（I/O 与 Spring 两条）、顶层分包判据表补 `utils/` 行、`model/` 禁止清单补一条。`error-handling.md` **不改**——判空规则保持全库统一（见 `design.md` 决策 4）。

## Constraints

- **不引入新依赖**
- **对外行为只允许 3 处变化**，全部在 `design.md` 里逐条列出并说明：
  1. `sizeOrZero` 归一后补 `Files.isRegularFile` 守卫（修 bug）
  2. `writeAtomically` 的异常消息去掉「字幕/清洗」前缀（消息文本变化）
  3. `isNonEmpty` 归一后不再各自记 debug 日志
- 除上述 3 处，HTTP 契约、错误码、产物文件名与内容一律不变
- 不新建承载单方法的顶层包之外的东西；`utils/` 下不做「预建空目录」

## Acceptance Criteria

- [ ] `mvn test` 全绿，且测试总数不低于改动前（搬迁不删覆盖）
- [ ] 全库检索 `sizeOrZero` / `nonEmptySize` / `writeAtomically` / `tailLines` 的**定义**各只剩 1 处，均在 `utils/` 下
- [ ] `SubtitleService` / `SubtitleCleanupService` 不再互相调用包级 static 方法
- [ ] `utils/` 下的类不带 Spring 注解、不依赖 `ApplicationContext` / `Environment` 等容器类型（`org.springframework.util.*` 这类纯静态工具可用）
- [ ] `SubtitleServiceTest` 中纯函数用例已迁至 `utils/subtitle/` 镜像测试包，原类只留服务编排相关用例
- [ ] `directory-structure.md` 的相应条款已更新，且与实际代码一致
- [ ] 新增测试类均遵循 `@TempDir(cleanup = CleanupMode.NEVER)`（若写文件）

## Notes

- 本任务的产物是「结构整理」，不是「功能变更」。审查时应重点看**搬得对不对**，而不是找新功能。
- 若审查时决定砍掉某几项，`design.md` 的收口清单已按价值排序，从下往上砍即可。
